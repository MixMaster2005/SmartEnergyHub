/**
 * Client HTTP de l'interface. Toutes les URL sont relatives a l'origine de la page :
 * l'interface est servie par la gateway (`:8080`), donc aucun CORS n'est necessaire.
 * Les helpers sont purs autant que possible ; seules les fonctions `api.*` touchent le reseau.
 */
import { ApiError, decodeProblem } from './errors.js';

const ORIGIN = typeof location !== 'undefined' && location.origin
  ? location.origin
  : 'http://localhost:8080';

/** Origine utilisee pour les appels (gateway). */
export const API_BASE = ORIGIN;

/** Construit une URL absolue a partir d'un chemin d'API. */
export function apiUrl(path) {
  return /^https?:\/\//.test(path) ? path : `${ORIGIN}${path}`;
}

function iso(value) {
  if (value instanceof Date) return value.toISOString();
  return value;
}

function qs(params = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, iso(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

function tagQuery(tags = {}) {
  const search = new URLSearchParams();
  for (const [tag, value] of Object.entries(tags)) {
    if (value !== undefined && value !== null) search.append('tag', `${tag}:${value}`);
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/**
 * Requete HTTP avec delai maximal et annulation cooperative.
 * @returns {Promise<{status:number, headers:Headers, payload:any}>}
 */
export async function requestDetailed(path, {
  method = 'GET',
  body,
  headers = {},
  signal,
  timeoutMs = 8000,
} = {}) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', onAbort, { once: true });
  }

  try {
    const init = { method, headers: { Accept: 'application/json', ...headers }, signal: controller.signal };
    if (body !== undefined) {
      if (typeof body === 'string') {
        init.body = body;
      } else {
        init.headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
      }
    }

    const response = await fetch(apiUrl(path), init);
    const raw = await response.text();
    let payload = null;
    if (raw) {
      try {
        payload = JSON.parse(raw);
      } catch {
        payload = raw;
      }
    }

    if (!response.ok) {
      throw new ApiError(decodeProblem(payload, response.status), path);
    }
    return { status: response.status, headers: response.headers, payload };
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (error?.name === 'AbortError') {
      throw new ApiError(decodeProblem(null, 0, timedOut ? 'TIMEOUT' : 'NETWORK_ERROR'), path);
    }
    throw new ApiError(decodeProblem(null, 0, 'NETWORK_ERROR'), path);
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
  }
}

/** Requete HTTP qui ne renvoie que le corps JSON (ou null pour 204). */
export async function request(path, options) {
  const { payload } = await requestDetailed(path, options);
  return payload;
}

/** Chemins de sante : la gateway se sert elle-meme, les services passent par les routes ops (D-26). */
export const HEALTH_PATHS = {
  gateway: '/actuator/health',
  collector: '/collector/actuator/health',
  storage: '/storage/actuator/health',
  analytics: '/analytics/actuator/health',
  sensor: '/sensor/actuator/health',
};

export function healthPath(service) {
  return HEALTH_PATHS[service] || `/${service}/actuator/health`;
}

export const api = {
  health(service, options) {
    return request(healthPath(service), options);
  },

  sensors({ page = 0, size = 100, building, active } = {}, options) {
    return request(`/api/sensors${qs({ page, size, building, active })}`, options);
  },

  /** Parcourt les pages de capteurs (size max 100) et renvoie la liste complete. */
  async allSensors(options) {
    const items = [];
    let page = 0;
    for (;;) {
      const result = await api.sensors({ page, size: 100 }, options);
      const batch = Array.isArray(result?.items) ? result.items : [];
      items.push(...batch);
      const total = Number(result?.totalElements ?? items.length);
      if (batch.length === 0 || items.length >= total || page >= 49) break;
      page += 1;
    }
    return items;
  },

  sensor(id, options) {
    return request(`/api/sensors/${encodeURIComponent(id)}`, options);
  },

  createSensor(body, options) {
    return request('/api/sensors', { ...options, method: 'POST', body });
  },

  updateSensor(id, body, options) {
    return request(`/api/sensors/${encodeURIComponent(id)}`, { ...options, method: 'PUT', body });
  },

  deactivateSensor(id, options) {
    return request(`/api/sensors/${encodeURIComponent(id)}`, { ...options, method: 'DELETE' });
  },

  exceedances({ threshold = -1, from, to } = {}, options) {
    return request(`/api/analytics/exceedances${qs({ threshold, from, to })}`, options);
  },

  latest(sensorId, options) {
    return request(`/api/measurements/${encodeURIComponent(sensorId)}/latest`, options);
  },

  history(sensorId, { from, to, limit = 500, order = 'asc', cursor } = {}, options) {
    return request(
      `/api/measurements/${encodeURIComponent(sensorId)}${qs({ from, to, limit, order, cursor })}`,
      options,
    );
  },

  summary(sensorId, { from, to } = {}, options) {
    return request(`/api/analytics/${encodeURIComponent(sensorId)}/summary${qs({ from, to })}`, options);
  },

  dailySummary(sensorId, { date } = {}, options) {
    return request(`/api/analytics/${encodeURIComponent(sensorId)}/daily-summary${qs({ date })}`, options);
  },

  anomalies(sensorId, { type, from, to, page = 0, size = 100 } = {}, options) {
    return request(
      `/api/analytics/${encodeURIComponent(sensorId)}/anomalies${qs({ type, from, to, page, size })}`,
      options,
    );
  },

  forecast(sensorId, { horizonHours = 6 } = {}, options) {
    return request(
      `/api/analytics/${encodeURIComponent(sensorId)}/forecast${qs({ horizonHours })}`,
      options,
    );
  },

  metric(service, name, tags = {}, options) {
    return request(
      `/${encodeURIComponent(service)}/actuator/metrics/${encodeURIComponent(name)}${tagQuery(tags)}`,
      options,
    );
  },

  /** POST d'une mesure : renvoie statut + en-tetes (dont `X-Event-Id`). */
  postMeasurement(body, options) {
    return requestDetailed('/api/measurements', { ...options, method: 'POST', body });
  },
};
