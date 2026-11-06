import test from 'node:test';
import assert from 'node:assert/strict';

import {
  escapeHtml,
  formatAge,
  formatCelsius,
  formatHertz,
  formatKwh,
  formatKw,
  formatLocal,
  formatNumber,
  formatPercent,
  formatUtc,
  formatUtcTime,
  formatVoltage,
  severityLabel,
  shortId,
  stateLabel,
  toDate,
  truncate,
  typeLabel,
} from '../format.js';

test('toDate : accepte Date, epoch, ISO et rejette le reste', () => {
  assert.equal(toDate('2026-11-05T09:00:00Z').toISOString(), '2026-11-05T09:00:00.000Z');
  assert.equal(toDate(new Date('2026-11-05T09:00:00Z')).toISOString(), '2026-11-05T09:00:00.000Z');
  assert.equal(toDate(Date.parse('2026-11-05T09:00:00Z')).toISOString(), '2026-11-05T09:00:00.000Z');
  assert.equal(toDate('pas une date'), null);
  assert.equal(toDate(''), null);
  assert.equal(toDate(null), null);
});

test('formatUtc : toujours en UTC, comme les preuves', () => {
  assert.equal(formatUtc('2026-11-05T09:01:09Z'), '2026-11-05 09:01:09 UTC');
  assert.equal(formatUtc('2026-11-05T09:01:09.987Z'), '2026-11-05 09:01:09 UTC');
  assert.equal(formatUtc(null), '—');
});

test('formatUtcTime : HH:mm:ss en UTC', () => {
  assert.equal(formatUtcTime('2026-11-05T09:01:09Z'), '09:01:09');
  assert.equal(formatUtcTime(undefined), '—');
});

test('formatLocal : infobulle non vide pour une date valide', () => {
  assert.notEqual(formatLocal('2026-11-05T09:01:09Z'), '');
  assert.equal(formatLocal('invalide'), '');
});

test('formatAge : unites lisibles', () => {
  const now = Date.parse('2026-11-05T10:00:00Z');

  assert.equal(formatAge('2026-11-05T09:59:59.500Z', now), "à l'instant");
  assert.equal(formatAge('2026-11-05T09:59:30Z', now), 'il y a 30 s');
  assert.equal(formatAge('2026-11-05T09:30:00Z', now), 'il y a 30 min');
  assert.equal(formatAge('2026-11-05T07:00:00Z', now), 'il y a 3 h');
  assert.equal(formatAge('2026-11-02T10:00:00Z', now), 'il y a 3 j');
  assert.equal(formatAge(null, now), '—');
});

test('formatNumber : decimales fixes et repli', () => {
  const value = formatNumber(1234.5, 1).replace(/\s/g, ' ');
  assert.equal(value.replace(/\u202f|\u00a0/g, ' '), '1 234,5');
  assert.equal(formatNumber(null), '—');
  assert.equal(formatNumber(Number.NaN), '—');
});

test('formatKw / formatKwh / tensions : unite presente', () => {
  assert.match(formatKw(3.72), /^3,72\s*kW$/);
  assert.match(formatKwh(0), /^0,000\s*kWh$/);
  assert.match(formatVoltage(226.4), /^226,4\s*V$/);
  assert.match(formatHertz(50), /^50,0\s*Hz$/);
  assert.match(formatCelsius(22.5), /^22,5\s*°C$/);
  assert.equal(formatKw(null), '—');
});

test('formatPercent : ratio multiplie par 100', () => {
  assert.match(formatPercent(0.5), /^50\s*%$/);
  assert.match(formatPercent(0.1234, 1), /^12,3\s*%$/);
  assert.equal(formatPercent(null), '—');
});

test('shortId : tronque avec ellipsis', () => {
  assert.equal(shortId('abcdefghijkl', 8), 'abcdefgh…');
  assert.equal(shortId('court', 8), 'court');
  assert.equal(shortId(null), '');
});

test('escapeHtml : neutralise les cinq caracteres dangereux', () => {
  assert.equal(
    escapeHtml('<a href="x">\'&\"</a>'),
    '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&quot;&lt;/a&gt;',
  );
  assert.equal(escapeHtml(null), '');
});

test('libelles metier', () => {
  assert.equal(stateLabel('ON'), 'Marche');
  assert.equal(stateLabel('OFF'), 'Arrêt');
  assert.equal(stateLabel('INCONNU'), 'INCONNU');
  assert.equal(severityLabel('HIGH'), 'Haute');
  assert.equal(typeLabel('THRESHOLD'), 'Seuil');
  assert.equal(typeLabel('FORECAST_RESIDUAL'), 'Résidu prévision');
});

test('truncate : borne la longueur sans casser le texte court', () => {
  assert.equal(truncate('abc', 10), 'abc');
  assert.equal(truncate('abcdefghij', 5), 'abcd…');
  assert.equal(truncate(null, 5), '');
});
