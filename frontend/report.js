/**
 * Generation du rapport de preuves au format `preuves/tests/resultats-modele.md`.
 * Module pur (teste par `node --test`), sans acces DOM.
 */

export const TEMPLATE_ROWS = [
  { id: 'T01', label: 'santé' },
  { id: 'T02', label: 'mesure valide' },
  { id: 'T03', label: 'champ manquant' },
  { id: 'T04', label: 'valeur invalide' },
  { id: 'T05', label: 'persistance Cassandra' },
  { id: 'T06', label: 'métadonnées MongoDB' },
  { id: 'T07', label: 'historique' },
  { id: 'T08', label: 'moyenne' },
  { id: 'T09', label: 'dépassement' },
  { id: 'T10', label: 'panne du stockage' },
  { id: 'T11', label: 'doublon' },
  { id: 'T12', label: 'Compose' },
  { id: 'T13', label: 'OpenAPI' },
  { id: 'T14', label: 'bout en bout' },
];

function cell(value) {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
}

function normalizeStatus(status) {
  const value = String(status ?? '').toUpperCase();
  return value === 'OK' || value === 'KO' ? value : 'NA';
}

/**
 * @param {object} options
 * @param {Record<string, {status: string, evidence?: string, comment?: string}>} options.results
 * @param {string} options.date date ISO (jour)
 * @param {string} [options.environment]
 * @param {string} [options.commit] laisse vide : a renseigner a la main
 */
export function buildReport({ results = {}, date = new Date().toISOString().slice(0, 10), environment = '', commit = '' } = {}) {
  const rows = TEMPLATE_ROWS.map((row) => {
    const result = results[row.id] || {};
    return `| ${row.id} — ${row.label} | ${normalizeStatus(result.status)} | ${cell(result.evidence)} | ${cell(result.comment)} |`;
  });

  return `# Rapport de campagne de tests — SmartEnergy Hub

> Généré par l'interface EXT-UI le ${date}. Les résultats proviennent d'exécutions réelles
> contre la plateforme locale ; ne jamais remplacer une preuve par une valeur supposée.

- **Date** : ${date}
- **Environnement** : ${environment || 'à renseigner (OS, Docker, JDK)'}
- **Commit** : ${commit || 'à renseigner'}

| Test | Statut (OK/KO/NA) | Preuve (log, commande, capture) | Commentaire |
|---|---|---|---|
${rows.join('\n')}

## Écarts et limites connues

- T10, T12 et les pannes Redis / sensor-service restent des manipulations manuelles guidées
  (la page Scénarios affiche les commandes et suit le lag).
- La prévision est désactivée (501 \`FORECAST_DISABLED\`, extension 9C).
`;
}

/** Rapport JSON brut, joint au Markdown pour l'archivage. */
export function buildJson({ results = {}, date = new Date().toISOString(), environment = '', commit = '' } = {}) {
  return JSON.stringify({
    generatedAt: date,
    environment,
    commit: commit || null,
    tests: TEMPLATE_ROWS.map((row) => ({
      id: row.id,
      label: row.label,
      ...(results[row.id] || { status: 'NA' }),
    })),
  }, null, 2);
}
