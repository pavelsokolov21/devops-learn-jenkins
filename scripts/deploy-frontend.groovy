pipeline {
  agent { label 'linux-build' } 

  environment {
    APP_DIR = '/opt/myapp'
    ENV_FILE = '/opt/myapp/.env'
    COMPOSE = 'docker compose --env-file /opt/myapp/.env -f /opt/myapp/docker-compose.yaml'
    DOCKERHUB_REPO = 'smplay/my-vite-frontend'
  }

  triggers {
    cron('H/5 * * * *')
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

    stage('Deploy frontend if changed') {
      steps {
        sh '''
          set -euxo pipefail

          cd "$APP_DIR"

          current_image=$(grep -E '^FRONTEND_IMAGE=' "$ENV_FILE" | cut -d= -f2- || true)
          base_image="docker.io/${DOCKERHUB_REPO}"
          new_image="${base_image}:${LATEST_TAG}"

          echo "Current: ${current_image:-<none>}"
          echo "New:     ${new_image}"

          if [ "${current_image:-}" = "$new_image" ]; then
            echo "Frontend already up-to-date, nothing to do."
            exit 0
          fi

          # Обновить/добавить FRONTEND_IMAGE в .env
          if grep -qE '^FRONTEND_IMAGE=' "$ENV_FILE"; then
            sed -i "s|^FRONTEND_IMAGE=.*|FRONTEND_IMAGE=${new_image}|" "$ENV_FILE"
          else
            echo "FRONTEND_IMAGE=${new_image}" >> "$ENV_FILE"
          fi

          # 1) скачать новый образ для frontend_build
          ${COMPOSE} pull frontend_build

          # 2) прогнать one-shot, чтобы dist попал в volume
          ${COMPOSE} run --rm frontend_build

          # 3) перезапустить nginx (подхватит обновлённый volume)
          ${COMPOSE} up -d --no-deps nginx

          ${COMPOSE} ps
        '''
      }
    }
  }
}
