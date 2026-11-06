/**
 * Page Analyses : indicateurs analytics, resume du jour, anomalies et etat de la prevision.
 * Les 404 `NO_DATA` sont des etats vides, pas des pannes ; le 501 de prevision est
 * une limite documentee (extension 9C).
 */
import { api } from '../api.js';
import { isEmptyData } from '../errors.js';
import {
  escapeHtml,
  formatKwh,
  formatKw,
  formatLocal,
  formatNumber,
  formatUtc,
  severityLabel,
  shortId,
  typeLabel,
} from '../format.js';
import { buildRange, dateOnly, presetById, todayUtc, validateRange } from '../ranges.js';
import { badge, emptyState, errorState, renderError, statCard } from '../ui.js';

const MAX_DAYS = 31;

export async function render({ mount, params, signal, navigate }) {
  mount.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Analyses</h1>
        <p>Indicateurs horaires, résumé quotidien et anomalies d'un capteur.</p>
      </div>
      <div class="page-head__actions">
        <select class="select" data-sensor aria-label="Capteur"></select>
        <div class="segmented" data-range role="group" aria-label="Plage des indicateurs">
          <button type="button" data-preset="24h" aria-pressed="true">24 h</button>
          <button type="button" data-preset="7d" aria-pressed="false">7 j</button>
          <button type="button" data-preset="31d" aria-pressed="false">31 j</button>
        </div>
        <button class="button button--ghost" type="button" data-refresh>Rafraîchir</button>
      </div>
    </div>
    <div data-banner></div>
    <section class="section">
      <div class="section__head"><h2>Indicateurs de la période</h2><span class="muted small" data-range-label></span></div>
      <div class="grid grid--stats" data-summary>
        <div class="loading"><span class="spinner"></span> Chargement…</div>
      </div>
    </section>
    <section class="section">
      <div class="section__head">
        <h2>Résumé du jour</h2>
        <span class="muted small" data-daily-label></span>
      </div>
      <div class="grid grid--stats" data-daily>
        <div class="loading"><span class="spinner"></span> Chargement…</div>
      </div>
      <p class="small muted" style="margin-top:.5rem">Jour UTC :
        <input class="input" type="date" data-date aria-label="Date du résumé quotidien">
      </p>
    </section>
    <section class="section">
      <div class="section__head">
        <h2>Anomalies</h2>
        <span class="muted small" data-anomalies-count></span>
        <select class="select" data-type aria-label="Filtrer par type d'anomalie">
          <option value="">Tous les types</option>
          <option value="THRESHOLD">Seuil</option>
          <option value="ZSCORE">Z-score</option>
          <option value="FORECAST_RESIDUAL">Résidu prévision</option>
        </select>
      </div>
      <div data-anomalies><div class="loading"><span class="spinner"></span> Chargement…</div></div>
    </section>
    <section class="section">
      <div class="section__head"><h2>Prévision</h2><span class="muted small">extension à venir</span></div>
      <div data-forecast><div class="loading"><span class="spinner"></span> Vérification…</div></div>
    </section>
  `;

  const nodes = {
    sensor: mount.querySelector('[data-sensor]'),
    date: mount.querySelector('[data-date]'),
    rangeLabel: mount.querySelector('[data-range-label]'),
    banner: mount.querySelector('[data-banner]'),
    summary: mount.querySelector('[data-summary]'),
    daily: mount.querySelector('[data-daily]'),
    dailyLabel: mount.querySelector('[data-daily-label]'),
    anomalies: mount.querySelector('[data-anomalies]'),
    anomaliesCount: mount.querySelector('[data-anomalies-count]'),
    type: mount.querySelector('[data-type]'),
    forecast: mount.querySelector('[data-forecast]'),
  };

  let sensors = [];
  let sensorId = params.id || '';
  let preset = presetById('24h');
  let date = todayUtc();
  nodes.date.value = date;
  nodes.date.max = todayUtc();

  async function loadSensors() {
    try {
      sensors = await api.allSensors({ signal, timeoutMs: 7000 });
      if (!sensorId || !sensors.some((sensor) => sensor.sensorId === sensorId)) {
        sensorId = sensors.find((sensor) => sensor.active !== false)?.sensorId || sensors[0]?.sensorId || '';
      }
      nodes.sensor.innerHTML = sensors.map((sensor) => `<option value="${escapeHtml(sensor.sensorId)}" ${sensor.sensorId === sensorId ? 'selected' : ''}>${escapeHtml(sensor.sensorId)} — ${escapeHtml(sensor.name || '')}</option>`).join('');
      if (!sensors.length) {
        const message = emptyState('Aucun capteur enregistré.', 'Lancez make sim pour créer des capteurs et publier des mesures.');
        nodes.summary.innerHTML = message;
        nodes.daily.innerHTML = message;
        nodes.anomalies.innerHTML = message;
        nodes.forecast.innerHTML = message;
        return false;
      }
      return true;
    } catch (error) {
      if (signal.aborted) return false;
      renderError(nodes.banner, error, () => refreshAll());
      return false;
    }
  }

  function renderSummary(summary) {
    nodes.summary.innerHTML = [
      statCard({ label: 'Mesures agrégées', value: formatNumber(summary.count, 0), hint: 'regroupées par heure' }),
      statCard({ label: 'Puissance moyenne', value: formatKw(summary.avgPowerKw), hint: 'granularité horaire' }),
      statCard({ label: 'Puissance minimale', value: formatKw(summary.minPowerKw), hint: 'sur la période' }),
      statCard({ label: 'Puissance maximale', value: formatKw(summary.maxPowerKw), hint: 'sur la période' }),
      statCard({ label: 'Énergie', value: formatKwh(summary.energyKwh), hint: 'intégration rectangle' }),
      statCard({
        label: 'Dépassements du seuil',
        value: formatNumber(summary.thresholdExceededCount, 0),
        hint: summary.thresholdKw === null || summary.thresholdKw === undefined ? 'seuil inconnu' : `seuil ${formatNumber(summary.thresholdKw, 2)} kW`,
        tone: summary.thresholdExceededCount > 0 ? 'warn' : 'ok',
      }),
      statCard({
        label: 'Anomalies',
        value: formatNumber(summary.anomalyCount, 0),
        hint: 'tous types confondus',
        tone: summary.anomalyCount > 0 ? 'warn' : 'ok',
      }),
    ].join('');
  }

  function renderDaily(daily) {
    nodes.daily.innerHTML = [
      statCard({ label: 'Mesures', value: formatNumber(daily.count, 0), hint: `jour UTC ${escapeHtml(daily.date)}` }),
      statCard({ label: 'Moyenne', value: formatKw(daily.avgPowerKw), hint: 'kW' }),
      statCard({ label: 'Minimum', value: formatKw(daily.minPowerKw), hint: 'kW' }),
      statCard({ label: 'Maximum', value: formatKw(daily.maxPowerKw), hint: 'kW' }),
      statCard({ label: 'Énergie', value: formatKwh(daily.energyKwh), hint: 'kWh du jour' }),
      statCard({ label: 'Dépassements', value: formatNumber(daily.thresholdExceededCount, 0), hint: 'seuil franchi', tone: daily.thresholdExceededCount > 0 ? 'warn' : 'ok' }),
      statCard({ label: 'Anomalies', value: formatNumber(daily.anomalyCount, 0), hint: 'détectées ce jour', tone: daily.anomalyCount > 0 ? 'warn' : 'ok' }),
    ].join('');
    nodes.dailyLabel.textContent = daily.date;
  }

  async function loadSummary() {
    const { from, to } = buildRange(preset.id);
    if (!validateRange(from, to, MAX_DAYS).ok) {
      nodes.rangeLabel.textContent = `Plage limitée à ${MAX_DAYS} jours.`;
      return;
    }
    nodes.rangeLabel.textContent = `${formatUtc(from)} → ${formatUtc(to)}`;
    try {
      const summary = await api.summary(sensorId, { from, to }, { signal, timeoutMs: 8000 });
      renderSummary(summary);
    } catch (error) {
      if (signal.aborted) return;
      if (isEmptyData(error)) {
        nodes.summary.innerHTML = emptyState('Aucune donnée sur cette période.', 'Les agrégats horaires apparaissent après le traitement des mesures.');
      } else {
        renderError(nodes.summary, error, () => loadSummary());
      }
    }
  }

  async function loadDaily() {
    try {
      const daily = await api.dailySummary(sensorId, { date }, { signal, timeoutMs: 8000 });
      renderDaily(daily);
    } catch (error) {
      if (signal.aborted) return;
      if (isEmptyData(error)) {
        nodes.daily.innerHTML = emptyState('Aucun agrégat pour ce jour.', 'Choisissez une autre date ou lancez le simulateur.');
        nodes.dailyLabel.textContent = date;
      } else {
        renderError(nodes.daily, error, () => loadDaily());
      }
    }
  }

  async function loadAnomalies() {
    const { from, to } = buildRange(preset.id);
    try {
      const page = await api.anomalies(sensorId, { from, to, type: nodes.type.value || undefined, size: 100 }, { signal, timeoutMs: 8000 });
      const items = Array.isArray(page?.items) ? page.items : [];
      const total = Number(page?.totalElements ?? items.length);
      nodes.anomaliesCount.textContent = `${total} anomalie(s)${total > items.length ? ` · ${items.length} affichée(s)` : ''}`;
      if (items.length === 0) {
        nodes.anomalies.innerHTML = emptyState('Aucune anomalie sur cette période.', 'Un pic au-delà de maxPowerKw ou un z-score élevé créera une ligne ici.');
        return;
      }
      nodes.anomalies.innerHTML = `<div class="card card--flush table-wrap">
        <table>
          <thead><tr><th>Horodatage</th><th>Type</th><th>Sévérité</th><th class="num">Puissance</th><th class="num">Seuil</th><th>Source</th><th class="num">z</th><th>Event</th></tr></thead>
          <tbody>${items.map((anomaly) => `<tr>
            <td title="${escapeHtml(formatLocal(anomaly.timestamp))}">${escapeHtml(formatUtc(anomaly.timestamp))}</td>
            <td>${badge(typeLabel(anomaly.type), anomaly.type === 'THRESHOLD' ? 'warn' : 'info')}</td>
            <td>${badge(severityLabel(anomaly.severity), anomaly.severity === 'HIGH' ? 'danger' : anomaly.severity === 'MEDIUM' ? 'warn' : 'neutral')}</td>
            <td class="num">${formatNumber(anomaly.powerKw, 2)} kW</td>
            <td class="num">${anomaly.thresholdKw === null || anomaly.thresholdKw === undefined ? '—' : `${formatNumber(anomaly.thresholdKw, 2)} kW`}</td>
            <td>${anomaly.thresholdSource ? (anomaly.thresholdSource === 'SENSOR' ? badge('seuil du capteur', 'ok', 'Seuil défini sur le capteur') : badge('seuil par défaut', 'warn', 'Capteur non enregistré : seuil par défaut appliqué')) : '—'}</td>
            <td class="num">${anomaly.zScore === null || anomaly.zScore === undefined ? '—' : formatNumber(anomaly.zScore, 2)}</td>
            <td><code title="${escapeHtml(anomaly.eventId || '')}">${escapeHtml(shortId(anomaly.eventId))}</code></td>
          </tr>`).join('')}</tbody>
        </table>
      </div>`;
    } catch (error) {
      if (signal.aborted) return;
      renderError(nodes.anomalies, error, () => loadAnomalies());
    }
  }

  async function loadForecast() {
    try {
      const forecast = await api.forecast(sensorId, { horizonHours: 6 }, { signal, timeoutMs: 6000 });
      const points = Array.isArray(forecast?.points) ? forecast.points : [];
      nodes.forecast.innerHTML = `<div class="card">
        <p><strong>Prévision disponible.</strong> ${points.length} point(s) sur ${formatNumber(forecast?.horizonHours ?? 6, 0)} h.</p>
        <div class="table-wrap"><table><thead><tr><th>Horodatage</th><th class="num">Puissance prévue</th></tr></thead>
        <tbody>${points.slice(0, 20).map((point) => `<tr><td>${escapeHtml(formatUtc(point.timestamp))}</td><td class="num">${formatKw(point.powerKw)}</td></tr>`).join('')}</tbody></table></div>
      </div>`;
    } catch (error) {
      if (signal.aborted) return;
      const code = error?.problem?.code || error?.code;
      if (code === 'FORECAST_DISABLED') {
        nodes.forecast.innerHTML = `<div class="card">
          <p>${badge('501 FORECAST_DISABLED', 'info')} La prévision est une extension documentée (CDC §18.1).</p>
          <p class="muted small">L'endpoint existe déjà et renverra 200 avec <code>points[]</code> lorsque <code>ANALYTICS_FORECAST_ENABLED=true</code> et qu'un modèle ONNX sera chargé.</p>
        </div>`;
      } else {
        renderError(nodes.forecast, error, () => loadForecast());
      }
    }
  }

  async function refreshAll() {
    const ok = await loadSensors();
    if (!ok) return;
    await Promise.allSettled([loadSummary(), loadDaily(), loadAnomalies(), loadForecast()]);
  }

  nodes.sensor.addEventListener('change', () => {
    sensorId = nodes.sensor.value;
    navigate(`/analytics/${encodeURIComponent(sensorId)}`, { replace: true });
    refreshAll();
  });

  mount.querySelectorAll('[data-preset]').forEach((button) => {
    button.addEventListener('click', () => {
      preset = presetById(button.dataset.preset);
      mount.querySelectorAll('[data-preset]').forEach((node) => node.setAttribute('aria-pressed', String(node === button)));
      loadSummary();
      loadAnomalies();
    });
  });

  nodes.type.addEventListener('change', () => loadAnomalies());
  nodes.date.addEventListener('change', () => {
    date = dateOnly(nodes.date.value) || todayUtc();
    nodes.date.value = date;
    loadDaily();
  });
  mount.querySelector('[data-refresh]')?.addEventListener('click', () => refreshAll());

  await refreshAll();
  return { destroy() {} };
}
