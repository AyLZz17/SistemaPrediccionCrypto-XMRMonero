# =====================================================================
# XMR-Forecast :: comandos reproducibles
#
# Uso: make <objetivo>      (en Windows: `make` de GNU Make o `mingw32-make`)
# =====================================================================

SHELL := /bin/bash

# Java 21 es obligatorio (R-36). Si JAVA_HOME no apunta a un JDK 21,
# el Enforcer del pom bloquea la compilacion con un mensaje explicito.
MVN          ?= mvn
COMPOSE      ?= docker compose
PYTHON       ?= python

.DEFAULT_GOAL := help

.PHONY: help
help: ## Muestra la lista de objetivos
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[0;36m%-18s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- build
.PHONY: build
build: ## Compila el backend con Java 21 (tests incluidos)
	$(MVN) clean verify

.PHONY: compile
compile: ## Solo compila, sin tests
	$(MVN) clean compile

.PHONY: frontend-build
frontend-build: ## Construye el frontend
	cd frontend && npm ci && npm run build

# ---------------------------------------------------------------- test
.PHONY: test
test: test-backend test-frontend test-ml ## Ejecuta todas las pruebas

.PHONY: test-backend
test-backend: ## Pruebas del backend
	$(MVN) clean test

.PHONY: test-frontend
test-frontend: ## Pruebas del frontend (incluye el pie de pagina)
	cd frontend && npm run test

.PHONY: test-ml
test-ml: ## Pruebas del servicio ML (incluye no-fuga de datos)
	cd ml-service && $(PYTHON) -m pytest

# ---------------------------------------------------------------- lint
.PHONY: lint
lint: lint-backend lint-frontend lint-ml ## Lint de los tres modulos

.PHONY: lint-backend
lint-backend: ## Compila el backend (detecta errores de tipos)
	$(MVN) clean compile

.PHONY: lint-frontend
lint-frontend: ## Lint y comprobacion de tipos del frontend
	cd frontend && npm run lint

.PHONY: lint-ml
lint-ml: ## ruff + mypy del servicio ML
	cd ml-service && $(PYTHON) -m ruff check . && $(PYTHON) -m mypy app

# ---------------------------------------------------------------- docker
.PHONY: up
up: certs ## Levanta todo el sistema
	$(COMPOSE) up -d --build
	$(COMPOSE) ps

.PHONY: down
down: ## Detiene el sistema (conserva los volumenes)
	$(COMPOSE) down

.PHONY: down-all
down-all: ## Detiene el sistema y BORRA los volumenes
	$(COMPOSE) down -v

.PHONY: logs
logs: ## Sigue los logs de todos los servicios
	$(COMPOSE) logs -f

.PHONY: ps
ps: ## Estado de los contenedores
	$(COMPOSE) ps

.PHONY: certs
certs: ## Genera los certificados TLS de desarrollo
	bash docker/certs/generate-dev-certs.sh

# ---------------------------------------------------------------- datos
.PHONY: backup
backup: ## Copia de seguridad de PostgreSQL
	bash docker/backup.sh backup

.PHONY: restore
restore: ## Restaura una copia: make restore FILE=backups/x.dump
	@test -n "$(FILE)" || { echo "Uso: make restore FILE=<fichero>"; exit 2; }
	bash docker/backup.sh restore "$(FILE)"

# ---------------------------------------------------------------- docs
.PHONY: diagrams
diagrams: ## Valida los diagramas Mermaid (parse + render) - R-25
	$(PYTHON) tools/validate_mermaid.py docs/04_diagramas.md

# ---------------------------------------------------------------- ml
.PHONY: ingest
ingest: ## Ingesta de datos de mercado (delegada al servicio ML)
	cd ml-service && $(PYTHON) -m app.pipelines.ingest

.PHONY: experiment
experiment: ## Entrena un experimento: make experiment CONFIG=configs/lstm_base.yaml
	@test -n "$(CONFIG)" || { echo "Uso: make experiment CONFIG=<fichero.yaml>"; exit 2; }
	cd ml-service && $(PYTHON) -m app.pipelines.train --config "$(CONFIG)"