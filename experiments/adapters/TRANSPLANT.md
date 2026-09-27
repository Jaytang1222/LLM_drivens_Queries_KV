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
| Same DraftIR contract | `DRAFT_IR_HINT` aligned to `schemas/draft-ir.schema.json` (`ir_version=1.0`, `source.entity`, object `semantics`) |
| Same early-reject gate (E1) | Java `ParseFairness` → `Dialog.earlyUnsupportedReason` for **kart / din-* / sag** before any LLM call; scored via `ParseScore` (gold labels only after inference) |
| HTTP audit | JSON-mode retry only on HTTP **400**; 402/401/403/429/5xx are infrastructure failures (`INFRASTRUCTURE_FAILURE`), not model DraftIR fails |

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

### `din-sql-spider` / `din-sql-bird` (deep transplant)

| | |
|--|--|
| Upstream | same DIN-SQL.py / DIN-SQL_BIRD.py stages |
| Ported | schema linking → classification → generation → upstream self-correction (`debuger` or `SYSTEM_/HUMAN_SELF_CORRECTION_PROMPT`, max 2) |
| Intermediate | **logical SQL** dialect `kart_logical_sql/1.0` |
| Translator | deterministic `translate/sql_to_draft_ir.py` (no LLM, no gold) |
| Changed | SQL dialect is KART trajectory_point view, not Spider/BIRD tables; no SQL executor; a dialect constraint is appended to the upstream repair prompt so the reply stays logical SQL |
| Note | Legacy `din-spider`/`din-bird` remain DraftIR-direct diagnostic arms. Provenance `self_correction` is `upstream_debuger_then_kart_translate` or `upstream_bird_self_correction_then_kart_translate`. |

### `sag-mql` / `sag-mql-nofeedback` (deep transplant)

| | |
|--|--|
| Upstream | `sag/runtime.py` attempt × repair × select_best |
| Intermediate | **logical MQL** dialect `kart_logical_mql/1.0` |
| Translator | `translate/mql_to_draft_ir.py` |
| WorldAccess | Opt-in `KART_WORLD_BACKEND=hbase` → Java `query-draft` (Binder + force `P_FULL`). `KART_WORLD_SAMPLE` remains plumbing-only and is fail-closed for formal feedback. Align sample BoundIR vs Oracle with `experiments/adapters/sag/align_world_oracle.py` before enabling `sag-mql` in formal tables. |
| Note | Without HBase WorldAccess + sample Oracle alignment, do **not** claim full SAG execution feedback. Default parse suite uses `sag-mql-nofeedback`. Legacy `sag` stays DraftIR-direct diagnostic. |

### `direct-draftir`

Same-model single-shot DraftIR baseline for fair E1 comparison.

## E2 — Plan

### `kart-conditional-llm`

CBO enumerates SafePlans + CostCards; LLM picks among them only when `n_safe>=2` and (relative cost gap ≤ freeze `rel_gap` / constructor threshold, or any `uncertainty=HIGH`). Effective threshold comes from `experiments/suites/conditional_llm_freeze.json` via `ConditionalLlmArm.fromRoot` (status `frozen_for_test` ignores env). On LLM failure, falls back to CBO. Not merged into pure `kart` LLM arm.

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

JSON-mode HTTP fallback (Python `llm_client.chat` and Java `OpenAiCompatibleClient`) counts as **two** calls **only** when the first response is HTTP 400 (format rejection). Billing/auth/rate-limit (402/401/403/429) and 5xx do **not** retry and are recorded as infrastructure failures with `http_status` / `llm_failed_attempts`.

Current DIN/SAG **deep** bridges (`din-sql-*`, `sag-mql*`) emit logical SQL/MQL then deterministic DraftIR translation (control-flow transplant). Legacy `din-spider`/`din-bird`/`sag` remain DraftIR-direct diagnostic arms. Without a snapshot-aligned WorldAccess, do not claim full SAG execution feedback.

## What we never claim

- That DIN/SAG numbers are comparable to Spider/BIRD/TEND public leaderboards
- That Bao uses a trained Bao regression on PG plan trees
- That LLMOpt uses the authors’ fine-tuned weights or PostgreSQL hints
