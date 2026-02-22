pipeline {
    agent { label 'linux-build' } 

    environment {
        APP_DIR  = '/opt/myapp'
        // Указываем путь к docker-compose файлу явно
        COMPOSE_FILE = "${APP_DIR}/docker-compose.yaml"
    }

    stages {
        stage('Check Deployment Directory') {
            steps {
                // Проверяем, существует ли директория и есть ли там docker-compose.yaml
                sh '''
                    set -euo pipefail
                    if [ ! -d "$APP_DIR" ]; then
                        echo "ERROR: Directory $APP_DIR does not exist."
                        exit 1
                    fi
                    if [ ! -f "$COMPOSE_FILE" ]; then
                        echo "ERROR: docker-compose.yaml not found in $APP_DIR"
                        exit 1
                    fi
                '''
            }
        }

        stage('Docker Compose Up') {
            steps {
                sh '''
                    set -euo pipefail
                    
                    echo "Moving to $APP_DIR..."
                    cd "$APP_DIR"

                    echo "Downing containers..."
                    docker compose down

                    echo "Deployment status:"
                    docker compose ps
                '''
            }
        }

        stage('Cleanup') {
            steps {
                // Очистка неиспользуемых слоев и старых образов для экономии места на VM
                sh 'docker image prune -f'
            }
        }
    }

    post {
        success {
            echo "Successfully down"
        }
        failure {
            echo "Downing failed. Check Jenkins console output for details."
        }
    }
}
