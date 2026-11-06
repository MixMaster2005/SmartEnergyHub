/**
 * Tableau de bord : sante des 5 points d'entree, etat courant des capteurs
 * (un seul appel `exceedances?threshold=-1`) et 4 indicateurs de flux.
 */
import { api } from '../api.js';
import { createPoller } from '../poller.js';
import { errorMessage } from '../errors.js';
import {
  escapeHtml,
  formatAge,
  formatKw,
  formatLocal,
  formatNumber,
  formatUtc,
  formatUtcTime,
  ageMs,
} from '../format.js';
import {
  RateTracker,
  counterValue,
  formatRate,
  gaugeValue,
  parseMetric,
} from '../metrics.js';
import { badge, emptyState, errorState, statCard } from '../ui.js';

const STALE_AFTER_MS = 30_000;
const SENSOR_CACHE_MS = 60_000;

const SERVICES = [
  { key: 'gateway', label: 'Gateway' },
  { key: 'collector', label: 'Collector' },
  { key: 'storage', label: 'Storage' },
  { key: 'analytics', label: 'Analytics' },
  { key: 'sensor', label: 'Sensor' },
];

const METRICS = [
  { key: 'lagStorage', service: 'storage', name: 'energy_stream_lag', tags: { group: 'storage-group' } },
  { key: 'pendingStorage', service: 'storage', name: 'energy_stream_pending', tags: { group: 'storage-group' } },
  { key: 'lagAnalytics', service: 'analytics', name: 'energy_stream_lag', tags: { group: 'analytics-group' } },
  { key: 'pendingAnalytics', service: 'analytics', name: 'energy_stream_pending', tags: { group: 'analytics-group' } },
  { key: 'published', service: 'collector', name: 'energy_measurements_published_total', tags: {} },
  { key: 'persistErrors', service: 'storage', name: 'energy_persist_errors_total', tags: {} },
  { key: 'rejected', service: 'collector', name: 'energy_measurements_received_total', tags: { result: 'rejected' } },
];

function valueOrDash(value) {
  return Number.isFinite(value) ? formatNumber(value, 0) : 'n/d';
}

