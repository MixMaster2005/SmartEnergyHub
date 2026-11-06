#!/usr/bin/env node
/**
 * Captures et verification de l'interface EXT-UI avec Playwright.
 *
 * Le script navigue sur chaque vue, attend la fin des chargements, verifie
 * qu'aucune exception JavaScript ni aucun etat d'erreur ne s'affiche, puis
 * enregistre une capture pleine page et un rapport JSON.
 *
 * Usage :
 *   node capture.mjs                       # http://localhost:8080, Chromium fourni
 *   node capture.mjs --channel=chrome      # Chrome systeme (sans telechargement)
 *   node capture.mjs --out=../preuves/captures/ext-ui
 *
 * Sortie non nulle si une vue echoue : utilisable en CI.
 */
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_OUT = resolve(HERE, '../../preuves/captures/ext-ui');

function parseArgs(argv) {
  const options = {
    baseUrl: 'http://localhost:8080',
    out: DEFAULT_OUT,
    channel: undefined,
    headed: false,
    only: null,
    timeout: 20_000,
  };
  for (const arg of argv) {
    if (arg === '--headed') options.headed = true;
    else if (arg.startsWith('--base-url=')) options.baseUrl = arg.slice('--base-url='.length).replace(/\/$/, '');
    else if (arg.startsWith('--out=')) options.out = resolve(arg.slice('--out='.length));
    else if (arg.startsWith('--channel=')) options.channel = arg.slice('--channel='.length);
    else if (arg.startsWith('--only=')) options.only = arg.slice('--only='.length);
    else if (arg.startsWith('--timeout=')) options.timeout = Number(arg.slice('--timeout='.length));
    else if (arg === '--help' || arg === '-h') {
      console.log('Options : --base-url=URL --out=CHEMIN --channel=chrome --only=vue --headed --timeout=ms');
      process.exit(0);
    } else {
      throw new Error(`Argument inconnu : ${arg}`);
    }
  }
  return options;
}

/** Premiere reponse d'erreur toleree : la prevision est desactivee (501, extension 9C). */
function isExpectedConsoleError(text) {
  return /Failed to load resource.*(501|404)/i.test(text)
    || /the server responded with a status of 501/i.test(text);
}

