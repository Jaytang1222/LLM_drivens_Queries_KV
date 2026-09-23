# Environment lock

- Date: 2026-09-22 (P0); updated 2026-09-23 (P5 MVP close)
- JDK: OpenJDK 1.8.0 (WSL Ubuntu-22.04), `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64`
- Maven: 3.6.3 (`/mnt/c/maven/apache-maven-3.6.3`)
- HBase server: 2.2.3 (`/home/jaytang/hbase` in WSL; Windows tree also at `F:\envs\hbase-2.2.3`)
- ZooKeeper: localhost:2181 (external)
- Client: `org.apache.hbase:hbase-client:2.2.3`
- Build workspace: rsync to `/home/jaytang/projects/llm-kv` (ext4; avoid NTFS + broken Windows `localRepository`)
- Compat symlink: `/home/jaytang/build/LLM_KV` → `/home/jaytang/projects/llm-kv` (old scripts keep working)
- Layout note: `/home/jaytang/projects/README.md` — `tman-spatial/` is drvfs to `F:\Projects\TMan-spatial`; `llm-kv/` is a native copy (not a mount)

## Maven local repository (critical on WSL)

Windows `settings.xml` may set `localRepository` to a path like `F:\maven-repository`. Under WSL that becomes a **relative** broken path (`$PWD/F:\...`), so compile sees empty jars.

**Fix used by scripts:** always pass `-Dmaven.repo.local=$HOME/.m2/repository` (see `scripts/rebuild-jar.sh`, `scripts/wsl-test.sh`).

## Dependency notes (OI-8)

- Excluded `slf4j-log4j12` from hbase-client; app uses SLF4J + Logback + `log4j-over-slf4j`.
- Guava 11.0.2 / Netty arrive transitively from hbase-client 2.2.3; no conflict observed during compile/package.
- `doctor` exercises live `Connection` + `Admin.listTableNames()` when HBase/ZK are up.

## HBase server version note (user-verified 2026-09-22)

- Client jar: **2.2.3** (pom).
- `Admin.getClusterStatus().getHBaseVersion()` reported **2.1.2** because the HBase classpath includes `TMan-spatial-...jar` that bundles older HBase classes.
- Functional `doctor` / listTables OK; recommend aligning server classpath (remove or isolate TMan-spatial) before production stress tests.

## LLM env (OI-1 closed 2026-09-23; provider switched 2026-09-23)

- Provider: **DeepSeek** (`https://api.deepseek.com/v1`)
- Model: `deepseek-chat`
- `response_format=json_object`: **supported** (curl + `kart probe-llm` / `kart.sh probe`, 2026-09-23)
- Live acceptance: `./scripts/kart.sh live-accept` → **PASS** on DeepSeek (2026-09-23)
- Java client: `OpenAiCompatibleClient` via `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` / `LLM_JSON_MODE`
- Secrets in gitignored `.env` only (do not commit)
- Previous PinAI/`gpt-5.5` relay retired due to instability

Load secrets in WSL:

```bash
source scripts/load-llm-env.sh   # reads gitignored .env
./scripts/kart.sh probe
./scripts/kart.sh chat           # live DeepSeek
# or batch:
./scripts/kart.sh live-accept
```

## Cost model (P5)

- Coefficients in `config/planner.yaml` under `cost:` with `calibrated: false`.
- Fast (search) and Final (selector) share `kart.cost.CostModel`; Final uses compiled scan range counts.

## Verified commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
cd /home/jaytang/projects/llm-kv   # or: cd /mnt/f/Projects/LLM_KV
./scripts/sync-wsl-workspace.sh    # optional if you edited on Windows
./scripts/kart.sh test             # unit + property tests + jar
./scripts/kart.sh doctor
./scripts/kart.sh failures
./scripts/kart.sh smoke            # needs READY snapshot + HBase
```
