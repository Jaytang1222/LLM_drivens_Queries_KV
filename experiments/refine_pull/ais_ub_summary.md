# Opportunity census opportunity-refine-ais-20261001-160034

> development_diagnostic — not a formal E2/E3 result; net_gain not claimed (LLM not run).

- queries: 5
- label_counts: {search_miss_with_exec_gain=4, candidate_fast_but_overhead_dominates=3, no_faster_safe_plan=1}
- provisional hybrid extra_plan_ms: 2000

| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |
|---|---|---:|---:|---:|
| ais_topk_frechet_wide | [search_miss_with_exec_gain] | 2881 | 0 | 4228 |
| ais_topk_dtw_2week | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1497 | 0 | 2503 |
| ais_st_port_focus | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 153 | 0 | 362 |
| ais_st_control_tiny | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 39 | 0 | 46 |
| ais_st_control_small | [no_faster_safe_plan] | 0 | 0 | 17 |
