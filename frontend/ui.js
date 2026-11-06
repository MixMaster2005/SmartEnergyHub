/**
 * Petits utilitaires d'interface : badges, cartes, etats vides et notifications.
 * Les chaines injectees dans le HTML doivent etre echappees par l'appelant
 * (voir `escapeHtml` dans `format.js`).
 */
import { errorMessage, isUpstreamUnavailable } from './errors.js';
import { escapeHtml } from './format.js';

let toastHost = null;

function ensureToastHost() {
  if (toastHost && document.body.contains(toastHost)) return toastHost;
  toastHost = document.getElementById('toasts');
  if (!toastHost) {
    toastHost = document.createElement('div');
    toastHost.id = 'toasts';
    toastHost.className = 'toasts';
    document.body.append(toastHost);
  }
  return toastHost;
}

/** Notification ephemere. `kind` : info, success, warn, danger. */
export function toast(message, kind = 'info', ttlMs = 6000) {
  const host = ensureToastHost();
  const node = document.createElement('div');
  node.className = `toast toast--${kind}`;
  node.setAttribute('role', kind === 'danger' ? 'alert' : 'status');
  node.innerHTML = `<span class="toast__text">${escapeHtml(message)}</span>
    <button class="toast__close" type="button" aria-label="Fermer">×</button>`;
  const remove = () => node.remove();
  node.querySelector('.toast__close')?.addEventListener('click', remove);
  host.append(node);
  if (ttlMs > 0) setTimeout(remove, ttlMs);
  return remove;
}

/** Badge HTML. `tone` : ok, warn, danger, info, neutral. */
export function badge(text, tone = 'neutral', title = '') {
  const safeTitle = title ? ` title="${escapeHtml(title)}"` : '';
  return `<span class="badge badge--${tone}"${safeTitle}>${escapeHtml(text)}</span>`;
}

export function statusBadge(status) {
  if (status === 'UP') return badge('UP', 'ok');
  if (status === 'DOWN' || status === 'OUT_OF_SERVICE') return badge(status, 'danger');
  return badge(status || 'INCONNU', 'warn');
}

/** Carte de statistique. */
export function statCard({ label, value, hint = '', tone = 'neutral', id = '' }) {
  const idAttr = id ? ` id="${escapeHtml(id)}"` : '';
  return `<article class="stat stat--${tone}"${idAttr}>
    <div class="stat__label">${escapeHtml(label)}</div>
    <div class="stat__value">${escapeHtml(value)}</div>
    ${hint ? `<div class="stat__hint">${hint}</div>` : ''}
  </article>`;
}

/** Etat vide (pas une erreur). */
export function emptyState(title, hint = '', actionHtml = '') {
  return `<div class="empty">
    <strong>${escapeHtml(title)}</strong>
    ${hint ? `<p>${escapeHtml(hint)}</p>` : ''}
    ${actionHtml}
  </div>`;
}

/** Bloc d'erreur lisible, avec code et traceId si presents. */
export function errorState(error, { retry = false } = {}) {
  const problem = error?.problem;
  const code = problem?.code || error?.code || 'ERREUR';
  const traceId = problem?.traceId;
  const upstream = isUpstreamUnavailable(error);
  return `<div class="empty empty--error">
    <strong>${escapeHtml(errorMessage(error))}</strong>
    <p class="muted">Code : <code>${escapeHtml(code)}</code>${traceId ? ` · traceId : <code>${escapeHtml(traceId)}</code>` : ''}</p>
    ${upstream ? '<p class="muted">La gateway n\'a pas pu joindre le service amont. Vérifiez <code>docker compose ps</code>.</p>' : ''}
    ${retry ? '<button class="button button--ghost" type="button" data-retry>Réessayer</button>' : ''}
  </div>`;
}

/** Affiche une erreur dans un conteneur et branche le bouton Reessayer. */
export function renderError(container, error, retry) {
  container.innerHTML = errorState(error, { retry: Boolean(retry) });
  if (retry) {
    container.querySelector('[data-retry]')?.addEventListener('click', retry);
  }
}

/** Indicateur de chargement. */
export function loading(container, message = 'Chargement…') {
  container.innerHTML = `<div class="loading"><span class="spinner"></span> ${escapeHtml(message)}</div>`;
}

/** Ajoute une ligne de journal dans un conteneur. */
export function logLine(container, message, tone = 'neutral') {
  const line = document.createElement('div');
  line.className = `log-line log-line--${tone}`;
  const time = new Date().toISOString().slice(11, 19);
  line.innerHTML = `<span class="log-line__time">${time} UTC</span> ${escapeHtml(message)}`;
  container.append(line);
  container.scrollTop = container.scrollHeight;
}

/** Copie dans le presse-papiers, avec repli. */
export async function copyText(value) {
  const text = String(value ?? '');
  try {
    await navigator.clipboard.writeText(text);
    toast('Copié dans le presse-papiers.', 'success', 2500);
    return true;
  } catch {
    toast('Copie impossible : sélectionnez le texte manuellement.', 'warn');
    return false;
  }
}

/** Telecharge un contenu texte. */
export function download(filename, content, mime = 'text/plain;charset=utf-8') {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
