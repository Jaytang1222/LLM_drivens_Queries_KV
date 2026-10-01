# timing_plan_exec

列：CBO / 优化前(refine_3) / 优化后(refine_4) 的 plan 与 exec；E2E 净收益 = CBO_e2e - hybrid_e2e。

| query | cbo_plan | cbo_plan_ms | cbo_exec_ms | before_plan | before_plan_ms | before_exec_ms | after_plan | after_plan_ms | after_exec_ms | cbo_e2e | after_e2e | net_ms | llm_action | gate |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | P_T | 14 | 18 | P_TZ | 4290 | 184 | P_T | 989 | 23 | 32 | 1012 | -980 | keep_cbo | gate_skip_speculate_low_s |
| ais_st_control_tiny | P_TZ | 28 | 62 | P_T | 3967 | 30 | P_TZ | 1065 | 60 | 90 | 1125 | -1035 | keep_cbo | llm_keep_cbo |
| ais_st_port_focus | P_TZ | 32 | 341 | P_Z | 4975 | 580 | P_TZ | 723 | 435 | 373 | 1158 | -785 | keep_cbo | llm_keep_cbo |
| ais_topk_dtw_2week | P_TZ | 140 | 2770 | P_T | 4306 | 2048 | P_TZ | 1111 | 2619 | 2910 | 3730 | -820 | keep_cbo | llm_keep_cbo |
| ais_topk_frechet_wide | P_TZ | 232 | 2463 | P_T | 7039 | 2780 | P_TZ | 1893 | 2478 | 2695 | 4371 | -1676 | keep_cbo | llm_keep_cbo |
| a2_topk_dtw_wide | P_TZ | 39 | 163 | P_Z | 1743 | 355 | P_TZ | 1234 | 252 | 202 | 1486 | -1284 | keep_cbo | gate_skip_speculate_low_s |
| a2_tz_hard_a | P_TZ | 33 | 1513 | P_Z | 3593 | 2669 | P_TZ | 1433 | 1621 | 1546 | 3054 | -1508 | keep_cbo | llm_keep_cbo |
| a2_tz_hard_d | P_TZ | 36 | 1861 | P_Z | 4211 | 3334 | P_TZ | 2996 | 1768 | 1897 | 4764 | -2867 | keep_cbo | llm_keep_cbo |
| topk_st_3 | P_TZ | 16 | 99 | P_T | 1552 | 73 | P_TZ | 1750 | 185 | 115 | 1935 | -1820 | keep_cbo | llm_keep_cbo |
