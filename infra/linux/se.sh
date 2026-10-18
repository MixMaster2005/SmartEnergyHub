#!/usr/bin/env bash
# SmartEnergy Hub - outil de cycle de vie (Linux, macOS, WSL, Git Bash)
# Equivalent Windows : infra/windows/se.cmd
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT_HOST="$(cd "$ROOT" && pwd -W 2>/dev/null || printf '%s' "$ROOT")"
cd "$ROOT" || exit 1

PROJECT="smartenergy-hub"
APP_SERVICES=(sensor-service collector-service storage-service analytics-service)
ALL_READY=(redis mongo cassandra cassandra-init sensor-service collector-service storage-service analytics-service)
DEFAULT_TIMEOUT=420
VOLUMES=(redis_data mongo_data cassandra_data)

if [[ -t 1 ]]; then
  C_R=$'\033[31m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_B=$'\033[36m'; C_N=$'\033[0m'
else
  C_R=""; C_G=""; C_Y=""; C_B=""; C_N=""
fi

info() { printf '%s==>%s %s\n' "$C_B" "$C_N" "$*"; }
ok()   { printf '%s OK %s %s\n' "$C_G" "$C_N" "$*"; }
warn() { printf '%s !! %s %s\n' "$C_Y" "$C_N" "$*"; }
err()  { printf '%sERR %s %s\n' "$C_R" "$C_N" "$*" >&2; }
die()  { err "$*"; exit 1; }
dc()   { docker compose "$@"; }
have() { command -v "$1" >/dev/null 2>&1; }

svc_port() {
  case "$1" in
    collector-service) printf '8081';;
    storage-service)   printf '8082';;
    analytics-service) printf '8083';;
    sensor-service)    printf '8084';;
    *) printf ''; ;;
  esac
}

is_known_service() {
  case "$1" in
    redis|mongo|cassandra|cassandra-init|sensor-service|collector-service|storage-service|analytics-service|simulator) return 0;;
    *) return 1;;
  esac
}

svc_cid() { dc ps -aq "$1" 2>/dev/null | head -n 1; }

svc_state() {
  local cid status health
  cid="$(svc_cid "$1")"
  [[ -n "$cid" ]] || { printf 'absent'; return; }
  status="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || true)"
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null || true)"
  if [[ -n "$health" ]]; then printf '%s' "$health"; else printf '%s' "$status"; fi
}

svc_exit_code() {
  local cid
  cid="$(svc_cid "$1")"
  [[ -n "$cid" ]] || { printf ''; return; }
  docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null || true
}

is_ready() {
  local service="$1" state
  state="$(svc_state "$service")"
  if [[ "$service" == cassandra-init ]]; then
    [[ "$state" == exited ]] && [[ "$(svc_exit_code "$service")" == 0 ]]
  else
    [[ "$state" == healthy ]]
  fi
}

