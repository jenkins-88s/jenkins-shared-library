def call(Map configMap){
    pipeline {
        agent { node { label 'roboshop' } } 
        stages {
            stage('test') {
                steps {
                    sh """
                        echo "triggering test: ${configMap.project}"
                    """
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'npm install'
                }
            }

            stage('Dependabot Security Check') {
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