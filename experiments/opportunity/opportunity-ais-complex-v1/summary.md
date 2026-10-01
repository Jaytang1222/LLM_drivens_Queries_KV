# Opportunity census opportunity-ais-complex-v1

> development_diagnostic — not a formal E2/E3 result; net_gain not claimed (LLM not run).

- queries: 16
- label_counts: {no_faster_safe_plan=12, search_miss_with_exec_gain=4, candidate_fast_but_overhead_dominates=4}
- provisional hybrid extra_plan_ms: 5000

| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |
|---|---|---:|---:|---:|
| ais_st_wide_week | [no_faster_safe_plan] | 0 | 0 | 376 |
| ais_st_wide_2week | [no_faster_safe_plan] | 0 | 0 | 579 |
| ais_s_large | [no_faster_safe_plan] | 0 | 0 | 3276 |
| ais_t_wide_month | [no_faster_safe_plan] | 0 | 0 | 1510 |
| ais_st_mid | [no_faster_safe_plan] | 0 | 0 | 104 |
| ais_st_mid_b | [no_faster_safe_plan] | 0 | 0 | 169 |
| ais_st_control_small | [no_faster_safe_plan] | 0 | 0 | 8 |
| ais_st_control_tiny | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 24 | 0 | 30 |
| ais_topk_dtw_wide | [no_faster_safe_plan] | 0 | 0 | 599 |
| ais_topk_dtw_2week | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1487 | 0 | 2232 |
| ais_topk_frechet_wide | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 2010 | 0 | 2574 |
| ais_topk_hausdorff_mid | [no_faster_safe_plan] | 0 | 0 | 133 |
| ais_topk_dtw_large_s | [no_faster_safe_plan] | 0 | 0 | 4230 |
| ais_topk_dtw_control | [no_faster_safe_plan] | 0 | 0 | 4 |
| ais_topk_dtw_t_wide | [no_faster_safe_plan] | 0 | 0 | 915 |
| ais_st_port_focus | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 219 | 0 | 341 |
