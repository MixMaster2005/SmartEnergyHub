/**
 * Lecture des reponses Actuator et calculs de debits (CDC section 9).
 *
 * Toutes les fonctions sont pures : aucun acces reseau ni DOM. Les valeurs sont testees
 * dans `tests/metrics.test.js`. Attention aux unites : Micrometer exprime `TOTAL_TIME`
 * en secondes, les compteurs sont cumulatifs et repartent de zero au redemarrage.
 */

/** Extrait `measurements` et `availableTags` d'une reponse Actuator. */
export function parseMetric(payload) {
  const measurements = {};
  for (const entry of payload?.measurements ?? []) {
    if (!entry || typeof entry.statistic !== 'string') continue;
    const value = Number(entry.value);
    if (Number.isFinite(value)) measurements[entry.statistic] = value;
  }

  const tags = {};
  for (const entry of payload?.availableTags ?? []) {
    if (!entry || typeof entry.tag !== 'string') continue;
    tags[entry.tag] = Array.isArray(entry.values) ? [...entry.values] : [];
  }

  return {
    name: typeof payload?.name === 'string' ? payload.name : '',
    measurements,
    tags,
  };
}

/** Valeur d'une jauge (statistique `VALUE`). */
export function gaugeValue(metric) {
  const value = metric?.measurements?.VALUE;
  return Number.isFinite(value) ? value : null;
}

/** Valeur d'un compteur (statistique `COUNT`). */
export function counterValue(metric) {
  const value = metric?.measurements?.COUNT;
  return Number.isFinite(value) ? value : null;
}

/** Latence moyenne d'un timer en millisecondes (`TOTAL_TIME` est en secondes). */
export function timerAverageMs(metric) {
  const count = metric?.measurements?.COUNT;
  const totalTime = metric?.measurements?.TOTAL_TIME;
  if (!Number.isFinite(count) || !Number.isFinite(totalTime) || count <= 0) return null;
  return (totalTime * 1000) / count;
}

/** Latence maximale d'un timer en millisecondes. */
export function timerMaxMs(metric) {
  const value = metric?.measurements?.MAX;
  return Number.isFinite(value) ? value * 1000 : null;
}

/**
 * Suivi d'un compteur cumulatif entre deux relevés.
 * Un delta negatif signifie un redemarrage du service : le debit vaut `null`
 * (affiche « n/d ») au lieu d'une valeur aberrante.
 */
export class RateTracker {
  constructor() {
    this.previous = null;
  }

  /**
   * @param {number} count valeur courante du compteur
   * @param {number} at horodatage du relevé (ms epoch)
   * @returns {number|null} debit par seconde
   */
  update(count, at) {
    if (!Number.isFinite(count) || !Number.isFinite(at)) return null;
    const previous = this.previous;
    this.previous = { count, at };
    if (!previous) return null;
    if (count < previous.count) return null;
    const elapsed = at - previous.at;
    if (!(elapsed > 0)) return null;
    return (count - previous.count) / (elapsed / 1000);
  }

  reset() {
    this.previous = null;
  }
}

/**
 * Tampon circulaire borné, utilise par la page Systeme (15 min a 5 s = 180 points).
 */
export class RingBuffer {
  constructor(capacity = 180) {
    this.capacity = Math.max(1, capacity);
    this.items = [];
  }

  push(item) {
    this.items.push(item);
    if (this.items.length > this.capacity) this.items.splice(0, this.items.length - this.capacity);
    return item;
  }

  toArray() {
    return [...this.items];
  }

  get size() {
    return this.items.length;
  }

  clear() {
    this.items.length = 0;
  }
}

/** Formate un debit par seconde en texte lisible. */
export function formatRate(rate) {
  if (!Number.isFinite(rate)) return 'n/d';
  if (rate >= 10) return `${rate.toFixed(0)}/s`;
  if (rate >= 1) return `${rate.toFixed(1)}/s`;
  if (rate > 0) return `${rate.toFixed(2)}/s`;
  return '0/s';
}
