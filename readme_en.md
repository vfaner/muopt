# SQL Optimizer Tool

[中文](README.md) | **English**

> 🎬 **Demo video (Bilibili, Chinese narration)**: [开源SQL优化神器：一键扫描项目代码，智能补全索引，还支持可视化执行计划！](https://www.bilibili.com/video/BV1vVYm6yEhf)
>
> <a href="https://www.bilibili.com/video/BV1vVYm6yEhf"><img src="docs/demo-video-cover.jpg" alt="SQL Optimizer Tool demo video" width="760"></a>
>
> Click the cover to watch on Bilibili (~9 min).

## 📖 Introduction

SQL Optimizer Tool is a Spring Boot 3 web application that performs intelligent optimization analysis on a given query SQL, produces appropriate index suggestions, and supports connecting to a real data source for deep optimization and execution-plan analysis.

**Core feature: color-coded index suggestions** — existing indexes are shown in **gray** (strikethrough), while missing ones are shown in **orange**, all with one-click copy.

### ✨ Key Features

- 🎯 **Smart index suggestions**: Parses the AST with JSqlParser and extracts candidate columns from WHERE equality/range conditions, JOIN ON, ORDER BY, and GROUP BY to generate reasonable (composite) indexes
- 🎨 **Color-coded status**: Existing = gray, missing/unverified = orange — clear at a glance, copy-paste ready
- ✏️ **Manual optimization**: Paste a single SQL for instant analysis
- 🔍 **Scan optimization**: Scan a project directory to extract SQL from MyBatis XML, SQL strings in Java code, and SQL in annotations (`@Select`/`@Insert`/`@Update`/`@Delete`/`@Query`); analyze each and replace in place with a one click (auto `.bak` backup)
- 🤖 **AI deep optimization** (optional): Configure multiple AI models (Bailian / DeepSeek / OpenAI / Anthropic and any OpenAI-compatible gateway), with one active at a time, to intelligently rewrite SQL
- 🔗 **Data source configuration** (optional): Once configured, enables —
  - Index existence detection (gray/orange distinction)
  - Redundant index detection (with DROP suggestions)
  - Small-table-drives-large-table analysis
- 📊 **SQL execution plan**: Runs EXPLAIN after connecting a data source, with **Table / Tree / Diagram** views (DBeaver-style):
  - Table: structured columns Operation / Object / Rows / Cost / Node Type
  - Tree / Diagram: nodes laid out by plan hierarchy, color-coded by operation type (full scan = red, index = green, join = purple, etc.)
  - Only suggests indexes when cost is high, and hints "no index needed" when data volume is small

### 🎨 Design Logic

| Scenario | Behavior |
|----------|----------|
| **No data source configured** | All index suggestions are **orange** (existence cannot be verified); `CREATE INDEX` is copyable; the execution-plan page prompts "please configure a data source first" |
| **Data source configured** | Indexes are distinguished as **gray (existing) / orange (missing)**; additionally provides redundant-index and small/large-table-driving suggestions; execution-plan analysis is available |
| **Manual/Scan page header** | After configuring a data source, the top shows "✅ Data source configured, deep optimization available" |

## 🖼️ Screenshots

> The UI is in Chinese; captions below describe each screen.

### ✏️ Manual Optimization

Paste a single query for instant analysis. With AI off you get index suggestions and tips (the SQL itself is untouched); with AI on the query is rewritten by the model, and an AI timeout/failure automatically falls back to local rule-based rewrites.

**Local rule analysis (AI off) — suggestions tagged "create" vs "already exists" when a data source is connected:**

<img src="docs/sql_yh_ai_sd.png" width="1180" alt="Manual optimization - local analysis">

**AI deep optimization enabled:**

<img src="docs/sql_yh_ai_sd_ai.png" width="1180" alt="Manual optimization - AI enabled">

**Deep analysis details:** actual row counts per table, small-table-driving-large-table hints, existing-index checks; when AI times out the reason is stated explicitly (here: 60s response timeout, with Volcengine Ark Base URL / endpoint-ID guidance):

<img src="docs/sql_yh_ai_sd_ai_tishi.png" width="1180" alt="Manual optimization - deep analysis and timeout hint">

### 🔍 Scan Optimization

Point the tool at a project directory to extract SQL from MyBatis XML, Java code and `.sql` scripts; every statement gets suggestions and can be replaced in place (with automatic `.bak` backups). Scans run as server-side background jobs — feel free to switch pages; the result renders automatically on return.

**Scan running in the background (8 concurrent AI rewrites; cancellable):**

<img src="docs/sql_yh_ai_zd.png" width="1180" alt="Scan optimization - running in background">

**Per-statement result: source SQL / rewritten SQL (AI or rule tag) / index suggestions:**

<img src="docs/sql_yh_ai_zd1.png" width="1180" alt="Scan optimization - result detail">

### 🔗 Data Source Configuration

Save multiple database connections and exclusively enable one via a toggle (enabling a new one disables the old). Passwords are encrypted at rest and configs survive restarts; DM / openGauss / KingBase drivers are bundled, and custom JDBC URLs are supported.

<img src="docs/sql_yh_db.png" width="1180" alt="Data source list">

<img src="docs/sql_yh_db_add.png" width="1100" alt="Add data source">

### 🤖 AI Models

Save multiple AI model configs (OpenAI-compatible / Anthropic protocol, Base URL, model, API key); only one can be enabled at a time. "Test connectivity" probes the endpoint and the latest probe status is shown; leaving the key blank on edit keeps the stored value.

<img src="docs/sql_yh_ai.png" width="1180" alt="AI model list">

<img src="docs/sql_yh_ai_add.png" width="1000" alt="Add AI model">

### 📊 SQL Execution Plan

Runs EXPLAIN against the connected data source with table / tree / diagram views (DBeaver-style), color-coded by operation type (full table scan in red, index lookup in green, JOIN in purple) plus a cost summary.

**Table view:**

<img src="docs/sql_yh_ai_exp.png" width="1180" alt="Execution plan - table view">

**Tree view:**

<img src="docs/sql_yh_ai_exp_shuxing.png" width="1180" alt="Execution plan - tree view">

## 🛠️ Tech Stack

| Component | Technology |
|-----------|------------|
| Backend | Spring Boot 3.2, JSqlParser 4.9, HikariCP, JDK HttpClient |
| Frontend | Vue 3 CDN + Element Plus CDN (pure HTML, no build tool) |
| Config store | Embedded H2 file database (`./data`) + Spring Data JPA; passwords/API keys encrypted at rest with spring-security-crypto |
| AI API | OpenAI-compatible protocol (Bailian / DeepSeek / OpenAI / intranet gateways) + Anthropic Messages protocol |
| DB drivers | MySQL, PostgreSQL, Oracle, Dameng DM8, openGauss, KingBaseES V8 (all domestic drivers bundled) |
| Build | Maven, JDK 17+ |

## 🚀 Quick Start

```bash
cd sql-optimizer-tool

# Build & package
mvn clean package

# Run (default port 8090)
java -jar target/sql-optimizer-tool-1.0.0.jar
```

Visit: http://localhost:8090

The build produces a single executable fat jar (~64MB; MySQL / PostgreSQL / Oracle / Dameng / openGauss / KingBase drivers and the frontend are bundled). Copy it to any intranet machine with **JDK 17+** and run it directly — no Maven or internet access required:

```bash
java -jar sql-optimizer-tool-1.0.0.jar
# Optional overrides: port / bind address / config-store encryption secret
java -jar sql-optimizer-tool-1.0.0.jar --server.port=8090 --server.address=0.0.0.0
```

Deployment notes:

- Database and AI configurations are stored in `./data` (embedded H2 file database) next to the working directory; logs go to `./logs/`. Persist/back up these directories, and **never delete `./data`** or all configurations are lost. Always launch from the same working directory, or pin the store to an absolute path via `APP_DATA_DIR` (e.g. `APP_DATA_DIR=/var/sql-optimizer/data`); the startup log prints the resolved store location.
- By default the app binds `127.0.0.1` only; set `--server.address=0.0.0.0` (plus gateway-level auth) for LAN access.
- Dameng / openGauss / KingBase drivers are bundled; for any other unbundled driver use the "custom" database type and point to the driver jar on the server machine.
- AI calls go directly from the server to the configured Base URL — make sure that endpoint is reachable from the intranet host.

### Configure AI (optional)

On the "AI Models" page, add a configuration: pick the protocol (OpenAI-compatible / Anthropic), fill in the Base URL, model name and API key, optionally run "Test connectivity", then save and switch it on. Multiple models can be saved but only one is enabled at a time — enabling one automatically disables the others. The Base URL is free-form, so any OpenAI-compatible intranet gateway or self-hosted inference service works.

Index analysis works fine without an enabled model (only AI-based deep rewriting is unavailable).

## 📊 Supported Databases (data source connection)

| Database | Type ID | Driver | Default Port |
|----------|---------|--------|--------------|
| MySQL | `mysql` | MySQL driver | 3306 |
| Oracle | `oracle` | Oracle driver | 1521 |
| PostgreSQL | `postgresql` | PostgreSQL driver | 5432 |
| Dameng DM8 | `dameng` | **DmJdbcDriver18 (bundled)** | 5236 |
| GaussDB | `gaussdb` | PostgreSQL driver (PG wire protocol) | 5432 |
| openGauss | `opengauss` | **opengauss-jdbc (bundled)** | 5432 |
| KingBaseES V8 | `kingbase` | **kingbase8 (bundled)** | 54321 |
| OceanBase | `oceanbase` | MySQL driver | 2881 |
| TiDB | `tidb` | MySQL driver | 4000 |
| Custom | `custom` | Full JDBC URL + driver class name; for non-bundled drivers, supply a server-local jar path (file or directory, `;`-separated) and use "scan jar" to discover the driver class; passwordless databases and extra URL params are supported | — |

### Domestic Database Drivers

The official drivers for Dameng DM8 (`com.dameng:DmJdbcDriver18:8.1.3.140`), openGauss
(`org.opengauss:opengauss-jdbc:5.0.3-og`) and KingBaseES V8 (`cn.com.kingbase:kingbase8:9.0.1`)
are bundled in the fat jar and work out of the box:

- **Dameng**: driver `dm.jdbc.driver.DmDriver`, URL `jdbc:dm://host:5236`; "database name" is the schema name
- **openGauss**: driver `org.opengauss.Driver`, URL `jdbc:opengauss://host:5432/db`
- **KingBase**: driver `com.kingbase8.Driver`, URL `jdbc:kingbase8://host:54321/db`
- **GaussDB**: uses the bundled PostgreSQL driver over the PG wire protocol, `jdbc:postgresql://host:5432/db`

If your database version needs a different driver build, no repackaging is required: choose the
"custom" database type, point to that driver jar on the server ("scan jar" auto-fills the driver class)
and enter the full JDBC URL.

## 📡 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/optimize/start` | POST | Start an async optimize job, returns jobId (used by the page) |
| `/api/optimize/jobs/{jobId}` | GET | Optimize job status (RUNNING/SUCCESS/FAILED/CANCELLED; carries result on success) |
| `/api/optimize/jobs/{jobId}/cancel` | POST | Cancel an optimize job |
| `/api/optimize` | POST | Optimize a single SQL synchronously (kept for compatibility) |
| `/api/optimize/batch` | POST | Batch-optimize multiple SQL (semicolon-separated) |
| `/api/status` | GET | Global status (data source / AI configured) |
| `/api/explain` | POST | Execution-plan analysis (requires data source) |
| `/api/scan/start` | POST | Start an async scan job, returns jobId (used by the page) |
| `/api/scan/jobs/{jobId}` | GET | Scan job status (RUNNING/SUCCESS/FAILED/CANCELLED; carries result on success) |
| `/api/scan/jobs/{jobId}/cancel` | POST | Cancel a running scan job |
| `/api/scan` | POST | Scan a project directory synchronously (kept for compatibility) |
| `/api/scan/replace` | POST | Replace optimized SQL back into the source file (auto `.bak` backup) |
| `/api/scan/dirs` | GET | Browse server directory tree (for the directory picker) |
| `/api/datasource/list`, `/save` | GET/POST | List connections (passwords never returned) / create·update |
| `/api/datasource/{id}/enable`, `/disable` | POST | Exclusive enable (validates then hot-swaps the pool) / disable |
| `/api/datasource/{id}/test`, `/api/datasource/test` | POST | Test a saved / unsaved connection |
| `/api/datasource/discover-drivers?jarPath=` | GET | List driver classes declared in an external driver jar (custom type) |
| `/api/datasource/{id}/delete`, `/status` | POST/GET | Delete (disable first) / active connection status |
| `/api/ai/list`, `/save`, `/{id}/enable`, `/disable`, `/delete`, `/test` | — | AI provider CRUD with the same exclusive-enable rule |
| `/api/ai/protocol-defaults` | GET | Protocol default Base URL |

## 📁 Project Structure

```
sql-optimizer-tool/
├── docs/                                     # Screenshots
├── src/main/java/com/sqloptimizer/
│   ├── SqlOptimizerApplication.java         # Startup class (H2 stores data-source/AI config)
│   ├── common/                              # Result / IndexSuggestion / OptimizeResult
│   │                                        # ExplainResult / ExplainRow / PlanNode / ScanItem
│   ├── entity/                              # DatabaseConfig / AiProvider / AiProtocol (JPA)
│   ├── repository/                          # Spring Data repositories (exclusive-enable updates)
│   ├── util/CryptoUtil.java                 # Password / API key encryption at rest
│   ├── config/                              # Global exception handling
│   ├── controller/
│   │   ├── OptimizeController.java          # Optimize + batch + status
│   │   ├── ExplainController.java           # Execution plan
│   │   ├── ScanController.java              # Project scan + replace + directory browsing
│   │   ├── DataSourceController.java        # Data source CRUD / exclusive enable
│   │   └── AiProviderController.java        # AI provider CRUD / exclusive enable
│   └── service/
│       ├── IndexAnalyzerService.java        # Index candidate extraction (rule engine core)
│       ├── SqlOptimizerService.java         # Optimization orchestration: rules/data source/AI
│       ├── OptimizeJobManager.java          # Background optimize jobs (submit/poll/cancel)
│       ├── ProjectScanService.java          # Project scanning & in-place replacement
│       ├── LocalRewriteService.java         # Deterministic fallback rewrites when AI is unavailable
│       ├── ScanJobManager.java              # Background scan jobs (submit/poll/cancel)
│       ├── DatabaseConfigService.java       # Data source CRUD + exclusive enabled flag (tx)
│       ├── DataSourceService.java           # Active pool hot-swap/index checks/table size
│       ├── AiProviderService.java           # AI provider CRUD + exclusive enable + probes
│       ├── AiChatClient.java                # OpenAI-compatible / Anthropic HTTP client
│       ├── AiConnectionTestService.java     # AI endpoint probe (max_tokens=1)
│       ├── ExplainService.java              # EXPLAIN execution, plan-tree parsing & cost evaluation
│       └── AiService.java                   # SQL deep optimization via the enabled model
└── src/main/resources/
    ├── application.yml
    └── static/
        ├── index.html         # Entry (redirects to manual optimization)
        ├── common.css         # Shared styles
        ├── manual.html        # Manual optimization
        ├── auto.html          # Scan optimization
        ├── explain.html       # SQL execution plan
        ├── datasource.html    # Data source management (multiple, exclusive enable)
        ├── ai.html            # AI model management (multiple, exclusive enable)
        ├── donate.html        # Donation support
        └── image/
            └── ds.png         # Donation QR code
```

## 📝 Notes

1. **Multiple configs with exclusive enable**: Any number of database connections and AI models can be saved, but at most one of each kind is enabled at a time; exclusivity is enforced inside a server-side transaction (not just in the browser). Configs persist in the embedded H2 file database under `./data` and survive restarts. Passwords/API keys are encrypted at rest and never returned to the frontend; leaving the field blank on edit keeps the stored value. Override the encryption secret via `APP_CRYPTO_PASSWORD` / `APP_CRYPTO_SALT`. On startup the app best-effort reconnects to the previously enabled database; a failed reconnect shows an "enabled but offline" state in the UI.
   - **Optimization and scans run as server-side background jobs**: both manual optimization and project scanning immediately return a job id; the job executes on a server thread, independent of the browser connection — switching menus, full-page navigation or even refreshing the page no longer loses an in-flight optimization. Returning to the page restores the "running" state from the job id kept in sessionStorage and resumes polling; the result renders automatically when ready, and the job can be cancelled. Finished job results are retained server-side for 30 minutes (jobs are lost on server restart — the UI then prompts to rerun).
   - **AI in scan mode**: every SQL gets millisecond-level local rule analysis first; AI rewrites then run **concurrently** (default 8 in parallel, max 20 statements per scan, 120s overall deadline, 45s per-request cap — a hung request frees its slot for later statements). Items not finished by the deadline fall back to local deterministic rewrites with a timeout note. Tune via `APP_AI_SCAN_CONCURRENCY` / `APP_AI_SCAN_TIMEOUT` / `APP_AI_SCAN_PER_REQUEST_TIMEOUT`.
   - **Automatic fallback when AI fails or times out**: with AI enabled, "nothing changed" no longer happens when no model is configured, a request times out, the connection fails, the endpoint returns an HTTP error, or the response is empty — the tool automatically falls back to deterministic, semantics-preserving rewrites: (1) comma-style implicit joins (`FROM a, b WHERE a.id=b.aid`) become ANSI `INNER JOIN ... ON`; (2) non-aggregated HAVING predicates move down to WHERE (when GROUP BY exists); (3) single-table `SELECT *` expands to concrete columns from live data-source metadata. Fallback results are tagged "rule rewrite" with every change listed; if no safe rewrite applies, the original SQL is kept with an explanation.
   - **AI networking and proxies**: connect timeouts (cannot reach the host — typical on corporate networks) and response timeouts (connected but the model never replies — busy model, timeout too short, or wrong Base URL / model name; for Volcengine Ark the model must be an endpoint ID `ep-xxxx` with Base URL `https://ark.cn-beijing.volces.com/api/v3`) produce distinct error messages. If outbound traffic requires a proxy, set `HTTPS_PROXY` (or `APP_AI_PROXY`, e.g. `http://127.0.0.1:7890`) and restart; localhost endpoints always connect directly.
2. **Execution-plan safety**: For PostgreSQL-family databases, `EXPLAIN` (without ANALYZE) is used, so no write operations are actually executed.
3. **Execution-plan views**: MySQL 8 uses `EXPLAIN FORMAT=JSON` to parse a structured plan tree (table/tree/diagram); other databases fall back to the raw table.
4. **Index matching rule**: Existing-index detection uses "leftmost-prefix matching", consistent with how databases use indexes.
5. **Cost thresholds**: An execution-plan cost ≥10000 is considered high before suggesting an index; an estimated scan of ≤500 rows is considered small data volume, hinting no index is needed (thresholds adjustable in `ExplainService`).

## ☕ Donation Support

If this project helps you, feel free to buy me a coffee ❤️

<img src="src/main/resources/static/image/ds.png" alt="Donation QR code" width="500">

> You can also view it via the "Donation Support" item in the left menu of the app.

## ⭐ Star Support

If you find it useful, please give the project a **[Star](https://github.com/vfaner/sql-optimizer-tool)** — it's the greatest encouragement for the author!

## 📄 License

This project is licensed under the [MIT License](LICENSE).
