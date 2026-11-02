# Environment lock

- Date: 2026-09-22 (P0); updated 2026-09-23 (P5 MVP close); **2026-09-25**（smoke **24/24**；cost 权威校准包；WSL canonical 路径锁定）；**2026-10-21 切换 4RS**
- JDK: OpenJDK 1.8.0, `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64`
- Maven: 3.6.3
- **HBase 4RS（正式实验环境）**
  - Master+ZK+DN+RS: `10.242.104.108` (startserver02)
  - RS+DN: `10.242.1.172` / `10.242.2.130` / `10.242.4.168` (startserver03/04/05)
  - Install tree: `/data1/hbase/tyq/envs/{hadoop-2.8.5,hbase-2.2.3,zookeeper-3.4.10}`
  - HDFS: `hdfs://10.242.104.108:9000`（DN 数据口 `50018`，避开他人默认 `50010`）
  - HBase root: `hdfs://10.242.104.108:9000/hbase-tyq`；znode `/hbase-tyq`
  - Master `16100` / RS `16120`（避开他人 `16000/16020`）
  - 客户端: `src/main/resources/config/hbase/hbase-site.xml` → ZK `10.242.104.108:2181` + `zookeeper.znode.parent=/hbase-tyq`
- Client: `org.apache.hbase:hbase-client:2.2.3`
- **Canonical 驱动机工作区:** `/home/tyq/projects/llm-kv`（大数据/结果优先 `/data1/hbase/tyq/...`）
- Windows source: `F:/Projects/LLM_KV`
- Experiment protocol: **`cache=warm`，`trials=10`**（报 t_plan / t_exec / t_e2e 的 P50/P95）
- Experiment manifest: **`tdrive_v1_ready`** / **`porto_v1_ready`** — 须在 4RS 上重建后才有效

## Maven local repository (critical on WSL)

Windows `settings.xml` may set `localRepository` to a path like `F:\maven-repository`. Under WSL that becomes a **relative** broken path (`$PWD/F:\...`), so compile sees empty jars.

**Fix used by scripts:** always pass `-Dmaven.repo.local=$HOME/.m2/repository`（`kart.sh rebuild` / `kart.sh test`）。

## Dependency notes (OI-8)

- Excluded `slf4j-log4j12` from hbase-client; app uses SLF4J + Logback + `log4j-over-slf4j`.
- Guava 11.0.2 / Netty arrive transitively from hbase-client 2.2.3; no conflict observed during compile/package.
- `doctor` exercises live `Connection` + `Admin.listTableNames()` when HBase/ZK are up.

## HBase server version note (frozen experiment limitation)

- Client jar: **2.2.3** (pom).
- `Admin.getClusterStatus().getHBaseVersion()` may report **2.1.2** because `HBASE_HOME` classpath can include `TMan-spatial-...jar` that bundles older HBase classes.
- Functional `doctor` / listTables / smoke / chat OK.
- **Experiment disclosure:** single-node, mixed classpath; do not claim numbers are comparable to a clean HBase 2.2.3-only cluster until classpath is cleaned.
- Capture: `./scripts/kart.sh hbase-evidence` → `experiments/results/hbase_version_evidence.txt`

## LLM env (OI-1 closed 2026-09-23; provider switched 2026-09-23)

- Provider: **DeepSeek** (`https://api.deepseek.com/v1`)
- Model: **`deepseek-flash`**（混合臂延迟包默认；旧名 `deepseek-chat` 仍可能被路由到 Flash）
- `response_format=json_object`: **supported** (curl + `kart probe-llm` / `kart.sh probe`, 2026-09-23)
- Live acceptance: `./scripts/kart.sh chat-easy` / `chat-handbook`；混合臂提示版本 `cbo_llm_compact_v5`；LLM-on：`KART_CBO_LLM_MAX_TOKENS`（默认 32）+ `KART_CBO_LLM_SPECULATE_PT`（默认 on）
- Java client: `OpenAiCompatibleClient` via `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` / `LLM_JSON_MODE`
- Secrets in gitignored `.env` only (do not commit)
- Previous PinAI/`gpt-5.5` relay retired due to instability

Load secrets in WSL:

```bash
./scripts/kart.sh probe          # loads .env via kart.sh
./scripts/kart.sh chat           # live DeepSeek
./scripts/kart.sh chat-easy      # batch
```

## Cost model (P5) — authoritative Java fit

- Version: **`cost_v2_rs_sched`**（§13.4 分项 + `ScheduleEstimate` RS affinity）
- **Authority:** `kart fit-cost` / `FeedbackCalibrator`（CostModel MAE）。
- Publish pack: `./scripts/kart.sh fit-cost-pack` → `experiments/results/cost_calib_pack_YYYYMMDD/`（pairs + report + coeffs + `planner.yaml.frozen` + `meta.json`）
- Live pointers: `experiments/results/cost_calibration_report.json`、`cost_coeffs_calibrated.json`、`cost_calibration_meta.json`
- Merge into `config/planner.yaml` is part of `./scripts/kart.sh fit-cost-pack`
- Freeze coeffs during the experiment window; do not hot-reload mid-query.

## WSL sync gate

```bash
cd /home/jaytang/projects/llm-kv   # after first sync; or edit on Windows then:
./scripts/kart.sh sync            # from either tree (uses KART_SRC→KART_DST)
./scripts/kart.sh sync-check      # stamp + key hashes must OK before experiment runs
```

## Verified commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh sync && ./scripts/kart.sh sync-check
./scripts/kart.sh test             # unit + property tests + jar
./scripts/kart.sh doctor
./scripts/kart.sh hbase-evidence
./scripts/kart.sh smoke            # needs READY snapshot + HBase → **24/24**
./scripts/kart.sh fit-cost-pack    # needs HBase; archives replayable calib pack
```
