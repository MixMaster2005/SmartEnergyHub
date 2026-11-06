import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ApiError,
  decodeProblem,
  errorMessage,
  isEmptyData,
  isUpstreamUnavailable,
} from '../errors.js';

test('decodeProblem : corps problem+json complet', () => {
  const problem = decodeProblem({
    type: 'https://smartenergy.local/problems/validation',
    title: 'Requete invalide',
    status: 400,
    code: 'VALIDATION_ERROR',
    detail: 'sensorId: must not be blank',
    traceId: 'abc-123',
    errors: [{ field: 'sensorId', message: 'must not be blank' }],
  }, 400);

  assert.equal(problem.status, 400);
  assert.equal(problem.code, 'VALIDATION_ERROR');
  assert.equal(problem.traceId, 'abc-123');
  assert.deepEqual(problem.errors, [{ field: 'sensorId', message: 'must not be blank' }]);
  assert.match(problem.message, /Requête invalide/);
  assert.equal(problem.upstreamUnavailable, false);
});

test('decodeProblem : corps absent et code de repli', () => {
  const problem = decodeProblem(null, 0, 'TIMEOUT');

  assert.equal(problem.status, 0);
  assert.equal(problem.code, 'TIMEOUT');
  assert.match(problem.message, /Délai dépassé/);
  assert.deepEqual(problem.errors, []);
  assert.equal(problem.raw, null);
});

test('decodeProblem : code inconnu -> message du statut HTTP', () => {
  const problem = decodeProblem({ code: 'MYSTERE' }, 404);

  assert.equal(problem.code, 'MYSTERE');
  assert.equal(problem.message, 'Ressource introuvable.');
});

test('decodeProblem : aucune information -> HTTP_<status>', () => {
  const problem = decodeProblem({}, 418);

  assert.equal(problem.code, 'HTTP_418');
  assert.equal(problem.message, 'Erreur inattendue.');
});

test('decodeProblem : reponse reduite de la gateway', () => {
  const problem = decodeProblem({
    status: 503,
    code: 'UPSTREAM_UNAVAILABLE',
    title: 'Service indisponible',
    traceId: 'gw-1',
  }, 503);

  assert.equal(problem.upstreamUnavailable, true);
  assert.match(problem.message, /Service amont indisponible/);
  assert.equal(problem.title, 'Service indisponible');
});

test('decodeProblem : entrees de champ incompletes filtrees', () => {
  const problem = decodeProblem({
    code: 'VALIDATION_ERROR',
    errors: [null, {}, { message: 'seul le message' }, { field: 'powerKw', message: 'doit etre positif' }],
  }, 400);

  assert.deepEqual(problem.errors, [
    { field: '', message: 'seul le message' },
    { field: 'powerKw', message: 'doit etre positif' },
  ]);
});

test('ApiError expose statut, code et chemin', () => {
  const error = new ApiError(decodeProblem({ code: 'NO_DATA', status: 404 }, 404), '/api/analytics/x/summary');

  assert.ok(error instanceof Error);
  assert.equal(error.name, 'ApiError');
  assert.equal(error.status, 404);
  assert.equal(error.code, 'NO_DATA');
  assert.equal(error.path, '/api/analytics/x/summary');
  assert.equal(isEmptyData(error), true);
});

test('errorMessage : ajoute les violations de champs', () => {
  const error = new ApiError(decodeProblem({
    code: 'VALIDATION_ERROR',
    status: 400,
    errors: [
      { field: 'sensorId', message: 'ne doit pas etre vide' },
      { field: 'powerKw', message: 'doit etre positif' },
    ],
  }, 400));

  const message = errorMessage(error);
  assert.match(message, /Requête invalide/);
  assert.match(message, /sensorId: ne doit pas etre vide/);
  assert.match(message, /powerKw: doit etre positif/);
});

test('errorMessage : accepte une Error standard', () => {
  assert.equal(errorMessage(new Error('panne locale')), 'panne locale');
  assert.equal(errorMessage(null), '');
});

test('isEmptyData : seules les absences de donnees comptent', () => {
  const noData = new ApiError(decodeProblem({ code: 'NO_DATA' }, 404));
  const notFound = new ApiError(decodeProblem({ code: 'MEASUREMENT_NOT_FOUND' }, 404));
  const other = new ApiError(decodeProblem({ code: 'INTERNAL_ERROR' }, 500));

  assert.equal(isEmptyData(noData), true);
  assert.equal(isEmptyData(notFound), true);
  assert.equal(isEmptyData(other), false);
  assert.equal(isEmptyData(null), false);
});

test('isUpstreamUnavailable : detection par code ou drapeau', () => {
  const viaCode = { code: 'UPSTREAM_UNAVAILABLE' };
  const viaProblem = { problem: { upstreamUnavailable: true } };

  assert.equal(isUpstreamUnavailable(viaCode), true);
  assert.equal(isUpstreamUnavailable(viaProblem), true);
  assert.equal(isUpstreamUnavailable({ code: 'NETWORK_ERROR' }), false);
});
