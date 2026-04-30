def call (){
    def call (Map configMap){
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
}