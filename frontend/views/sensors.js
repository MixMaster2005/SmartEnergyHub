/**
 * Page Capteurs : liste filtrable et fiche detaillee (courbe de puissance,
 * seuil, anomalies, derniere mesure, pagination par curseur).
 */
import { api } from '../api.js';
import { isEmptyData } from '../errors.js';
import {
  escapeHtml,
  formatAge,
  formatAmpere,
  formatCelsius,
  formatHertz,
  formatKw,
  formatLocal,
  formatNumber,
  formatUtc,
  formatUtcTime,
  formatVoltage,
  shortId,
  stateLabel,
  typeLabel,
  severityLabel,
} from '../format.js';
import { buildRange, presetById, RANGE_PRESETS, validateRange } from '../ranges.js';
import { badge, emptyState, errorState, renderError, statCard } from '../ui.js';

const HISTORY_MAX_DAYS = 7;

export async function render(ctx) {
  if (ctx.params.id) return renderDetail(ctx);
  return renderList(ctx);
}

/* -------------------------------------------------------------------- liste */

async function renderList({ mount, signal, navigate }) {
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Capteurs</h1>
        <p>Référentiel sensor-service (MongoDB). Cliquez sur un capteur pour ouvrir sa fiche.</p>
      </div>
      <div class="page-head__actions">
        <input class="input" type="search" placeholder="Rechercher…" data-search aria-label="Rechercher un capteur">
        <select class="select" data-building aria-label="Filtrer par bâtiment"><option value="">Tous les bâtiments</option></select>
        <select class="select" data-active aria-label="Filtrer par état">
          <option value="">Tous</option>
          <option value="true">Actifs</option>
          <option value="false">Inactifs</option>
        </select>
        <button class="button button--ghost" type="button" data-refresh>Rafraîchir</button>
      </div>
    </div>
    <div data-content><div class="loading"><span class="spinner"></span> Chargement des capteurs…</div></div>
  `;

  const content = mount.querySelector('[data-content]');
  const search = mount.querySelector('[data-search]');
  const building = mount.querySelector('[data-building]');
  const active = mount.querySelector('[data-active]');
  let sensors = [];

  function applyFilters() {
    const query = search.value.trim().toLowerCase();
    const buildingValue = building.value;
    const activeValue = active.value;
    const filtered = sensors.filter((sensor) => {
      if (buildingValue && sensor.building !== buildingValue) return false;
      if (activeValue && String(sensor.active !== false) !== activeValue) return false;
      if (!query) return true;
      return [sensor.sensorId, sensor.name, sensor.building, ...(sensor.tags || [])]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(query));
    });
    renderTable(filtered);
  }

  function renderTable(rows) {
    if (rows.length === 0) {
      content.innerHTML = emptyState(
        sensors.length === 0 ? 'Aucun capteur enregistré.' : 'Aucun capteur ne correspond aux filtres.',
        sensors.length === 0 ? 'Lancez make sim pour créer les capteurs simulés.' : '',
      );
      return;
    }
    content.innerHTML = `<div class="card card--flush table-wrap">
      <table>
        <thead><tr>
          <th>Capteur</th><th>Nom</th><th>Bâtiment</th><th class="num">Étage</th>
          <th class="num">Seuil</th><th>État</th><th>Tags</th><th></th>
        </tr></thead>
        <tbody>
          ${rows.map((sensor) => `<tr>
            <td><code>${escapeHtml(sensor.sensorId)}</code></td>
            <td>${escapeHtml(sensor.name || '—')}</td>
            <td>${escapeHtml(sensor.building || '—')}</td>
            <td class="num">${sensor.floor ?? '—'}</td>
            <td class="num">${formatNumber(sensor.maxPowerKw, 2)} kW</td>
            <td>${sensor.active === false ? badge('inactif', 'neutral') : badge('actif', 'ok')}</td>
            <td>${(sensor.tags || []).map((tag) => badge(tag, 'info')).join(' ') || '—'}</td>
            <td><a class="button button--ghost button--sm" href="#/sensors/${encodeURIComponent(sensor.sensorId)}">Ouvrir</a></td>
          </tr>`).join('')}
        </tbody>
      </table>
    </div>`;
  }

  async function load() {
    try {
      sensors = await api.allSensors({ signal, timeoutMs: 7000 });
      const buildings = [...new Set(sensors.map((sensor) => sensor.building).filter(Boolean))].sort();
      const current = building.value;
      building.innerHTML = '<option value="">Tous les bâtiments</option>'
        + buildings.map((value) => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join('');
      building.value = buildings.includes(current) ? current : '';
      applyFilters();
    } catch (error) {
      if (signal.aborted) return;
      renderError(content, error, () => load());
    }
  }

  search.addEventListener('input', applyFilters);
  building.addEventListener('change', applyFilters);
  active.addEventListener('change', applyFilters);
  mount.querySelector('[data-refresh]')?.addEventListener('click', load);

  await load();
  return {
    destroy() {
      /* aucun sondage sur la liste */
    },
  };
}

/* ------------------------------------------------------------------ detail */

async function renderDetail({ mount, params, signal, navigate }) {
  const sensorId = params.id;
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <p class="small"><a href="#/sensors">← Tous les capteurs</a></p>
        <h1><code>${escapeHtml(sensorId)}</code></h1>
        <p data-subtitle>Chargement de la fiche…</p>
      </div>
      <div class="page-head__actions">
        <div class="segmented" data-range role="group" aria-label="Plage de la courbe">
          ${RANGE_PRESETS.filter((preset) => preset.maxDays === HISTORY_MAX_DAYS).map((preset) => `
            <button type="button" data-preset="${preset.id}" aria-pressed="${preset.id === '24h'}">${preset.label}</button>`).join('')}
        </div>
        <button class="button button--ghost" type="button" data-refresh>Rafraîchir</button>
      </div>
    </div>
    <div data-banner></div>
    <section class="section">
      <div class="grid grid--stats" data-latest>
        ${statCard({ label: 'Puissance', value: '…' })}
      </div>
    </section>
    <section class="section">
      <div class="card chart-card">
        <div class="section__head"><h2>Puissance et anomalies</h2><span class="muted small" data-range-label></span></div>
        <div class="chart-wrap"><canvas data-chart aria-label="Courbe de puissance du capteur"></canvas></div>
        <p class="muted small" data-chart-note></p>
        <button class="button button--ghost button--sm" type="button" data-more hidden>Charger la suite</button>
      </div>
    </section>
    <section class="section">
      <div class="section__head"><h2>Anomalies de la période</h2><span class="muted small" data-anomalies-count></span></div>
      <div data-anomalies><div class="loading"><span class="spinner"></span> Chargement…</div></div>
    </section>
  `;

  const nodes = {
    subtitle: mount.querySelector('[data-subtitle]'),
    banner: mount.querySelector('[data-banner]'),
    latest: mount.querySelector('[data-latest]'),
    chart: mount.querySelector('[data-chart]'),
    chartNote: mount.querySelector('[data-chart-note]'),
    rangeLabel: mount.querySelector('[data-range-label]'),
    more: mount.querySelector('[data-more]'),
    anomalies: mount.querySelector('[data-anomalies]'),
    anomaliesCount: mount.querySelector('[data-anomalies-count]'),
  };

  let sensor = null;
  let preset = presetById('24h');
  let chart = null;
  let points = [];
  let anomalies = [];
  let nextCursor = null;
  let requestSeq = 0;
  let refreshing = false;

  function destroyChart() {
    if (chart) {
      chart.destroy();
      chart = null;
    }
  }

  function renderLatest(latest, latestError) {
    if (latestError && isEmptyData(latestError)) {
      nodes.latest.innerHTML = emptyState('Pas encore de mesure pour ce capteur.', 'Dès la première mesure publiée, elle apparaîtra ici.');
      return;
    }
    if (latestError) {
      nodes.latest.innerHTML = errorState(latestError);
      return;
    }
    const item = latest;
    nodes.latest.innerHTML = [
      statCard({ label: 'Puissance', value: formatKw(item.powerKw), hint: `mesure du ${formatUtc(item.timestamp)}` }),
      statCard({ label: 'Tension', value: formatVoltage(item.voltage), hint: 'volts' }),
      statCard({ label: 'Courant', value: formatAmpere(item.currentA), hint: 'ampères' }),
      statCard({ label: 'Température', value: formatCelsius(item.temperature), hint: 'degrés Celsius' }),
      statCard({ label: 'Fréquence', value: formatHertz(item.frequencyHz), hint: 'hertz' }),
      statCard({ label: 'État', value: stateLabel(item.state), hint: `âge : ${formatAge(item.timestamp)}` }),
    ].join('');
  }

  function renderChart() {
    destroyChart();
    if (!window.Chart) {
      nodes.chartNote.textContent = 'Chart.js n\'est pas chargé : vérifiez /vendor/chart.umd.js.';
      return;
    }
    const threshold = Number(sensor?.maxPowerKw);
    const times = points.map((point) => new Date(point.timestamp).getTime()).filter(Number.isFinite);
    const minX = times.length ? Math.min(...times) : null;
    const maxX = times.length ? Math.max(...times) : null;
    const thresholdData = minX !== null && Number.isFinite(threshold)
      ? [{ x: minX, y: threshold }, { x: maxX, y: threshold }]
      : [];

    chart = new window.Chart(nodes.chart.getContext('2d'), {
      type: 'line',
      data: {
        datasets: [
          {
            label: 'Puissance (kW)',
            data: points.map((point) => ({ x: new Date(point.timestamp).getTime(), y: point.powerKw })),
            borderColor: '#0b6bcb',
            backgroundColor: 'rgba(11, 107, 203, .10)',
            fill: true,
            borderWidth: 2,
            pointRadius: 0,
            pointHitRadius: 8,
            tension: 0.25,
          },
          {
            label: 'Seuil maxPowerKw',
            data: thresholdData,
            borderColor: '#b42318',
            borderDash: [6, 5],
            borderWidth: 1.5,
            pointRadius: 0,
          },
          {
            label: 'Anomalies',
            type: 'scatter',
            data: anomalies.map((anomaly) => ({
              x: new Date(anomaly.timestamp).getTime(),
              y: anomaly.powerKw,
            })),
            backgroundColor: '#b42318',
            borderColor: '#ffffff',
            borderWidth: 1,
            pointRadius: 4,
            pointHoverRadius: 6,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        parsing: false,
        animation: false,
        interaction: { mode: 'nearest', intersect: false },
        scales: {
          x: {
            type: 'linear',
            grid: { color: '#eef2f7' },
            ticks: { maxRotation: 0, autoSkip: true, callback: (value) => formatUtcTime(value) },
          },
          y: {
            beginAtZero: true,
            title: { display: true, text: 'kW' },
            grid: { color: '#eef2f7' },
          },
        },
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              title: (items) => (items[0] ? formatUtc(items[0].parsed.x) : ''),
              label: (item) => `${item.dataset.label} : ${formatNumber(item.parsed.y, 2)} kW`,
            },
          },
        },
      },
    });

    nodes.chartNote.textContent = points.length
      ? `${points.length} point(s) affiché(s) · seuil ${Number.isFinite(threshold) ? `${formatNumber(threshold, 2)} kW` : 'inconnu'} · heures UTC`
      : 'Aucune mesure sur cette période.';
    nodes.more.hidden = !nextCursor;
  }

  function renderAnomalies() {
    nodes.anomaliesCount.textContent = `${anomalies.length} anomalie(s)`;
    if (anomalies.length === 0) {
      nodes.anomalies.innerHTML = emptyState('Aucune anomalie sur cette période.', 'Les dépassements de seuil et les z-scores apparaîtront ici.');
      return;
    }
    nodes.anomalies.innerHTML = `<div class="card card--flush table-wrap">
      <table>
        <thead><tr><th>Horodatage</th><th>Type</th><th>Sévérité</th><th class="num">Puissance</th><th class="num">Seuil</th><th>Source</th><th class="num">z</th><th>Event</th></tr></thead>
        <tbody>
          ${anomalies.map((anomaly) => `<tr>
            <td title="${escapeHtml(formatLocal(anomaly.timestamp))}">${escapeHtml(formatUtc(anomaly.timestamp))}</td>
            <td>${badge(typeLabel(anomaly.type), anomaly.type === 'THRESHOLD' ? 'warn' : 'info')}</td>
            <td>${badge(severityLabel(anomaly.severity), anomaly.severity === 'HIGH' ? 'danger' : anomaly.severity === 'MEDIUM' ? 'warn' : 'neutral')}</td>
            <td class="num">${formatNumber(anomaly.powerKw, 2)} kW</td>
            <td class="num">${anomaly.thresholdKw === null || anomaly.thresholdKw === undefined ? '—' : `${formatNumber(anomaly.thresholdKw, 2)} kW`}</td>
            <td>${anomaly.thresholdSource ? badge(anomaly.thresholdSource, anomaly.thresholdSource === 'SENSOR' ? 'ok' : 'warn') : '—'}</td>
            <td class="num">${anomaly.zScore === null || anomaly.zScore === undefined ? '—' : formatNumber(anomaly.zScore, 2)}</td>
            <td><code title="${escapeHtml(anomaly.eventId || '')}">${escapeHtml(shortId(anomaly.eventId))}</code></td>
          </tr>`).join('')}
        </tbody>
      </table>
    </div>`;
  }

  async function loadSensor() {
    try {
      sensor = await api.sensor(sensorId, { signal, timeoutMs: 6000 });
      nodes.subtitle.textContent = `${sensor.name || sensorId} · ${sensor.building || 'bâtiment non renseigné'}`
        + `${sensor.active === false ? ' · inactif' : ''} · seuil ${formatNumber(sensor.maxPowerKw, 2)} kW`;
    } catch (error) {
      if (signal.aborted) return false;
      renderError(nodes.banner, error, () => loadSensor());
      return false;
    }
    return true;
  }

  async function loadLatest() {
    try {
      const latest = await api.latest(sensorId, { signal, timeoutMs: 6000 });
      renderLatest(latest, null);
    } catch (error) {
      if (signal.aborted) return;
      renderLatest(null, error);
    }
  }

  async function loadData({ append = false } = {}) {
    const seq = ++requestSeq;
    const { from, to } = buildRange(preset.id);
    if (!validateRange(from, to, HISTORY_MAX_DAYS).ok) {
      nodes.chartNote.textContent = `Plage limitée à ${HISTORY_MAX_DAYS} jours.`;
      return;
    }
    nodes.rangeLabel.textContent = `${formatUtc(from)} → ${formatUtc(to)}`;
    try {
      const history = await api.history(sensorId, {
        from,
        to,
        limit: 500,
        order: 'asc',
        cursor: append ? nextCursor : undefined,
      }, { signal, timeoutMs: 10000 });
      if (seq !== requestSeq) return;
      const batch = Array.isArray(history?.items) ? history.items : [];
      points = append ? [...points, ...batch] : batch;
      nextCursor = history?.nextCursor || null;
    } catch (error) {
      if (signal.aborted || seq !== requestSeq) return;
      if (!append) nodes.chartNote.textContent = '';
      nodes.banner.innerHTML = errorState(error);
      return;
    }

    try {
      const page = await api.anomalies(sensorId, { from, to, size: 100 }, { signal, timeoutMs: 8000 });
      if (seq !== requestSeq) return;
      anomalies = Array.isArray(page?.items) ? page.items : [];
    } catch (error) {
      if (signal.aborted || seq !== requestSeq) return;
      anomalies = [];
      nodes.anomaliesCount.textContent = 'anomalies indisponibles';
    }
    renderChart();
    renderAnomalies();
  }

  async function refreshAll() {
    if (refreshing) return;
    refreshing = true;
    try {
      const ok = await loadSensor();
      if (!ok) return;
      await Promise.allSettled([loadLatest(), loadData({ append: false })]);
    } finally {
      refreshing = false;
    }
  }

  mount.querySelectorAll('[data-preset]').forEach((button) => {
    button.addEventListener('click', async () => {
      preset = presetById(button.dataset.preset);
      mount.querySelectorAll('[data-preset]').forEach((node) => {
        node.setAttribute('aria-pressed', String(node === button));
      });
      nextCursor = null;
      await loadData({ append: false });
    });
  });

  nodes.more.addEventListener('click', () => loadData({ append: true }));
  mount.querySelector('[data-refresh]')?.addEventListener('click', () => refreshAll());

  await refreshAll();

  return {
    destroy() {
      destroyChart();
    },
  };
}
