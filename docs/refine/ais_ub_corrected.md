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

