# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Monorepo with two subprojects:
- **jdbc-driver/** -- Java JDBC driver (v2.1.4) connecting DBeaver/DataGrip to Keboola projects
- **vscode-sqltools/** -- TypeScript VSCode SQLTools extension (v2.1.4)

Two API layers used by both:
- **Storage API** (`connection.keboola.com`) - connection setup: tokens, branches, workspaces. Also provides data for virtual `_keboola.*` tables.
- **Query Service API** (`query.keboola.com`, auto-discovered) - async SQL execution against Snowflake via workspace

JDBC mapping: Catalog = Database (from Snowflake), Schema = Schema (from Snowflake), Table = Table. All metadata (databases, schemas, tables, columns) comes from SHOW commands executed via the Query Service. Virtual `_keboola.*` tables provide Keboola platform metadata (jobs, components, events, etc.) from the Storage API.

## Build & Test Commands

### JDBC Driver

**IMPORTANT: Always `cd jdbc-driver` before running `mvn` commands.** Running Maven from the repo root creates a `target/` directory in the wrong place.

```bash
cd jdbc-driver
mvn clean package          # Build uber-jar (target/keboola-jdbc-driver-2.1.4.jar)
mvn test                   # Run all unit tests
mvn test -pl . -Dtest=TypeMapperTest          # Run single test class
mvn test -pl . -Dtest=TypeMapperTest#testVarchar  # Run single test method
mvn verify -Pkeboola-integration              # Run integration tests (needs KEBOOLA_TOKEN env)
```

Manual integration test:
```bash
cd jdbc-driver
KEBOOLA_TOKEN=xxx java -cp target/keboola-jdbc-driver-2.1.4.jar com.keboola.jdbc.ManualConnectionTest
```

**After every version bump or code change, always run `make dist`** (from `jdbc-driver/`) to rebuild the uber-jar and copy it to `jdbc-driver/dist/`. The `dist/` directory contains the release-ready jars that users download. **Never delete old jars from `jdbc-driver/dist/`** -- keep all previous versions for version history.

Java 11+. Surefire needs `-Dnet.bytebuddy.experimental=true` (already configured in pom.xml for Java 25 compat).

### VSCode SQLTools Extension

```bash
cd vscode-sqltools
npm install                # Install dependencies
npm run compile            # Build with tsup (extension.ts + ls/plugin.ts -> out/)
npm test                   # Run 171 unit tests via vscode-test
npm run package            # Create .vsix
npm run test:integration   # Run live API integration tests (needs .env)
```

**After every version bump, rebuild and copy VSIX to dist:**
```bash
cd vscode-sqltools
npm run package
cp sqltools-keboola-driver-*.vsix dist/
```

**Never delete old .vsix files from `vscode-sqltools/dist/`** -- keep all previous versions.

## Release Checklist

**MANDATORY: Before merging to main and creating a release, ALWAYS run tests first.**

**The git tag is the single source of truth for the release version.** The Release
GitHub Actions workflow (`.github/workflows/release.yml`) derives the version from the
tag (`vX.Y.Z` -> `X.Y.Z`), injects it into the Maven build (`-Drevision=X.Y.Z`) and
into `package.json` (`npm version`), builds the jar + VSIX, and attaches them to the
GitHub Release. No manual file edits are required to release.

The committed defaults are only the *local/dev* version and do not need to be bumped
to release: `<revision>` in `jdbc-driver/pom.xml` and `"version"` in
`vscode-sqltools/package.json`. Bump them when you want local `make dist` /
`npm run package` artifacts to carry the new number. `DriverConfig.DRIVER_VERSION`
is read from a filtered `version.properties` at build time (no longer hardcoded);
`MAJOR_VERSION`/`MINOR_VERSION` stay hardcoded and are bumped manually on major/minor releases.

### Before releasing
1. Run unit tests: `cd jdbc-driver && mvn test` and `cd vscode-sqltools && npm test`
2. Run E2E tests: `cd jdbc-driver && KEBOOLA_TOKEN=xxx KEBOOLA_WORKSPACE=yyy mvn verify -Pkeboola-integration`
3. Fix any failures
4. (Optional) Update version references in `README.md` (download links, install commands)

### Release (tag-driven)
1. `git checkout main && git pull`
2. `git tag vX.Y.Z && git push origin vX.Y.Z`
3. The Release workflow builds and publishes the jar + VSIX to the GitHub Release automatically.

To build release-versioned artifacts locally (without a tag):
`cd jdbc-driver && mvn -Drevision=X.Y.Z clean package` and
`cd vscode-sqltools && npm version X.Y.Z --no-git-tag-version && npm run package`.

## Architecture

### JDBC Driver

#### SQL Execution Flow
`KeboolaStatement.execute(sql)` -> `QueryServiceClient.submitJob()` -> poll `waitForCompletion()` with exponential backoff (100ms->2s) -> `fetchResults()` (paginated, 1000 rows/page) -> `KeboolaResultSet` (lazy paging)

#### Connection Setup Flow
`KeboolaConnection(config)` -> `StorageApiClient.verifyToken()` -> `discoverQueryServiceUrl()` (from Storage API index) -> `resolveBranchId()` (auto-detect default) -> `resolveWorkspaceId()` (auto-select newest) -> create `QueryServiceClient` -> `initCatalogAndSchema()` (executes `SELECT CURRENT_DATABASE(), CURRENT_SCHEMA()`)

#### Key Patterns
- **Metadata via SHOW commands**: `KeboolaDatabaseMetaData` executes `SHOW DATABASES`, `SHOW SCHEMAS`, `SHOW TABLES/VIEWS/OBJECTS`, and `SHOW COLUMNS` via the connection to proxy real Snowflake metadata. Column type info is parsed from JSON `data_type` field in `SHOW COLUMNS` output. Virtual `_keboola` schema is injected alongside real Snowflake schemas.
- **Schema/catalog tracking via backendContext**: After each query, the driver reads `backendContext.catalog` and `backendContext.schema` from the Query Service response to keep local state in sync. Falls back to parsing USE SCHEMA/DATABASE from SQL if backendContext is not available. `setCatalog()`/`setSchema()` execute `USE DATABASE`/`USE SCHEMA` on the server.
- **Virtual tables (`_keboola.*`)**: `VirtualTableMetadata` defines schema for `_keboola.components`, `_keboola.jobs`, `_keboola.events`, `_keboola.tables`, `_keboola.buckets`. Handled by `VirtualTableHandler` via `KeboolaCommandDispatcher`.
- **All constants in `DriverConfig`**: Page sizes, timeouts, polling intervals, retry counts. Never hardcode values elsewhere.
- **`ArrayResultSet`**: In-memory ResultSet used by `KeboolaDatabaseMetaData` for metadata queries (getSchemas, getTables, getColumns, etc.).
- **Uber-jar via maven-shade-plugin**: OkHttp, Jackson, Kotlin runtime relocated under `com.keboola.jdbc.shaded.*` to avoid classpath conflicts with host applications.
- **SPI registration**: `META-INF/services/java.sql.Driver` -> `com.keboola.jdbc.KeboolaDriver`

### VSCode SQLTools Extension

#### Architecture
- **extension.ts** -- Extension entry point (VSCode context). Registers driver with SQLTools, handles QuickPick branch/workspace selection in `parseBeforeSaveConnection` and `resolveConnection` hooks.
- **ls/plugin.ts** -- Language server plugin. Registers `KeboolaDriver` with the SQLTools LS.
- **ls/driver.ts** -- Core driver (~900 LOC). Implements `IConnectionDriver`: open/close, query execution with async polling, sidebar explorer (buckets/tables/columns), virtual `_keboola.*` tables, SHOW HISTORY, USE SCHEMA, query cancellation, autocomplete.
- **ls/schema-cache.ts** -- 60s TTL cache with stale-on-error fallback for bucket/table metadata.
- **ls/virtual-tables.ts** -- 5 virtual tables fetched from Storage API.
- **ls/help-command.ts** -- KEBOOLA HELP command output.
- **constants.ts** -- All configuration: polling intervals, page sizes, timeouts, URL mappings.

#### Key Patterns
- **Two entry points**: `extension.ts` runs in VSCode context (has access to `vscode.window` for QuickPick), `ls/plugin.ts` runs in Language Server process (no VSCode UI access).
- **parseBeforeSaveConnection**: The right hook for QuickPick -- its return value is persisted to settings. `resolveConnection` only modifies in-memory values for the current session.
- **Query execution**: POST to Query Service -> poll with exponential backoff (100ms->2s, 300s timeout) -> paginated fetch (1000 rows/page, up to 10000 total).
- **HTTP retry**: 3x for 5xx/429, fail fast for 401/403/400/404. Detailed error messages include HTTP status and response body.
- **Query Service URL auto-discovery**: `GET /v2/storage` -> parse `services[]` where `id === "query"`. Fallback: `connection.X` -> `query.X` naming convention.

### API Specifics
- Workspace IDs can exceed `Integer.MAX_VALUE` -- use `long`/string everywhere (not `int`)
- Storage API branches URL: `/v2/storage/dev-branches` (no trailing slash, follow redirects)
- Query Service result page size minimum is 100
- `StatementStatus.rowsAffected` is 0 for SELECT too -- use `numberOfRows > 0` + SQL keyword heuristic to distinguish SELECT from DML
- Query Service history: `GET /api/v1/branches/{b}/workspaces/{w}/queries?pageSize=500` -- SQL text is at `item.query` (top-level field, NOT inside `statements[]`)

## Testing

### JDBC Driver
- 214 unit tests in `jdbc-driver/src/test/java/com/keboola/jdbc/`: TypeMapperTest, ConnectionConfigTest, ArrayResultSetTest, KeboolaDriverTest, KeboolaStatementTest, SchemaCacheTest, EpochConverterTest, HelpCommandHandlerTest, KeboolaCommandDispatcherTest, VirtualTableHandlerTest
- `KeboolaDriverIT` is an E2E integration test (run by `mvn verify -Pkeboola-integration`, skips without `KEBOOLA_TOKEN`)
- `ManualConnectionTest` is a CLI integration test (not run by `mvn test`), needs `KEBOOLA_TOKEN` env var
- Use JUnit 5 + Mockito 5.11

### VSCode Extension
- 171 unit tests in `vscode-sqltools/src/test/suite/`: driver.test.ts, schema-cache.test.ts, virtual-tables.test.ts, epoch-converter.test.ts, schema.test.ts, constants.test.ts
- 10 integration tests in `vscode-sqltools/tests/integration.test.ts` (needs `.env` with KEBOOLA_TOKEN)
- Uses Mocha + VSCode test runner

## Project Structure

```
jdbc-driver/src/main/java/com/keboola/jdbc/
├── KeboolaDriver.java                # SPI entry point, URL parsing
├── KeboolaConnection.java            # Connection lifecycle, service discovery
├── KeboolaStatement.java             # SQL execution, command interception
├── KeboolaPreparedStatement.java     # Parameterized queries
├── KeboolaResultSet.java             # Lazy-paging result set
├── KeboolaDatabaseMetaData.java      # SHOW commands metadata (+ virtual _keboola)
├── ArrayResultSet.java               # In-memory ResultSet for metadata
├── command/
│   ├── KeboolaCommandHandler.java    # Handler interface
│   ├── KeboolaCommandDispatcher.java # Chain-of-responsibility dispatcher
│   ├── HelpCommandHandler.java       # KEBOOLA HELP command
│   ├── VirtualTableHandler.java      # _keboola.* SQL detection + LIMIT parsing
│   ├── VirtualTableRegistry.java     # API calls -> ArrayResultSet for each table
│   └── VirtualTableMetadata.java     # Column definitions for IDE integration
├── config/
│   ├── DriverConfig.java             # Driver constants and defaults
│   └── ConnectionConfig.java         # URL + properties parsing
├── http/
│   ├── StorageApiClient.java         # Storage API v2 (virtual tables + discovery)
│   ├── QueryServiceClient.java       # Query Service API v1 (SQL + SHOW metadata)
│   ├── JobQueueClient.java           # Job Queue API client (lazy init)
│   └── model/                        # API data models
├── meta/
│   └── TypeMapper.java               # Snowflake -> JDBC type mapping
└── exception/
    └── KeboolaJdbcException.java     # SQLSTATE error codes

vscode-sqltools/src/
├── extension.ts                      # VSCode entry point, QuickPick, driver registration
├── constants.ts                      # All config constants, URL helpers
├── types.ts                          # API response interfaces
└── ls/
    ├── plugin.ts                     # Language server plugin registration
    ├── driver.ts                     # Core driver (~900 LOC)
    ├── queries.ts                    # IBaseQueries stubs
    ├── schema-cache.ts               # TTL cache with stale-on-error
    ├── virtual-tables.ts             # 5 virtual _keboola.* tables
    └── help-command.ts               # KEBOOLA HELP command
```