wait_ready() {
  local timeout="$1" start=$SECONDS last=-10 pending service state elapsed
  shift
  info "Attente des services (timeout ${timeout}s) ..."
  while :; do
    pending=()
    for service in "$@"; do
      if ! is_ready "$service"; then
        state="$(svc_state "$service")"
        if [[ "$service" != cassandra-init && ( "$state" == unhealthy || "$state" == exited || "$state" == dead ) ]]; then
          err "$service est en etat '$state'. Voir : ./infra/linux/se.sh logs $service"
          return 1
        fi
        if [[ "$service" == cassandra-init && "$state" == exited && "$(svc_exit_code "$service")" != 0 ]]; then
          err "cassandra-init a echoue. Voir : ./infra/linux/se.sh logs cassandra-init"
          return 1
        fi
        pending+=("$service($state)")
      fi
    done
    elapsed=$((SECONDS - start))
    if ((${#pending[@]} == 0)); then ok "Tous les services sont prets (${elapsed}s)"; return 0; fi
    if ((elapsed >= timeout)); then
      err "Timeout apres ${timeout}s. En attente : ${pending[*]}"
      warn "Cassandra peut etre lent au premier demarrage : relancez ./infra/linux/se.sh health dans une minute, ou consultez les logs."
      return 1
    fi
    if ((elapsed - last >= 10)); then info "  en attente : ${pending[*]}"; last=$elapsed; fi
    sleep 3
  done
}

print_urls() {
  cat <<'URLS'

  Services              API                      Swagger UI
  collector-service     http://localhost:8081    http://localhost:8081/swagger-ui.html
  storage-service       http://localhost:8082    http://localhost:8082/swagger-ui.html
  analytics-service     http://localhost:8083    http://localhost:8083/swagger-ui.html
  sensor-service        http://localhost:8084    http://localhost:8084/swagger-ui.html

  Sante : /api/health  |  Detail : /actuator/health/readiness  |  Metriques : /actuator/prometheus
URLS
}

set_env_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp)" || return 1
  awk -v key="$key" -v value="$value" 'BEGIN { FS="=" } $1 == key { print key "=" value; found=1; next } { print } END { if (!found) print key "=" value }' "$file" > "$tmp" \
    && cat "$tmp" > "$file"
  local result=$?
  rm -f "$tmp"
  return "$result"
}

gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; printf '\n'; }

ensure_env() {
  local random="${1:-}" key
  if [[ ! -f .env ]]; then
    [[ -f .env.example ]] || die ".env absent et .env.example introuvable"
    cp .env.example .env || die "Impossible de creer .env"
    ok ".env cree depuis .env.example"
    if [[ "$random" == --random-secrets ]]; then
      if docker volume ls -q 2>/dev/null | grep -q "^${PROJECT}_mongo_data$"; then
        die "Des volumes existent deja : les mots de passe aleatoires ne s'appliqueraient pas. Faites d'abord ./infra/linux/se.sh reset"
      fi
      for key in REDIS_PASSWORD MONGO_ROOT_PASSWORD MONGO_SENSOR_PASSWORD MONGO_ANALYTICS_PASSWORD; do
        set_env_var .env "$key" "$(gen_secret)" || die "Impossible de mettre a jour $key"
      done
      ok "Mots de passe aleatoires generes dans .env (ne pas commiter .env)"
    fi
  elif [[ "$random" == --random-secrets ]]; then
    warn ".env existe deja : --random-secrets ignore"
  fi
}

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

cmd_doctor() {
  local errors=0 warnings=0 mem port
  info "Verification de l'environnement"
  if have docker; then ok "docker : $(docker --version 2>/dev/null)"; else err "docker introuvable (installez Docker Desktop / Docker Engine)"; errors=$((errors + 1)); fi
  if have docker; then
    if docker info >/dev/null 2>&1; then ok "le daemon Docker repond"; else err "le daemon Docker ne repond pas (lancez Docker Desktop / le service docker)"; errors=$((errors + 1)); fi
    if docker compose version >/dev/null 2>&1; then ok "docker compose : $(docker compose version --short 2>/dev/null)"; else err "docker compose v2 introuvable (plugin 'docker compose')"; errors=$((errors + 1)); fi
    mem="$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
    if [[ "$mem" =~ ^[0-9]+$ ]]; then
      if ((mem < 6000000000)); then
        warn "Docker dispose de $((mem / 1024 / 1024)) Mo de RAM : 6 Go recommandes (Cassandra + MongoDB + 4 JVM)."
        warnings=$((warnings + 1))
      else
        ok "memoire Docker : $((mem / 1024 / 1024)) Mo"
      fi
    fi
  fi
  if [[ -f docker-compose.yml || -f compose.yml || -f compose.yaml ]]; then ok "fichier Compose present"; else err "docker-compose.yml introuvable (lancez depuis la racine du depot)"; errors=$((errors + 1)); fi
  if [[ -f .env ]]; then
    ok ".env present"
    if grep -q 'change-me' .env; then info "  secrets par defaut (placeholders) : acceptable en developpement"; fi
  else
    warn ".env absent : sera cree par first-start (ou : cp .env.example .env)"
    warnings=$((warnings + 1))
  fi
  if have docker && [[ -z "$(dc ps -q 2>/dev/null)" ]]; then
    for port in 6379 27017 9042 8081 8082 8083 8084; do
      if port_in_use "$port"; then warn "le port $port est deja utilise sur cette machine (conflit probable)"; warnings=$((warnings + 1)); fi
    done
  fi
  if have mvn; then info "  Maven local : $(mvn -v 2>/dev/null | head -n 1)"; else info "  Maven local absent : build/test utiliseront un conteneur Maven"; fi
  if have java; then info "  Java local : $(java -version 2>&1 | head -n 1)"; fi
  if ! have curl; then warn "curl absent : health et e2e en ont besoin"; warnings=$((warnings + 1)); fi
  printf '\n'
  if ((errors > 0)); then err "$errors erreur(s), $warnings avertissement(s)"; return 1; fi
  ok "Environnement pret ($warnings avertissement(s))"
}

