import test from 'node:test';
import assert from 'node:assert/strict';

/**
 * Fumee de chargement : chaque module de l'interface doit s'importer sans
 * dependre du DOM au niveau racine. `app.js` est exclu (il demarre le routeur
 * et lit `document` des l'evaluation) ; il est charge par `index.html`.
 */
const MODULES = [
  'api.js',
  'errors.js',
  'format.js',
  'metrics.js',
  'poller.js',
  'ranges.js',
  'report.js',
  'router.js',
  'ui.js',
  'views/dashboard.js',
  'views/sensors.js',
  'views/analytics.js',
  'views/system.js',
  'views/scenarios.js',
];

for (const module of MODULES) {
  test(`import sans DOM : ${module}`, async () => {
    const loaded = await import(`../${module}`);
    assert.equal(typeof loaded, 'object');
  });
}

test('api : URL relatives a l origine de la page', async () => {
  const { apiUrl } = await import('../api.js');

  assert.equal(apiUrl('/api/sensors'), 'http://localhost:8080/api/sensors');
  assert.equal(apiUrl('https://exemple.test/api'), 'https://exemple.test/api');
});

test('vues : export render', async () => {
  for (const view of ['dashboard', 'sensors', 'analytics', 'system', 'scenarios']) {
    const loaded = await import(`../views/${view}.js`);
    assert.equal(typeof loaded.render, 'function', view);
  }
});
