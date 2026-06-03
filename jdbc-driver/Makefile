.PHONY: build test test-e2e test-all clean dist jar manual-test help

JAVA_HOME ?= $(shell mvn -q help:evaluate -Dexpression=java.home -DforceStdout 2>/dev/null)
JAVA := $(JAVA_HOME)/bin/java

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

build: ## Build uber-jar (skip tests)
	mvn clean package -q -DskipTests

test: ## Run unit tests (no network needed)
	mvn test

test-e2e: ## Run E2E integration tests (needs KEBOOLA_TOKEN env)
	@test -n "$(KEBOOLA_TOKEN)" || (echo "Error: KEBOOLA_TOKEN is not set" && exit 1)
	mvn verify -Pkeboola-integration

test-all: test test-e2e ## Run unit + E2E tests

dist: build ## Build and copy jar to dist/ (version comes from pom revision)
	@mkdir -p dist
	cp target/keboola-jdbc-driver-*.jar dist/
	@echo "dist/ ready:" && ls -1sh dist/keboola-jdbc-driver-*.jar | tail -1

jar: dist ## Alias for dist

clean: ## Remove build artifacts (keeps released jars in dist/)
	mvn clean -q

manual-test: ## Run manual connection test (needs KEBOOLA_TOKEN env)
	@test -n "$(KEBOOLA_TOKEN)" || (echo "Error: KEBOOLA_TOKEN is not set" && exit 1)
	mvn -q test-compile exec:java -Dexec.mainClass=com.keboola.jdbc.ManualConnectionTest -Dexec.classpathScope=test
