# Comparative experiments

Four entries (see `spec/comparative_experiment.md` and
`docs/comparative-experiment-readiness-2026-09-25.md`):

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh up && ./scripts/kart.sh rebuild

./scripts/bench-parse.sh --run-id r1 --arm kart,din-spider,din-bird,sag
./scripts/bench-plan.sh  --run-id r1 --arm bao,llmopt,kart
./scripts/bench-e2e.sh   --run-id r1 --arm fullscan,rbo,cbo,kart --cache cold --trials 1
# or
./scripts/bench-all.sh --run-id r1 --parse-arm kart --plan-arm kart --e2e-arm fullscan,rbo,cbo
```

Useful: `--limit N`, `--trials N`, `--workload path`, `--arm a,b`, `--cache cold|warm`.

`fullscan` and `rbo` are fixed baselines (`n_candidates=1`, `n_cost_cards=1`,
`candidate_search=false`). RBO uses the published rule `TZ → T → Z → H → FULL`.

## Cache protocol

| `--cache` | Meaning | Formal use |
|-----------|---------|------------|
| `cold` | Before each E3 trial, attempt HBase `clearBlockCache`. OS page cache is **not** dropped unless `KART_DROP_PAGE_CACHE=1` succeeds. | `--trials 1` for first-run data. `meta.json` sets `cache_enforced` only if both flushes succeed. |
| `warm` | 2 untimed in-process warmups (all arms), then timed samples. | `--trials 5` (or more) for P50/P95. |

Do not mix cold/warm in one main table. Repeated `cold --trials 3` is mixed-cache smoke.
Without passwordless `sudo` for `drop_caches`, use `--cache warm --trials 5` for formal
latency; do not put `cache_enforced=false` cold runs in the cold main table.
Arms still share one RegionServer. Timed E2/E3 samples rotate arm order by query and
trial (`arm_position` on each row). Warm untimed passes use the same rotation family
(`warmup_arm_order_policy=rotate_by_query_and_warmup_pass`). Carry-over remains
possible and is recorded (`sequential_arm_cache_carryover=true`). `cache_enforced`
is true only when every BlockCache clear and every OS page-cache drop in that run
succeeded.

## Timing (honest split)

- E1: `t_parse_total_ms` (to BoundIR), `t_parse_model_ms` (LLM HTTP), `t_adapter_startup_ms` (Python process − model).
- E2: `t_plan_ms` is generate→select (`plan_start_ms` / `plan_end_ms`). Artifact IO is excluded.
- E3: `t_e2e_ms = t_plan_ms + t_exec_ms`. `t_artifact_ms` is recorded separately when keep-artifacts.

KART LLM rows that fully fall back to RulePolicy are labeled `kart-rule-fallback`, not merged into `kart`.

## Results (only these)

```text
experiments/results/<run_id>/
  meta.json
  parse.jsonl | plan.jsonl | e2e.jsonl
  summary.md
```

Read `summary.md` for main tables. JSONL rows match §4.4 fields plus the split
timings, `query_class`, and provenance extras. `meta.json` must include workload
SHA256, git commit, dirty flag, JDK/host, HBase site/evidence hashes, LLM model,
third-party audit (entry files + adapter SHA256), and cache protocol.

## Ablation (planning leave-one-out)

Orthogonal to the four comparative entries. Spec: `spec/ablation_experiment.md`.

```bash
./scripts/bench-ablation.sh --run-id abl1 --cache cold --trials 1
./scripts/bench-ablation.sh --run-id abl1-risk --allow-unsafe --factor no_coverage --cache cold --trials 1
```

`--factor` on a **safe** leave-one-out id auto-includes `full` so paired deltas are
defined in one run (`no_llm_rule`, `no_llm_best_first`, `llm_direct`, `no_fast_cost`,
`no_final_cost`, `single_index`, `uncalibrated`). `--factor no_coverage --allow-unsafe`
stays unpaired (risk table). Use `--no-pair-full` for single-arm diagnostics.
`no_coverage` is omitted unless `--allow-unsafe`.

Results also write `ablation.jsonl` (same rows as `e2e.jsonl` plus `factor` / `unsafe`).
Do not put DIN/SAG/Bao/LLMOpt/fullscan/rbo/cbo in this suite.

`meta.json.hbase_env` records the runtime client version of the shaded classes
(`hbase_client_implementation_version` from classpath `pom.properties`, usually
`2.2.3`) and `hbase_client_specification_version` from the dedicated
`hbase-client-*.jar` under `$HBASE_HOME/lib` or Maven local when the fat jar
manifest has no Spec. `region_server_count` comes from cluster status; failure
leaves it null with `region_server_probe_error`. Cluster version may still differ
(e.g. client 2.2.3 vs cluster 2.1.2); conclusions stay single-node mixed classpath.

## Fairness & authenticity

Full disclosure: [`adapters/TRANSPLANT.md`](adapters/TRANSPLANT.md).

- **Fair LLM**: one endpoint/model, `temperature=0` (`adapters/llm_client.py`); JSON-mode retries count as extra calls
- **Fair early-reject (E1)**: same Java gate for kart / DIN / SAG before any LLM call
- **Authentic control flow**: DIN loads upstream makers/templates; SAG mirrors `runtime.py` repair loop; Bao uses `select_plan` argmin; LLMOpt keeps G→S
- **Intentional retargets**: DraftIR / KART plan_id instead of SQL / Mongo / PG — never claimed as full upstream stacks

## External arms

Clone once (will not `git pull` an existing checkout):

```bash
bash experiments/adapters/clone-third-party.sh
bash experiments/adapters/clone-third-party.sh --verify
```

Commits frozen in `experiments/third_party/commits.json` (also copied into `meta.json`).

| arm | adapter |
|-----|---------|
| din-spider / din-bird | `adapters/din/din_bridge.py` |
| sag | `adapters/sag/sag_bridge.py` |
| bao | Java `BaoPlanArm` + `adapters/bao/kart_plan_family_weights.json` |
| llmopt | `adapters/llmopt/llmopt_bridge.py` (G→S) |
