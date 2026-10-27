#!/usr/bin/env bash
# ============================================================================
# Scenarios de recette HTTP (docs/tests.md) - plateforme dockerisee.
# Utilisation : make up  puis  make e2e   (ou ./scripts/e2e.sh)
#
# Couverture : sante des services puis scenarios 1 a 11 du CDC.
#   1  mesure valide -> 202 + evenement dans le stream
#   2  champ manquant -> 400 application/problem+json, aucun evenement
#   3  historique pagine + latest (etape 8)
#   4  pic x3 -> anomalie THRESHOLD listee via /anomalies
#   5  arret de storage-service -> messages conserves (lag en hausse)
#   6  redemarrage de storage-service -> aucune mesure perdue
#   7  redemarrage complet de la plateforme -> donnees toujours la
#   8  doublon -> une seule ligne Cassandra, compteurs analytics inchanges
#   9  Redis arrete -> collector 503 + Retry-After
#   10 message poison -> DLQ, le flux continue
#   11 sensor-service arrete -> seuil par defaut (thresholdSource = DEFAULT)
#
# Le script arrete lui-meme storage-service, redis et sensor-service puis les
# restaure en sortie (trap) ; il termine par le redemarrage complet (scenario 7).
# Les resultats sont reportes dans docs/tests.md (matrice de recette).
# ============================================================================
set -uo pipefail

COLLECTOR="${COLLECTOR_URL:-http://localhost:8081}"
STORAGE="${STORAGE_URL:-http://localhost:8082}"
ANALYTICS="${ANALYTICS_URL:-http://localhost:8083}"
SENSOR="${SENSOR_URL:-http://localhost:8084}"

PASS=0
FAIL=0
STOPPED=()
PLATFORM_DOWN=0
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TODAY=$(date -u +%F)
BODY=$(mktemp)
HDRS=$(mktemp)

# ------------------------------------------------------------------ helpers
cleanup() {
  local s
  if [ "$PLATFORM_DOWN" = "1" ]; then
    echo "  [....] plateforme a l'arret, remise en route"
    docker compose up -d >/dev/null 2>&1
  fi
  for s in ${STOPPED[@]+"${STOPPED[@]}"}; do
    echo "  [....] restauration de $s (sortie anticipee)"
    docker compose start "$s" >/dev/null 2>&1
  done
  rm -f "$BODY" "$HDRS"
}
trap cleanup EXIT

check() { # check "<libelle>" <attendu> <obtenu>
  if [ "$2" = "$3" ]; then
    echo "  [OK]   $1 ($3)"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $1 : attendu $2, obtenu ${3:-vide}"
    FAIL=$((FAIL + 1))
  fi
}

check_ge() { # check_ge "<libelle>" <minimum> <obtenu>
  if [[ "${3:-}" =~ ^-?[0-9]+$ ]] && [ "$3" -ge "$2" ]; then
    echo "  [OK]   $1 ($3 >= $2)"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $1 : attendu >= $2, obtenu ${3:-vide}"
    FAIL=$((FAIL + 1))
  fi
}

payload() { # payload <sensorId> <timestamp ISO> <powerKw>
  printf '{"sensorId":"%s","timestamp":"%s","powerKw":%s,"voltage":226.4}' "$1" "$2" "$3"
}

ts_off() { date -u -d "@$(( $(date +%s) + $1 ))" +%Y-%m-%dT%H:%M:%SZ; }

get_status() { curl -s -o /dev/null -w '%{http_code}' "$1"; }
get_body() { curl -s "$1"; }

post_status() { # post_status <url> <corps>
  curl -s -o /dev/null -w '%{http_code}' -X POST "$1" -H 'Content-Type: application/json' -d "$2"
}

post_full() { # post_full <url> <corps> : remplit $CODE, $BODY, $HDRS
  CODE=$(curl -s -D "$HDRS" -o "$BODY" -w '%{http_code}' -X POST "$1" \
    -H 'Content-Type: application/json' -d "$2")
}

# ------------------------------------------------------------------- Redis
redis_cli() { # redis_cli "<commandes redis-cli>"
  docker compose exec -T redis sh -c \
    "redis-cli -a \"\$REDIS_PASSWORD\" --no-auth-warning $1" 2>/dev/null | tr -d '\r'
}

xlen() { redis_cli "XLEN energy-measurements"; }
dlq_xlen() { redis_cli "XLEN energy-measurements-dlq"; }

