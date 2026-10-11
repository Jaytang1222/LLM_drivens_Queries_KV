# Opportunity census opp-newidx-20261009-185737

> development_diagnostic — not a formal E2/E3 result; net_gain not claimed (LLM not run).

- queries: 48
- label_counts: {no_faster_safe_plan=35, search_miss_with_exec_gain=8, candidate_fast_but_overhead_dominates=8, oracle_or_execution_failure=5}
- provisional hybrid extra_plan_ms: 900

| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |
|---|---|---:|---:|---:|
| st_ss_2 | [no_faster_safe_plan] | 0 | 0 | 13 |
| st_sl_1 | [no_faster_safe_plan] | 0 | 0 | 137 |
| st_ls_1 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 7 | 0 | 36 |
| h_t_large | [no_faster_safe_plan] | 0 | 0 | 6 |
| h_s_small | [no_faster_safe_plan] | 0 | 0 | 5 |
| topk_st_2 | [no_faster_safe_plan] | 0 | 0 | 20 |
| topk_st_3 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 53 | 0 | 94 |
| st_mid_1 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 5 | 0 | 29 |
| h_st_3 | [no_faster_safe_plan] | 0 | 0 | 5 |
| h_t_mid | [no_faster_safe_plan] | 0 | 0 | 6 |
| topk_st_k1 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1 | 0 | 11 |
| topk_st_frechet_k5 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1 | 0 | 11 |
| topk_st_hausdorff_k1 | [no_faster_safe_plan] | 0 | 0 | 11 |
| st_ll_2 | [oracle_or_execution_failure] | 0 | 0 | null |
| st_grid_1 | [no_faster_safe_plan] | 0 | 0 | 8 |
| st_grid_2 | [no_faster_safe_plan] | 0 | 0 | 7 |
| st_grid_3 | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 3 | 0 | 9 |
| h_st_4 | [no_faster_safe_plan] | 0 | 0 | 6 |
| topk_st_k10 | [no_faster_safe_plan] | 0 | 0 | 11 |
| topk_st_2_k1 | [no_faster_safe_plan] | 0 | 0 | 19 |
| a2_tzh_a | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_b | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_c | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_d | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_e | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_f | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_g | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tzh_h | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_tz_hard_a | [oracle_or_execution_failure] | 0 | 0 | null |
| a2_tz_hard_b | [oracle_or_execution_failure] | 0 | 0 | null |
| a2_tz_hard_c | [oracle_or_execution_failure] | 0 | 0 | null |
| a2_tz_hard_d | [oracle_or_execution_failure] | 0 | 0 | null |
| a2_tz_hard_e | [no_faster_safe_plan] | 0 | 0 | 819 |
| a2_tz_hard_f | [no_faster_safe_plan] | 0 | 0 | 2033 |
| a2_tz_hard_g | [no_faster_safe_plan] | 0 | 0 | 104 |
| a2_tz_hard_h | [no_faster_safe_plan] | 0 | 0 | 153 |
| a2_th_unc_a | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_th_unc_b | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_zh_unc_a | [no_faster_safe_plan] | 0 | 0 | 5 |
| a2_zh_unc_b | [no_faster_safe_plan] | 0 | 0 | 6 |
| a2_topk_dtw_k3 | [no_faster_safe_plan] | 0 | 0 | 20 |
| a2_topk_dtw_k7 | [no_faster_safe_plan] | 0 | 0 | 56 |
| a2_topk_dtw_wide | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 46 | 0 | 127 |
| a2_topk_frechet_k3 | [no_faster_safe_plan] | 0 | 0 | 21 |
| a2_topk_frechet_k7 | [no_faster_safe_plan] | 0 | 0 | 29 |
| a2_topk_frechet_wide | [search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 11 | 0 | 67 |
| a2_topk_haus_k3 | [no_faster_safe_plan] | 0 | 0 | 21 |
| a2_topk_haus_k5 | [no_faster_safe_plan] | 0 | 0 | 50 |
