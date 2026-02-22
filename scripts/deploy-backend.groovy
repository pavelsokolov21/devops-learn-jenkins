pipeline {
  agent { label 'linux-build' } 

  environment {
    APP_DIR = '/opt/myapp'
    ENV_FILE = '/opt/myapp/.env'
    COMPOSE = 'docker compose --env-file /opt/myapp/.env -f /opt/myapp/docker-compose.yaml'
    DOCKERHUB_REPO = 'smplay/my-backend'
  }

  triggers {
    cron('H/5 * * * *') // раз в ~5 минут
  }

  stages {
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
