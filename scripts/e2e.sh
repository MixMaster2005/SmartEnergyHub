#!/usr/bin/env bash
# ============================================================================
# Scenarios de recette HTTP (docs/tests.md). Prerequis : plateforme demarree (make up).
# Les scenarios 1 et 2 fonctionnent des que le collector est active (etape 3).
# Les suivants sont marques TODO : completez-les au fil de l'avancement et reportez
# les resultats dans docs/tests.md.
# ============================================================================
set -uo pipefail

COLLECTOR="${COLLECTOR_URL:-http://localhost:8081}"
STORAGE="${STORAGE_URL:-http://localhost:8082}"
ANALYTICS="${ANALYTICS_URL:-http://localhost:8083}"
SENSOR="${SENSOR_URL:-http://localhost:8084}"
PASS=0; FAIL=0

check() { # check "<libelle>" <attendu> <obtenu>
  if [ "$2" == "$3" ]; then echo "  [OK]   $1 (HTTP $3)"; PASS=$((PASS+1));
  else echo "  [FAIL] $1 : attendu $2, obtenu $3"; FAIL=$((FAIL+1)); fi
}
post_status() { curl -s -o /dev/null -w '%{http_code}' -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }
xlen() { docker compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning XLEN energy-measurements' 2>/dev/null | tr -d '\r'; }
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "== Sante des services"
for u in "$COLLECTOR" "$STORAGE" "$ANALYTICS" "$SENSOR"; do
  check "GET $u/api/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$u/api/health")"
done

echo "== Scenario 1 : mesure valide -> 202 + evenement Redis"
BEFORE=$(xlen)
check "POST mesure valide" 202 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4,\"temperature\":29.1}")"
AFTER=$(xlen); check "XLEN +1" 1 "$((AFTER-BEFORE))"

echo "== Scenario 2 : mesure sans sensorId -> 400, aucun evenement"
BEFORE=$(xlen)
check "POST sans sensorId" 400 "$(post_status "$COLLECTOR/api/measurements" \
  "{\"timestamp\":\"$NOW\",\"powerKw\":3.72,\"voltage\":226.4}")"
AFTER=$(xlen); check "XLEN inchange" 0 "$((AFTER-BEFORE))"

echo "== Scenario 3 : historique -> TODO (etape 8)"
# FROM=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ)
# check "GET historique" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$STORAGE/api/measurements/SENSOR-001?from=$FROM&to=$NOW")"

echo "== Scenario 4 : pic x3 -> anomalie THRESHOLD -> TODO (etape 9)"
# lancer : docker compose run --rm simulator --smartenergy.simulator.anomaly.sensor=SENSOR-003
# check "GET anomalies" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$ANALYTICS/api/analytics/SENSOR-003/anomalies")"

echo "== Scenarios 5-6 : arret/redemarrage storage -> TODO (etape 4/7)"
# docker compose stop storage-service ; envoyer des mesures ; verifier que XINFO GROUPS montre lag/pending
# docker compose start storage-service ; comparer comptage Cassandra et mesures acceptees

echo "== Scenario 7 : redemarrage complet -> TODO (etape 10)"
# make down && make up ; verifier que l'historique est toujours present

echo
echo "Resultat : $PASS OK, $FAIL echec(s)"
[ "$FAIL" -eq 0 ]
