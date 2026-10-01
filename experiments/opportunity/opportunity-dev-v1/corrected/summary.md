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