cmd_first_start() {
  local timeout="$DEFAULT_TIMEOUT" random=""
  while (($#)); do
    case "$1" in
      --random-secrets) random=--random-secrets;;
      --timeout) shift; timeout="${1:-$DEFAULT_TIMEOUT}";;
      *) die "option inconnue : $1";;
    esac
    shift
  done
  cmd_doctor || die "Corrigez les erreurs ci-dessus puis relancez."
  ensure_env "$random"
  info "Construction des images (premier build : Maven telecharge les dependances) ..."
  dc build || die "Echec du build des images"
  info "Demarrage de la plateforme ..."
  dc up -d || die "Echec du demarrage"
  wait_ready "$timeout" "${ALL_READY[@]}" || { warn "Etat : ./infra/linux/se.sh status"; return 1; }
  print_urls
  ok "Premier demarrage termine."
  info "Etape suivante : ./infra/linux/se.sh simulate (5 capteurs pendant 2 minutes)"
}

cmd_start() {
  local timeout="$DEFAULT_TIMEOUT" wait_for_ready=1 service
  local -a services=()
  while (($#)); do
    case "$1" in
      --no-wait) wait_for_ready=0;;
      --timeout) shift; timeout="${1:-$DEFAULT_TIMEOUT}";;
      -*) die "option inconnue : $1";;
      *) is_known_service "$1" || die "service inconnu : $1"; services+=("$1");;
    esac
    shift
  done
  ensure_env
  dc up -d "${services[@]}" || die "Echec du demarrage"
  if ((wait_for_ready)); then
    if ((${#services[@]})); then
      wait_ready "$timeout" "${services[@]}" || return 1
    else
      wait_ready "$timeout" "${ALL_READY[@]}" || return 1
      print_urls
    fi
  fi
}

cmd_stop() { dc stop "$@" && ok "Arret effectue (conteneurs et donnees conserves)"; }
cmd_down() { dc --profile tools down --remove-orphans && ok "Conteneurs supprimes (volumes conserves : les donnees sont gardees)"; }
cmd_restart() { dc restart "$@"; }

cmd_rebuild() {
  local timeout="$DEFAULT_TIMEOUT" service
  local -a services=() build_options=()
  while (($#)); do
    case "$1" in
      --no-cache) build_options+=(--no-cache);;
      --timeout) shift; timeout="${1:-$DEFAULT_TIMEOUT}";;
      -*) die "option inconnue : $1";;
      *) is_known_service "$1" || die "service inconnu : $1"; services+=("$1");;
    esac
    shift
  done
  ((${#services[@]})) || services=("${APP_SERVICES[@]}")
  info "Reconstruction : ${services[*]}"
  dc build "${build_options[@]}" "${services[@]}" || die "Echec du build"
  dc up -d --no-deps "${services[@]}" || die "Echec du redemarrage"
  wait_ready "$timeout" "${services[@]}"
}

cmd_logs() {
  local tail_count=100 follow=-f since="" pretty=0 service
  local -a services=()
  while (($#)); do
    case "$1" in
      -n|--tail) shift; tail_count="${1:-100}";;
      --no-follow) follow="";;
      --since) shift; since="${1:-}";;
      --pretty) pretty=1;;
      --raw) pretty=0;;
      -*) die "option inconnue : $1";;
      *) is_known_service "$1" || die "service inconnu : $1 (voir ./infra/linux/se.sh help)"; services+=("$1");;
    esac
    shift
  done
  local -a args=(logs --tail "$tail_count")
  [[ -n "$follow" ]] && args+=("$follow")
  [[ -n "$since" ]] && args+=(--since "$since")
  if ((pretty)); then
    have jq || die "--pretty necessite jq"
    ((${#services[@]} == 1)) || die "--pretty necessite exactement un service"
    dc "${args[@]}" --no-log-prefix "${services[@]}" 2>&1 | jq -R -r 'try (fromjson | "\(.["@timestamp"] // "") \(.["log.level"] // "") \(.message // "")") catch .'
  else
    dc "${args[@]}" "${services[@]}"
  fi
}

cmd_status() { dc --profile tools ps -a; printf '\n'; cmd_health_quiet; }
http_code() { curl -s -o /dev/null -m 3 -w '%{http_code}' "$1" 2>/dev/null || true; }
cmd_health_quiet() {
  have curl || { warn "curl absent : sante HTTP non testee"; return 0; }
  local service port code failed=0
  for service in "${APP_SERVICES[@]}"; do
    port="$(svc_port "$service")"
    code="$(http_code "http://localhost:$port/actuator/health/readiness")"
    if [[ "$code" == 200 ]]; then printf '  %s%-18s UP%s   http://localhost:%s\n' "$C_G" "$service" "$C_N" "$port"
    else printf '  %s%-18s DOWN%s (HTTP %s)\n' "$C_R" "$service" "$C_N" "${code:-000}"; failed=1; fi
  done
  return "$failed"
}
cmd_health() {
  info "Sante des services (readiness)"
  if cmd_health_quiet; then ok "Tous les services repondent"; else warn "Au moins un service est indisponible : ./infra/linux/se.sh logs <service>"; return 1; fi
}
cmd_urls() { print_urls; }

open_url() {
  if have xdg-open; then xdg-open "$1" >/dev/null 2>&1
  elif have open; then open "$1" >/dev/null 2>&1
  elif have cmd.exe; then cmd.exe /c start "" "$1" >/dev/null 2>&1
  else return 1; fi
}
cmd_open() {
  local url
  for url in http://localhost:8081/swagger-ui.html http://localhost:8082/swagger-ui.html \
             http://localhost:8083/swagger-ui.html http://localhost:8084/swagger-ui.html; do
    open_url "$url" || { print_urls; return 0; }
  done
}

cmd_reset() {
  local yes=0 answer
  [[ "${1:-}" == --yes || "${1:-}" == -y ]] && yes=1
  warn "RESET : supprime les conteneurs ET les volumes (Redis, MongoDB, Cassandra). Toutes les donnees seront perdues."
  if (( ! yes )); then
    [[ -t 0 ]] || die "Mode non interactif : ajoutez --yes pour confirmer."
    read -r -p "Tapez 'oui' pour confirmer : " answer
    [[ "$answer" == oui ]] || { info "Annule."; return 0; }
  fi
  dc --profile tools down -v --remove-orphans && ok "Reset effectue. Les scripts d'initialisation seront rejoues au prochain demarrage."
}
cmd_clean() {
  cmd_reset "$@" || return 1
  dc --profile tools down -v --rmi local --remove-orphans >/dev/null 2>&1 || true
  ok "Images locales supprimees. Prochain demarrage : ./infra/linux/se.sh first-start"
}
cmd_pull() { dc pull redis mongo cassandra cassandra-init; }
cmd_stats() {
  local ids
  ids="$(dc ps -q)"
  [[ -n "$ids" ]] || { info "Aucun conteneur en cours"; return 0; }
  # IDs are returned by Docker, one per line.
  local -a ids_array=()
  while IFS= read -r id; do
    [[ -n "$id" ]] && ids_array+=("$id")
  done <<< "$ids"
  docker stats --no-stream "${ids_array[@]}"
}

mvn_run() {
  if have mvn && [[ "${SE_DOCKER_MAVEN:-0}" != 1 ]]; then
    mvn -B "$@"
  else
    have docker || die "Ni Maven ni Docker disponibles"
    info "Maven local absent : execution dans un conteneur (cache des dependances : volume se-m2)"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${ROOT_HOST}:/workspace" -v se-m2:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-17 mvn -B "$@"
  fi
}
cmd_build() {
  if [[ -n "${1:-}" ]]; then
    is_known_service "$1" || [[ "$1" == simulator ]] || die "service inconnu : $1"
    mvn_run clean package -DskipTests -pl "$1" -am
  else mvn_run clean package -DskipTests; fi
}
cmd_test() {
  if [[ -n "${1:-}" ]]; then mvn_run test -pl "$1" -am; else mvn_run test; fi
}

cmd_sim() {
  local -a args=()
  while (($#)); do
    case "$1" in
      --anomaly) args+=(--smartenergy.simulator.anomaly.sensor=SENSOR-003);;
      --anomaly=*) args+=("--smartenergy.simulator.anomaly.sensor=${1#--anomaly=}");;
      --chaos) args+=(--smartenergy.simulator.invalid-rate=0.05 --smartenergy.simulator.duplicate-rate=0.02);;
      --long) args+=(--smartenergy.simulator.duration=PT0S);;
      --sensors) shift; args+=("--smartenergy.simulator.sensors=${1:-5}");;
      --duration) shift; args+=("--smartenergy.simulator.duration=${1:-PT2M}");;
      --interval) shift; args+=("--smartenergy.simulator.interval=${1:-PT2S}");;
      *) args+=("$1");;
    esac
    shift
  done
  ensure_env
  info "Simulateur (Ctrl+C pour interrompre)"
  dc run --rm simulator "${args[@]}"
}

