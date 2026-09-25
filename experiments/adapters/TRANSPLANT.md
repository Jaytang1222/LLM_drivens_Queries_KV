# Transplant honesty (comparative experiments)

This document discloses what is **ported** vs **intentionally changed** for each external arm.
Read alongside `spec/comparative_experiment.md` §5.4 / §6.4.

Upstream checkouts: `experiments/third_party/` (gitignored). Frozen SHAs: `commits.json`.

## Fairness contract (all LLM arms)

| Rule | Implementation |
|------|----------------|
| Same endpoint / model | `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` |
| `temperature=0` | `experiments/adapters/llm_client.py` (`KART_LLM_TEMPERATURE` override only if paper requires) |
| Same logical catalog | `LOGICAL_CATALOG` in `llm_client.py` |
| Same early-reject gate (E1) | Java `ParseFairness` → `Dialog.earlyUnsupportedReason` for **kart / din-* / sag** before any LLM call |

## E1 — Parse

### `kart`

Native `Dialog` / `QueryEngine` NL→DraftIR path. No transplant.

### `din-spider`

| | |
|--|--|
| Upstream | `din-sql/DIN-SQL.py` |
| Ported | `schema_linking_prompt_maker` → `classification_prompt_maker` → `easy\|medium\|hard_prompt_maker` → `debuger` order; few-shot makers loaded via sanitized `exec` of the upstream file |
| Changed | `find_fields_*` → KART catalog; SQL surface → DraftIR JSON; no Spider SQL executor; fair `llm_client` instead of hardcoded GPT-4 |
| Loader note | Upstream file has a broken `API_KEY = #key` and argv gate; we strip that boot section and keep makers + few-shots intact |

### `din-bird`

| | |
|--|--|
| Upstream | `din-sql/DIN-SQL_BIRD.py` |
| Ported | `SYSTEM_*/HUMAN_*` templates and `extract_schema_links` / `extract_label_and_sub_questions`; same stage order as `__main__` loop |
| Changed | Schema/hint → frozen `bird_knowledge.txt` + KART catalog; SQL → DraftIR; **do not** import LangChain / `ChatOpenAI` / BIRD sqlite DBs at load time |

### `sag`

| | |
|--|--|
| Upstream | `text-to-nosql/.../sag/runtime.py` (`_run_attempt`, `select_best`, `cluster_attempts`) |
| Ported | k-attempt × repair-round loop; gate feedback into next decode; fingerprint majority when `KART_SAG_K>1` |
| Changed | DraftIR instead of Mongo MQL; local schema gate instead of `A_path`/`A_value`+pymongo; no `MongoWorld`, empty bisection, or synthetic-`_id` |
| Fairness | Early-reject **not** duplicated in Python (Java `ParseFairness` only) |

## E2 — Plan

### `bao`

| | |
|--|--|
| Upstream | `bao/bao_server/main.py` `BaoModel.select_plan` (+ `PG_OPTIMIZER_INDEX=0`) |
| Ported | Enumerate arms → predict reward → **argmin**; no-model path returns index 0 |
| Changed | Arms = KART `SafePlan` plan_ids; reward = CostCard `estimated_ms` (+ optional family prior JSON); **no** PG tree-CNN features / IMDb weights / C extension; **one** candidate search then `executeSelected` (no second beam) |

### `llmopt`

| | |
|--|--|
| Upstream | `llmopt/README.md` Generator → Selector protocol |
| Ported | G proposes candidates; S picks one; `t_plan` = Python G+S + Java generate→select (not execute) |
| Changed | Candidates from KART plan family; Java `forcePlanId`+validator replaces `pg_hint_plan`; same fair LLM (no vLLM / LLMOpt checkpoints) |

### `kart` (plan)

Native `LlmProposalPolicy` / cost search. No transplant.

KART LLM rows that use RulePolicy (no client, zero calls, illegal action, or rule fill) are labeled `kart-rule-fallback` and are **not** merged into the `kart` arm.

## E3 — E2E

`fullscan` / `rbo` / `cbo` / `kart` are **native** KART arms (not third-party transplants). See spec §7.

## Provenance in results

Bridge stdout includes `provenance` (method, upstream_file, intentional_changes). Java arms write matching fields into `TrialResult.extras` / `ParseTrialResult.extras`. `meta.json` stores adapter SHA256 and whether upstream entry files exist.

JSON-mode HTTP fallback (Python `llm_client.chat` and Java `OpenAiCompatibleClient`) counts as **two** calls and both latencies.

## What we never claim

- That DIN/SAG numbers are comparable to Spider/BIRD/TEND public leaderboards
- That Bao uses a trained Bao regression on PG plan trees
- That LLMOpt uses the authors’ fine-tuned weights or PostgreSQL hints
