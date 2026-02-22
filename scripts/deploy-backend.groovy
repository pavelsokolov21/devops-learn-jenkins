pipeline {
  agent { label 'linux-build' } 

  environment {
    APP_DIR = '/opt/myapp'
    ENV_FILE = "${APP_DIR}.env"
    COMPOSE = 'docker compose --env-file /opt/myapp/.env -f /opt/myapp/docker-compose.yaml'
    DOCKERHUB_REPO = 'smplay/my-backend'
    DB_PASS = credentials('db-password')
    DB_USER = credentials('db-user')
  }

  triggers {
    cron('H/5 * * * *') // раз в ~5 минут
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
              echo "FRONTEND_IMAGE=docker.io/${DOCKERHUB_REPO}:latest" >> "$ENV_FILE"
            fi
          '''
        }
      }
    }

    stage('Check tools') {
      steps {
        sh '''
          set -euxo pipefail
          docker version
          docker compose version
          curl --version
          jq --version
          test -f "$ENV_FILE"
        '''
      }
    }

    stage('Find latest tag on Docker Hub') {
      steps {
        script {
          // Берём самый свежий тег по last_updated (простая и универсальная стратегия)
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

    stage('Deploy backend if changed') {
      steps {
        sh '''
          set -euxo pipefail

          cd "$APP_DIR"

          current_image=$(grep -E '^BACKEND_IMAGE=' "$ENV_FILE" | cut -d= -f2- || true)
          base_image="docker.io/${DOCKERHUB_REPO}"
          new_image="${base_image}:${LATEST_TAG}"

          echo "Current: ${current_image:-<none>}"
          echo "New:     ${new_image}"

          if [ "${current_image:-}" = "$new_image" ]; then
            echo "Backend already up-to-date, nothing to do."
            exit 0
          fi

          # Обновить/добавить BACKEND_IMAGE в .env
          if grep -qE '^BACKEND_IMAGE=' "$ENV_FILE"; then
            sed -i "s|^BACKEND_IMAGE=.*|BACKEND_IMAGE=${new_image}|" "$ENV_FILE"
          else
            echo "BACKEND_IMAGE=${new_image}" >> "$ENV_FILE"
          fi

          ${COMPOSE} pull backend
          ${COMPOSE} up -d --no-deps backend

          ${COMPOSE} ps
        '''
      }
    }
  }
}
