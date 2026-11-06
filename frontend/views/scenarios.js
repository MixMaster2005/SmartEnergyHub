/**
 * Page Scenarios : rejoue les controles de recette depuis le navigateur et produit
 * un rapport Markdown/JSON au format `preuves/tests/resultats-modele.md`.
 *
 * Les scenarios automatiques utilisent le capteur dedie `UI-TEST-001` (jamais les
 * capteurs du simulateur) et attendent la persistance par sondage, sans `sleep` fixe.
 */
import { api, request } from '../api.js';
import { errorMessage } from '../errors.js';
import { escapeHtml, formatNumber } from '../format.js';
import { buildJson, buildReport } from '../report.js';
import { badge, download, logLine, toast } from '../ui.js';

const UI_SENSOR_ID = 'UI-TEST-001';
const UI_MAX_POWER_KW = 10;
const WAIT_TIMEOUT_MS = 40_000;
const POLL_INTERVAL_MS = 1_500;

function nowIso(offsetMs = 0) {
  return new Date(Date.now() + offsetMs).toISOString();
}

function measurement({ sensorId = UI_SENSOR_ID, timestamp = nowIso(), powerKw = 3.72 } = {}) {
  return {
    sensorId,
    timestamp,
    powerKw,
    voltage: 230,
    temperature: 22.5,
    currentA: Number((powerKw / 0.23).toFixed(2)),
    frequencyHz: 50,
    state: 'ON',
  };
}

function wait(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(new DOMException('aborted', 'AbortError'));
    }, { once: true });
  });
}

/** Attend qu'un predicat asynchrone devienne vrai, sans depassement de delai. */
async function waitFor(predicate, { signal, timeoutMs = WAIT_TIMEOUT_MS, intervalMs = POLL_INTERVAL_MS, label = 'condition' } = {}) {
  const started = Date.now();
  for (;;) {
    if (signal?.aborted) throw new DOMException('aborted', 'AbortError');
    try {
      const value = await predicate();
      if (value) return value;
    } catch (error) {
      const code = error?.problem?.code;
      if (code !== 'NO_DATA' && code !== 'MEASUREMENT_NOT_FOUND') throw error;
    }
    if (Date.now() - started >= timeoutMs) {
      throw new Error(`Délai dépassé (${label}, ${Math.round(timeoutMs / 1000)} s).`);
    }
    await wait(intervalMs, signal);
  }
}

function sameInstant(left, right) {
  return new Date(left).getTime() === new Date(right).getTime();
}

async function expectError(action, expectedStatus, expectedCode) {
  try {
    await action();
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    const problem = error?.problem;
    if (!problem) throw error;
    if (expectedStatus && error.status !== expectedStatus) {
      throw new Error(`Attendu ${expectedStatus}, obtenu ${error.status} (${problem.code}).`);
    }
    if (expectedCode && problem.code !== expectedCode) {
      throw new Error(`Attendu ${expectedCode}, obtenu ${problem.code}.`);
    }
    return problem;
  }
  throw new Error(`Attendu ${expectedStatus || ''} ${expectedCode || ''}, mais la requête a réussi.`);
}

