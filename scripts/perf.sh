#!/usr/bin/env bash
# ============================================================================
# Mesures de performance (docs/tests.md, sections "Mesures de performance").
# Utilisation : plateforme up (make up) puis  ./scripts/perf.sh
#
#   P1  Debit soutenu du collector : 1000 POST sequentiels sur une connexion
#       keep-alive (fichier de config curl, une seule connexion TCP reutilisee).
#       Cible : >= 200 mesures/s.
#   P2  Lecture d'un jour d'historique : 50 GET limit=1000 sur SENSOR-PERF.
#       Cible : p95 < 300 ms.
#   P3  Retard de bout en bout (POST -> la mesure figure en tete de l'historique) :
#       30 essais, timestamps uniques, polling sur GET historique (non mis en cache).
#       Cible : p95 < 5 s.
#
# Amorcage : le script poste d'abord ~1100 mesures pour SENSOR-PERF afin que
# la requete d'historique lise reellement 1000 lignes Cassandra.
# Les resultats sont reportes dans docs/tests.md (et preuves/tests/).
# ============================================================================
set -uo pipefail

COLLECTOR="${COLLECTOR_URL:-http://localhost:8081}"
STORAGE="${STORAGE_URL:-http://localhost:8082}"
SENSOR="${SENSOR_URL:-http://localhost:8084}"

NOW=$(date -u +%s)

# --------------------------------------------------------------- helpers
ts_off() { date -u -d "@$(( NOW + $1 ))" +%Y-%m-%dT%H:%M:%SZ; }

payload() { # payload <sensorId> <ISO> <powerKw>  (fin de ligne obligatoire : une mesure par ligne pour xargs)
  printf '{"sensorId":"%s","timestamp":"%s","powerKw":%s,"voltage":226.4}\n' "$1" "$2" "$3"
}

p95() { # p95 <fichier de valeurs ms> : 95e percentile (methode rang)
  sort -n "$1" | awk '{v[NR]=$1} END {if (NR>0) printf "%.1f", v[int(NR*0.95+0.5)]; else print "n/a"}'
}

median() {
  sort -n "$1" | awk '{v[NR]=$1} END {if (NR>0) printf "%.1f", v[int(NR/2)+ (NR%2?1:0)]; else print "n/a"}'
}

now_ms() { date +%s%N; }

cql_total() { # total des lignes d'un capteur sur les jours donnees
  local id=$1 d total=0 n
  for d in "$2" "$3"; do
    n=$(docker compose exec -T cassandra cqlsh -e \
      "SELECT count(*) FROM smartenergy.measurements_by_sensor_day WHERE sensor_id='$id' AND day='$d'" \
      2>/dev/null | tr -d ' \r' | grep -E '^[0-9]+$' | head -1)
    total=$(( total + ${n:-0} ))
  done
  printf '%s' "$total"
}

# ------------------------------------------------- capteur dedie aux mesures
echo "== Preparation : capteur SENSOR-PERF"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$SENSOR/api/sensors" \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-PERF","name":"Capteur performances","building":"PERF","maxPowerKw":100,"active":true}')
if [ "$code" = "409" ]; then
  curl -s -o /dev/null -X PUT "$SENSOR/api/sensors/SENSOR-PERF" \
    -H 'Content-Type: application/json' \
    -d '{"sensorId":"SENSOR-PERF","name":"Capteur performances","building":"PERF","maxPowerKw":100,"active":true}'
fi

DAY_TODAY=$(date -u +%F)
DAY_PREV=$(date -u -d "@$(( NOW - 86400 ))" +%F)

# Amorcage : 1100 mesures espacees de 30 s (~9 h) pour remplir l'historique
echo "== Amorcage : 1100 mesures pour SENSOR-PERF (env. 1 min)"
SEQ=$(mktemp); PAYLOADS=$(mktemp); LAT=$(mktemp)
for i in $(seq 0 1099); do
  printf '%s\n' "$(( -30 * i ))" >> "$SEQ"
done
# generation des corps de requetes (une ligne par mesure)
: > "$PAYLOADS"
i=0
while IFS= read -r off; do
  v=$(awk -v n="$i" 'BEGIN{printf "%.3f", 1 + (n % 800) / 100}')
  payload SENSOR-PERF "$(ts_off "$off")" "$v" >> "$PAYLOADS"
  i=$(( i + 1 ))
done < "$SEQ"

xargs -d '\n' -P 8 -I {} curl -s -o /dev/null --max-time 10 \
  -X POST "$COLLECTOR/api/measurements" -H 'Content-Type: application/json' -d '{}' < "$PAYLOADS"

echo "== Attente de la persistance Cassandra (~1100 lignes)"
ok=0
for _ in $(seq 1 60); do
  n=$(cql_total SENSOR-PERF "$DAY_TODAY" "$DAY_PREV")
  [ "${n:-0}" -ge 1000 ] && { ok=1; break; }
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "KO : seulement ${n:-0} lignes Cassandra pour SENSOR-PERF (abandon)" >&2
  rm -f "$SEQ" "$PAYLOADS" "$LAT"
  exit 1
