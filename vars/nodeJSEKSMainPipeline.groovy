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
            // resolved in Init stage — pick from webhook env var first, fall back to build param
            DEPLOY_TO     = ""
            TARGET_VERSION = ""
            JIRA_ISSUE    = ""
        }

        stages {
            // ── INIT — normalise webhook env vars vs manual build params ────────
            stage('Init') {
                steps {
                    script {
                        DEPLOY_TO      = env.deploy_to  ?: params.deploy_to  ?: 'dev'
                        TARGET_VERSION = env.VERSION    ?: params.VERSION    ?: ''
                        JIRA_ISSUE     = env.JIRA_KEY   ?: params.JIRA_KEY   ?: ''
                        echo "DEPLOY_TO: ${DEPLOY_TO}  TARGET_VERSION: ${TARGET_VERSION}  JIRA_ISSUE: ${JIRA_ISSUE}"
                    }
                }
            }

            // ── DEV ────────────────────────────────────────────────────────────
            stage('Read Version') {
                when { expression { DEPLOY_TO == 'dev' } }
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
                when { expression { DEPLOY_TO == 'dev' } }
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
                when { expression { DEPLOY_TO == 'dev' } }
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
                when { expression { DEPLOY_TO == 'dev' } }
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
                when { expression { DEPLOY_TO == 'dev' } }
                steps {
                    script {
                        utils.createJiraTicket(JIRA_PROJECT, COMPONENT, appVersion, shortCommit)
                    }
                }
            }

            // ── UAT ────────────────────────────────────────────────────────────
            stage('Deploy to UAT') {
                when { expression { DEPLOY_TO == 'uat' } }
                steps {
                    script {
                        sh "git checkout ${TARGET_VERSION}"
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${TARGET_VERSION}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-uat.yaml -n ${PROJECT}-uat --atomic --wait --timeout=5m .
                            """
                        }
                        utils.transitionJiraTicket(JIRA_ISSUE, 'UAT Passed')
                    }
                }
            }

            // ── PROD ───────────────────────────────────────────────────────────
            stage('Deploy to PROD') {
                when { expression { DEPLOY_TO == 'prod' } }
                steps {
                    script {
                        sh "git checkout ${TARGET_VERSION}"
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${TARGET_VERSION}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-prod.yaml -n ${PROJECT}-prod --atomic --wait --timeout=5m .
                            """
                        }
                        utils.transitionJiraTicket(JIRA_ISSUE, 'Done')
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
                echo "${DEPLOY_TO} deploy succeeded for ${COMPONENT}"
            }
            failure {
                echo "${DEPLOY_TO} deploy failed for ${COMPONENT}"
            }
        }
    }
}
