# Opportunity census opportunity-dev-v1

> development_diagnostic — not a formal E2/E3 result; net_gain not claimed (LLM not run).

- queries: 79
- label_counts: {censored=79, search_miss_with_exec_gain=21, candidate_fast_but_overhead_dominates=21, oracle_or_execution_failure=19}
- provisional hybrid extra_plan_ms: 900

| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |
|---|---|---:|---:|---:|
| st_ss_1 | [censored] | 0 | 0 | 52 |
| st_ss_2 | [censored] | 0 | 0 | 24 |
| st_sl_1 | [censored] | 0 | 0 | 513 |
| st_ls_1 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 386 | 0 | 431 |
| h_t_small | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 10 | 0 | 19 |
| h_t_large | [censored] | 0 | 0 | 18 |
| h_s_small | [censored] | 0 | 0 | 21 |
| h_st_1 | [censored] | 0 | 0 | 19 |
| topk_st_1 | [censored] | 0 | 0 | 20 |
| topk_st_2 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 4 | 0 | 34 |
| topk_st_3 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 212 | 0 | 273 |
| topk_st_frechet_1 | [censored] | 0 | 0 | 19 |
| st_mid_1 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 10 | 0 | 46 |
| h_st_3 | [censored] | 0 | 0 | 20 |
| h_t_mid | [censored] | 0 | 0 | 23 |
| topk_st_k1 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 4 | 0 | 24 |
| topk_st_k5 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 4 | 0 | 23 |
| topk_st_frechet_k5 | [censored] | 0 | 0 | 20 |
| topk_st_hausdorff_k1 | [censored] | 0 | 0 | 18 |
| st_ll_2 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 76 | 0 | 3487 |
| st_grid_1 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 2 | 0 | 15 |
| st_grid_2 | [censored] | 0 | 0 | 16 |
| st_grid_3 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1 | 0 | 17 |
| h_st_4 | [censored] | 0 | 0 | 21 |
| topk_st_k10 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 1 | 0 | 19 |
| topk_st_2_k1 | [censored] | 0 | 0 | 30 |
| h2_tz_mid_b | [censored] | 0 | 0 | 16 |
| a2_tzh_a | [censored] | 0 | 0 | 20 |
| a2_tzh_b | [censored] | 0 | 0 | 20 |
| a2_tzh_c | [censored] | 0 | 0 | 21 |
| a2_tzh_d | [censored] | 0 | 0 | 21 |
| a2_tzh_e | [censored] | 0 | 0 | 19 |
| a2_tzh_f | [censored] | 0 | 0 | 19 |
| a2_tzh_g | [censored] | 0 | 0 | 22 |
| a2_tzh_h | [censored] | 0 | 0 | 21 |
| a2_tz_hard_a | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 197 | 0 | 4077 |
| a2_tz_hard_b | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 67 | 0 | 5223 |
| a2_tz_hard_c | [censored] | 0 | 0 | 4242 |
| a2_tz_hard_d | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 425 | 0 | 5368 |
| a2_tz_hard_e | [censored] | 0 | 0 | 3162 |
| a2_tz_hard_f | [censored] | 0 | 0 | 8329 |
| a2_tz_hard_g | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 157 | 0 | 571 |
| a2_tz_hard_h | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 162 | 0 | 706 |
| a2_th_unc_a | [censored] | 0 | 0 | 19 |
| a2_th_unc_b | [censored] | 0 | 0 | 21 |
| a2_zh_unc_a | [censored] | 0 | 0 | 21 |
| a2_zh_unc_b | [censored] | 0 | 0 | 20 |
| a2_topk_dtw_k3 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 5 | 0 | 32 |
| a2_topk_dtw_k7 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 30 | 0 | 92 |
| a2_topk_dtw_wide | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 232 | 0 | 364 |
| a2_topk_frechet_k3 | [censored] | 0 | 0 | 32 |
| a2_topk_frechet_k7 | [censored] | 0 | 0 | 47 |
| a2_topk_frechet_wide | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 33 | 0 | 116 |
| a2_topk_haus_k3 | [censored] | 0 | 0 | 32 |
| a2_topk_haus_k5 | [censored] | 0 | 0 | 77 |
| pilot_cbo_miss_01 | [censored, search_miss_with_exec_gain, candidate_fast_but_overhead_dominates] | 2 | 0 | 16 |
| pilot_cbo_miss_02 | [censored] | 0 | 0 | 499 |
| pilot_cbo_miss_03 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_04 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_05 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_06 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_07 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_08 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_09 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_10 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_cbo_miss_11 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_01 | [censored] | 0 | 0 | 17 |
| pilot_bao_tzh_02 | [censored] | 0 | 0 | 19 |
| pilot_bao_tzh_03 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_04 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_05 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_06 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_07 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_08 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_09 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_10 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_11 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_bao_tzh_12 | [oracle_or_execution_failure, censored] | 0 | 0 | null |
| pilot_control_01 | [censored] | 0 | 0 | 15 |