# group_field <groupe> <lag|pending|consumers> : champ scalaire de XINFO GROUPS.
# redis-cli en sortie non-TTY (docker compose exec -T) imprime les valeurs "plates",
# sans guillemets ni numerotation : on nettoie puis on compare a l'identique.
group_field() {
  redis_cli "XINFO GROUPS energy-measurements" \
    | sed -E -e 's/^[[:space:]]*[0-9]+[.)][[:space:]]*//' -e 's/"//g' \
    | awk -v g="$1" -v f="$2" \
        '$0 == g { in_g = 1; next }
         in_g && $0 == f { want = 1; next }
         want { gsub(/\(integer\) /, ""); print; exit }
         in_g && $0 == "name" { in_g = 0 }'
}

# ---------------------------------------------------------------- Cassandra
# cql_count "<clause sans WHERE>" : comptage via cqlsh (sortie greppee)
cql_count() {
  docker compose exec -T cassandra cqlsh -e \
    "SELECT count(*) FROM smartenergy.measurements_by_sensor_day WHERE $1" 2>/dev/null \
    | tr -d ' \r' | grep -E '^[0-9]+$' | head -1
}

# ------------------------------------------------------------------- JSON
jget() { # jget <json> <expression python sur d>
  printf '%s' "$1" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('')
    raise SystemExit
print($2)" 2>/dev/null
}

summary_count() { # compteur d'un agregat horaire (GET /summary)
  get_body "$ANALYTICS/api/analytics/$1/summary" \
    | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("count", ""))
except Exception:
    print("")' 2>/dev/null
}

count_anomalies() { # <sensorId> <type>
  get_body "$ANALYTICS/api/analytics/$1/anomalies?type=$2&size=100" \
    | python3 -c 'import json, sys
t = sys.argv[1]
try:
    items = json.load(sys.stdin).get("items", [])
except Exception:
    print(0)
    raise SystemExit
print(len([a for a in items if a.get("type") == t]))' "$2" 2>/dev/null
}

anomaly_first() { # <sensorId> <type> <expression python portant sur a>
  get_body "$ANALYTICS/api/analytics/$1/anomalies?type=$2&size=100" \
    | python3 -c 'import json, sys
expr = sys.argv[1]
try:
    items = json.load(sys.stdin).get("items", [])
except Exception:
    print("")
    raise SystemExit
if not items:
    print("")
    raise SystemExit
a = items[0]
print(eval(expr))' "$3" 2>/dev/null
}

# ------------------------------------------------------------ attentes
wait_ready() { # wait_ready <url> <timeout_s>
  local url=$1 timeout=$2
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    [ "$(get_status "$url")" = "200" ] && return 0
    sleep 2
  done
  return 1
}

