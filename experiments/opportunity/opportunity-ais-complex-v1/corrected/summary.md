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