export async function render({ mount, signal }) {
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Scénarios</h1>
        <p>Preuves de recette exécutées depuis le navigateur. Le capteur <code>${UI_SENSOR_ID}</code> est dédié à ces tests.</p>
      </div>
      <div class="page-head__actions">
        <button class="button button--ghost" type="button" data-prepare>Préparer le capteur</button>
        <button class="button" type="button" data-run-all>Tout exécuter</button>
        <button class="button button--ghost" type="button" data-export>Exporter le rapport</button>
      </div>
    </div>

    <section class="section">
      <div class="card">
        <div class="section__head">
          <h2>Capteur de test</h2>
          <span class="muted small" data-sensor-state>non vérifié</span>
        </div>
        <p class="small muted">Identifiant réservé <code>${UI_SENSOR_ID}</code> · seuil ${UI_MAX_POWER_KW} kW ·
          la suppression logique (<code>DELETE</code>) ne fait que désactiver : ce scénario fait un upsert idempotent.</p>
      </div>
    </section>

    <section class="section">
      <div class="section__head"><h2>Scénarios automatiques</h2><span class="muted small">T01 à T14 (partiels)</span></div>
      <div class="grid grid--two" data-scenarios></div>
    </section>

    <section class="section">
      <div class="section__head"><h2>Manipulations guidées</h2><span class="muted small">T10, Redis, sensor-service, Compose</span></div>
      <div class="grid grid--two">
        <div class="card">
          <h3 style="margin-top:0">T10 — panne du stockage</h3>
          <p class="small muted">Dans un terminal :</p>
          <pre class="log-panel"><code>docker compose stop storage-service</code></pre>
          <p class="small muted">Le lag analytics doit monter. Puis :</p>
          <pre class="log-panel"><code>docker compose start storage-service</code></pre>
          <div class="page-head__actions" style="margin-top:.6rem">
            <button class="button button--ghost button--sm" type="button" data-check-t10>Vérifier maintenant</button>
            <span class="small muted" data-t10-state>—</span>
          </div>
        </div>

        <div class="card">
          <h3 style="margin-top:0">Redis arrêté — 503 attendu</h3>
          <p class="small muted">Arrêtez Redis, puis testez l'ingestion :</p>
          <pre class="log-panel"><code>docker compose stop redis</code></pre>
          <div class="page-head__actions" style="margin-top:.6rem">
            <button class="button button--ghost button--sm" type="button" data-check-redis>Tester POST (503)</button>
            <span class="small muted" data-redis-state>—</span>
          </div>
          <p class="small muted" style="margin-top:.6rem">Relance : <code>docker compose start redis</code></p>
        </div>

        <div class="card">
          <h3 style="margin-top:0">sensor-service arrêté — seuil par défaut</h3>
          <p class="small muted"><code>docker compose stop sensor-service</code> puis lisez les anomalies :
            <code>thresholdSource=DEFAULT</code> doit apparaître.</p>
          <div class="page-head__actions" style="margin-top:.6rem">
            <button class="button button--ghost button--sm" type="button" data-check-sensor>Lire les anomalies</button>
            <span class="small muted" data-sensor-check-state>—</span>
          </div>
        </div>

        <div class="card">
          <h3 style="margin-top:0">T12 — Compose</h3>
          <p class="small muted">Redémarrage complet, volumes conservés :</p>
          <pre class="log-panel"><code>docker compose down
docker compose up -d</code></pre>
          <p class="small muted">La page se reconnecte automatiquement ; vérifiez la santé et l'historique.</p>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section__head"><h2>Journal d'exécution</h2><span class="muted small" data-log-count></span></div>
      <div class="log-panel" data-log></div>
    </section>
  `;

  const nodes = {
    sensorState: mount.querySelector('[data-sensor-state]'),
    scenarios: mount.querySelector('[data-scenarios]'),
    log: mount.querySelector('[data-log]'),
    logCount: mount.querySelector('[data-log-count]'),
    t10: mount.querySelector('[data-t10-state]'),
    redis: mount.querySelector('[data-redis-state]'),
    sensorCheck: mount.querySelector('[data-sensor-check-state]'),
  };

  const results = new Map();
  let sensor = null;
  let running = false;
  let logCount = 0;

  function log(message, tone = 'neutral') {
    logCount += 1;
    logLine(nodes.log, message, tone);
    nodes.logCount.textContent = `${logCount} ligne(s)`;
  }

  function setResult(id, status, evidence = '', comment = '') {
    results.set(id, { status, evidence, comment, at: new Date().toISOString() });
    const card = mount.querySelector(`[data-scenario="${id}"]`);
    if (!card) return;
    const badgeNode = card.querySelector('[data-badge]');
    const evidenceNode = card.querySelector('[data-evidence]');
    if (badgeNode) {
      badgeNode.innerHTML = status === 'OK'
        ? badge('OK', 'ok')
        : status === 'KO'
          ? badge('KO', 'danger')
          : badge('NA', 'neutral');
    }
    if (evidenceNode) evidenceNode.textContent = evidence;
  }

  async function ensureSensor(force = false) {
    if (sensor && !force) return sensor;
    try {
      sensor = await api.sensor(UI_SENSOR_ID, { signal, timeoutMs: 6000 });
    } catch (error) {
      if (error?.problem?.code !== 'SENSOR_NOT_FOUND') throw error;
      sensor = null;
    }
    const body = {
      sensorId: UI_SENSOR_ID,
      name: 'Capteur UI de recette',
      building: 'Bâtiment UI',
      floor: 0,
      maxPowerKw: UI_MAX_POWER_KW,
      active: true,
      tags: ['ui-test'],
    };
    if (!sensor) {
      try {
        sensor = await api.createSensor(body, { signal, timeoutMs: 6000 });
        log(`Capteur ${UI_SENSOR_ID} créé.`, 'ok');
      } catch (error) {
        if (error?.problem?.code === 'SENSOR_ALREADY_EXISTS') {
          sensor = await api.sensor(UI_SENSOR_ID, { signal, timeoutMs: 6000 });
        } else {
          throw error;
        }
      }
    } else if (sensor.active === false || Number(sensor.maxPowerKw) !== UI_MAX_POWER_KW) {
      sensor = await api.updateSensor(UI_SENSOR_ID, { ...body, version: sensor.version }, { signal, timeoutMs: 6000 });
      log(`Capteur ${UI_SENSOR_ID} réactivé (version ${sensor.version}).`, 'ok');
    }
    nodes.sensorState.textContent = `${sensor.sensorId} · seuil ${formatNumber(sensor.maxPowerKw, 1)} kW · ${sensor.active === false ? 'inactif' : 'actif'} · version ${sensor.version}`;
    return sensor;
  }

  async function post(payload, options = {}) {
    return api.postMeasurement(payload, { signal, timeoutMs: 10000, ...options });
  }

  async function historyContains(timestamp, sensorId = UI_SENSOR_ID, { count = 1 } = {}) {
    const from = new Date(new Date(timestamp).getTime() - 2_000);
    const to = new Date(Date.now() + 10_000);
    const history = await api.history(sensorId, { from, to, limit: 1000, order: 'asc' }, { signal, timeoutMs: 10000 });
    const items = Array.isArray(history?.items) ? history.items : [];
    return items.filter((item) => sameInstant(item.timestamp, timestamp)).length >= count;
  }

  async function findAnomaly(timestamp, type = 'THRESHOLD') {
    const from = new Date(new Date(timestamp).getTime() - 5 * 60_000);
    const to = new Date(Date.now() + 60_000);
    const page = await api.anomalies(UI_SENSOR_ID, { from, to, type, size: 100 }, { signal, timeoutMs: 10000 });
    const items = Array.isArray(page?.items) ? page.items : [];
    return items.find((anomaly) => sameInstant(anomaly.timestamp, timestamp)) || null;
  }

  async function checkHealth() {
    const services = ['gateway', 'collector', 'storage', 'analytics', 'sensor'];
    const resultsHealth = await Promise.allSettled(
      services.map((service) => api.health(service, { signal, timeoutMs: 5000 })),
    );
    const down = services.filter((service, index) => {
      const result = resultsHealth[index];
      return result.status !== 'fulfilled' || result.value?.status !== 'UP';
    });
    if (down.length) throw new Error(`Services non sains : ${down.join(', ')}.`);
    return { evidence: '5/5 services UP (gateway + 4 routes ops D-26)', comment: 'santé globale' };
  }

  async function checkMetadata() {
    const created = !sensor;
    await ensureSensor();
    if (!sensor?.sensorId) throw new Error('Le capteur UI-TEST-001 est introuvable après upsert.');
    return {
      evidence: `${created ? 'création' : 'lecture/upsert'} OK · GET /api/sensors/${UI_SENSOR_ID}`,
      comment: `MongoDB sensor_db · tags=${(sensor.tags || []).join(',') || '—'}`,
    };
  }

  async function checkValidMeasurement() {
    await ensureSensor();
    const payload = measurement({ powerKw: 3.72 });
    const response = await post(payload);
    if (response.status !== 202) throw new Error(`Attendu 202, obtenu ${response.status}.`);
    const eventId = response.headers.get('x-event-id');
    if (!eventId) throw new Error('En-tête X-Event-Id absent.');
    await waitFor(() => historyContains(payload.timestamp), { signal, label: 'persistance de la mesure' });
    return {
      evidence: `202 · X-Event-Id=${eventId} · mesure retrouvée dans l'historique`,
      comment: `timestamp=${payload.timestamp} · powerKw=3.72`,
    };
  }

  async function checkMissingField() {
    const payload = measurement({ powerKw: 2.5 });
    delete payload.sensorId;
    const problem = await expectError(() => post(payload), 400, 'VALIDATION_ERROR');
    const fields = (problem.errors || []).map((entry) => entry.field).filter(Boolean);
    return {
      evidence: `400 VALIDATION_ERROR${fields.length ? ` · champs: ${fields.join(', ')}` : ''}`,
      comment: 'aucune publication attendue',
    };
  }

  async function checkInvalidValue() {
    const stringPower = measurement({ powerKw: 2.5 });
    stringPower.powerKw = '3.72';
    await expectError(() => post(stringPower), 400, 'MALFORMED_JSON');

    const oversized = `${JSON.stringify(measurement({ powerKw: 2.5 })).slice(0, -1)},"padding":"${'x'.repeat(11_000)}"}`;
    await expectError(
      () => post(oversized, { headers: { 'Content-Type': 'application/json' } }),
      413,
      'PAYLOAD_TOO_LARGE',
    );

    return {
      evidence: '400 MALFORMED_JSON (powerKw en chaîne) · 413 PAYLOAD_TOO_LARGE (> 10 Ko)',
      comment: 'aucune coercition de type ; limite de corps respectée',
    };
  }

  async function checkTemporalBounds() {
    const old = measurement({ timestamp: nowIso(-25 * 3_600_000), powerKw: 2.4 });
    await expectError(() => post(old), 400, 'MEASUREMENT_TOO_OLD');
    const future = measurement({ timestamp: nowIso(10 * 60_000), powerKw: 2.4 });
    await expectError(() => post(future), 400, 'MEASUREMENT_IN_FUTURE');
    return {
      evidence: '400 MEASUREMENT_TOO_OLD (25 h) · 400 MEASUREMENT_IN_FUTURE (+10 min)',
      comment: 'bornes D-21',
    };
  }

  async function checkHistory() {
    await ensureSensor();
    const from = new Date(Date.now() - 24 * 3_600_000);
    const to = new Date(Date.now() + 60_000);
    const history = await api.history(UI_SENSOR_ID, { from, to, limit: 10, order: 'asc' }, { signal, timeoutMs: 8000 });
    const items = Array.isArray(history?.items) ? history.items : [];
    const ordered = items.every((item, index) => index === 0
      || new Date(items[index - 1].timestamp).getTime() <= new Date(item.timestamp).getTime());
    if (!ordered) throw new Error('Historique non ordonné en ordre ascendant.');
    await expectError(
      () => api.history(UI_SENSOR_ID, { from, to, limit: 0, order: 'asc' }, { signal, timeoutMs: 8000 }),
      400,
      'INVALID_PARAMETER',
    );
    return {
      evidence: `${items.length} élément(s) ordonné(s) · limit=0 → 400 INVALID_PARAMETER`,
      comment: 'pagination par nextCursor (opaque)',
    };
  }

  async function checkAverage() {
    await ensureSensor();
    const payload = measurement({ powerKw: 5.04 });
    const response = await post(payload);
    if (response.status !== 202) throw new Error(`Attendu 202, obtenu ${response.status}.`);
    const summary = await waitFor(async () => {
      const value = await api.summary(UI_SENSOR_ID, {
        from: new Date(Date.now() - 2 * 3_600_000),
        to: new Date(Date.now() + 60_000),
      }, { signal, timeoutMs: 8000 });
      return Number(value?.count) > 0 ? value : null;
    }, { signal, label: 'agrégat horaire' });
    return {
      evidence: `summary.count=${summary.count} · avg=${formatNumber(summary.avgPowerKw, 2)} kW · energy=${formatNumber(summary.energyKwh, 3)} kWh`,
      comment: 'agrégat horaire analytics (D-13)',
    };
  }

  async function checkThreshold() {
    await ensureSensor();
    const payload = measurement({ powerKw: UI_MAX_POWER_KW * 3 });
    const response = await post(payload);
    if (response.status !== 202) throw new Error(`Attendu 202, obtenu ${response.status}.`);
    const anomaly = await waitFor(() => findAnomaly(payload.timestamp, 'THRESHOLD'), { signal, label: 'anomalie THRESHOLD' });
    return {
      evidence: `THRESHOLD ${anomaly.severity} · power=${formatNumber(anomaly.powerKw, 2)} kW · threshold=${formatNumber(anomaly.thresholdKw, 2)} kW · source=${anomaly.thresholdSource}`,
      comment: `pic ×3 (${UI_MAX_POWER_KW * 3} kW)`,
    };
  }

  async function checkDuplicate() {
    await ensureSensor();
    const payload = measurement({ powerKw: 4.04 });
    const first = await post(payload);
    const second = await post(payload);
    if (first.status !== 202 || second.status !== 202) {
      throw new Error(`Attendu deux 202, obtenu ${first.status} puis ${second.status}.`);
    }
    const firstId = first.headers.get('x-event-id');
    const secondId = second.headers.get('x-event-id');
    if (!firstId || firstId !== secondId) {
      throw new Error(`X-Event-Id différents : ${firstId || 'absent'} / ${secondId || 'absent'}.`);
    }
    await waitFor(() => historyContains(payload.timestamp, UI_SENSOR_ID, { count: 1 }), { signal, label: 'déduplication Cassandra' });
    const from = new Date(new Date(payload.timestamp).getTime() - 2_000);
    const to = new Date(Date.now() + 10_000);
    const history = await api.history(UI_SENSOR_ID, { from, to, limit: 100, order: 'asc' }, { signal, timeoutMs: 8000 });
    const count = (history?.items || []).filter((item) => sameInstant(item.timestamp, payload.timestamp)).length;
    if (count !== 1) throw new Error(`${count} ligne(s) pour le même timestamp (attendu 1).`);
    return {
      evidence: `2 × 202 · même X-Event-Id (${firstId}) · 1 seule ligne dans l'historique`,
      comment: `timestamp=${payload.timestamp}`,
    };
  }

  async function checkOpenApi() {
    const services = ['collector', 'storage', 'analytics', 'sensor'];
    for (const service of services) {
      const doc = await request(`/v3/api-docs/${service}`, { signal, timeoutMs: 8000 });
      if (!doc || (!doc.openapi && !doc.swagger)) throw new Error(`Document OpenAPI ${service} illisible.`);
    }
    return { evidence: '4/4 /v3/api-docs/{service} → 200 et document OpenAPI parsé', comment: 'Swagger agrégé sur :8080' };
  }

  async function checkPersistence() {
    await ensureSensor();
    const payload = measurement({ powerKw: 6.06 });
    const response = await post(payload);
    if (response.status !== 202) throw new Error(`Attendu 202, obtenu ${response.status}.`);
    await waitFor(() => historyContains(payload.timestamp), { signal, label: 'lecture Cassandra' });
    return {
      evidence: `mesure ${payload.timestamp} relue via GET /api/measurements/${UI_SENSOR_ID}`,
      comment: 'storage-service · Cassandra measurements_by_sensor_day',
    };
  }

  const AUTO = [
    { id: 'T01', title: 'T01 — Santé', description: 'Les 5 points d\'entrée répondent UP via la gateway.', run: checkHealth },
    { id: 'T06', title: 'T06 — Métadonnées MongoDB', description: 'Upsert idempotent du capteur UI-TEST-001 dans sensor-service.', run: checkMetadata },
    { id: 'T02', title: 'T02 — Mesure valide', description: 'POST valide → 202, X-Event-Id, puis mesure visible dans l\'historique.', run: checkValidMeasurement },
    { id: 'T03', title: 'T03 — Champ manquant', description: 'sensorId absent → 400 VALIDATION_ERROR avec la liste des champs.', run: checkMissingField },
    { id: 'T04', title: 'T04 — Valeur invalide', description: 'powerKw en chaîne → 400 MALFORMED_JSON ; corps > 10 Ko → 413.', run: checkInvalidValue },
    { id: 'T04b', title: 'T04b — Bornes temporelles', description: 'Mesure de 25 h → 400 MEASUREMENT_TOO_OLD ; future +10 min → 400.', run: checkTemporalBounds },
    { id: 'T07', title: 'T07 — Historique', description: 'Lecture ordonnée, puis limit=0 → 400 INVALID_PARAMETER.', run: checkHistory },
    { id: 'T08', title: 'T08 — Moyenne', description: 'Summary horaire avec count et moyenne non nuls après une mesure.', run: checkAverage },
    { id: 'T09', title: 'T09 — Dépassement', description: 'Pic ×3 → anomalie THRESHOLD visible dans /anomalies.', run: checkThreshold },
    { id: 'T11', title: 'T11 — Doublon', description: 'Même mesure deux fois → même X-Event-Id et une seule ligne.', run: checkDuplicate },
    { id: 'T05', title: 'T05 — Persistance Cassandra', description: 'Mesure relue depuis Cassandra via l\'API storage.', run: checkPersistence },
    { id: 'T13', title: 'T13 — OpenAPI', description: 'Les 4 documents OpenAPI sont servis par la gateway.', run: checkOpenApi },
  ];

  function scenarioCard(scenario) {
    return `<article class="scenario" data-scenario="${scenario.id}">
      <div class="scenario__head">
        <h3>${escapeHtml(scenario.title)}</h3>
        <span data-badge>${badge('NA', 'neutral')}</span>
      </div>
      <p class="scenario__desc">${escapeHtml(scenario.description)}</p>
      <div class="scenario__actions">
        <button class="button button--ghost button--sm" type="button" data-run="${scenario.id}">Lancer</button>
      </div>
      <div class="scenario__result" data-evidence></div>
    </article>`;
  }

  nodes.scenarios.innerHTML = AUTO.map(scenarioCard).join('');

  async function runScenario(scenario) {
    const card = mount.querySelector(`[data-scenario="${scenario.id}"]`);
    const button = card?.querySelector('[data-run]');
    if (button) button.disabled = true;
    const badgeNode = card?.querySelector('[data-badge]');
    if (badgeNode) badgeNode.innerHTML = badge('en cours', 'info');
    const started = performance.now();
    log(`▶ ${scenario.id} — ${scenario.title}`, 'neutral');
    try {
      const outcome = await scenario.run();
      const elapsed = Math.round(performance.now() - started);
      setResult(scenario.id, 'OK', outcome.evidence, outcome.comment);
      log(`✓ ${scenario.id} OK (${elapsed} ms) — ${outcome.evidence}`, 'ok');
      toast(`${scenario.id} : OK`, 'success', 3000);
    } catch (error) {
      if (error?.name === 'AbortError' || signal.aborted) return;
      const message = errorMessage(error);
      setResult(scenario.id, 'KO', message, 'voir le journal');
      log(`✗ ${scenario.id} KO — ${message}`, 'danger');
      toast(`${scenario.id} : KO`, 'danger', 5000);
    } finally {
      if (button) button.disabled = false;
    }
  }

  mount.querySelector('[data-prepare]')?.addEventListener('click', async () => {
    try {
      await ensureSensor(true);
      toast(`Capteur ${UI_SENSOR_ID} prêt.`, 'success');
    } catch (error) {
      toast(errorMessage(error), 'danger');
    }
  });

  mount.querySelector('[data-run-all]')?.addEventListener('click', async (event) => {
    if (running) return;
    running = true;
    const button = event.currentTarget;
    button.disabled = true;
    nodes.scenarios.querySelectorAll('[data-run]').forEach((node) => { node.disabled = true; });
    log('=== Exécution complète des scénarios automatiques ===', 'warn');
    try {
      for (const scenario of AUTO) {
        if (signal.aborted) break;
        await runScenario(scenario);
      }
      log('=== Fin de l\'exécution ===', 'warn');
    } finally {
      running = false;
      button.disabled = false;
      nodes.scenarios.querySelectorAll('[data-run]').forEach((node) => { node.disabled = false; });
    }
  });

  nodes.scenarios.addEventListener('click', (event) => {
    const button = event.target.closest('[data-run]');
    if (!button) return;
    const scenario = AUTO.find((entry) => entry.id === button.dataset.run);
    if (scenario) runScenario(scenario);
  });

  mount.querySelector('[data-check-t10]')?.addEventListener('click', async () => {
    try {
      const health = await api.health('storage', { signal, timeoutMs: 4000 });
      const lag = await api.metric('analytics', 'energy_stream_lag', { group: 'analytics-group' }, { signal, timeoutMs: 5000 });
      const value = lag?.measurements?.find((entry) => entry.statistic === 'VALUE')?.value;
      if (health?.status === 'UP') {
        nodes.t10.textContent = `storage UP · lag analytics = ${formatNumber(value, 0)} (arrêtez storage-service pour prouver T10).`;
        setResult('T10', 'NA', 'storage UP au moment du contrôle', 'manipulation manuelle');
      } else {
        nodes.t10.textContent = `storage DOWN · lag analytics = ${formatNumber(value, 0)}`;
        setResult('T10', 'OK', `storage DOWN · lag analytics=${formatNumber(value, 0)}`, 'lag mesuré pendant la panne');
      }
    } catch (error) {
      nodes.t10.textContent = errorMessage(error);
    }
  });

  mount.querySelector('[data-check-redis]')?.addEventListener('click', async () => {
    try {
      const response = await post(measurement({ powerKw: 1.23 }));
      nodes.redis.textContent = `POST accepté (${response.status}) : Redis n'est pas arrêté.`;
    } catch (error) {
      if (error?.problem?.code === 'BROKER_UNAVAILABLE') {
        nodes.redis.textContent = '503 BROKER_UNAVAILABLE · Retry-After respecté';
        setResult('REDIS', 'OK', '503 BROKER_UNAVAILABLE', 'Redis arrêté manuellement');
      } else {
        nodes.redis.textContent = errorMessage(error);
      }
    }
  });

  mount.querySelector('[data-check-sensor]')?.addEventListener('click', async () => {
    try {
      const page = await api.anomalies(UI_SENSOR_ID, { size: 20 }, { signal, timeoutMs: 6000 });
      const items = Array.isArray(page?.items) ? page.items : [];
      const fallback = items.find((anomaly) => anomaly.thresholdSource === 'DEFAULT');
      if (fallback) {
        nodes.sensorCheck.textContent = `thresholdSource=DEFAULT confirmé (${fallback.anomalyId})`;
        setResult('SENSOR', 'OK', 'thresholdSource=DEFAULT', 'sensor-service arrêté manuellement');
      } else {
        nodes.sensorCheck.textContent = `Aucune anomalie DEFAULT dans les ${items.length} dernières.`;
      }
    } catch (error) {
      nodes.sensorCheck.textContent = errorMessage(error);
    }
  });

  mount.querySelector('[data-export]')?.addEventListener('click', () => {
    const date = new Date().toISOString().slice(0, 10);
    const resultsObject = Object.fromEntries(results.entries());
    const required = ['T01', 'T02', 'T03', 'T04', 'T05', 'T07', 'T08', 'T09', 'T11', 'T13'];
    const allOk = required.every((id) => resultsObject[id]?.status === 'OK');
    if (allOk) {
      resultsObject.T14 = {
        status: 'OK',
        evidence: 'tous les scénarios automatiques sont OK',
        comment: 'T10/T12 restent manuels',
      };
    } else {
      resultsObject.T14 = {
        status: 'NA',
        evidence: 'scénarios incomplets',
        comment: 'relancez « Tout exécuter »',
      };
    }
    resultsObject.T12 = {
      status: resultsObject.T12?.status || 'NA',
      evidence: resultsObject.T12?.evidence || 'redémarrage Compose manuel',
      comment: resultsObject.T12?.comment || 'voir la manipulation guidée',
    };
    const environment = `${navigator.userAgent} · ${location.origin}`;
    const report = buildReport({ results: resultsObject, date, environment });
    const json = buildJson({ results: resultsObject, date: new Date().toISOString(), environment });
    download(`resultats-ui-${date}.md`, report, 'text/markdown;charset=utf-8');
    download(`resultats-ui-${date}.json`, json, 'application/json;charset=utf-8');
    log('Rapport Markdown + JSON exportés.', 'ok');
  });

  log('Page prête. Le capteur UI-TEST-001 sera créé au premier scénario.', 'neutral');

  return {
    destroy() {},
  };
}
