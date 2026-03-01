pipeline {
    agent { label 'linux-build' } 

    environment {
        APP_DIR  = '/opt/myapp'
        DOCKER_CONTEXT = 'prod-vm'
        // IP нам нужен только если мы хотим проверить доступность SSH
        PROD_IP = credentials('prod-vm-ip')
    }

    stages {
        stage('Docker Compose Down') {
            steps {
                sh '''
                set -euo pipefail
                
                echo "Stopping and removing containers on Prod (${PROD_IP})..."
                
                # Используем контекст, чтобы достучаться до демона на prod.
                # Docker сам найдет compose-файл на удаленной машине по указанному пути.
                docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml down
                '''
            }
        }

        stage('Remote Cleanup') {
            steps {
                // Очистка старых образов прямо на прод-машине
                sh "docker --context ${DOCKER_CONTEXT} image prune -f"
            }
        }
    }

    post {
        success {
            echo "Application successfully stopped on Prod."
            // Проверка, что контейнеров действительно нет
            sh "docker --context ${DOCKER_CONTEXT} ps -a"
        }
    }
}
