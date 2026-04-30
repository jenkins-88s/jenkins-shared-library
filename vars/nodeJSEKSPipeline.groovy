def call(Map configMap){
    pipeline {
        agent { node { label 'roboshop' } } 
        environment {
            COURSE = "Jenkins"
            appVersion = ""
            ACC_ID = "160885265516"
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")
        }
        stages {
            stage('test') {
                steps {
                    sh """
                        echo "triggering test: ${configMap.project}"
                    """
                }
            }
            stage('Read version'){
                steps {
                    script {
                        // Load and parse the JSON file
                        def packageJson = readJSON file: 'package.json'
                        
                        // Access fields directly
                        appVersion = packageJson.version
                        echo "Building version ${appVersion}"
                    }
                }
            }
            // THis is for VM
            /* stage('Install Dependencies') {
                steps {
                    sh 'npm install'
                }
            } */

            /*stage ('SonarQube Analysis'){
                steps {
                    script {
                        def scannerHome = tool name: 'sonar-8' // agent configuration
                        withSonarQubeEnv('sonar-server') { // analysing and uploading to server
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
            
            stage("Quality Gate") {
                steps {
                timeout(time: 1, unit: 'HOURS') {
                    waitForQualityGate abortPipeline: true
                }
                }
            } */
            /* stage('Dependabot Security Check') {
                steps {
                    script {
                        withCredentials([string(credentialsId: 'GITHUB_TOKEN_SCAN', variable: 'GITHUB_TOKEN_SCAN')]) {
                            def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
                            def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

                            def alertCount = sh(
                                script: """
                                    curl -sf \
                                        -H "Authorization: Bearer \$GITHUB_TOKEN_SCAN" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "https://api.github.com/repos/${repoPath}/dependabot/alerts?state=open&per_page=100" \
                                    | jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length'
                                """,
                                returnStdout: true
                            ).trim()

                            if (alertCount.toInteger() > 0) {
                                error("Build aborted: ${alertCount} HIGH/CRITICAL Dependabot alert(s) detected. Resolve them before proceeding.")
                            }
                            echo "Dependabot check passed — no HIGH or CRITICAL vulnerabilities found."
                        }
                    }
                }
            } */
        
            stage('Build Image') {
                steps {
                    script{
                        // Commands here have AWS authentication
                        sh """
                            docker build -t ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                        """
                    }
                }
            }
            stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                            script: """
                                trivy image \
                                    --scanners vuln \
                                    --pkg-types os \
                                    --severity HIGH,MEDIUM \
                                    --format table \
                                    --exit-code 1 \
                                    --quiet \
                                    ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }
            stage('Trivy Dockerfile Scan') {
                steps {
                    script {
                        sh """
                            trivy config \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-dockerfile-report.txt \
                                Dockerfile
                        """

                        sh 'cat trivy-dockerfile-report.txt'

                        def scanResult = sh(
                            script: """
                                trivy config \
                                    --severity HIGH,MEDIUM \
                                    --exit-code 1 \
                                    --format table \
                                    Dockerfile
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM misconfigurations in Dockerfile. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM Dockerfile misconfigurations found. Pipeline continues."
                        }
                    }
                }
            }
            stage ('Push image to ECR'){
                steps {
                script{
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            // Commands here have AWS authentication
                            sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                docker push ${ACC_ID}.dkr.ecr.${region}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                            """
                        }
                    }
                }
            }
        }
        post {
            success {
                echo "Pipeline succeeded on branch: ${env.BRANCH_NAME}"
            }
            failure {
                echo "Pipeline failed on branch: ${env.BRANCH_NAME}"
            }
        }
    }
}