fi
echo "   $n lignes disponibles"

# ====================================================================== P1
echo "== P1 : debit soutenu du collector (1000 POST sequentiels, keep-alive)"
# Un fichier de config curl = une seule connexion TCP reutilisee pour les 1000 requetes
# (sinon ~14 ms de handshake TCP par requete ecrase le debit reel, mesure artefactuelle).
# Les timestamps sont uniques (ecartes de 30 s) : pas de dedup ni de collision d'eventId.
CFG=$(mktemp)
python3 - "$COLLECTOR" "$NOW" > "$CFG" <<'PYEOF'
import datetime, sys
url, now = sys.argv[1], int(sys.argv[2])
out = []
for i in range(1000):
    ts = datetime.datetime.fromtimestamp(
        now - 43200 - i * 30, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    v = 2 + (i % 700) / 100
    body = '{"sensorId":"SENSOR-PERF","timestamp":"%s","powerKw":%.3f,"voltage":226.4}' % (ts, v)
    out.append('url = "%s/api/measurements"' % url)
    out.append('header = "Content-Type: application/json"')
    out.append('output = "/dev/null"')
    out.append('data = "%s"' % body.replace('\\', '\\\\').replace('"', '\\"'))
print('\n'.join(out))
PYEOF
T0=$(now_ms)
curl -s -K "$CFG"
T1=$(now_ms)
rm -f "$CFG"
RATE=$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.1f", 1000000000 / (b - a) * 1000}')
DUR=$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.2f", (b - a) / 1e9}')

# ====================================================================== P2
echo "== P2 : lecture d'historique, 50 GET limit=1000 (p95 cible < 300 ms)"
: > "$LAT"
for _ in $(seq 1 50); do
  curl -s -o /dev/null -w '%{time_total}\n' --max-time 10 \
    "$STORAGE/api/measurements/SENSOR-PERF?limit=1000" >> "$LAT"
done
awk '{print $1 * 1000}' "$LAT" > "$LAT.ms"
P95_HIST=$(p95 "$LAT.ms")
MED_HIST=$(median "$LAT.ms")

# ====================================================================== P3
echo "== P3 : retard de bout en bout, 30 essais (p95 cible < 5 s)"
# Capteur dedie : SENSOR-PERF est pollue par l'amorcage (une mesure postee a NOW reste
# toujours en tete de limit=1). Ici chaque essai poste une mesure PLUS RECENTE que la
# precedente sur son propre capteur : le poll limit=1 devient un test exact de persistance.
P3_SENSOR="SENSOR-PERF-E2E"
: > "$LAT"
for i in $(seq 1 30); do
  v=$(awk -v n="$i" 'BEGIN{printf "%.3f", 5 + n / 100}')
  # Timestamp unique et croissant par essai (-29 s .. 0 s) : un eventId par essai.
  TS=$(ts_off -$(( 30 - i )))
  T0=$(now_ms)
  curl -s -o /dev/null --max-time 10 -X POST "$COLLECTOR/api/measurements" \
    -H 'Content-Type: application/json' -d "$(payload "$P3_SENSOR" "$TS" "$v")"
  # On poll l'historique (lecture Cassandra DIRECTE, pas le cache /latest TTL 30 s qui
  # servirait une valeur anterieure) jusqu'a ce que la mesure postee arrive en tete.
  seen=0
  for _ in $(seq 1 100); do
    if curl -s --max-time 5 "$STORAGE/api/measurements/$P3_SENSOR?limit=1" \
        | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    items = d.get('items') or []
except Exception:
    raise SystemExit(1)
raise SystemExit(0 if items and items[0].get('timestamp') == '$TS' else 1)"; then
      seen=1; break
    fi
    sleep 0.2
  done
  T1=$(now_ms)
  if [ "$seen" = 1 ]; then
    awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%.0f\n", (b - a) / 1e6}' >> "$LAT"
  else
    echo "timeout" >> "$LAT"
  fi
done
grep -E '^[0-9]+$' "$LAT" > "$LAT.ok" || true
P95_E2E=$(p95 "$LAT.ok")
MED_E2E=$(median "$LAT.ok")
NB_OK=$(wc -l < "$LAT.ok")

rm -f "$SEQ" "$PAYLOADS" "$LAT" "$LAT.ms" "$LAT.ok"

# ================================================================== resultats
echo
echo "==================== RESULTATS ===================="
echo "P1 debit collector        : $RATE mesures/s (1000 POST sequentiels keep-alive en $DUR s)  [cible >= 200]"
echo "P2 historique limit=1000  : p95 ${P95_HIST} ms, mediane ${MED_HIST} ms (50 requetes)   [cible p95 < 300]"
echo "P3 bout en bout           : p95 ${P95_E2E} ms, mediane ${MED_E2E} ms ($NB_OK/30 essais)   [cible p95 < 5000]"
