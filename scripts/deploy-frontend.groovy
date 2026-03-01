pipeline {
  agent { label 'linux-build' } 

  environment {
        APP_DIR = '/opt/myapp'
        DOCKER_CONTEXT = 'prod-vm'
        DOCKERHUB_REPO = 'smplay/my-vite-frontend'
        
        PROD_IP = credentials('prod-vm-ip')
        DB_PASS = credentials('db-password')
        DB_USER = credentials('db-user')
        
        SSH_CMD = "ssh jenkins@${PROD_IP}"
  }

  triggers {
    cron('H/5 * * * *')
  }

  stages {
    stage('Find latest tag on Docker Hub') {
      steps {
        script {
          def tag = sh(
            script: '''
              set -euo pipefail
              curl -fsSL "https://hub.docker.com/v2/repositories/${DOCKERHUB_REPO}/tags?page_size=50" \
                | jq -r '.results | sort_by(.last_updated) | reverse | .[0].name'
            ''',
            returnStdout: true
          ).trim()

          if (!tag) error("Cannot detect latest tag for ${env.DOCKERHUB_REPO}")
          env.LATEST_TAG = tag
          echo "Latest tag: ${env.LATEST_TAG}"
        }
      }
    }

    stage('Sync Config & Check .env') {
      steps {
          sh '''
          # Синхронизируем docker-compose и nginx.conf
          scp docker-compose.yaml nginx.conf jenkins@${PROD_IP}:${APP_DIR}/

          # Если .env на проде нет — создаем базу (используем \\n для корректного переноса через SSH)
          if ! ${SSH_CMD} "[ -f ${APP_DIR}/.env ]"; then
              echo "Initializing new .env on prod..."
              ${SSH_CMD} "printf 'POSTGRES_USER=%s\\nPOSTGRES_PASSWORD=%s\\nPOSTGRES_DB=app_db\\nBACKEND_IMAGE=docker.io/smplay/my-backend:latest\\n' '${DB_USER}' '${DB_PASS}' > ${APP_DIR}/.env"
          fi
          '''
      }
    }

    stage('Update Image & Deploy') {
        steps {
            sh '''
            set -euo pipefail
            NEW_IMAGE="docker.io/${DOCKERHUB_REPO}:${LATEST_TAG}"
            
            # Получаем текущий образ из .env на удаленной машине
            CURRENT_IMAGE=$(${SSH_CMD} "grep '^FRONTEND_IMAGE=' ${APP_DIR}/.env | cut -d= -f2-" || true)

            if [ "$CURRENT_IMAGE" = "$NEW_IMAGE" ]; then
                echo "Frontend is already up-to-date ($CURRENT_IMAGE). Exit."
                exit 0
            fi

            echo "Updating frontend from $CURRENT_IMAGE to $NEW_IMAGE"

            # Обновляем строку в .env на проде
            if ${SSH_CMD} "grep -q '^FRONTEND_IMAGE=' ${APP_DIR}/.env"; then
                ${SSH_CMD} "sed -i 's|^FRONTEND_IMAGE=.*|FRONTEND_IMAGE=${NEW_IMAGE}|' ${APP_DIR}/.env"
            else
                ${SSH_CMD} "echo 'FRONTEND_IMAGE=${NEW_IMAGE}' >> ${APP_DIR}/.env"
            fi

            # Выполняем деплой через контекст
            docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml pull frontend_build
            docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml run --rm frontend_build
            docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml up -d --no-deps nginx
            
            echo "Deployment finished. Current status:"
            docker --context ${DOCKER_CONTEXT} compose -f ${APP_DIR}/docker-compose.yaml ps
            '''
        }
    }
  }
}