async function resolveSensorId(baseUrl) {
  try {
    const response = await fetch(`${baseUrl}/api/sensors?size=100`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return null;
    const page = await response.json();
    const sensors = Array.isArray(page?.items) ? page.items : [];
    return sensors.find((sensor) => sensor.active !== false)?.sensorId || sensors[0]?.sensorId || null;
  } catch {
    return null;
  }
}

const VIEWS = [
  {
    id: 'dashboard',
    label: 'Tableau de bord',
    path: '/dashboard',
    ready: '[data-kpis] .stat',
    checks: [
      { selector: '[data-services] .service', min: 5, label: 'cartes de sante' },
      { selector: '[data-sensors] .sensor', min: 1, label: 'capteurs' },
    ],
  },
  {
    id: 'sensors',
    label: 'Liste des capteurs',
    path: '/sensors',
    ready: '#app table, #app .empty',
    checks: [{ selector: '#app table tbody tr', min: 1, label: 'lignes du tableau' }],
  },
  {
    id: 'sensor-detail',
    label: 'Fiche capteur',
    path: (sensorId) => (sensorId ? `/sensors/${encodeURIComponent(sensorId)}` : '/sensors'),
    ready: '[data-latest] .stat',
    checks: [{ selector: '.chart-wrap canvas', min: 1, label: 'graphique Chart.js' }],
  },
  {
    id: 'analytics',
    label: 'Analyses',
    path: '/analytics',
    ready: '[data-summary] .stat, [data-summary] .empty',
    checks: [{ selector: '[data-sensors], #app', min: 1, label: 'conteneur' }],
  },
  {
    id: 'system',
    label: 'Systeme',
    path: '/system',
    ready: '[data-kpis] .stat',
    checks: [
      { selector: '.chart-wrap canvas', min: 3, label: 'graphiques de metriques' },
      { selector: '[data-table] table tbody tr', min: 1, label: 'table des metriques' },
    ],
  },
  {
    id: 'scenarios',
    label: 'Scenarios',
    path: '/scenarios',
    ready: '[data-scenario="T01"]',
    checks: [{ selector: '[data-scenario]', min: 10, label: 'scenarios automatiques' }],
  },
];

async function captureView(browser, options, view, sensorId) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: 'fr-FR',
    timezoneId: 'Indian/Antananarivo',
  });
  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];

  page.on('console', (message) => {
    if (message.type() === 'error' && !isExpectedConsoleError(message.text())) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => pageErrors.push(String(error?.stack || error)));

  const path = typeof view.path === 'function' ? view.path(sensorId) : view.path;
  const url = `${options.baseUrl}/#${path}`;
  const started = Date.now();
  const problems = [];

  try {
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: options.timeout });
    if (response && response.status() >= 400) {
      problems.push(`HTTP ${response.status()} sur ${url}`);
    }
    await page.waitForSelector(view.ready, { timeout: options.timeout });
    // Laisse les sondages initiaux (sante, metriques) terminer leur premier cycle.
    await page.waitForFunction(
      () => document.querySelectorAll('#app .loading').length === 0,
      null,
      { timeout: options.timeout },
    );
    await page.waitForTimeout(400);

    for (const check of view.checks || []) {
      const count = await page.locator(check.selector).count();
      if (count < check.min) {
        problems.push(`${check.label} : ${count} < ${check.min} (${check.selector})`);
      }
    }

    const errorStates = await page.locator('.empty--error').allInnerTexts();
    if (errorStates.length) {
      problems.push(`etat d'erreur affiche : ${errorStates.join(' | ').replace(/\s+/g, ' ').slice(0, 400)}`);
    }

    if (pageErrors.length) problems.push(...pageErrors.map((error) => `exception JS : ${error}`));
    if (consoleErrors.length) problems.push(...consoleErrors.map((error) => `console : ${error}`));

    const file = `${options.out}/${view.id}.png`;
    await page.screenshot({ path: file, fullPage: true });

    return {
      id: view.id,
      label: view.label,
      url,
      title: await page.title(),
      screenshot: `${view.id}.png`,
      durationMs: Date.now() - started,
      problems,
      ok: problems.length === 0,
    };
  } catch (error) {
    const file = `${options.out}/${view.id}.png`;
    try {
      await page.screenshot({ path: file, fullPage: true });
    } catch {
      /* la capture d'echec est optionnelle */
    }
    problems.push(String(error?.message || error));
    return {
      id: view.id,
      label: view.label,
      url,
      title: '',
      screenshot: `${view.id}.png`,
      durationMs: Date.now() - started,
      problems,
      ok: false,
    };
  } finally {
    await context.close();
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  await mkdir(options.out, { recursive: true });

  const sensorId = await resolveSensorId(options.baseUrl);
  const views = options.only ? VIEWS.filter((view) => view.id === options.only) : VIEWS;
  if (!views.length) throw new Error(`Vue inconnue : ${options.only}`);

  const launchOptions = { headless: !options.headed };
  if (options.channel) launchOptions.channel = options.channel;
  const browser = await chromium.launch(launchOptions);

  const results = [];
  try {
    for (const view of views) {
      const result = await captureView(browser, options, view, sensorId);
      results.push(result);
      const status = result.ok ? 'OK  ' : 'ECHEC';
      console.log(`[${status}] ${result.id.padEnd(14)} ${result.url} (${result.durationMs} ms)`);
      for (const problem of result.problems) console.log(`        - ${problem}`);
    }
  } finally {
    await browser.close();
  }

  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl: options.baseUrl,
    sensorId,
    viewport: '1440x1000',
    browser: options.channel || 'chromium (Playwright)',
    results,
    ok: results.every((result) => result.ok),
  };
  const reportPath = `${options.out}/capture-report.json`;
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(`\nRapport : ${reportPath}`);
  console.log(`Captures : ${options.out}`);

  if (!report.ok) process.exitCode = 1;
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
