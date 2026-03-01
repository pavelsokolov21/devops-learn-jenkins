pipeline {
    agent { label 'linux-build' } 

    environment {
        APP_DIR  = '/opt/myapp'
        DOCKER_CONTEXT = 'prod-vm'
        PROD_IP  = credentials('prod-vm-ip')
        DB_PASS = credentials('db-password')
        DB_USER = credentials('db-user')
    }

    stages {
        stage('Prepare Remote Dir & Sync') {
            steps {
                // Копируем файлы через scp на prod
                sh '''
                ssh jenkins@${PROD_IP} "rm -f ${APP_DIR}/docker-compose.yaml ${APP_DIR}/nginx.conf"
                scp docker-compose.yaml nginx.conf jenkins@${PROD_IP}:${APP_DIR}/
                '''
            }
        }

        stage('Docker Deploy') {
            steps {
                sh '''
                set -euo pipefail

                export DOCKER_CONTEXT=${DOCKER_CONTEXT}
                
                # Создаем .env прямо на удаленной машине через SSH, если его нет
                if ! ssh jenkins@${PROD_IP} "[ -f ${APP_DIR}/.env ]"; then
                    ssh jenkins@${PROD_IP} "printf 'POSTGRES_USER=%s\\nPOSTGRES_PASSWORD=%s\\nPOSTGRES_DB=app_db\\nBACKEND_IMAGE=docker.io/smplay/my-backend:latest\\nFRONTEND_IMAGE=docker.io/smplay/my-vite-frontend:latest\\n' '${DB_USER}' '${DB_PASS}' > ${APP_DIR}/.env"
                fi

                # Запуск деплоя через контекст
                docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml pull
                docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml up -d --remove-orphans
                '''
            }
        }
    }
}
