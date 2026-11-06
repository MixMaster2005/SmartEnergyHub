import test from 'node:test';
import assert from 'node:assert/strict';

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

test('parseMetric : extrait mesures et tags', () => {
  const metric = parseMetric({
    name: 'energy_stream_lag',
    measurements: [
      { statistic: 'VALUE', value: '3' },
      { statistic: 'BROKEN', value: 'n/a' },
      null,
    ],
    availableTags: [{ tag: 'group', values: ['storage-group'] }, { tag: 'vide' }],
  });

  assert.equal(metric.name, 'energy_stream_lag');
  assert.deepEqual(metric.measurements, { VALUE: 3 });
  assert.deepEqual(metric.tags, { group: ['storage-group'], vide: [] });
});

test('parseMetric : charge utile nulle', () => {
  assert.deepEqual(parseMetric(null), { name: '', measurements: {}, tags: {} });
});

test('gaugeValue et counterValue ne confondent pas les statistiques', () => {
  const gauge = parseMetric({ measurements: [{ statistic: 'VALUE', value: 7 }] });
  const counter = parseMetric({ measurements: [{ statistic: 'COUNT', value: 12 }] });

  assert.equal(gaugeValue(gauge), 7);
  assert.equal(counterValue(gauge), null);
  assert.equal(counterValue(counter), 12);
  assert.equal(gaugeValue(counter), null);
});

test('timerAverageMs : TOTAL_TIME en secondes converti en millisecondes', () => {
  const timer = parseMetric({
    measurements: [
      { statistic: 'COUNT', value: 100 },
      { statistic: 'TOTAL_TIME', value: 2.5 },
      { statistic: 'MAX', value: 0.12 },
    ],
  });

  assert.equal(timerAverageMs(timer), 25);
  assert.equal(timerMaxMs(timer), 120);
});

test('timerAverageMs : division par zero ou metrique absente -> null', () => {
  assert.equal(timerAverageMs(parseMetric({ measurements: [{ statistic: 'COUNT', value: 0 }] })), null);
  assert.equal(timerAverageMs(null), null);
  assert.equal(timerMaxMs(null), null);
});

test('RateTracker : premier releve sans debit, second releve calcule', () => {
  const tracker = new RateTracker();

  assert.equal(tracker.update(10, 1000), null);
  assert.equal(tracker.update(20, 3000), 5);
  assert.equal(tracker.update(30, 5000), 5);
});

test('RateTracker : redemarrage (compteur negatif) -> null au lieu d une valeur aberrante', () => {
  const tracker = new RateTracker();
  tracker.update(100, 1000);

  assert.equal(tracker.update(2, 2000), null);
  // Le nouveau point de reference est le compteur bas : le relevé suivant repart proprement.
  assert.equal(tracker.update(3, 3000), 1);
});

test('RateTracker : horodatages identiques ou valeurs invalides -> null', () => {
  const tracker = new RateTracker();
  tracker.update(1, 1000);

  assert.equal(tracker.update(2, 1000), null);
  assert.equal(tracker.update(Number.NaN, 2000), null);
  assert.equal(tracker.update(3, Number.NaN), null);
});

test('RateTracker.reset : oublie le point de reference', () => {
  const tracker = new RateTracker();
  tracker.update(1, 1000);
  tracker.reset();

  assert.equal(tracker.update(5, 2000), null);
});

test('RingBuffer : capacite bornee et ordre conserve', () => {
  const buffer = new RingBuffer(3);
  buffer.push(1);
  buffer.push(2);
  buffer.push(3);
  buffer.push(4);

  assert.equal(buffer.size, 3);
  assert.deepEqual(buffer.toArray(), [2, 3, 4]);
  assert.deepEqual(buffer.toArray(), [2, 3, 4], 'toArray ne modifie pas le tampon');

  buffer.clear();
  assert.equal(buffer.size, 0);
});

test('RingBuffer : capacite minimale de 1', () => {
  const buffer = new RingBuffer(0);
  buffer.push('a');
  buffer.push('b');

  assert.deepEqual(buffer.toArray(), ['b']);
});

test('formatRate : valeurs lisibles et inconnues', () => {
  assert.equal(formatRate(null), 'n/d');
  assert.equal(formatRate(Number.NaN), 'n/d');
  assert.equal(formatRate(0), '0/s');
  assert.equal(formatRate(0.125), '0.13/s');
  assert.equal(formatRate(1.25), '1.3/s');
  assert.equal(formatRate(42.4), '42/s');
});