export async function render({ mount, signal }) {
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Tableau de bord</h1>
        <p>Santé des services, flux de mesures et état courant des capteurs.</p>
      </div>
      <div class="page-head__actions">
        <button class="button button--ghost" type="button" data-refresh>Rafraîchir</button>
      </div>
    </div>
    <section class="section">
      <div class="section__head"><h2>Services</h2><span class="muted small" data-health-at>—</span></div>
      <div class="grid grid--services" data-services>
        <div class="loading"><span class="spinner"></span> Vérification…</div>
      </div>
    </section>
    <section class="section">
      <div class="section__head"><h2>Flux et persistance</h2><span class="muted small" data-metrics-at>—</span></div>
      <div class="grid grid--stats" data-kpis>
        ${statCard({ label: 'Retard max du stream', value: '…', hint: 'messages non lus' })}
        ${statCard({ label: 'Messages en attente', value: '…', hint: 'non acquittés' })}
        ${statCard({ label: 'Débit de publication', value: '…', hint: 'mesures / s' })}
        ${statCard({ label: 'Erreurs de persistance', value: '…', hint: 'cumul depuis le démarrage' })}
      </div>
    </section>
    <section class="section">
      <div class="section__head">
        <h2>Capteurs</h2>
        <span class="muted small" data-sensors-count>—</span>
        <span class="muted small" data-stale-note></span>
      </div>
      <div class="sensor-grid" data-sensors>
        <div class="loading"><span class="spinner"></span> Chargement des capteurs…</div>
      </div>
    </section>
  `;

  const nodes = {
    services: mount.querySelector('[data-services]'),
    healthAt: mount.querySelector('[data-health-at]'),
    kpis: mount.querySelector('[data-kpis]'),
    metricsAt: mount.querySelector('[data-metrics-at]'),
    sensors: mount.querySelector('[data-sensors]'),
    sensorsCount: mount.querySelector('[data-sensors-count]'),
    staleNote: mount.querySelector('[data-stale-note]'),
  };

  const state = {
    sensors: null,
    sensorsAt: 0,
    sensorsError: null,
    exceedances: null,
    exceedancesError: null,
    metrics: {},
    metricsErrors: {},
    publishedRate: new RateTracker(),
    rejectedRate: new RateTracker(),
    publishedRateValue: null,
    lastRefresh: null,
  };

  async function loadHealth() {
    const results = await Promise.allSettled(
      SERVICES.map((service) => api.health(service.key, { signal, timeoutMs: 5000 })),
    );
    const checkedAt = new Date();
    nodes.services.innerHTML = SERVICES.map((service, index) => {
      const result = results[index];
      const up = result.status === 'fulfilled' && result.value?.status === 'UP';
      const detail = up ? 'UP' : (result.status === 'rejected' ? errorMessage(result.reason) : (result.value?.status || 'INCONNU'));
      return `<div class="service ${up ? 'service--up' : 'service--down'}">
        <span class="service__dot" aria-hidden="true"></span>
        <div>
          <div class="service__name">${escapeHtml(service.label)}</div>
          <div class="service__meta" title="${escapeHtml(detail)}">${up ? 'opérationnel' : escapeHtml(detail)}</div>
        </div>
      </div>`;
    }).join('');
    nodes.healthAt.textContent = `Vérifié à ${formatUtcTime(checkedAt)} UTC`;
  }

  async function loadSensors(force = false) {
    if (!force && state.sensors && Date.now() - state.sensorsAt < SENSOR_CACHE_MS) return;
    try {
      state.sensors = await api.allSensors({ signal, timeoutMs: 6000 });
      state.sensorsAt = Date.now();
      state.sensorsError = null;
    } catch (error) {
      state.sensorsError = error;
    }
  }

  async function loadExceedances() {
    try {
      state.exceedances = await api.exceedances({ threshold: -1 }, { signal, timeoutMs: 6000 });
      state.exceedancesError = null;
    } catch (error) {
      state.exceedancesError = error;
    }
  }

  async function loadMetrics() {
    const results = await Promise.allSettled(
      METRICS.map((entry) => api.metric(entry.service, entry.name, entry.tags, { signal, timeoutMs: 5000 })),
    );
    const now = Date.now();
    METRICS.forEach((entry, index) => {
      const result = results[index];
      if (result.status === 'fulfilled') {
        state.metrics[entry.key] = parseMetric(result.value);
        state.metricsErrors[entry.key] = null;
      } else {
        state.metrics[entry.key] = null;
        state.metricsErrors[entry.key] = result.reason;
      }
    });

    const published = counterValue(state.metrics.published);
    const rejected = counterValue(state.metrics.rejected);
    state.publishedRateValue = state.publishedRate.update(published, now);
    state.rejectedRate.update(rejected, now);
  }

  function renderKpis() {
    const lagStorage = gaugeValue(state.metrics.lagStorage);
    const lagAnalytics = gaugeValue(state.metrics.lagAnalytics);
    const lagValues = [lagStorage, lagAnalytics].filter(Number.isFinite);
    const lagMax = lagValues.length ? Math.max(...lagValues) : null;
    const pendingValues = [gaugeValue(state.metrics.pendingStorage), gaugeValue(state.metrics.pendingAnalytics)]
      .filter(Number.isFinite);
    const pending = pendingValues.length ? pendingValues.reduce((sum, value) => sum + value, 0) : null;
    const publishRate = state.publishedRateValue;
    const persistErrors = counterValue(state.metrics.persistErrors);
    const rejectedCount = counterValue(state.metrics.rejected);

    const lagTone = lagMax === null ? 'neutral' : lagMax > 1000 ? 'danger' : lagMax > 100 ? 'warn' : 'ok';
    const pendingTone = pending === null ? 'neutral' : pending > 1000 ? 'danger' : pending > 100 ? 'warn' : 'ok';
    const errorTone = persistErrors === null ? 'neutral' : persistErrors > 0 ? 'danger' : 'ok';

    const lagHint = Number.isFinite(lagStorage) || Number.isFinite(lagAnalytics)
      ? `storage ${valueOrDash(lagStorage)} · analytics ${valueOrDash(lagAnalytics)}`
      : 'métrique indisponible';
    const pendingHint = Number.isFinite(pending)
      ? `storage ${valueOrDash(gaugeValue(state.metrics.pendingStorage))} · analytics ${valueOrDash(gaugeValue(state.metrics.pendingAnalytics))}`
      : 'métrique indisponible';

    nodes.kpis.innerHTML = [
      statCard({ label: 'Retard max du stream', value: valueOrDash(lagMax), hint: lagHint, tone: lagTone, id: 'kpi-lag' }),
      statCard({ label: 'Messages en attente', value: valueOrDash(pending), hint: pendingHint, tone: pendingTone, id: 'kpi-pending' }),
      statCard({
        label: 'Débit de publication',
        value: formatRate(publishRate),
        hint: state.publishedRate.previous ? `compteur ${valueOrDash(counterValue(state.metrics.published))}` : 'en attente d\'un second relevé',
        tone: 'neutral',
        id: 'kpi-rate',
      }),
      statCard({
        label: 'Erreurs de persistance',
        value: valueOrDash(persistErrors),
        hint: Number.isFinite(rejectedCount) ? `${valueOrDash(rejectedCount)} mesure(s) rejetée(s)` : 'cumul depuis le démarrage',
        tone: errorTone,
        id: 'kpi-errors',
      }),
    ].join('');

    nodes.metricsAt.textContent = `Relevé à ${formatUtcTime(new Date())} UTC`;
  }

  function renderSensors() {
    if (state.sensorsError) {
      nodes.sensors.innerHTML = errorState(state.sensorsError, { retry: false });
      nodes.sensorsCount.textContent = '';
      return;
    }
    if (!state.sensors) return;

    const total = state.sensors.length;
    const active = state.sensors.filter((sensor) => sensor.active !== false).length;
    nodes.sensorsCount.textContent = `${total} capteur(s) · ${active} actif(s)`;

    if (total === 0) {
      nodes.sensors.innerHTML = emptyState(
        'Aucun capteur enregistré.',
        'Lancez le simulateur pour créer SENSOR-001 à SENSOR-005 et publier des mesures.',
        '<p class="small">Commande : <code>make sim</code></p>',
      );
      nodes.staleNote.textContent = '';
      return;
    }

    if (state.exceedancesError) {
      nodes.sensors.innerHTML = errorState(state.exceedancesError, { retry: false });
      return;
    }

    const byId = new Map((state.exceedances || []).map((item) => [item.sensorId, item]));
    const now = Date.now();
    const rows = state.sensors
      .map((sensor) => {
        const current = byId.get(sensor.sensorId);
        return { sensor, current, age: current?.lastMeasuredAt ? ageMs(current.lastMeasuredAt, now) : null };
      })
      .sort((a, b) => {
        const aActive = a.sensor.active !== false ? 0 : 1;
        const bActive = b.sensor.active !== false ? 0 : 1;
        if (aActive !== bActive) return aActive - bActive;
        return (b.current?.lastPowerKw ?? -1) - (a.current?.lastPowerKw ?? -1);
      });

    const staleCount = rows.filter((row) => row.age === null || row.age > STALE_AFTER_MS).length;
    nodes.staleNote.textContent = staleCount ? `${staleCount} sans mesure récente (> 30 s)` : 'toutes les mesures sont fraîches';

    nodes.sensors.innerHTML = '<div class="sensor-head" aria-hidden="true"><span>Capteur</span><span>Puissance</span><span>Charge</span><span>Dernière mesure</span></div>' + rows.map(({ sensor, current, age }) => {
      const power = current?.lastPowerKw;
      const max = Number(sensor.maxPowerKw) || 0;
      const ratio = max > 0 && Number.isFinite(power) ? Math.min(power / max, 1.25) : 0;
      const alert = max > 0 && Number.isFinite(power) && power > max;
      const stale = age === null || age > STALE_AFTER_MS;
      const inactive = sensor.active === false;
      const classes = ['sensor'];
      if (stale && !inactive) classes.push('sensor--stale');
      if (inactive) classes.push('sensor--off');


      const pct = max > 0 && Number.isFinite(power) ? Math.round((power / max) * 100) : null;
      const lamp = inactive ? 'off' : alert ? 'danger' : stale ? 'warn' : 'ok';
      const state = inactive ? badge('désactivé', 'neutral')
        : alert ? badge('seuil dépassé', 'danger')
        : stale ? badge('sans mesure récente', 'warn', 'Aucune mesure depuis plus de 30 s')
        : '';
      // Échelle de la barre : 125 % du seuil ; le trait vertical marque le seuil (80 % de la largeur).
      const fill = Math.min(Math.round((ratio / 1.25) * 100), 100);
      const when = current?.lastMeasuredAt
        ? `<span title="${escapeHtml(formatUtc(current.lastMeasuredAt))} UTC · ${escapeHtml(formatLocal(current.lastMeasuredAt))}">${escapeHtml(formatAge(current.lastMeasuredAt))}</span>`
        : '<span>en attente de mesure</span>';

      return `<a class="${classes.join(' ')}" href="#/sensors/${encodeURIComponent(sensor.sensorId)}">
        <span class="sensor__who">
          <span class="sensor__lamp sensor__lamp--${lamp}" aria-hidden="true"></span>
          <span class="sensor__text">
            <span class="sensor__name">${escapeHtml(sensor.name || sensor.sensorId)}</span>
            <span class="sensor__id"><span>${escapeHtml(sensor.sensorId)}</span>${sensor.building ? `<span>${escapeHtml(sensor.building)}</span>` : ''}</span>
          </span>
        </span>
        <span class="sensor__power">${current ? formatKw(power) : '—'}</span>
        <span class="sensor__load">
          <span class="sensor__bar"><span class="${alert ? 'is-alert' : ''}" style="width:${fill}%"></span><i title="Seuil"></i></span>
          <span class="sensor__loadtext">${max ? `${pct === null ? '—' : pct} % du seuil de ${formatNumber(max, 1)} kW` : 'seuil non défini'}</span>
        </span>
        <span class="sensor__when">${when}${state}</span>
      </a>`;
    }).join('');
  }

  async function tick(forceSensors = false) {
    await Promise.allSettled([loadHealth(), loadSensors(forceSensors), loadExceedances(), loadMetrics()]);
    state.lastRefresh = new Date();
    const last = document.getElementById('last-refresh');
    if (last) last.textContent = `Dernier relevé : ${formatUtc(state.lastRefresh)}`;
    renderKpis();
    renderSensors();
  }

  const poller = createPoller(() => tick(false), { intervalMs: 5000 });

  mount.querySelector('[data-refresh]')?.addEventListener('click', async () => {
    await loadSensors(true);
    poller.refresh();
  });

  poller.start();

  return {
    destroy() {
      poller.stop();
    },
  };
}
