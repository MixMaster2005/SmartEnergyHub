/**
 * Formatage pur des dates, nombres et libelles. Aucun acces DOM.
 * Toutes les dates sont affichees en UTC (coherence avec les preuves) ;
 * l'heure locale est fournie en infobulle par les vues.
 */

const NBSP = '\u00a0';

export function toDate(value) {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === 'number') {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  if (typeof value === 'string' && value.trim()) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  return null;
}

/** `2026-11-05 14:03:22 UTC` */
export function formatUtc(value) {
  const date = toDate(value);
  if (!date) return '—';
  return `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC`;
}

/** Heure locale française, pour l'infobulle. */
export function formatLocal(value) {
  const date = toDate(value);
  if (!date) return '';
  return date.toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'medium' });
}

/** `HH:mm:ss` en UTC (axes de graphiques). */
export function formatUtcTime(value) {
  const date = toDate(value);
  if (!date) return '—';
  return date.toISOString().slice(11, 19);
}

/** Age lisible d'un instant. */
export function formatAge(value, now = Date.now()) {
  const date = toDate(value);
  if (!date) return '—';
  const ms = Math.max(0, now - date.getTime());
  if (ms < 1000) return "à l'instant";
  if (ms < 60_000) return `il y a ${Math.floor(ms / 1000)} s`;
  if (ms < 3_600_000) return `il y a ${Math.floor(ms / 60_000)} min`;
  if (ms < 86_400_000) return `il y a ${Math.floor(ms / 3_600_000)} h`;
  return `il y a ${Math.floor(ms / 86_400_000)} j`;
}

export function ageMs(value, now = Date.now()) {
  const date = toDate(value);
  return date ? Math.max(0, now - date.getTime()) : null;
}

/** Nombre avec séparateurs français et nombre de décimales fixe. */
export function formatNumber(value, digits = 2) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—';
  return Number(value).toLocaleString('fr-FR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function formatKw(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 2)}${NBSP}kW`;
}

export function formatKwh(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 3)}${NBSP}kWh`;
}

export function formatVoltage(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 1)}${NBSP}V`;
}

export function formatAmpere(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 2)}${NBSP}A`;
}

export function formatHertz(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 1)}${NBSP}Hz`;
}

export function formatCelsius(value) {
  return value === null || value === undefined ? '—' : `${formatNumber(value, 1)}${NBSP}°C`;
}

export function formatPercent(value, digits = 0) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—';
  return `${formatNumber(Number(value) * 100, digits)}${NBSP}%`;
}

/** Identifiant court pour les tableaux (les UUID complets restent en infobulle). */
export function shortId(value, length = 8) {
  const text = String(value ?? '');
  return text.length > length ? `${text.slice(0, length)}…` : text;
}

/** Echappe une chaine avant insertion dans un template HTML. */
export function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/** `ON` -> `Marche`, etc. */
export function stateLabel(state) {
  const labels = { ON: 'Marche', OFF: 'Arrêt', STANDBY: 'Veille', FAULT: 'Défaut' };
  return labels[state] || state || '—';
}

export function severityLabel(severity) {
  const labels = { LOW: 'Basse', MEDIUM: 'Moyenne', HIGH: 'Haute' };
  return labels[severity] || severity || '—';
}

export function typeLabel(type) {
  const labels = {
    THRESHOLD: 'Seuil',
    ZSCORE: 'Z-score',
    FORECAST_RESIDUAL: 'Résidu prévision',
  };
  return labels[type] || type || '—';
}

/** Tronque un texte pour un titre. */
export function truncate(value, length = 80) {
  const text = String(value ?? '');
  return text.length > length ? `${text.slice(0, length - 1)}…` : text;
}
