# Contrats inter-services

Tout ce qui est **partagé** entre services vit ici, dans des modules Maven légers (aucune dépendance Spring) :
un changement de contrat se voit à la compilation de **tous** les services concernés.

| Module | Contenu | Utilisé par |
|---|---|---|
| **`measurement-contracts`** (priorité 1) | `EnergyMeasurementRequest` (corps HTTP), `EnergyMeasurement` (évènement), `MeasurementEventCodec` (champs Redis ↔ objet), `MalformedEventException`, `MeasurementFields`, `MeasurementSchema`, `EventIds` (UUID v5), `StreamContract`, `RedisKeys`, `DlqFields`, `MeasurementItem` / `MeasurementHistoryResponse`, `SensorState` | collector, storage, analytics, simulator |
| `error-contracts` | `ErrorCode` (catalogue), `ApiException`, `FieldViolation`, `ApiProblem` | tous les services REST |
| `common-contracts` | `HeaderNames`, `ApiPaths`, `PageResponse`, `SensorIdPattern` | tous |
| `sensor-contracts` | `SensorRequest`, `SensorResponse` | sensor-service, analytics (client), simulator |
| `analytics-contracts` | `SummaryResponse`, `DailySummaryResponse`, `AnomalyResponse`, `ExceedanceResponse`, `ForecastResponse`, `ForecastPoint`, `AnomalyType`, `Severity` | analytics-service, gateway |

Pas de `pom.xml` agrégateur dans ce dossier : les modules sont déclarés directement dans le
pom parent racine. `schemas/` contient les **schémas JSON** (indépendants du langage) du
corps HTTP, de l'événement Redis et des erreurs.

## Règles
1. Un DTO, une clé Redis, un nom de stream ou un code d'erreur n'est défini **qu'ici**.
2. Compatibilité des évènements : `measurement-contracts/COMPATIBILITY.md` (champ optionnel = compatible ; champ obligatoire,
   renommage, changement d'unité = nouvelle `schemaVersion`, déploiement consommateurs d'abord).
3. Garder `schemas/*.json` synchronisés avec les classes Java (idéalement par un test de contrat).
4. Les contrats ne contiennent **pas** de logique dépendante de l'horloge ou de l'infrastructure (ex. règles temporelles → collector).

## Ce qui est déjà vérifié
`EventIds` (vecteur de référence UUID v5), aller-retour `MeasurementEventCodec`, champs optionnels omis, nombres non finis et
versions inconnues rejetés : voir `measurement-contracts/src/test`.

## Extension (hors MVP)

Le contrat d'événement est **portable** : c'est la base de l'extension **`EXT-B`** (CDC §18.5) — un
passage à Kafka ne doit pas changer `EnergyMeasurement` ni sa `schemaVersion`
(`measurement-contracts/COMPATIBILITY.md`). Les extensions ajoutent leurs routes aux README de
services, jamais de champ obligatoire sans bump de `schemaVersion` (§5.1 du CDC).
Renvois : CDC §18.5 · `docs/TODO-MAP.md` partie B · README racine §8.2.
