# Keboola Database Connectivity

Monorepo for tools that connect SQL clients to [Keboola](https://www.keboola.com/) projects via the Query Service API.

## Projects

### [jdbc-driver/](jdbc-driver/) -- Keboola JDBC Driver (v2.1.4)

JDBC driver for DBeaver, DataGrip, and any JDBC-compatible client. Provides auto-discovery of branches and workspaces, virtual `_keboola.*` tables, `KEBOOLA HELP` command, and full Snowflake metadata via `SHOW` commands.

```bash
cd jdbc-driver
mvn clean package          # Build uber-jar
mvn test                   # Run unit tests
make dist                  # Copy jar to dist/
```

**Download:** Grab the latest `keboola-jdbc-driver-X.Y.Z.jar` from the [GitHub Releases page](https://github.com/keboola/jdbc-driver/releases/latest). Older builds remain available in [jdbc-driver/dist/](jdbc-driver/dist/) for reference.

**Installation guides:**
- [DBeaver](docs/dbeaver.md)
- [DataGrip](docs/datagrip.md) (and other JetBrains IDEs)
- [Tableau Desktop](docs/tableau.md)

See [jdbc-driver/](jdbc-driver/) for source and build instructions.

### [vscode-sqltools/](vscode-sqltools/) -- VSCode SQLTools Extension (v2.1.4)

TypeScript extension for Visual Studio Code that integrates with the [SQLTools](https://marketplace.visualstudio.com/items?itemName=mtxr.sqltools) ecosystem. Provides Keboola connectivity directly in VSCode.

**Features:**
- Connect to any Keboola stack (AWS US/EU, GCP, Azure, custom)
- QuickPick selection for branch and workspace on first connect
- Browse buckets, tables, and columns in the sidebar explorer
- Execute SQL queries with async polling and pagination
- Virtual `_keboola.*` tables (components, events, jobs, tables, buckets)
- `KEBOOLA HELP`, `SHOW HISTORY`, `USE SCHEMA` commands
- Query cancellation from Command Palette
- Schema cache with 60s TTL and stale-on-error fallback

```bash
cd vscode-sqltools
npm install && npm run compile  # Build
npm test                        # Run 147 unit tests
npm run package                 # Create .vsix
```

**Install:** Download the VSIX from [vscode-sqltools/dist/](vscode-sqltools/dist/) and install via:
```bash
code --install-extension vscode-sqltools/dist/sqltools-keboola-driver-2.1.4.vsix
```

## Prerequisites

### Storage API Token

Both the JDBC driver and VSCode extension require a Keboola Storage API token. The token **must not** be a bucket-scoped token -- it needs access to workspaces and the Query Service.

**How to create the token:**

1. Go to your Keboola project
2. Navigate to **Settings** > **API Tokens**
3. Click **New Token**
4. Set a description (e.g. "DBeaver access")
5. Under **Access to buckets**, select **All buckets** (or at least the buckets you need to query)
6. Under **Access to components**, you can leave it at **None** (not required for SQL access)
7. Optionally set token expiration
8. Click **Create**

**Minimum required permissions:**
- Read access to buckets you want to query
- The token must be able to list workspaces (`GET /v2/storage/branch/{id}/workspaces`)
- A workspace must already exist in the project (the driver auto-selects the newest one)

**What does NOT work:**
- Bucket-scoped tokens with limited permissions -- the driver needs to list branches and workspaces during connection setup
- Tokens from a different project than the workspace

> **Tip:** If you get connection errors, first verify your token works by visiting `https://connection.keboola.com/v2/storage/tokens/verify` with the header `X-StorageApi-Token: <your-token>`.

## Repository Structure

```
keboola_jdbc/
├── jdbc-driver/               # Java JDBC driver
│   ├── src/                   # Java source and tests
│   ├── dist/                  # Release jars (all versions)
│   ├── pom.xml                # Maven build
│   └── Makefile               # Build shortcuts
├── vscode-sqltools/           # VSCode SQLTools extension
│   ├── src/                   # TypeScript source
│   │   ├── extension.ts       # Extension entry point (QuickPick, registration)
│   │   ├── constants.ts       # Configuration constants
│   │   └── ls/                # Language server (driver, plugin, cache)
│   ├── dist/                  # Release .vsix files (all versions)
│   ├── package.json           # Extension manifest
│   └── connection.schema.json # Connection form schema
├── docs/                      # Project documentation and plans
├── CLAUDE.md                  # AI coding instructions
└── README.md                  # This file
```
