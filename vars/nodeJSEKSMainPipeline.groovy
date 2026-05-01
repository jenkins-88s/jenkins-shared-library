def call(Map configMap) {
    pipeline {
        agent { node { label 'roboshop' } }
        parameters {
            choice(name: 'deploy_to', choices: ['dev', 'uat', 'prod'], description: 'Target environment')
            string(name: 'VERSION',  defaultValue: '', description: 'Short commit SHA — set by Jira webhook for UAT/PROD')
            string(name: 'JIRA_KEY', defaultValue: '', description: 'Jira issue key — set by Jira webhook for UAT/PROD')
        }
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'deploy_to', value: '$.deploy_to'],
                    [key: 'VERSION',   value: '$.VERSION'],
                    [key: 'JIRA_KEY',  value: '$.JIRA_KEY']
                ],
                token: "${configMap.get('project')}-main-pipeline",
                causeString: 'Triggered by Jira — $deploy_to deploy',
                printContributedVariables: true,
                printPostContent: true
            )
        }
        environment {
            appVersion   = ""
            shortCommit  = ""
            ACC_ID       = "160885265516"
            PROJECT      = configMap.get("project")
            COMPONENT    = configMap.get("component")
            JIRA_PROJECT = configMap.get("jiraProject")
            region       = "us-east-1"
            CLUSTER      = "roboshop-dev"
        }

        stages {
            // ── DEV ────────────────────────────────────────────────────────────
            stage('Read Version') {
                when { expression { params.deploy_to == 'dev' } }
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion  = packageJson.version
                        shortCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        echo "appVersion: ${appVersion}   shortCommit: ${shortCommit}"
                    }
                }
            }

            stage('Promote Image') {
                when { expression { params.deploy_to == 'dev' } }
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            sh """
                                aws ecr get-login-password --region ${region} \
                                    | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.${region}.amazonaws.com
                                docker pull ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                                docker tag  ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} \
                                            ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${shortCommit}
                                docker push ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${shortCommit}
                            """
                        }
                    }
                }
            }

            stage('Deploy to DEV') {
                when { expression { params.deploy_to == 'dev' } }
                steps {
                    script {
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${shortCommit}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-dev.yaml -n ${PROJECT}-dev --atomic --wait --timeout=5m .
                            """
                        }
                    }
                }
            }

            stage('Functional Tests') {
                when { expression { params.deploy_to == 'dev' } }
                steps {
                    script {
                        def result = build(job: "${PROJECT}/${COMPONENT}-tests", wait: true, propagate: false)
                        if (result.result != 'SUCCESS') {
                            error("Functional tests failed — Jira ticket not created.")
                        }
                    }
                }
            }

            stage('Create Jira Ticket') {
                when { expression { params.deploy_to == 'dev' } }
                steps {
                    script {
                        utils.createJiraTicket(JIRA_PROJECT, COMPONENT, appVersion, shortCommit)
                    }
                }
            }

            // ── UAT ────────────────────────────────────────────────────────────
            stage('Deploy to UAT') {
                when { expression { params.deploy_to == 'uat' } }
                steps {
                    script {
                        sh "git checkout ${params.VERSION}"
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${params.VERSION}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-uat.yaml -n ${PROJECT}-uat --atomic --wait --timeout=5m .
                            """
                        }
                        utils.transitionJiraTicket(params.JIRA_KEY, 'UAT Passed')
                    }
                }
            }

            // ── PROD ───────────────────────────────────────────────────────────
            stage('Deploy to PROD') {
                when { expression { params.deploy_to == 'prod' } }
                steps {
                    script {
                        sh "git checkout ${params.VERSION}"
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${params.VERSION}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-prod.yaml -n ${PROJECT}-prod --atomic --wait --timeout=5m .
                            """
                        }
                        utils.transitionJiraTicket(params.JIRA_KEY, 'Done')
                        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                            sh '''
                                APP_VERSION=$(jq -r .version package.json)
                                REPO_PATH=$(git remote get-url origin | sed 's/.*github\\.com[\\/:]//;s/\\.git$//')
                                git remote set-url origin https://$GITHUB_TOKEN@github.com/$REPO_PATH
                                git tag $APP_VERSION
                                git push origin $APP_VERSION
                            '''
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "${params.deploy_to} deploy succeeded for ${COMPONENT}"
            }
            failure {
                echo "${params.deploy_to} deploy failed for ${COMPONENT}"
            }
        }
    }
}
