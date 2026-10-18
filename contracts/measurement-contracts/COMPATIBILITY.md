# Regles de compatibilite du contrat de mesures

Le contrat est partage par 3 services deployes independamment (collector, storage, analytics).
Toute modification doit respecter ces regles. `schemaVersion` courant : **1** (`MeasurementSchema`).

## Changements COMPATIBLES (pas de changement de `schemaVersion`)
- Ajouter un champ **optionnel** (ex. un nouveau champ `humidity`).
- Ajouter une valeur a l'enum `state` **si les consommateurs gerent une valeur inconnue** (sinon : incompatible).
- Assouplir une validation du collector (ex. elargir une plage).

Les consommateurs **ignorent les champs inconnus** (lecteur tolerant).

## Changements INCOMPATIBLES (incrementer `schemaVersion`)
- Ajouter un champ **obligatoire**, renommer ou supprimer un champ.
- Changer le type, l'unite ou le format d'un champ (ex. kW -> W, UTC -> heure locale).
- Changer l'espace de noms de l'`eventId` (`EventIds.NAMESPACE`) : casserait toute la deduplication.

## Procedure de migration (consumer-first)
1. Les consommateurs apprennent la version N+1 (`MIN_SUPPORTED_VERSION` = N, `CURRENT_VERSION` = N+1 cote lecture).
2. Deploiement des consommateurs.
3. Le collector passe a la version N+1 (ecriture).
4. Plus tard seulement : retirer le support de N.
Pour un changement trop lourd : nouveau stream `energy-measurements.v2` avec double publication temporaire.

## Comportement face a une version inconnue
Un evenement dont `schemaVersion` est hors de [`MIN_SUPPORTED_VERSION`, `CURRENT_VERSION`] leve
`MalformedEventException` -> **DLQ immediate** (jamais de retry : il ne deviendra pas valide).

## Fichiers a garder synchronises (tests de contrat)
- `contracts/schemas/measurement-event.schema.json` (representation Redis)
- `contracts/schemas/energy-measurement-request.schema.json` (corps HTTP)
- `EnergyMeasurementRequest`, `EnergyMeasurement`, `MeasurementEventCodec`
