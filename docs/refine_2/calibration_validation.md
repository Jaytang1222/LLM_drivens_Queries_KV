# calibration_validation

> comparative_refine_2.md §8 — relative benefit labels; development only.

## Status

**Partial — not a trained holdout model.** Labels and ranking diagnostics below use
existing opportunity corrected summaries + after E2E pairs. The nine development
queries remain development data (not renamed into holdout).

## Labels

`S(q,p) = E_cbo(q) - E_p(q)` from opportunity repeats (multi-trial where available).
Missing/unmeasured FullScan stays marked — not imputed as zero.

## Prior opportunity corrected (development)

### T-Drive

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



### AIS

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



## Ranking vs after E2E

FastCost rank_hint is still the speculative chooser before LLM returns. After v7,
LLM may diverge; see `llm_independent_decision.md`. Without a grouped holdout fit,
do **not** treat FastCost or LLM scores as calibrated confidence.

## Gate idea (not enforced)

Speculate/adopt only if predicted `S` exceeds critical-path overhead with κ≥1.
