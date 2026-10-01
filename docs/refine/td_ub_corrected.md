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

