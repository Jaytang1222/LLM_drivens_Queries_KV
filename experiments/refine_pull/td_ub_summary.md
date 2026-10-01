# Opportunity census opportunity-refine-tdrive4q-20261001-160034

> development_diagnostic — not a formal E2/E3 result; net_gain not claimed (LLM not run).

- queries: 4
- label_counts: {search_miss_with_exec_gain=4, candidate_fast_but_overhead_dominates=4}
- provisional hybrid extra_plan_ms: 900

| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |
|---|---|---:|---:|---:|
| a2_tz_hard_d | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 424 | 0 | 2007 |
| a2_topk_dtw_wide | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 59 | 0 | 121 |
| topk_st_3 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 57 | 0 | 87 |
| a2_tz_hard_a | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 594 | 0 | 1798 |
