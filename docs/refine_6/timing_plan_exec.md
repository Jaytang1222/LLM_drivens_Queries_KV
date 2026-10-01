# timing_plan_exec

列：CBO / 优化前(refine_5) / 优化后(refine_6) 的 plan、exec 与 E2E。

| query | cbo_plan | cbo_plan_ms | cbo_exec_ms | before_plan | before_plan_ms | before_exec_ms | after_plan | after_plan_ms | after_exec_ms | cbo_e2e | before_e2e | after_e2e | net_ms | action | schedule | gate | cbo_par | probe_ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| a2_topk_dtw_wide | P_TZ | 25 | 221 | P_TZ | 1258 | 237 | P_TZ | 2633 | 238 | 246 | 1258 | 2633 | -2387 | PROPOSE_P_T | cbo_parallel | gate_conservative_s | True | 0 |
| a2_tz_hard_a | P_TZ | 62 | 1712 | P_TZ | 2126 | 1301 | P_TZ | 3283 | 2614 | 1774 | 3427 | 3283 | -1509 | PROPOSE_P_T | cbo_parallel | gate_negative_s | True | 0 |
| a2_tz_hard_d | P_TZ | 33 | 2078 | P_TZ | 3193 | 1519 | P_TZ | 3911 | 3142 | 2111 | 4712 | 3911 | -1800 | PROPOSE_P_T | cbo_parallel | gate_negative_s | True | 0 |
| ais_st_control_small | P_T | 17 | 19 | P_T | 281 | 30 | P_T | 2390 | 24 | 36 | 281 | 2391 | -2355 | PROPOSE_P_T | cbo_parallel | keep | True | 0 |
| ais_st_control_tiny | P_TZ | 21 | 90 | P_TZ | 1143 | 50 | P_T | 2194 | 20 | 111 | 1193 | 2214 | -2103 | PROPOSE_P_T | cbo_parallel | accept | False | 0 |
| ais_st_port_focus | P_TZ | 33 | 355 | P_TZ | 1331 | 403 | P_TZ | 2286 | 688 | 388 | 1734 | 2286 | -1898 | PROPOSE_P_T | cbo_parallel | gate_conservative_s | True | 0 |
| ais_topk_dtw_2week | P_TZ | 136 | 2580 | P_TZ | 3869 | 2728 | P_TZ | 3839 | 4201 | 2716 | 6597 | 4389 | -1673 | PROPOSE_P_T | cbo_parallel | gate_conservative_s | True | 0 |
| ais_topk_frechet_wide | P_TZ | 220 | 2579 | P_TZ | 4854 | 2556 | P_TZ | 4998 | 4278 | 2799 | 7410 | 4998 | -2199 | PROPOSE_P_T | cbo_parallel | gate_conservative_s | True | 0 |
| topk_st_3 | P_TZ | 37 | 137 | P_TZ | 1468 | 171 | P_T | 3711 | 99 | 174 | 1639 | 3810 | -3636 | PROPOSE_P_T | cbo_parallel | accept | False | 0 |
