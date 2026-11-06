/**
 * Page Systeme : metriques Actuator en direct (CDC section 9).
 * L'historique des courbes vit dans le navigateur (tampon de 15 min, 180 points a 5 s) :
 * le back n'expose que la valeur courante.
 */
import { api } from '../api.js';
import { createPoller } from '../poller.js';
import { escapeHtml, formatNumber, formatUtc, formatUtcTime } from '../format.js';
import {
  RateTracker,
  RingBuffer,
  counterValue,
  formatRate,
  gaugeValue,
  parseMetric,
  timerAverageMs,
  timerMaxMs,
} from '../metrics.js';
import { badge, errorState, statCard } from '../ui.js';

const CAPACITY = 180; // 15 min a 5 s
const COLORS = {
  storage: '#0b6bcb',
  analytics: '#0e9f8a',
  pending: '#d79b00',
  danger: '#b42318',
  neutral: '#64748b',
};

const QUERIES = [
  { key: 'lagStorage', service: 'storage', name: 'energy_stream_lag', tags: { group: 'storage-group' } },
  { key: 'lagAnalytics', service: 'analytics', name: 'energy_stream_lag', tags: { group: 'analytics-group' } },
  { key: 'pendingStorage', service: 'storage', name: 'energy_stream_pending', tags: { group: 'storage-group' } },
  { key: 'pendingAnalytics', service: 'analytics', name: 'energy_stream_pending', tags: { group: 'analytics-group' } },
  { key: 'published', service: 'collector', name: 'energy_measurements_published_total', tags: {} },
  { key: 'accepted', service: 'collector', name: 'energy_measurements_received_total', tags: { result: 'accepted' } },
  { key: 'rejected', service: 'collector', name: 'energy_measurements_received_total', tags: { result: 'rejected' } },
  { key: 'persistDuration', service: 'storage', name: 'energy_persist_duration', tags: {} },
  { key: 'persistErrors', service: 'storage', name: 'energy_persist_errors_total', tags: {} },
  { key: 'anomaliesThreshold', service: 'analytics', name: 'energy_anomalies_detected_total', tags: { type: 'THRESHOLD' } },
  { key: 'anomaliesZscore', service: 'analytics', name: 'energy_anomalies_detected_total', tags: { type: 'ZSCORE' } },
];

const SERIES = ['lagStorage', 'lagAnalytics', 'pendingStorage', 'pendingAnalytics', 'publishRate', 'rejectRate', 'persistAvg', 'persistMax'];

function createLineChart(canvas, series) {
  if (!window.Chart) return null;
  return new window.Chart(canvas.getContext('2d'), {
    type: 'line',
    data: {
      datasets: series.map((entry) => ({
        label: entry.label,
        data: [],
        borderColor: entry.color,
        backgroundColor: `${entry.color}18`,
        borderWidth: 2,
        pointRadius: 0,
        tension: 0.2,
        fill: Boolean(entry.fill),
      })),
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      parsing: false,
      animation: false,
      interaction: { mode: 'index', intersect: false },
      scales: {
        x: {
          type: 'linear',
          grid: { color: '#eef2f7' },
          ticks: { maxRotation: 0, autoSkip: true, callback: (value) => formatUtcTime(value) },
        },
        y: {
          beginAtZero: true,
          grid: { color: '#eef2f7' },
          ticks: { callback: (value) => formatNumber(value, Number.isInteger(value) ? 0 : 1) },
          title: { display: true, text: 'valeur' },
        },
      },
      plugins: {
        legend: { position: 'bottom' },
        tooltip: {
          callbacks: {
            title: (items) => (items[0] ? formatUtc(items[0].parsed.x) : ''),
            label: (item) => `${item.dataset.label} : ${formatNumber(item.parsed.y, 2)}`,
          },
        },
      },
    },
  });
}

