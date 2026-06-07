# Orbital — a visual client for Azure Cosmos DB (PyCharm / IntelliJ plugin)

A full-featured visual client for **Azure Cosmos DB** (SQL / Core API) that lives in a tool
window in PyCharm (and any other IntelliJ-based IDE). Browse and manage your account, run
queries, and edit documents and server-side scripts without leaving the IDE.

## Features

**Connections**
- Manage multiple Cosmos DB accounts. Account keys are stored in the IDE's secure
  `PasswordSafe`, never in plain text.
- Add by Account URI + key, or just paste a full connection string into the URI field.
- **Test connection** button right in the dialog.

**Explorer tree** (loaded on demand)
- Account → databases → containers → **Stored Procedures / Triggers / User Defined Functions**.
- **Create / delete databases** (with manual or autoscale throughput).
- **Create / delete containers** (partition key path + throughput).
- **Container properties** dialog: partition key, default TTL, indexing mode, throughput (RU/s).

**Query & documents** (one closable tab per container)
- SQL editor with line numbers; **Execute** and **Load more** (continuation-token paging).
- Results shown in a sortable **table** with dynamic columns over schemaless documents.
- Selected row opens in a **JSON editor** with syntax highlighting.
- **New / Save (upsert) / Delete** documents, with single and hierarchical partition keys.
- **Find by id** and **Export** the loaded results to a JSON file.
- Status bar shows request charge (RU), elapsed time, and whether more pages are available.

**Server-side scripts**
- View / create / edit / delete stored procedures, triggers and UDFs.
- **Execute stored procedures** with a partition key and JSON parameters; see the response.

**Error handling**
- Every operation reports failures as a balloon notification with a **Show details** action
  that surfaces the Cosmos `HTTP status / sub-status / activity id / request charge` and the
  full message. Inputs are validated before calls are made.

## Requirements

- **JDK 21** to *build* the plugin. On macOS: `brew install openjdk@21` (keg-only). If your
  default JDK differs, Gradle's toolchain support fetches a JDK 21 automatically.
- **PyCharm / IntelliJ 2024.3 or newer** (build 243+) to *run* it. No upper version bound.

The Gradle wrapper is included, so you do not need to install Gradle.

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew buildPlugin
```

The installable archive is written to `build/distributions/orbital-0.1.0.zip`.

## Install into PyCharm

1. **Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk…**
2. Select `build/distributions/orbital-0.1.0.zip`, then restart the IDE.
3. Open it from **View → Tool Windows → Orbital** (right-hand side by default).

Or launch a sandbox IDE with the plugin pre-loaded: `./gradlew runIde`.

## Tests

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew test
```

The suite (35 tests) has three layers:

- **Unit tests** (no IDE, no network) — JSON parsing / pretty-printing, partition-key resolution
  (single, nested, hierarchical, none), stored-procedure parameter parsing, connection-string
  parsing, error-message formatting, and the result-table model.
- **Platform tests** (headless IDE via the IntelliJ test fixture) — connection persistence and
  secure key storage in `PasswordSafe`, service & notification-group registration, and tool
  window panel construction.
- **Live integration test** (`CosmosEmulatorIntegrationTest`) — runs the full `CosmosService`
  surface against a real account or the Cosmos DB Emulator: create database → create container →
  read properties → upsert → query → point-read → update → delete → drop container. Skipped
  automatically unless `COSMOS_TEST_ENDPOINT` / `COSMOS_TEST_KEY` are set.

  One command sets up the (arm64-capable, HTTPS) vNext emulator in Docker, trusts its
  certificate and runs the integration tests:

  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    ./scripts/run-emulator-integration-tests.sh
  ```

  Verified locally: the full database / container / document lifecycle passes against the
  emulator. The stored-procedure test is **skipped** there because the vNext (pgcosmos)
  emulator does not implement server-side scripts (`BadRequest: Server-side scripts are not
  supported in this emulator`); it runs against a real account. To point the suite at any
  account manually:

  ```bash
  COSMOS_TEST_ENDPOINT=https://your-account.documents.azure.com:443/ \
  COSMOS_TEST_KEY=... ./gradlew test --tests '*CosmosEmulatorIntegrationTest'
  ```

The HTML report is written to `build/reports/tests/test/index.html`.

## Usage

1. Click **＋** in the toolbar and add a connection (use **Test connection** to verify).
2. Expand the connection → database → container in the tree.
3. **Double-click a container** to open a query tab. The default `SELECT * FROM c` runs
   automatically; edit the SQL and press **Execute**, or **Load more** to page.
4. Click a result row to see its JSON; edit and **Save (upsert)**, create with **New**, or
   **Delete** it. Use **Export** to write the loaded documents to a file.
5. Right-click nodes for management actions (new/delete database & container, container
   properties, and stored-procedure / trigger / UDF create / edit / delete / execute).

## Project layout

```
build.gradle.kts                          Gradle build (IntelliJ Platform Gradle Plugin 2.11)
src/main/resources/META-INF/plugin.xml    manifest (tool window + notification group)
src/main/kotlin/com/github/cosmosdbclient/
  model/CosmosConnection.kt               connection value object
  service/
    CosmosConnectionStorage.kt            persistence + PasswordSafe for keys
    CosmosService.kt                      Azure SDK wrapper (DDL, items, scripts, throughput)
    CosmosDtos.kt                         DTOs / enums
    CosmosErrors.kt                       error formatting + balloon notifications
  util/Bg.kt                              background-task helper (off EDT → EDT)
  ui/
    CosmosToolWindowFactory.kt            tool window entry point
    CosmosExplorerPanel.kt                tree + query tabs + context menus
    QueryPanel.kt                         SQL editor + results table + JSON document editor
    CosmosDialogs.kt                      connection / database / container / script dialogs
    CosmosEditors.kt                      embedded JSON / code editors
    ResultTableModel.kt                   dynamic table model over schemaless documents
    CosmosNodes.kt                        tree node data + renderer
```

## Implementation notes

- Bundles the **Azure Cosmos Java SDK** (`com.azure:azure-cosmos`) and its transitive deps
  (azure-core, reactor-netty, Jackson). They load inside the isolated plugin class loader.
- `CosmosService` pins the thread context class loader around SDK calls so the SDK's
  `ServiceLoader`-based HTTP client is found on IDE pooled threads. Connections default to
  **Gateway** mode (HTTPS).
- All network calls run on background threads with a progress indicator; the UI is only
  touched on the EDT.
- Built against PyCharm Community 2024.3 (build 243) with no upper compatibility bound.
```

## Trademarks & third-party licenses

Orbital is an independent, third-party product. It is **not affiliated with, endorsed, or
sponsored by Microsoft**. "Azure" and "Azure Cosmos DB" are trademarks of the Microsoft group
of companies and are used here only to describe the service Orbital connects to (nominative
use). Orbital does not use any Microsoft logos.

The plugin bundles open-source libraries under permissive licenses only — MIT, MIT-0,
Apache-2.0, BSD-2-Clause and CC0 (no copyleft). Their notices are collected in
[`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt) and shipped inside the plugin at
`META-INF/THIRD-PARTY-NOTICES.txt`. Regenerate after changing dependencies:

```bash
./gradlew buildPlugin && python3 scripts/generate-third-party-notices.py
```

This README is informational and not legal advice. Before distributing Orbital commercially,
review the JetBrains Marketplace paid-plugin terms and consult a qualified attorney
(especially regarding the product name and your EULA).