cmd_shell() {
  local target="${1:-}"
  case "$target" in
    redis) dc exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning';;
    mongo|mongosh) dc exec mongo sh -c 'mongosh -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin';;
    cassandra|cql|cqlsh) dc exec cassandra cqlsh;;
    "") die "usage : shell <redis|mongo|cassandra|service>";;
    *) is_known_service "$target" || die "inconnu : $target"; dc exec "$target" bash;;
  esac
}
redis_cli() { dc exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@"' _ "$@"; }
cmd_redis() { if (($# == 0)); then cmd_shell redis; else redis_cli "$@"; fi; }
cmd_cql() { if (($# == 0)); then cmd_shell cassandra; else dc exec -T cassandra cqlsh -e "$*"; fi; }
cmd_mongo() {
  if (($# == 0)); then cmd_shell mongo
  else dc exec -T mongo sh -c 'mongosh -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --quiet --eval "$1"' _ "$*"; fi
}
cmd_stream() {
  info "Stream energy-measurements"
  printf 'XLEN          : '; redis_cli XLEN energy-measurements
  printf 'DLQ (XLEN)    : '; redis_cli XLEN energy-measurements-dlq
  printf '%s\n' '--- groupes (pending = livres non acquittes, lag = restant a lire) ---'
  redis_cli XINFO GROUPS energy-measurements
}
cmd_count() {
  local sensor="${1:-}" day="${2:-$(date -u +%F)}"
  [[ -n "$sensor" ]] || die "usage : count <SENSOR-ID> [YYYY-MM-DD]"
  [[ "$sensor" =~ ^[A-Za-z0-9_-]+$ ]] || die "identifiant de capteur invalide"
  [[ "$day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "date invalide (format attendu : YYYY-MM-DD)"
  cmd_cql "SELECT count(*) FROM smartenergy.measurements_by_sensor_day WHERE sensor_id='$sensor' AND day='$day';"
}
cmd_e2e() {
  [[ -f scripts/e2e.sh ]] || die "scripts/e2e.sh introuvable"
  bash scripts/e2e.sh
}
cmd_todo() { grep -rn --include='*.java' '^// TODO(' . | sed 's#^\./##' || true; }

vol_name() { printf '%s_%s' "$PROJECT" "$1"; }
cmd_backup() {
  have docker || die "docker requis"
  local timestamp dir volume running host_backup
  timestamp="$(date +%Y%m%d-%H%M%S)"
  dir="$ROOT/backups/$timestamp"
  mkdir -p "$dir" || die "Impossible de creer $dir"
  running="$(dc ps -q | wc -l | tr -d ' ')"
  warn "Arret temporaire de la plateforme pour une sauvegarde coherente (Cassandra)."
  dc stop >/dev/null || die "Impossible d'arreter les services pour la sauvegarde"
  host_backup="$(cd "$dir" && (pwd -W 2>/dev/null || pwd))"
  for volume in "${VOLUMES[@]}"; do
    if docker volume inspect "$(vol_name "$volume")" >/dev/null 2>&1; then
      info "Sauvegarde du volume $volume ..."
      MSYS_NO_PATHCONV=1 docker run --rm -v "$(vol_name "$volume"):/data:ro" -v "${host_backup}:/backup" alpine tar czf "/backup/$volume.tar.gz" -C /data . || die "Echec de la sauvegarde de $volume"
    else warn "Volume $volume absent : ignore"; fi
  done
  if ((running > 0)); then dc start >/dev/null; info "Plateforme redemarree."; fi
  ok "Sauvegarde dans backups/$timestamp"
}
cmd_restore() {
  have docker || die "docker requis"
  local selected="${1:-}" dir volume answer= yes=0 host_backup
  [[ -d backups ]] || die "Aucun dossier backups/"
  if [[ -z "$selected" || "$selected" == latest ]]; then selected="$(ls -1 backups 2>/dev/null | sort | tail -n 1)"; fi
  [[ -n "$selected" ]] || die "Aucune sauvegarde disponible"
  dir="$ROOT/backups/$selected"
  [[ -d "$dir" ]] || die "Sauvegarde introuvable : backups/$selected"
  [[ "${2:-}" == --yes || "${2:-}" == -y ]] && yes=1
  warn "RESTAURATION de backups/$selected : les donnees actuelles seront REMPLACEES."
  if (( ! yes )); then
    [[ -t 0 ]] || die "Mode non interactif : ajoutez --yes."
    read -r -p "Tapez 'oui' pour confirmer : " answer
    [[ "$answer" == oui ]] || { info "Annule."; return 0; }
  fi
  dc --profile tools down --remove-orphans >/dev/null || die "Impossible d'arreter les services"
  host_backup="$(cd "$dir" && (pwd -W 2>/dev/null || pwd))"
  for volume in "${VOLUMES[@]}"; do
    if [[ -f "$dir/$volume.tar.gz" ]]; then
      info "Restauration de $volume ..."
      docker volume create "$(vol_name "$volume")" >/dev/null || die "Impossible de creer le volume $volume"
      MSYS_NO_PATHCONV=1 docker run --rm -v "$(vol_name "$volume"):/data" -v "${host_backup}:/backup" alpine sh -c "rm -rf /data/* /data/.[!.]* 2>/dev/null; tar xzf /backup/$volume.tar.gz -C /data" || die "Echec de la restauration de $volume"
    else warn "$volume.tar.gz absent de la sauvegarde : ignore"; fi
  done
  dc up -d || die "Echec du redemarrage"
  wait_ready "$DEFAULT_TIMEOUT" "${ALL_READY[@]}"
}

cmd_help() {
  cat <<'HELP'
SmartEnergy Hub - outil de cycle de vie (Windows : infra\windows\se.cmd <commande>)

DEMARRER / ARRETER
  first-start [--random-secrets] [--timeout N]  Verifie, cree .env, construit et demarre
  start [service...] [--no-wait]                 Demarre sans reconstruire
  stop [service...]                              Arrete (donnees conservees)
  restart [service...]                           Redemarre les conteneurs
  down                                           Supprime les conteneurs, garde les volumes
  rebuild [service...] [--no-cache]              Reconstruit puis redemarre
  reset [--yes]                                  DANGER : supprime conteneurs et volumes
  clean [--yes]                                  Reset et suppression des images locales

OBSERVER
  status                                         Conteneurs et sante HTTP
  health                                         Readiness de chaque service applicatif
  logs [service...] [-n N] [--no-follow] [--since 10m] [--pretty]
  stream                                         Longueur du stream Redis, DLQ et groupes
  count <SENSOR> [YYYY-MM-DD]                    Nombre de lignes Cassandra pour un jour
  stats                                          CPU et memoire des conteneurs
  urls | open                                    Affiche les adresses | ouvre les Swagger UI
  doctor                                         Diagnostic Docker, memoire, ports et .env

DONNEES DE TEST
  simulate [--anomaly[=SENSOR-003]] [--chaos] [--long] [--sensors N] [--duration PT5M] [--interval PT1S]
  e2e                                            Lance scripts/e2e.sh

BASES DE DONNEES
  shell <redis|mongo|cassandra|service>          Shell interactif
  redis [commande...]                            Ex. redis XLEN energy-measurements
  cql [requete]                                   Ex. cql "SELECT * FROM smartenergy.latest_measurement_by_sensor;"
  mongo [js]                                      Commande JavaScript mongosh
  backup                                         Sauvegarde des volumes dans backups/
  restore [dossier|latest] [--yes]                Restaure une sauvegarde

DEVELOPPEMENT
  build [module]                                 mvn clean package
  test [module]                                  mvn test
  pull                                           Met a jour Redis, MongoDB, Cassandra
  todo                                           Recherche les TODO Java

Services : redis mongo cassandra sensor-service collector-service storage-service analytics-service simulator
HELP
}

command_name="${1:-help}"
if (($#)); then shift; fi
case "$command_name" in
  help|-h|--help) cmd_help;;
  doctor) cmd_doctor;;
  first-start|init|bootstrap) cmd_first_start "$@";;
  start|up) cmd_start "$@";;
  stop) cmd_stop "$@";;
  down) cmd_down;;
  restart) cmd_restart "$@";;
  rebuild) cmd_rebuild "$@";;
  reset) cmd_reset "$@";;
  clean) cmd_clean "$@";;
  logs|log) cmd_logs "$@";;
  status|ps) cmd_status;;
  health) cmd_health;;
  urls) cmd_urls;;
  open) cmd_open;;
  stats) cmd_stats;;
  stream) cmd_stream;;
  count) cmd_count "$@";;
  sim|simulate|simulator) cmd_sim "$@";;
  e2e) cmd_e2e;;
  shell|sh) cmd_shell "$@";;
  redis|redis-cli) cmd_redis "$@";;
  cql|cqlsh) cmd_cql "$@";;
  mongo|mongosh) cmd_mongo "$@";;
  backup) cmd_backup;;
  restore) cmd_restore "$@";;
  build) cmd_build "$@";;
  test) cmd_test "$@";;
  pull) cmd_pull;;
  todo) cmd_todo;;
  *) err "commande inconnue : $command_name"; printf '\n'; cmd_help; exit 1;;
esac