wait_for() { # wait_for <timeout_s> <commande...> (succes = code retour 0)
  local timeout=$1 end
  shift
  end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# Predicats pour wait_for
p_readiness() { [ "$(get_status "$1")" = "200" ]; }
p_history_not_empty() {
  [ "$(jget "$(get_body "$STORAGE/api/measurements/$1?limit=100")" '1 if d.get("items") else 0')" = "1" ]
}
p_cql_count_eq() { [ "$(cql_count "$1")" = "$2" ]; }
p_summary_count() { [ "$(summary_count "$1")" = "$2" ]; }
p_anomalies_ge() { [ "$(count_anomalies "$1" "$2")" -ge "$3" ] 2>/dev/null; }
p_dlq_grew() {
  local n
  n=$(dlq_xlen)
  [ -n "$n" ] && [ "$n" -ge "$1" ] 2>/dev/null
}
p_group_drained() {
  [ "$(group_field storage-group lag)" = "0" ] && [ "$(group_field storage-group pending)" = "0" ]
}
p_redis_up() {
  docker compose exec -T redis sh -c \
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping' 2>/dev/null | grep -q PONG
}

# -------------------------------------------------------- services docker
stop_svc() {
  echo "  [....] arret de $1"
  docker compose stop "$1" >/dev/null 2>&1
  STOPPED+=("$1")
}

start_svc() {
  echo "  [....] redemarrage de $1"
  docker compose start "$1" >/dev/null 2>&1
  local kept=() s
  for s in ${STOPPED[@]+"${STOPPED[@]}"}; do
    [ "$s" != "$1" ] && kept+=("$s")
  done
  STOPPED=(${kept[@]+"${kept[@]}"})
}

ensure_sensor() { # ensure_sensor <sensorId> <corps JSON> -> code HTTP (201 ou 200)
  local id=$1 json=$2 code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$SENSOR/api/sensors" \
    -H 'Content-Type: application/json' -d "$json")
  if [ "$code" = "409" ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$SENSOR/api/sensors/$id" \
      -H 'Content-Type: application/json' -d "$json")
  fi
  printf '%s' "$code"
}

# ==========================================================================
# Demarrage : les quatre services doivent repondre
# ==========================================================================
echo "== Attente des services (readiness)"
for u in "$COLLECTOR" "$STORAGE" "$ANALYTICS" "$SENSOR"; do
  wait_ready "$u/actuator/health/readiness" 420
  check "readiness $u" 200 "$(get_status "$u/actuator/health/readiness")"
done

echo "== Sante des services"
for u in "$COLLECTOR" "$STORAGE" "$ANALYTICS" "$SENSOR"; do
  check "GET $u/api/health" 200 "$(get_status "$u/api/health")"
done

# ==========================================================================
echo "== Scenario 1 : mesure valide -> 202 + evenement Redis"
BEFORE=$(xlen)
check "POST mesure valide" 202 "$(post_status "$COLLECTOR/api/measurements" \
  "$(payload SENSOR-001 "$NOW" 3.72)")"
AFTER=$(xlen)
check "XLEN +1" 1 "$((AFTER - BEFORE))"
LAST=$(redis_cli "XREVRANGE energy-measurements + - COUNT 1")
check "dernier evenement du stream porte SENSOR-001" 1 \
  "$(printf '%s' "$LAST" | grep -c 'SENSOR-001')"

# ==========================================================================
echo "== Scenario 2 : mesure sans sensorId -> 400, aucun evenement"
BEFORE=$(xlen)
post_full "$COLLECTOR/api/measurements" \
  '{"timestamp":"'"$NOW"'","powerKw":3.72,"voltage":226.4}'
check "POST sans sensorId" 400 "$CODE"
CT=$(tr -d '\r' < "$HDRS" | awk 'tolower($1) == "content-type:" {print $2}')
check "content-type problem+json" "application/problem+json" "${CT%%;*}"
check "code VALIDATION_ERROR" 1 "$(grep -c '"code":"VALIDATION_ERROR"' "$BODY")"
AFTER=$(xlen)
check "XLEN inchange" 0 "$((AFTER - BEFORE))"

# ==========================================================================
# Etape 12 : limite de taille du corps (10 Ko). Le champ "pad" est ignore par
# le lecteur tolerant : il ne sert qu'a depasser la limite.
echo "== Corps > 10 Ko -> 413 PAYLOAD_TOO_LARGE (etape 12)"
BEFORE=$(xlen)
post_full "$COLLECTOR/api/measurements" \
  '{"sensorId":"SENSOR-001","timestamp":"'"$NOW"'","powerKw":3.72,"voltage":226.4,"pad":"'"$(printf 'x%.0s' $(seq 1 11000))"'"}'
check "POST corps > 10 Ko" 413 "$CODE"
CT=$(tr -d '\r' < "$HDRS" | awk 'tolower($1) == "content-type:" {print $2}')
check "413 content-type problem+json" "application/problem+json" "${CT%%;*}"
check "413 code PAYLOAD_TOO_LARGE" 1 "$(grep -c '"code":"PAYLOAD_TOO_LARGE"' "$BODY")"
AFTER=$(xlen)
check "XLEN inchange apres 413" 0 "$((AFTER - BEFORE))"

# ==========================================================================
echo "== Scenario 3 : historique d'un capteur (etape 8)"
wait_for 45 p_history_not_empty SENSOR-001
HIST=$(get_body "$STORAGE/api/measurements/SENSOR-001?limit=100")
check "GET historique" 200 "$(get_status "$STORAGE/api/measurements/SENSOR-001?limit=100")"
check "au moins 1 mesure dans l'historique" 1 \
  "$(jget "$HIST" '1 if d.get("items") else 0')"
check "GET latest" 200 "$(get_status "$STORAGE/api/measurements/SENSOR-001/latest")"
check "plage invalide (limit=0) -> 400" 400 \
  "$(get_status "$STORAGE/api/measurements/SENSOR-001?limit=0")"

# ==========================================================================
echo "== Scenario 4 : pic x3 -> anomalie THRESHOLD"
S4_CODE=$(ensure_sensor SENSOR-003 \
  '{"sensorId":"SENSOR-003","name":"Capteur pic x3","building":"B1","maxPowerKw":10,"active":true}')
check "capteur SENSOR-003 (seuil 10 kW) en place" 1 \
  "$([ "$S4_CODE" = "201" ] || [ "$S4_CODE" = "200" ] && echo 1 || echo 0)"
# 3 mesures nominales puis un pic x3 : 30 kW > seuil 10 kW
for off in -140 -130 -120; do
  post_status "$COLLECTOR/api/measurements" "$(payload SENSOR-003 "$(ts_off $off)" 3.72)" >/dev/null
done
check "pic 30 kW accepte" 202 "$(post_status "$COLLECTOR/api/measurements" \
  "$(payload SENSOR-003 "$(ts_off -110)" 30)")"
wait_for 45 p_anomalies_ge SENSOR-003 THRESHOLD 1
check "anomalie THRESHOLD listee via /anomalies" 1 \
  "$([ "$(count_anomalies SENSOR-003 THRESHOLD)" -ge 1 ] 2>/dev/null && echo 1 || echo 0)"
check "seuil applique = 10 kW (sensor-service)" 10.0 \
  "$(anomaly_first SENSOR-003 THRESHOLD 'a.get("thresholdKw")')"
check "source du seuil = SENSOR" SENSOR \
  "$(anomaly_first SENSOR-003 THRESHOLD 'a.get("thresholdSource")')"
check "severite = HIGH" HIGH "$(anomaly_first SENSOR-003 THRESHOLD 'a.get("severity")')"
check "puissance du pic = 30 kW" 30.0 \
  "$(anomaly_first SENSOR-003 THRESHOLD 'a.get("powerKw")')"

# ==========================================================================
echo "== Scenario 8 : doublon -> une seule ligne, compteurs inchanges"
DUP_TS=$(ts_off -100)
DUP_DAY=$(date -u -d "$DUP_TS" +%F)
DUP_JSON=$(payload SENSOR-DUP "$DUP_TS" 5.5)
check "1er envoi" 202 "$(post_status "$COLLECTOR/api/measurements" "$DUP_JSON")"
check "2e envoi identique (doublon)" 202 "$(post_status "$COLLECTOR/api/measurements" "$DUP_JSON")"
wait_for 45 p_cql_count_eq "sensor_id='SENSOR-DUP' AND day='$DUP_DAY'" 1
check "une seule ligne Cassandra" 1 "$(cql_count "sensor_id='SENSOR-DUP' AND day='$DUP_DAY'")"
wait_for 45 p_summary_count SENSOR-DUP 1
check "compteur analytics inchange (count=1)" 1 "$(summary_count SENSOR-DUP)"

# ==========================================================================
echo "== Scenario 10 : message poison -> DLQ, le flux continue"
DLQ_BEFORE=$(dlq_xlen)
# Evenement illisible : champs obligatoires absents -> MalformedEventException
redis_cli "XADD energy-measurements '*' sensorId POISON-001 state ENCORE" >/dev/null
wait_for 30 p_dlq_grew "$DLQ_BEFORE"
DLQ_AFTER=$(dlq_xlen)
check_ge "evenement poison envoye en DLQ" 1 "$((DLQ_AFTER - DLQ_BEFORE))"
check "mesure suivante acceptee (le flux continue)" 202 \
  "$(post_status "$COLLECTOR/api/measurements" "$(payload SENSOR-010 "$(ts_off -30)" 6.1)")"
wait_for 45 p_cql_count_eq "sensor_id='SENSOR-010' AND day='$TODAY'" 1
check "mesure suivante persistee en Cassandra" 1 "$(cql_count "sensor_id='SENSOR-010' AND day='$TODAY'")"

# ==========================================================================
echo "== Scenario 11 : sensor-service arrete -> seuil par defaut"
S11_CODE=$(ensure_sensor SENSOR-011 \
  '{"sensorId":"SENSOR-011","name":"Seuil eleve","maxPowerKw":50,"active":true}')
check "capteur SENSOR-011 (seuil 50 kW) en place" 1 \
  "$([ "$S11_CODE" = "201" ] || [ "$S11_CODE" = "200" ] && echo 1 || echo 0)"
check "mesure 30 kW sous le seuil 50 kW" 202 \
  "$(post_status "$COLLECTOR/api/measurements" "$(payload SENSOR-011 "$(ts_off -90)" 30)")"
wait_for 45 p_summary_count SENSOR-011 1
check "aucune anomalie tant que sensor-service repond" 0 \
  "$(count_anomalies SENSOR-011 THRESHOLD)"
stop_svc sensor-service
redis_cli "DEL cache:sensor:SENSOR-011" >/dev/null   # TTL 60 s : on simule l'expiration
check "mesure 30 kW apres coupure de sensor-service" 202 \
  "$(post_status "$COLLECTOR/api/measurements" "$(payload SENSOR-011 "$(ts_off -60)" 30)")"
wait_for 60 p_anomalies_ge SENSOR-011 THRESHOLD 1
check "anomalie THRESHOLD detectee malgre la coupure" 1 \
  "$([ "$(count_anomalies SENSOR-011 THRESHOLD)" -ge 1 ] 2>/dev/null && echo 1 || echo 0)"
check "seuil par defaut 10 kW applique" 10.0 \
  "$(anomaly_first SENSOR-011 THRESHOLD 'a.get("thresholdKw")')"
check "thresholdSource = DEFAULT" DEFAULT \
  "$(anomaly_first SENSOR-011 THRESHOLD 'a.get("thresholdSource")')"
start_svc sensor-service
wait_ready "$SENSOR/actuator/health/readiness" 120
check "sensor-service de nouveau pret" 200 "$(get_status "$SENSOR/actuator/health/readiness")"

# ==========================================================================
echo "== Scenario 5 : arret de storage-service -> messages conserves dans Redis"
N5=10
stop_svc storage-service
sleep 2
for i in $(seq 1 "$N5"); do
  post_status "$COLLECTOR/api/measurements" "$(payload SENSOR-005 "$(ts_off -$((400 + i)))" 4.2)" >/dev/null
done
sleep 4
LAG5=$(group_field storage-group lag)
check_ge "lag storage-group en hausse (messages non lus)" "$N5" "$LAG5"
check "storage-service hors service" 000 "$(get_status "$STORAGE/api/health")"
wait_for 45 p_summary_count SENSOR-005 "$N5"
check "analytics continue de traiter (groupes independants)" "$N5" "$(summary_count SENSOR-005)"

# ==========================================================================
echo "== Scenario 6 : redemarrage de storage-service -> aucune mesure perdue"
start_svc storage-service
wait_ready "$STORAGE/actuator/health/readiness" 180
check "storage-service pret apres redemarrage" 200 \
  "$(get_status "$STORAGE/actuator/health/readiness")"
wait_for 90 p_group_drained
check "lag storage-group revenu a 0" 0 "$(group_field storage-group lag)"
check "pending storage-group revenu a 0" 0 "$(group_field storage-group pending)"
check "comptage Cassandra = mesures acceptees ($N5)" "$N5" \
  "$(cql_count "sensor_id='SENSOR-005' AND day='$TODAY'")"
check "GET historique SENSOR-005" 200 "$(get_status "$STORAGE/api/measurements/SENSOR-005")"

# ==========================================================================
echo "== Scenario 9 : Redis arrete -> collector 503 + Retry-After"
stop_svc redis
sleep 2
post_full "$COLLECTOR/api/measurements" "$(payload SENSOR-009 "$(ts_off -20)" 3.72)"
check "POST avec Redis KO -> 503" 503 "$CODE"
check "Retry-After: 5" 5 \
  "$(tr -d '\r' < "$HDRS" | awk 'tolower($1) == "retry-after:" {print $2}')"
check "code BROKER_UNAVAILABLE" 1 "$(grep -c '"code":"BROKER_UNAVAILABLE"' "$BODY")"
start_svc redis
wait_for 60 p_redis_up
check "Redis de nouveau disponible" 1 "$(p_redis_up && echo 1 || echo 0)"
# Les consumers retablissent leur groupe (retry toutes les 2 s) apres la panne
sleep 15

# ==========================================================================
echo "== Scenario 7 : redemarrage complet de la plateforme (2 a 4 min)"
COUNT_BEFORE=$(cql_count "sensor_id='SENSOR-001' AND day='$TODAY'")
PLATFORM_DOWN=1
echo "  [....] docker compose down (volumes conserves)"
docker compose down >/dev/null 2>&1
echo "  [....] docker compose up -d"
docker compose up -d >/dev/null 2>&1
PLATFORM_DOWN=0
for u in "$COLLECTOR" "$STORAGE" "$ANALYTICS" "$SENSOR"; do
  wait_ready "$u/actuator/health/readiness" 420
  check "readiness apres redemarrage : $u" 200 "$(get_status "$u/actuator/health/readiness")"
done
check "mesures toujours presentes (comptage inchange)" "$COUNT_BEFORE" \
  "$(cql_count "sensor_id='SENSOR-001' AND day='$TODAY'")"
check "GET historique toujours disponible" 200 \
  "$(get_status "$STORAGE/api/measurements/SENSOR-001")"
check "GET summary analytics toujours disponible" 200 \
  "$(get_status "$ANALYTICS/api/analytics/SENSOR-001/summary")"

echo
echo "Resultat : $PASS OK, $FAIL echec(s)"
[ "$FAIL" -eq 0 ]
