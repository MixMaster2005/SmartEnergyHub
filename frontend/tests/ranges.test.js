import test from 'node:test';
import assert from 'node:assert/strict';

import {
  DAY_MS,
  HOUR_MS,
  RANGE_PRESETS,
  buildRange,
  dateOnly,
  presetById,
  rangeDays,
  todayUtc,
  validateRange,
} from '../ranges.js';

const NOW = Date.parse('2026-11-05T12:00:00Z');

test('presetById : repli sur 24 h', () => {
  assert.equal(presetById('7d').id, '7d');
  assert.equal(presetById('inconnu').id, '24h');
});

test('buildRange : fin = maintenant, debut = maintenant - duree', () => {
  const { from, to, preset } = buildRange('6h', NOW);

  assert.equal(to.getTime(), NOW);
  assert.equal(from.getTime(), NOW - 6 * HOUR_MS);
  assert.equal(preset.id, '6h');
});

test('buildRange : 31 jours couvre 31 * 24 h', () => {
  const { from, to } = buildRange('31d', NOW);

  assert.equal(to.getTime() - from.getTime(), 31 * DAY_MS);
});

test('validateRange : accepte une plage dans la limite', () => {
  const from = new Date(NOW - 3 * DAY_MS);
  const to = new Date(NOW);

  assert.deepEqual(validateRange(from, to, 7), { ok: true });
});

test('validateRange : limite exacte acceptee, depassement refuse', () => {
  const exact = { from: new Date(NOW - 7 * DAY_MS), to: new Date(NOW) };
  const tooLarge = { from: new Date(NOW - 7 * DAY_MS - 1), to: new Date(NOW) };

  assert.equal(validateRange(exact.from, exact.to, 7).ok, true);
  assert.equal(validateRange(tooLarge.from, tooLarge.to, 7).ok, false);
});

test('validateRange : dates inversees ou illisibles refusees', () => {
  assert.equal(validateRange(new Date(NOW), new Date(NOW - 1000), 7).ok, false);
  assert.equal(validateRange('invalide', new Date(NOW), 7).ok, false);
});

test('rangeDays : arrondi superieur', () => {
  assert.equal(rangeDays(NOW - HOUR_MS, NOW), 1);
  assert.equal(rangeDays(NOW - DAY_MS, NOW), 1);
  assert.equal(rangeDays(NOW - DAY_MS - 1, NOW), 2);
  assert.equal(rangeDays(NOW, NOW), 0);
});

test('todayUtc / dateOnly : jour UTC', () => {
  assert.equal(todayUtc(new Date('2026-11-05T23:59:59Z')), '2026-11-05');
  assert.equal(todayUtc(new Date('2026-11-06T00:00:00Z')), '2026-11-06');
  assert.equal(dateOnly('2026-11-05T23:59:59Z'), '2026-11-05');
  assert.equal(dateOnly('invalide'), '');
});

test('presets : identifiants uniques et limites documentees', () => {
  const ids = RANGE_PRESETS.map((preset) => preset.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const preset of RANGE_PRESETS) {
    assert.ok(preset.ms > 0);
    assert.ok(preset.maxDays >= preset.ms / DAY_MS);
  }
});
