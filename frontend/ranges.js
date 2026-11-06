/**
 * Plages temporelles et garde-fous cote client.
 * Les bornes serveur restent seules jugees (400 RANGE_TOO_LARGE) ; ce module evite
 * simplement d'envoyer une requete vouee a l'echec et fournit des libelles coherents.
 */

export const HOUR_MS = 3_600_000;
export const DAY_MS = 86_400_000;

/** Presets communs. `maxDays` est la limite du service appele. */
export const RANGE_PRESETS = [
  { id: '1h', label: '1 h', ms: HOUR_MS, maxDays: 7 },
  { id: '6h', label: '6 h', ms: 6 * HOUR_MS, maxDays: 7 },
  { id: '24h', label: '24 h', ms: 24 * HOUR_MS, maxDays: 7 },
  { id: '7d', label: '7 j', ms: 7 * DAY_MS, maxDays: 7 },
  { id: '31d', label: '31 j', ms: 31 * DAY_MS, maxDays: 31 },
];

export function presetById(id) {
  return RANGE_PRESETS.find((preset) => preset.id === id) || RANGE_PRESETS[2];
}

/**
 * Construit une plage {from, to} (Dates) pour un preset.
 * @param {string} id identifiant du preset
 * @param {number} now horodatage de reference (ms)
 */
export function buildRange(id, now = Date.now()) {
  const preset = presetById(id);
  const to = new Date(now);
  const from = new Date(now - preset.ms);
  return { from, to, preset };
}

/**
 * Verifie une plage avant appel.
 * @returns {{ok: boolean, reason?: string}}
 */
export function validateRange(from, to, maxDays) {
  const start = from instanceof Date ? from.getTime() : new Date(from).getTime();
  const end = to instanceof Date ? to.getTime() : new Date(to).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) return { ok: false, reason: 'Dates illisibles.' };
  if (!(end > start)) return { ok: false, reason: 'La fin doit être postérieure au début.' };
  if (end - start > maxDays * DAY_MS) {
    return { ok: false, reason: `Plage limitée à ${maxDays} jours.` };
  }
  return { ok: true };
}

/** Nombre de jours couverts (arrondi supérieur), pour l'affichage. */
export function rangeDays(from, to) {
  const start = from instanceof Date ? from.getTime() : new Date(from).getTime();
  const end = to instanceof Date ? to.getTime() : new Date(to).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 0;
  return Math.ceil((end - start) / DAY_MS);
}

/** Minuit UTC du jour courant, au format `YYYY-MM-DD`. */
export function todayUtc(now = new Date()) {
  return now.toISOString().slice(0, 10);
}

/** `YYYY-MM-DD` d'une date. */
export function dateOnly(value) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10);
}
