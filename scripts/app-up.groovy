pipeline {
    agent { label 'linux-build' } 

    environment {
        APP_DIR  = '/opt/myapp'
        // Указываем путь к docker-compose файлу явно
        COMPOSE_FILE = "${APP_DIR}/docker-compose.yaml"
        DB_PASS = credentials('db-password')
        DB_USER = credentials('db-user')
    }

    stages {
        stage('Sync Project Files') {
            steps {
                // Копируем yaml и conf из текущего воркспейса (куда Jenkins склонировал git) в /opt/myapp
                sh '''
                cp docker-compose.yaml "$APP_DIR/docker-compose.yaml"
                cp nginx.conf "$APP_DIR/nginx.conf"
                '''
            }
        }

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

        stage('Initialize .env') {
            steps {
                script {
                // Если .env не существует, создаем его с базовыми значениями
                // Используем printf, чтобы избежать проблем с экранированием спецсимволов в паролях
                sh '''
                    if [ ! -f "$ENV_FILE" ]; then
                    printf "POSTGRES_USER=%s\nPOSTGRES_PASSWORD=%s\nPOSTGRES_DB=app_db\n" \
                        "$DB_USER" "$DB_PASS" > "$ENV_FILE"
                    echo "BACKEND_IMAGE=docker.io/smplay/my-backend:latest" >> "$ENV_FILE"
                    echo "FRONTEND_IMAGE=docker.io/smplay/my-vite-frontend:latest" >> "$ENV_FILE"
                    fi
                '''
                }
            }
        }

        stage('Docker Compose Up') {
            steps {
                sh '''
                    set -euo pipefail
                    
                    echo "Moving to $APP_DIR..."
                    cd "$APP_DIR"

                    echo "Pulling latest images..."
                    # Pull скачивает новые версии образов, указанных в docker-compose.yaml
                    docker compose pull

                    echo "Starting containers..."
                    # --remove-orphans удаляет контейнеры, которых больше нет в конфиге
                    # -d запускает в фоновом режиме
                    docker compose up -d --remove-orphans

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
            echo "Successfully deployed to $APP_DIR"
        }
        failure {
            echo "Deployment failed. Check Jenkins console output for details."
        }
    }
}
