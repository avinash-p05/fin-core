#!/bin/sh
# Deploy the latest FinCore image on the server. Lives in ~/fincore next to compose.yaml and .env.
# The image is built by GitHub Actions; the server never builds or needs the source code.
#   ./deploy.sh                      # pull and restart (FINCORE_IMAGE_TAG in .env, default latest)
set -eu
cd "$(dirname "$0")"

# Refresh compose.yaml and the edge site file from main, so the server's copy never drifts.
raw=https://raw.githubusercontent.com/avinash-p05/fin-core/main
curl -fsSL "$raw/compose.yaml" -o compose.yaml.new && mv compose.yaml.new compose.yaml
curl -fsSL "$raw/deploy/edge/fincore.caddy" -o fincore.caddy.new && mv fincore.caddy.new fincore.caddy

docker compose pull app
docker compose up -d --no-build --remove-orphans app
docker image prune -f > /dev/null

echo "Waiting for FinCore to report healthy..."
for _ in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8080/actuator/health | grep -q '"UP"'; then
    echo "FinCore is up: $(docker compose images app --format '{{.Repository}}:{{.Tag}}' 2>/dev/null || true)"
    exit 0
  fi
  sleep 3
done
echo "FinCore did not become healthy in 3 minutes; check: docker compose logs --tail=100 app" >&2
exit 1
