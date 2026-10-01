# isolation_timing_audit

> comparative_refine_5.md §6 — `llm_call_ms`（完整请求）与 `llm_await_after_exec_ms`（执行后等待）分离。

| query | plan | mode | n | exec_med | exec_min | exec_max | llm_call_med | await_after_exec_med | tok_in | tok_out |
|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | P_T | alone | 3 | 14 | 12 | 15 |  |  |  |  |
| ais_st_control_small | P_T | par | 3 | 21 | 15 | 23 | 725 | 709 | 165 | 6 |
| ais_st_control_small | P_TZ | alone | 3 | 65 | 58 | 122 |  |  |  |  |
| ais_st_control_small | P_TZ | par | 3 | 222 | 124 | 225 | 3361 | 3133 | 165 | 6 |
| ais_st_control_tiny | P_T | alone | 3 | 10 | 8 | 15 |  |  |  |  |
| ais_st_control_tiny | P_T | par | 3 | 20 | 18 | 24 | 847 | 820 | 166 | 6 |
| ais_st_control_tiny | P_TZ | alone | 3 | 60 | 39 | 63 |  |  |  |  |
| ais_st_control_tiny | P_TZ | par | 3 | 89 | 72 | 153 | 2186 | 2105 | 166 | 6 |
| ais_st_port_focus | P_T | alone | 3 | 276 | 269 | 346 |  |  |  |  |
| ais_st_port_focus | P_T | par | 3 | 382 | 380 | 595 | 921 | 532 | 165 | 6 |
| ais_st_port_focus | P_TZ | alone | 3 | 392 | 382 | 638 |  |  |  |  |
| ais_st_port_focus | P_TZ | par | 3 | 718 | 708 | 832 | 4256 | 3386 | 165 | 6 |
| ais_topk_dtw_2week | P_T | alone | 3 | 1375 | 1219 | 1426 |  |  |  |  |
| ais_topk_dtw_2week | P_T | par | 3 | 2200 | 2064 | 2282 | 1772 | 0 | 169 | 6 |
| ais_topk_dtw_2week | P_TZ | alone | 3 | 2769 | 2651 | 4760 |  |  |  |  |
| ais_topk_dtw_2week | P_TZ | par | 3 | 5344 | 3870 | 5845 | 4980 | 0 | 169 | 6 |
| ais_topk_frechet_wide | P_T | alone | 3 | 658 | 627 | 759 |  |  |  |  |
| ais_topk_frechet_wide | P_T | par | 3 | 1326 | 1187 | 1409 | 1271 | 0 | 168 | 6 |
| ais_topk_frechet_wide | P_TZ | alone | 3 | 2893 | 2582 | 4281 |  |  |  |  |
| ais_topk_frechet_wide | P_TZ | par | 3 | 5485 | 3896 | 5583 | 4558 | 0 | 168 | 6 |

第四轮 `llm_latency_ms=0` 多为执行后等待已结束，不代表请求成本为 0。
