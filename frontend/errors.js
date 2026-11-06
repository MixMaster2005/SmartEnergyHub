/**
 * Decodage tolerant des erreurs HTTP de la plateforme.
 *
 * Deux formes coexistent :
 *  - les services renvoient un Problem Details complet (`type`, `title`, `status`, `code`,
 *    `detail`, `traceId`, `errors[]`) ;
 *  - la gateway renvoie un corps reduit (`code=UPSTREAM_UNAVAILABLE`) sans `errors[]`.
 *
 * Ce module ne suppose donc jamais qu'un champ existe. Il est pur (aucun acces DOM) et
 * directement teste par `node --test`.
 */

const MESSAGES = {
  VALIDATION_ERROR: 'Requête invalide : un champ est manquant ou mal formé.',
  MALFORMED_JSON: 'JSON mal formé ou type de valeur incorrect.',
  MEASUREMENT_TOO_OLD: 'Mesure trop ancienne (maximum 24 h).',
  MEASUREMENT_IN_FUTURE: 'Mesure dans le futur (maximum 5 min de tolérance).',
  INVALID_PARAMETER: 'Paramètre invalide.',
  RANGE_TOO_LARGE: 'Plage de dates trop large.',
  SENSOR_NOT_FOUND: 'Capteur introuvable.',
  MEASUREMENT_NOT_FOUND: 'Aucune mesure pour ce capteur.',
  NO_DATA: 'Aucune donnée sur cette période.',
  RESOURCE_NOT_FOUND: 'Ressource introuvable.',
  SENSOR_ALREADY_EXISTS: 'Ce capteur existe déjà.',
  VERSION_CONFLICT: 'Conflit de version : rechargez la fiche du capteur.',
  METHOD_NOT_ALLOWED: 'Méthode HTTP non autorisée.',
  PAYLOAD_TOO_LARGE: 'Corps de requête trop volumineux (maximum 10 Ko).',
  UNSUPPORTED_MEDIA_TYPE: 'Type de contenu non supporté.',
  INTERNAL_ERROR: 'Erreur interne du service.',
  FORECAST_DISABLED: 'Prévision désactivée : extension 9C non activée.',
  BROKER_UNAVAILABLE: 'Broker Redis indisponible : réessayez dans quelques secondes.',
  UPSTREAM_UNAVAILABLE: 'Service amont indisponible derrière la gateway.',
  NETWORK_ERROR: 'Plateforme injoignable : vérifiez que la gateway répond sur :8080.',
  TIMEOUT: 'Délai dépassé : le service ne répond pas assez vite.',
};

const STATUS_MESSAGES = {
  400: 'Requête invalide.',
  401: 'Authentification requise.',
  403: 'Accès refusé.',
  404: 'Ressource introuvable.',
  409: 'Conflit de version.',
  413: 'Corps de requête trop volumineux.',
  415: 'Type de contenu non supporté.',
  500: 'Erreur interne du service.',
  501: 'Fonctionnalité non activée.',
  502: 'Passerelle en erreur.',
  503: 'Service temporairement indisponible.',
  504: 'Délai dépassé côté passerelle.',
};

/** Erreur applicative normalisée, levee par `api.js`. */
export class ApiError extends Error {
  constructor(problem, path = '') {
    super(problem?.message || 'Erreur API');
    this.name = 'ApiError';
    this.problem = problem;
    this.path = path;
    this.status = problem?.status ?? 0;
    this.code = problem?.code ?? 'NETWORK_ERROR';
  }
}

function text(value) {
  return typeof value === 'string' ? value : '';
}

function normalizeFieldError(entry) {
  if (!entry || typeof entry !== 'object') return null;
  const field = text(entry.field) || text(entry.propertyPath) || text(entry.name);
  const message = text(entry.message) || text(entry.defaultMessage);
  if (!field && !message) return null;
  return { field, message };
}

/**
 * Transforme un corps d'erreur (ou `null`) en objet stable.
 * @param {unknown} payload corps JSON deja parse, ou null
 * @param {number} status statut HTTP, 0 si la requete n'a pas abouti
 * @param {string} fallbackCode code utilise si le corps n'en fournit pas
 */
export function decodeProblem(payload, status = 0, fallbackCode = '') {
  const body = payload && typeof payload === 'object' ? payload : {};
  const code = text(body.code) || fallbackCode || (status ? `HTTP_${status}` : 'NETWORK_ERROR');
  const title = text(body.title);
  const detail = text(body.detail) || text(body.message);
  const errors = Array.isArray(body.errors)
    ? body.errors.map(normalizeFieldError).filter(Boolean)
    : [];
  const message = MESSAGES[code] || STATUS_MESSAGES[status] || title || 'Erreur inattendue.';

  return {
    status: Number.isFinite(body.status) ? Number(body.status) : Number(status) || 0,
    code,
    title,
    detail,
    message,
    traceId: text(body.traceId) || null,
    type: text(body.type) || null,
    errors,
    upstreamUnavailable: code === 'UPSTREAM_UNAVAILABLE',
    raw: payload ?? null,
  };
}

/** Message court affichable, avec les violations de champs eventuelles. */
export function errorMessage(error) {
  if (!error) return '';
  if (!error.problem && !error.raw && typeof error.message === 'string' && error.message) {
    return error.message;
  }
  const problem = error.problem || decodeProblem(error.raw, error.status, error.code);
  const fields = problem.errors?.length
    ? ` (${problem.errors.map((e) => (e.field ? `${e.field}: ${e.message}` : e.message)).join(' ; ')})`
    : '';
  return `${problem.message}${fields}`;
}

/** Vrai si l'erreur signale une absence de donnees (etat vide, pas panne). */
export function isEmptyData(error) {
  const code = error?.problem?.code || error?.code;
  return code === 'NO_DATA' || code === 'MEASUREMENT_NOT_FOUND';
}

/** Vrai si la gateway n'a pas pu joindre le service amont. */
export function isUpstreamUnavailable(error) {
  return error?.problem?.upstreamUnavailable === true || error?.code === 'UPSTREAM_UNAVAILABLE';
}
