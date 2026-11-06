import test from 'node:test';
import assert from 'node:assert/strict';

import { TEMPLATE_ROWS, buildJson, buildReport } from '../report.js';

const RESULTS = {
  T01: { status: 'OK', evidence: '5/5 services UP', comment: 'sante' },
  T02: { status: 'OK', evidence: '202 + X-Event-Id', comment: 'mesure valide' },
  T03: { status: 'KO', evidence: 'attendu 400', comment: 'a corriger' },
  T04: { status: 'na', evidence: 'non execute' },
  T05: { status: 'peut-etre', evidence: 'statut inconnu' },
};

test('TEMPLATE_ROWS : T01 a T14, identifiants uniques', () => {
  assert.equal(TEMPLATE_ROWS.length, 14);
  assert.equal(TEMPLATE_ROWS[0].id, 'T01');
  assert.equal(TEMPLATE_ROWS.at(-1).id, 'T14');
  assert.equal(new Set(TEMPLATE_ROWS.map((row) => row.id)).size, 14);
});

test('buildReport : une ligne par test et statuts normalises', () => {
  const report = buildReport({ results: RESULTS, date: '2026-11-05', environment: 'Linux · Docker 29' });

  assert.match(report, /^# Rapport de campagne de tests/m);
  assert.match(report, /- \*\*Date\*\* : 2026-11-05/);
  assert.match(report, /- \*\*Environnement\*\* : Linux · Docker 29/);
  assert.match(report, /\| T01 — santé \| OK \| 5\/5 services UP \| sante \|/);
  assert.match(report, /\| T03 — champ manquant \| KO \| attendu 400 \| a corriger \|/);
  assert.match(report, /\| T04 — valeur invalide \| NA \| non execute \|  \|/);
  assert.match(report, /\| T05 — persistance Cassandra \| NA \| statut inconnu \|  \|/);
});

test('buildReport : les tests non renseignes sont NA, jamais OK par defaut', () => {
  const report = buildReport({ results: {}, date: '2026-11-05' });
  const lines = report.split('\n').filter((line) => /^\| T\d{2}/.test(line));

  assert.equal(lines.length, 14);
  for (const line of lines) {
    assert.match(line, /\| NA \|/, line);
  }
  assert.match(report, /Commit\*\* : à renseigner/);
});

test('buildReport : echappe les pipes et les retours a la ligne', () => {
  const report = buildReport({
    date: '2026-11-05',
    results: { T01: { status: 'OK', evidence: 'a | b', comment: 'ligne 1\nligne 2' } },
  });

  assert.match(report, /a \\\| b/);
  assert.match(report, /ligne 1 ligne 2/);
});

test('buildJson : structure stable et horodatage fourni', () => {
  const json = JSON.parse(buildJson({
    results: RESULTS,
    date: '2026-11-05T10:00:00.000Z',
    environment: 'CI',
    commit: 'abc123',
  }));

  assert.equal(json.generatedAt, '2026-11-05T10:00:00.000Z');
  assert.equal(json.environment, 'CI');
  assert.equal(json.commit, 'abc123');
  assert.equal(json.tests.length, 14);
  assert.deepEqual(json.tests[0], {
    id: 'T01',
    label: 'santé',
    status: 'OK',
    evidence: '5/5 services UP',
    comment: 'sante',
  });
});

test('buildJson : commit absent devient null', () => {
  const json = JSON.parse(buildJson({ results: {}, date: '2026-11-05T10:00:00.000Z' }));

  assert.equal(json.commit, null);
});
