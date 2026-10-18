.PHONY: help build up down reset logs ps sim e2e todo redis-cli mongosh cqlsh

help: ## Liste des commandes
	@grep -E '^[a-z-]+:.*##' Makefile | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

build: ## Compile tout (contrats + services) avec Maven local
	mvn -B -q clean package -DskipTests

up: ## Demarre la plateforme (build des images inclus)
	@test -f .env || cp .env.example .env
	docker compose up -d --build

down: ## Arrete la plateforme (les volumes sont conserves)
	docker compose down

reset: ## Arrete et SUPPRIME les volumes (re-joue les scripts d'init)
	docker compose down -v

ps: ## Etat des conteneurs
	docker compose ps

logs: ## Logs de tous les services (Ctrl+C pour quitter)
	docker compose logs -f --tail=100

sim: ## Lance le simulateur (5 capteurs, 2 minutes par defaut)
	docker compose run --rm simulator

e2e: ## Scenarios de recette HTTP (scripts/e2e.sh)
	./scripts/e2e.sh

todo: ## Liste les classes restant a implementer
	@grep -rn --include=*.java "^// TODO(" . | sed 's/^\.\///'

redis-cli: ## Shell Redis
	docker compose exec redis sh -c 'redis-cli -a "$$REDIS_PASSWORD" --no-auth-warning'

mongosh: ## Shell MongoDB (root)
	docker compose exec mongo sh -c 'mongosh -u "$$MONGO_INITDB_ROOT_USERNAME" -p "$$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin'

cqlsh: ## Shell Cassandra
	docker compose exec cassandra cqlsh
