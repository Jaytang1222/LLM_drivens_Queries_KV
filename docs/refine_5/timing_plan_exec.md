# timing_plan_exec

列：CBO / 优化前(refine_4) / 优化后(refine_5) 的 plan 与 exec。

| query | cbo_plan | cbo_plan_ms | cbo_exec_ms | before_plan | before_plan_ms | before_exec_ms | after_plan | after_plan_ms | after_exec_ms | cbo_e2e | after_e2e | net_ms | llm_action | schedule | gate | cbo_par | spec |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | P_T | 13 | 18 | P_T | 989 | 23 | P_T | 281 | 30 | 31 | 281 | -250 | keep_cbo | cbo_parallel | gate_negative_s | True | False |
| ais_st_control_tiny | P_TZ | 22 | 95 | P_TZ | 1065 | 60 | P_TZ | 1143 | 50 | 117 | 1193 | -1076 | keep_cbo | speculate_candidate | gate_negative_s | False | False |
| ais_st_port_focus | P_TZ | 43 | 276 | P_TZ | 723 | 435 | P_TZ | 1331 | 403 | 319 | 1734 | -1415 | keep_cbo | speculate_candidate | gate_conservative_s | False | False |
| ais_topk_dtw_2week | P_TZ | 138 | 2285 | P_TZ | 1111 | 2619 | P_TZ | 3869 | 2728 | 2423 | 6597 | -4174 | keep_cbo | speculate_candidate | gate_conservative_s | False | False |
| ais_topk_frechet_wide | P_TZ | 201 | 2516 | P_TZ | 1893 | 2478 | P_TZ | 4854 | 2556 | 2717 | 7410 | -4693 | keep_cbo | speculate_candidate | gate_conservative_s | False | False |
| a2_topk_dtw_wide | P_TZ | 20 | 202 | P_TZ | 1234 | 252 | P_TZ | 1258 | 237 | 222 | 1258 | -1036 | keep_cbo | cbo_parallel | gate_conservative_s | True | False |
| a2_tz_hard_a | P_TZ | 27 | 1174 | P_TZ | 1433 | 1621 | P_TZ | 2126 | 1301 | 1201 | 3427 | -2226 | keep_cbo | speculate_candidate | gate_negative_s | False | False |
| a2_tz_hard_d | P_TZ | 31 | 1632 | P_TZ | 2996 | 1768 | P_TZ | 3193 | 1519 | 1663 | 4712 | -3049 | keep_cbo | speculate_candidate | gate_negative_s | False | False |
| topk_st_3 | P_TZ | 42 | 125 | P_TZ | 1750 | 185 | P_TZ | 1468 | 171 | 167 | 1639 | -1472 | keep_cbo | speculate_candidate | gate_negative_s | False | False |
