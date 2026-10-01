# opportunity_upper_bound

> comparative_refine.md §3 — development diagnostic; not a deployable method score.

## Build identity

```
build_id=refine-20261001-155001
host=startserver02
pwd=/home/tyq/projects/llm-kv
time=2026-10-01T15:50:01+08:00
git_head=unknown
-rw-rw-r-- 1 tyq tyq 48M 10月  1 15:49 target/kart.jar
cbo_llm_compact_v6
```

## Prior corrected census (stable 3/3 repeats)

### T-Drive opportunity-dev-v1

# Corrected opportunity census — opportunity-dev-v1

> development_diagnostic; stable labels from repeat pairs only.

- queries in search: 79
- queries with repeat pairs: 60
- missing_oracle queries: 19
- fullscan_unmeasured rows: 79
- true censor rows: 0
- label_counts: {'no_faster_safe_plan': 56, 'search_miss_with_exec_gain': 4, 'candidate_fast_but_overhead_dominates': 4, 'missing_oracle': 19}

## Stable opportunities (3/3 repeat faster)

| query_id | plan | miss_class | median_saving_ms | min | raw |
|---|---|---|---:|---:|---|
| a2_tz_hard_d | P_T | candidate_miss | 285.0 | 181.0 | [181.0, 484.0, 285.0] |
| a2_topk_dtw_wide | P_T | candidate_miss | 237.0 | 211.0 | [237.0, 237.0, 211.0] |
| topk_st_3 | P_T | candidate_miss | 211.0 | 210.0 | [211.0, 221.0, 210.0] |
| a2_tz_hard_a | P_T | candidate_miss | 197.0 | 78.0 | [197.0, 78.0, 306.0] |


### AIS opportunity-ais-complex-v1

# Corrected opportunity census — opportunity-ais-complex-v1

> development_diagnostic; stable labels from repeat pairs only.

- queries in search: 16
- queries with repeat pairs: 12
- missing_oracle queries: 0
- fullscan_unmeasured rows: 16
- true censor rows: 0
- label_counts: {'insufficient_repeats': 4, 'no_faster_safe_plan': 8, 'search_miss_with_exec_gain': 4, 'candidate_fast_but_overhead_dominates': 4}

## Stable opportunities (3/3 repeat faster)

| query_id | plan | miss_class | median_saving_ms | min | raw |
|---|---|---|---:|---:|---|
| ais_topk_frechet_wide | P_T | candidate_miss | 2007.0 | 1981.0 | [2071.0, 2007.0, 1981.0] |
| ais_topk_dtw_2week | P_T | candidate_miss | 1492.0 | 1459.0 | [1492.0, 1492.0, 1459.0] |
| ais_st_port_focus | P_T | candidate_miss | 220.0 | 208.0 | [220.0, 221.0, 208.0] |
| ais_st_control_tiny | P_T | candidate_miss | 22.0 | 21.0 | [25.0, 22.0, 21.0] |


### T-Drive refine re-run

# Corrected opportunity census — opportunity-refine-tdrive4q-20261001-160034

> development_diagnostic; stable labels from repeat pairs only.

- queries in search: 4
- queries with repeat pairs: 4
- missing_oracle queries: 0
- fullscan_unmeasured rows: 4
- true censor rows: 0
- label_counts: {'search_miss_with_exec_gain': 4, 'candidate_fast_but_overhead_dominates': 4}

## Stable opportunities (3/3 repeat faster)

| query_id | plan | miss_class | median_saving_ms | min | raw |
|---|---|---|---:|---:|---|
| a2_tz_hard_d | P_Z | candidate_miss | 451.0 | 76.0 | [451.0, 76.0, 562.0] |
| a2_tz_hard_a | P_T | candidate_miss | 291.0 | 87.0 | [291.0, 87.0, 630.0] |
| a2_topk_dtw_wide | P_T | candidate_miss | 61.0 | 50.0 | [50.0, 115.0, 61.0] |
| topk_st_3 | P_T | candidate_miss | 58.0 | 55.0 | [59.0, 55.0, 58.0] |


### AIS refine re-run

# Corrected opportunity census — opportunity-refine-ais-20261001-160034

> development_diagnostic; stable labels from repeat pairs only.

- queries in search: 5
- queries with repeat pairs: 5
- missing_oracle queries: 0
- fullscan_unmeasured rows: 5
- true censor rows: 0
- label_counts: {'no_faster_safe_plan': 1, 'search_miss_with_exec_gain': 4, 'candidate_fast_but_overhead_dominates': 3}

## Stable opportunities (3/3 repeat faster)

| query_id | plan | miss_class | median_saving_ms | min | raw |
|---|---|---|---:|---:|---|
| ais_topk_frechet_wide | P_T | candidate_miss | 2881.0 | 2354.0 | [2881.0, 3204.0, 2354.0] |
| ais_topk_dtw_2week | P_T | candidate_miss | 1475.0 | 1416.0 | [1416.0, 1598.0, 1475.0] |
| ais_st_port_focus | P_T | candidate_miss | 162.0 | 135.0 | [135.0, 162.0, 285.0] |
| ais_st_control_tiny | P_T | candidate_miss | 39.0 | 38.0 | [38.0, 39.0, 44.0] |



## Interpretation (phase 1)

- Stable opportunities on the 4 T-Drive overhead queries and 4 AIS opportunity queries
  are **candidate_miss** of `P_T` (CBO selected `P_TZ` / similar; `P_T` not in CBO safe set).
- Median exec savings are typically **~200–500 ms** (T-Drive) and **~0.2–2 s** (AIS frechet/dtw).
- Against provisional hybrid extra-plan ~900–2000 ms, most savings alone are
  `candidate_fast_but_overhead_dominates` unless LLM/validate tax is compressed
  or overlapped below the saving.
- Classification: bottleneck is mixed — **search miss (omit P_T)** creates opportunity;
  **serial/overlapped planning tax** often still exceeds S; v6 FastCost ranking
  often prefers `P_Z` (selection signal error relative to measured winners).