export async function render({ mount, signal }) {
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Système</h1>
        <p>Lag du stream, débits et latence de persistance, relevés toutes les 5 s.</p>
      </div>
      <div class="page-head__actions">
        <button class="button button--ghost" type="button" data-refresh>Rafraîchir</button>
      </div>
    </div>
    <div data-banner></div>
    <section class="section">
      <div class="grid grid--stats" data-kpis>
        ${statCard({ label: 'Retard max', value: '…', hint: 'messages non lus' })}
        ${statCard({ label: 'En attente', value: '…', hint: 'non acquittés' })}
        ${statCard({ label: 'Publication', value: '…', hint: 'mesures / s' })}
        ${statCard({ label: 'Rejets', value: '…', hint: 'mesures / s' })}
        ${statCard({ label: 'Persistance', value: '…', hint: 'latence moyenne' })}
        ${statCard({ label: 'Anomalies', value: '…', hint: 'cumul THRESHOLD + ZSCORE' })}
      </div>
    </section>
    <section class="section grid grid--two">
      <div class="card chart-card">
        <div class="section__head"><h2>Stream Redis</h2><span class="muted small">lag et pending par groupe</span></div>
        <div class="chart-wrap"><canvas data-chart="stream" aria-label="Lag et pending du stream"></canvas></div>
      </div>
      <div class="card chart-card">
        <div class="section__head"><h2>Débit d'ingestion</h2><span class="muted small">mesures / s</span></div>
        <div class="chart-wrap"><canvas data-chart="rate" aria-label="Débit de publication et de rejet"></canvas></div>
      </div>
      <div class="card chart-card">
        <div class="section__head"><h2>Latence de persistance</h2><span class="muted small">ms</span></div>
        <div class="chart-wrap chart-wrap--sm"><canvas data-chart="latency" aria-label="Latence de persistance"></canvas></div>
      </div>
      <div class="card chart-card">
        <div class="section__head"><h2>Derniers relevés</h2><span class="muted small" data-at>—</span></div>
        <div class="table-wrap" data-table></div>
        <p class="muted small">Tampon navigateur : 15 min. Les compteurs repartent de zéro au redémarrage d'un service.</p>
      </div>
    </section>
  `;

  const nodes = {
    banner: mount.querySelector('[data-banner]'),
    kpis: mount.querySelector('[data-kpis]'),
    at: mount.querySelector('[data-at]'),
    table: mount.querySelector('[data-table]'),
  };

  const charts = {
    stream: createLineChart(mount.querySelector('[data-chart="stream"]'), [
      { label: 'lag storage', color: COLORS.storage },
      { label: 'lag analytics', color: COLORS.analytics },
      { label: 'pending storage', color: COLORS.pending },
      { label: 'pending analytics', color: COLORS.neutral },
    ]),
    rate: createLineChart(mount.querySelector('[data-chart="rate"]'), [
      { label: 'publication', color: COLORS.analytics, fill: true },
      { label: 'rejets', color: COLORS.danger, fill: true },
    ]),
    latency: createLineChart(mount.querySelector('[data-chart="latency"]'), [
      { label: 'moyenne', color: COLORS.storage, fill: true },
      { label: 'maximum', color: COLORS.pending },
    ]),
  };

  const buffers = Object.fromEntries(SERIES.map((key) => [key, new RingBuffer(CAPACITY)]));
  const state = {
    metrics: {},
    errors: {},
    publishedRate: new RateTracker(),
    rejectRate: new RateTracker(),
    anomaliesRate: new RateTracker(),
    lastAt: null,
    publishRateValue: null,
    rejectRateValue: null,
    anomaliesRateValue: null,
  };

  function push(key, x, y) {
    if (Number.isFinite(y)) buffers[key].push({ x, y });
  }

  function updateCharts() {
    if (charts.stream) {
      charts.stream.data.datasets[0].data = buffers.lagStorage.toArray();
      charts.stream.data.datasets[1].data = buffers.lagAnalytics.toArray();
      charts.stream.data.datasets[2].data = buffers.pendingStorage.toArray();
      charts.stream.data.datasets[3].data = buffers.pendingAnalytics.toArray();
      charts.stream.update('none');
    }
    if (charts.rate) {
      charts.rate.data.datasets[0].data = buffers.publishRate.toArray();
      charts.rate.data.datasets[1].data = buffers.rejectRate.toArray();
      charts.rate.update('none');
    }
    if (charts.latency) {
      charts.latency.data.datasets[0].data = buffers.persistAvg.toArray();
      charts.latency.data.datasets[1].data = buffers.persistMax.toArray();
      charts.latency.update('none');
    }
  }

  function finishLabel(metric) {
    return metric?.tags?.group?.[0] || metric?.tags?.result?.[0] || metric?.tags?.type?.[0] || '—';
  }

  function renderKpis(now) {
    const lagValues = [gaugeValue(state.metrics.lagStorage), gaugeValue(state.metrics.lagAnalytics)].filter(Number.isFinite);
    const pendingValues = [gaugeValue(state.metrics.pendingStorage), gaugeValue(state.metrics.pendingAnalytics)].filter(Number.isFinite);
    const lagMax = lagValues.length ? Math.max(...lagValues) : null;
    const pending = pendingValues.length ? pendingValues.reduce((sum, value) => sum + value, 0) : null;
    const persistAvg = timerAverageMs(state.metrics.persistDuration);
    const anomalies = (counterValue(state.metrics.anomaliesThreshold) || 0) + (counterValue(state.metrics.anomaliesZscore) || 0);
    const anomaliesKnown = counterValue(state.metrics.anomaliesThreshold) !== null || counterValue(state.metrics.anomaliesZscore) !== null;
    const errors = counterValue(state.metrics.persistErrors);

    nodes.kpis.innerHTML = [
      statCard({ label: 'Retard max', value: Number.isFinite(lagMax) ? formatNumber(lagMax, 0) : 'n/d', hint: `storage ${formatNumber(gaugeValue(state.metrics.lagStorage), 0)} · analytics ${formatNumber(gaugeValue(state.metrics.lagAnalytics), 0)}`, tone: lagMax > 100 ? 'warn' : 'neutral' }),
      statCard({ label: 'En attente', value: Number.isFinite(pending) ? formatNumber(pending, 0) : 'n/d', hint: 'messages non acquittés', tone: pending > 100 ? 'warn' : 'neutral' }),
      statCard({ label: 'Publication', value: formatRate(state.publishRateValue), hint: state.publishedRate.previous ? 'mesures / s' : 'second relevé requis' }),
      statCard({ label: 'Rejets', value: formatRate(state.rejectRateValue), hint: `${formatNumber(counterValue(state.metrics.rejected), 0)} cumulés`, tone: (counterValue(state.metrics.rejected) || 0) > 0 ? 'warn' : 'neutral' }),
      statCard({ label: 'Persistance', value: Number.isFinite(persistAvg) ? `${formatNumber(persistAvg, 1)} ms` : 'n/d', hint: `${formatNumber(errors, 0)} erreur(s)`, tone: errors > 0 ? 'danger' : 'neutral' }),
      statCard({ label: 'Anomalies', value: anomaliesKnown ? formatNumber(anomalies, 0) : 'n/d', hint: 'THRESHOLD + ZSCORE', tone: anomalies > 0 ? 'warn' : 'neutral' }),
    ].join('');

    nodes.at.textContent = `Relevé à ${formatUtc(now)}`;
  }

  function renderTable() {
    const rows = QUERIES.map((query) => {
      const metric = state.metrics[query.key];
      const error = state.errors[query.key];
      const value = query.key.startsWith('lag') || query.key.startsWith('pending')
        ? gaugeValue(metric)
        : query.key === 'persistDuration'
          ? timerAverageMs(metric)
          : counterValue(metric);
      const display = Number.isFinite(value) ? formatNumber(value, query.key === 'persistDuration' ? 1 : 0) : 'n/d';
      const unit = query.key === 'persistDuration' ? 'ms' : query.key.startsWith('lag') || query.key.startsWith('pending') ? 'messages' : 'cumul';
      return `<tr>
        <td><code>${escapeHtml(query.name)}</code></td>
        <td>${escapeHtml(finishLabel(metric))}</td>
        <td class="num">${display} <span class="muted small">${unit}</span></td>
        <td>${error ? badge('indisponible', 'danger') : badge('ok', 'ok')}</td>
      </tr>`;
    });
    nodes.table.innerHTML = `<table class="table--metrics">
      <thead><tr><th>Métrique</th><th>Tag</th><th class="num">Valeur</th><th>État</th></tr></thead>
      <tbody>${rows.join('')}</tbody>
    </table>`;
  }

  async function load() {
    const results = await Promise.allSettled(
      QUERIES.map((query) => api.metric(query.service, query.name, query.tags, { signal, timeoutMs: 5000 })),
    );
    const now = Date.now();
    state.lastAt = now;
    QUERIES.forEach((query, index) => {
      const result = results[index];
      if (result.status === 'fulfilled') {
        state.metrics[query.key] = parseMetric(result.value);
        state.errors[query.key] = null;
      } else {
        state.metrics[query.key] = null;
        state.errors[query.key] = result.reason;
      }
    });

    state.publishRateValue = state.publishedRate.update(counterValue(state.metrics.published), now);
    state.rejectRateValue = state.rejectRate.update(counterValue(state.metrics.rejected), now);
    state.anomaliesRateValue = state.anomaliesRate.update(
      (counterValue(state.metrics.anomaliesThreshold) || 0) + (counterValue(state.metrics.anomaliesZscore) || 0),
      now,
    );

    push('lagStorage', now, gaugeValue(state.metrics.lagStorage));
    push('lagAnalytics', now, gaugeValue(state.metrics.lagAnalytics));
    push('pendingStorage', now, gaugeValue(state.metrics.pendingStorage));
    push('pendingAnalytics', now, gaugeValue(state.metrics.pendingAnalytics));
    push('publishRate', now, state.publishRateValue);
    push('rejectRate', now, state.rejectRateValue);
    push('persistAvg', now, timerAverageMs(state.metrics.persistDuration));
    push('persistMax', now, timerMaxMs(state.metrics.persistDuration));

    const failed = QUERIES.filter((query) => state.errors[query.key]);
    if (failed.length === QUERIES.length) {
      nodes.banner.innerHTML = errorState(failed[0] ? state.errors[failed[0].key] : new Error('Métriques indisponibles'));
    } else {
      nodes.banner.innerHTML = '';
    }

    renderKpis(now);
    renderTable();
    updateCharts();
  }

  const poller = createPoller(load, {
    intervalMs: 5000,
    onError: (error) => {
      nodes.banner.innerHTML = errorState(error);
    },
  });

  mount.querySelector('[data-refresh]')?.addEventListener('click', () => poller.refresh());
  poller.start();

  return {
    destroy() {
      poller.stop();
      for (const chart of Object.values(charts)) chart?.destroy();
    },
  };
}
