import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { counterValue, gaugeValue, parseMetric, timerAverageMs, timerMaxMs } from '../metrics.js';

/** Charge une reponse reelle capturee sur la plateforme locale (`/tmp/opencode/fixtures`). */
function fixture(name) {
  const path = fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url));
  return JSON.parse(readFileSync(path, 'utf8'));
}

test('fixture sensors : contrat de pagination et champs utilises par les vues', () => {
  const page = fixture('sensors.json');

  assert.ok(Array.isArray(page.items));
  assert.equal(typeof page.totalElements, 'number');
  assert.equal(typeof page.page, 'number');
  assert.equal(typeof page.size, 'number');
  for (const sensor of page.items) {
    assert.equal(typeof sensor.sensorId, 'string');
    assert.equal(typeof sensor.name, 'string');
    assert.equal(typeof sensor.maxPowerKw, 'number');
    assert.equal(typeof sensor.active, 'boolean');
    assert.ok(Array.isArray(sensor.tags));
  }
});

test('fixture exceedances : etat courant avec threshold=-1', () => {
  const rows = fixture('exceedances.json');

  assert.ok(Array.isArray(rows));
  assert.ok(rows.length > 0);
  for (const row of rows) {
    assert.equal(row.thresholdKw, -1);
    assert.equal(typeof row.lastPowerKw, 'number');
    assert.ok(!Number.isNaN(Date.parse(row.lastMeasuredAt)));
  }
});

test('fixture history : items horodates et curseur opaque', () => {
  const history = fixture('history.json');

  assert.equal(typeof history.sensorId, 'string');
  assert.ok(Array.isArray(history.items));
  assert.ok(history.nextCursor === null || typeof history.nextCursor === 'string');
  for (const item of history.items) {
    assert.ok(!Number.isNaN(Date.parse(item.timestamp)));
    assert.equal(typeof item.powerKw, 'number');
  }
});

test('fixture latest : puissance, tension et horodatage', () => {
  const latest = fixture('latest.json');

  assert.ok(!Number.isNaN(Date.parse(latest.timestamp)));
  assert.equal(typeof latest.powerKw, 'number');
  assert.equal(typeof latest.voltage, 'number');
});

test('fixture summary : agregat horaire complet', () => {
  const summary = fixture('summary.json');

  for (const field of ['count', 'avgPowerKw', 'minPowerKw', 'maxPowerKw', 'energyKwh', 'thresholdExceededCount', 'anomalyCount']) {
    assert.equal(typeof summary[field], 'number', field);
  }
  assert.ok(!Number.isNaN(Date.parse(summary.effectiveFrom)));
  assert.ok(!Number.isNaN(Date.parse(summary.effectiveTo)));
});

test('fixture daily-summary : date au format jour UTC', () => {
  const daily = fixture('daily-summary.json');

  assert.match(daily.date, /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(typeof daily.count, 'number');
  assert.equal(typeof daily.energyKwh, 'number');
});

test('fixture anomalies : page vide valide', () => {
  const page = fixture('anomalies.json');

  assert.ok(Array.isArray(page.items));
  assert.equal(page.totalElements, 0);
});

test('fixture anomalies-page : champs affiches par les tableaux', () => {
  const page = fixture('anomalies-page.json');

  assert.equal(page.items.length, 2);
  for (const anomaly of page.items) {
    assert.ok(['THRESHOLD', 'ZSCORE', 'FORECAST_RESIDUAL'].includes(anomaly.type));
    assert.ok(['LOW', 'MEDIUM', 'HIGH'].includes(anomaly.severity));
    assert.ok(!Number.isNaN(Date.parse(anomaly.timestamp)));
    assert.equal(typeof anomaly.powerKw, 'number');
  }
});

test('fixture health : statut Actuator attendu', () => {
  assert.equal(fixture('health.json').status, 'UP');
});

test('fixture metric-gauge : lecture VALUE', () => {
  const metric = parseMetric(fixture('metric-gauge.json'));

  assert.equal(gaugeValue(metric), 3);
  assert.deepEqual(metric.tags.group, ['storage-group', 'analytics-group']);
});

test('fixture metric-counter : lecture COUNT et tags result', () => {
  const metric = parseMetric(fixture('metric-counter.json'));

  assert.equal(counterValue(metric), 12);
  assert.deepEqual(metric.tags.result, ['accepted', 'rejected']);
});

test('fixture metric-timer : moyenne et maximum en millisecondes', () => {
  const metric = parseMetric(fixture('metric-timer.json'));

  assert.equal(timerAverageMs(metric), 25);
  assert.equal(timerMaxMs(metric), 120);
});
