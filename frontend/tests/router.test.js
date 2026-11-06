import test from 'node:test';
import assert from 'node:assert/strict';

import { parseHash, resolveRoute } from '../router.js';

const ROUTES = [
  { pattern: '/dashboard', view: () => {} },
  { pattern: '/sensors', view: () => {} },
  { pattern: '/sensors/:id', view: () => {} },
  { pattern: '/analytics/:id', view: () => {} },
];

test('parseHash : hash vide -> tableau de bord', () => {
  const parsed = parseHash('');

  assert.equal(parsed.path, '/dashboard');
  assert.deepEqual(parsed.segments, ['dashboard']);
  assert.equal(parsed.query.toString(), '');
});

test('parseHash : segments decodes et query separee', () => {
  const parsed = parseHash('#/sensors/SENSOR%2D001?from=a&to=b');

  assert.equal(parsed.path, '/sensors/SENSOR-001');
  assert.deepEqual(parsed.segments, ['sensors', 'SENSOR-001']);
  assert.equal(parsed.query.get('from'), 'a');
  assert.equal(parsed.query.get('to'), 'b');
});

test('parseHash : pourcentage invalide laisse le segment brut', () => {
  const parsed = parseHash('#/sensors/%E0%A4%A');

  assert.deepEqual(parsed.segments, ['sensors', '%E0%A4%A']);
});

test('resolveRoute : route simple', () => {
  const route = resolveRoute(ROUTES, '#/dashboard');

  assert.equal(route.pattern, '/dashboard');
  assert.deepEqual(route.params, {});
  assert.equal(route.path, '/dashboard');
});

test('resolveRoute : parametre de chemin', () => {
  const route = resolveRoute(ROUTES, '#/sensors/SENSOR-001?x=1');

  assert.equal(route.pattern, '/sensors/:id');
  assert.deepEqual(route.params, { id: 'SENSOR-001' });
  assert.equal(route.query.get('x'), '1');
});

test('resolveRoute : nombre de segments different -> aucune correspondance', () => {
  assert.equal(resolveRoute(ROUTES, '#/sensors/SENSOR-001/extra'), null);
  assert.equal(resolveRoute(ROUTES, '#/inconnu'), null);
});
