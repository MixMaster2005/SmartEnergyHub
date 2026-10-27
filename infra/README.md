# SmartEnergy Hub — dossier `infra` reconstruit

Ce dossier regroupe les commandes de cycle de vie pour le dépôt SmartEnergy Hub. Il est prévu pour être placé dans la racine du dépôt, à côté de `docker-compose.yml` (ou `compose.yml`), `.env.example`, et des sources du projet. Il ne remplace pas ces fichiers du dépôt.

## Utilisation

Linux, macOS, WSL ou Git Bash :

```bash
./infra/linux/se.sh help
./infra/linux/se.sh doctor
./infra/linux/se.sh first-start
./infra/linux/se.sh start --no-wait
./infra/linux/se.sh logs storage-service -n 50 --no-follow --since 5m
./infra/linux/se.sh stream
```

Windows PowerShell / CMD :

```powershell
infra\windows\se.cmd help
infra\windows\se.cmd doctor
infra\windows\se.cmd first-start
```

Les opérations `reset` et `clean` suppriment les volumes et peuvent effacer les données. Vérifiez toujours la confirmation affichée avant de les lancer. `backup` / `restore` gèrent les volumes déclarés dans le script.

## Fichiers

- `linux/se.sh` : script principal Bash.
- `linux/*.sh` : raccourcis Bash.
- `linux/tests/smoke-mock-docker.sh` : test fonctionnel sans daemon Docker réel.
- `windows/se.ps1` : version PowerShell.
- `windows/se.cmd` : entrée générique Windows.
- `windows/*.cmd` : raccourcis Windows.

## Vérification

`se.sh` a été vérifié avec `bash -n` et testé avec un faux binaire `docker` pour les commandes `help`, `doctor`, `start --no-wait`, `stop`, `restart`, `rebuild`, `logs`, `sim`, `stream`, `count`, `redis`, `shell`, `down`, `reset --yes` et la gestion d'une commande inconnue. Les tests peuvent être rejoués avec `bash infra/linux/tests/smoke-mock-docker.sh`. Ils n'allument pas une plateforme réelle. La version PowerShell n'a pas pu être exécutée dans l'environnement de reconstruction faute de `pwsh`; elle doit être validée sur Windows/PowerShell avant utilisation en production.

## Extension (hors MVP)

Les extensions du CDC §18 se déploieront par **profils Compose** supplémentaires (par exemple
`--profile monitoring` pour `EXT-11` Prometheus/Grafana, profil `gateway` pour `EXT-GW`) sans
modifier les commandes de cycle de vie ci-dessus. Renvois : CDC §18 · `docs/TODO-MAP.md` partie B ·
README racine §8.2.
