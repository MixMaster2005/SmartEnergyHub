/**
 * Point d'entree de l'interface EXT-UI.
 * Routeur par hash, sante de la gateway dans l'en-tete et gestion globale des erreurs.
 */
import { api } from './api.js';
import { errorMessage } from './errors.js';
import { formatUtc } from './format.js';
import { createPoller } from './poller.js';
import { createRouter } from './router.js';
import { badge, toast } from './ui.js';
import * as dashboard from './views/dashboard.js';
import * as sensors from './views/sensors.js';
import * as analytics from './views/analytics.js';
import * as system from './views/system.js';
import * as scenarios from './views/scenarios.js';

const routes = [
  { pattern: '/dashboard', view: dashboard.render, title: 'Tableau de bord' },
  { pattern: '/sensors', view: sensors.render, title: 'Capteurs' },
  { pattern: '/sensors/:id', view: sensors.render, title: 'Capteur' },
  { pattern: '/analytics', view: analytics.render, title: 'Analyses' },
  { pattern: '/analytics/:id', view: analytics.render, title: 'Analyses' },
  { pattern: '/system', view: system.render, title: 'Système' },
  { pattern: '/scenarios', view: scenarios.render, title: 'Scénarios' },
];

const mount = document.getElementById('app');
const navLinks = [...document.querySelectorAll('[data-nav]')];
const gatewayBadge = document.getElementById('gateway-status');
const lastRefresh = document.getElementById('last-refresh');

function setActiveNav(path) {
  for (const link of navLinks) {
    const target = link.dataset.nav;
    const active = path === target || path.startsWith(`${target}/`);
    if (active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
}

const router = createRouter({
  routes,
  mount,
  defaultPath: '/dashboard',
  onNavigate(route) {
    const matched = routes.find((entry) => entry.pattern === route.pattern);
    document.title = `${matched?.title || 'Interface'} — SmartEnergy Hub`;
    setActiveNav(route.path);
    window.scrollTo({ top: 0, behavior: 'instant' });
  },
});

router.start();

/* --------------------------------------------------- sante globale (en-tete) */

async function refreshGateway() {
  try {
    const health = await api.health('gateway', { timeoutMs: 4000 });
    const up = health?.status === 'UP';
    gatewayBadge.innerHTML = badge(up ? 'gateway : UP' : `gateway : ${health?.status || 'INCONNU'}`, up ? 'ok' : 'danger');
    gatewayBadge.title = `Santé de la gateway · ${formatUtc(new Date())}`;
  } catch (error) {
    gatewayBadge.innerHTML = badge('gateway : DOWN', 'danger');
    gatewayBadge.title = errorMessage(error);
  }
  if (lastRefresh) lastRefresh.textContent = `Vérifié à ${formatUtc(new Date())}`;
}

const gatewayPoller = createPoller(refreshGateway, { intervalMs: 10000 });
gatewayPoller.start();

window.addEventListener('beforeunload', () => gatewayPoller.stop());

/* ------------------------------------------------------- erreurs globales */

window.addEventListener('unhandledrejection', (event) => {
  const message = errorMessage(event.reason);
  if (message) toast(message, 'danger', 7000);
});

window.addEventListener('error', (event) => {
  if (event.error) toast(errorMessage(event.error), 'danger', 7000);
});
