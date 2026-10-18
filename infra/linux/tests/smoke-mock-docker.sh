#!/usr/bin/env bash
# Smoke test pour les commandes qui ne requierent pas un vrai daemon Docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PROJECT="$TMP/project"
mkdir -p "$PROJECT/infra/linux" "$TMP/fakebin"
cp "$INFRA_DIR/se.sh" "$PROJECT/infra/linux/se.sh"
printf 'REDIS_PASSWORD=test\nMONGO_ROOT_PASSWORD=test\n' > "$PROJECT/.env"
cp "$PROJECT/.env" "$PROJECT/.env.example"
touch "$PROJECT/docker-compose.yml"
cat > "$TMP/fakebin/docker" <<'DOCKER'
#!/usr/bin/env bash
echo "DOCKER $*" >> "${DOCKER_CALLS_LOG:?}"
case "$1 $2" in
  "compose version") echo "v2.29.0"; exit 0;;
esac
if [ "$1" = "info" ]; then
  if [[ "$*" == *MemTotal* ]]; then echo 8589934592; fi
  exit 0
fi
if [ "$1" = "compose" ] && [ "$2" = "ps" ]; then echo "cid123"; exit 0; fi
if [ "$1" = "inspect" ]; then
  if [[ "$*" == *ExitCode* ]]; then echo 0
  elif [[ "$*" == *Health* ]]; then echo healthy
  else echo running; fi
  exit 0
fi
exit 0
DOCKER
chmod +x "$TMP/fakebin/docker"
export PATH="$TMP/fakebin:$PATH"
export DOCKER_CALLS_LOG="$TMP/docker_calls.log"
: > "$DOCKER_CALLS_LOG"
cd "$PROJECT"

run_ok() {
  local command="$1" expected_text="${2:-}"
  # Each command is deliberately split as shell words like the CLI invocation.
  # shellcheck disable=SC2086
  bash infra/linux/se.sh $command > "$TMP/output.txt" 2>&1 || { cat "$TMP/output.txt"; echo "FAILED: $command" >&2; exit 1; }
  if [[ -n "$expected_text" ]] && ! grep -Fq "$expected_text" "$TMP/output.txt"; then
    cat "$TMP/output.txt"; echo "Missing output '$expected_text' for: $command" >&2; exit 1
  fi
}

run_ok 'help' 'Services :'
run_ok 'doctor' 'Environnement pret'
run_ok 'start --no-wait'
run_ok 'stop collector-service' 'Arret effectue'
run_ok 'restart'
run_ok 'rebuild collector-service --no-cache' 'Tous les services sont prets'
run_ok 'logs storage-service -n 50 --no-follow --since 5m'
run_ok 'sim --anomaly --chaos --sensors 3 --duration PT1M' 'Simulateur'
run_ok 'stream' 'Stream energy-measurements'
run_ok 'count SENSOR-001 2026-10-18'
run_ok 'redis XLEN foo'
run_ok 'shell redis'
run_ok 'down' 'Conteneurs supprimes'
run_ok 'reset --yes' 'Reset effectue'
if bash infra/linux/se.sh foo > "$TMP/output.txt" 2>&1; then
  echo 'FAILED: unknown command should return a non-zero exit code' >&2
  exit 1
fi

grep -Fq 'compose build --no-cache collector-service' "$DOCKER_CALLS_LOG"
grep -Fq 'compose up -d --no-deps collector-service' "$DOCKER_CALLS_LOG"
grep -Fq 'compose logs --tail 50 --since 5m storage-service' "$DOCKER_CALLS_LOG"
grep -Fq 'compose --profile tools down -v --remove-orphans' "$DOCKER_CALLS_LOG"
echo 'Smoke test OK (mock Docker; no real containers started).'
