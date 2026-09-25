# Environment lock

- Date: 2026-09-22 (P0); updated 2026-09-23 (P5 MVP close); **2026-09-25**（smoke **24/24**；cost 权威校准包；WSL canonical 路径锁定）
- JDK: OpenJDK 1.8.0 (WSL Ubuntu-22.04), `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64`
- Maven: 3.6.3 (`/mnt/c/maven/apache-maven-3.6.3`)
- HBase server: 2.2.3 install tree (`/home/jaytang/hbase`; Windows `F:\envs\hbase-2.2.3`) — **cluster status may report 2.1.2** (see below)
- ZooKeeper: localhost:2181 (external)
- Client: `org.apache.hbase:hbase-client:2.2.3`
- **Canonical WSL workspace:** `/home/jaytang/projects/llm-kv` (`KART_DST`)
- Windows source: `/mnt/f/Projects/LLM_KV` (`KART_SRC`)
- Compat symlink only: `/home/jaytang/build/LLM_KV` → `KART_DST`（旧脚本兼容；禁止当作第二份工作树）
- Experiment manifest: **`tdrive_v1_ready`** — see `docs/experiment-scope.md`

## Maven local repository (critical on WSL)

Windows `settings.xml` may set `localRepository` to a path like `F:\maven-repository`. Under WSL that becomes a **relative** broken path (`$PWD/F:\...`), so compile sees empty jars.

**Fix used by scripts:** always pass `-Dmaven.repo.local=$HOME/.m2/repository` (see `scripts/rebuild-jar.sh`, `scripts/wsl-test.sh`).

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
- Model: `deepseek-chat`
- `response_format=json_object`: **supported** (curl + `kart probe-llm` / `kart.sh probe`, 2026-09-23)
- Live acceptance: `./scripts/kart.sh chat-easy` / `chat-handbook`（DeepSeek；`LLM_MODEL=deepseek-chat` → 服务端常报 `deepseek-flash`）
- Java client: `OpenAiCompatibleClient` via `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` / `LLM_JSON_MODE`
- Secrets in gitignored `.env` only (do not commit)
- Previous PinAI/`gpt-5.5` relay retired due to instability

Load secrets in WSL:

```bash
source scripts/load-llm-env.sh   # reads gitignored .env
./scripts/kart.sh probe
./scripts/kart.sh chat           # live DeepSeek
# or batch:
./scripts/kart.sh chat-easy
```

## Cost model (P5) — authoritative Java fit

- Version: **`cost_v2_rs_sched`**（§13.4 分项 + `ScheduleEstimate` RS affinity）
- **Authority:** `kart fit-cost` / `FeedbackCalibrator`（CostModel MAE），**不是** `fit_cost_coeffs.py` 诊断 NNLS。
- Publish pack: `./scripts/kart.sh fit-cost-pack` → `experiments/results/cost_calib_pack_YYYYMMDD/`（pairs + report + coeffs + `planner.yaml.frozen` + `meta.json`）
- Live pointers: `experiments/results/cost_calibration_report.json`、`cost_coeffs_calibrated.json`、`cost_calibration_meta.json`
- Merge into `config/planner.yaml` via `scripts/merge_cost_coeffs_into_planner.py`
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
