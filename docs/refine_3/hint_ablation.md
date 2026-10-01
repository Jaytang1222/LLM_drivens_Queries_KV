# hint_ablation

> comparative_refine_3.md §8 — rank_hint 消融（已测）。

固定模型与输出协议；仅改 `KART_CBO_LLM_HINT=on|off|shuffle`。

| query | hint | sel_on | act_on | sel_off | act_off | fb_off | sel_shuf | act_shuf | fb_shuf | llm_on | llm_off | llm_shuf |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | P_TZ | P_TZ | propose | P_TZ | propose |  | P_Z | propose |  | 4268 | 3886 | 1542 |
| ais_st_control_tiny | P_T | P_T | propose | P_T | propose |  | P_T | propose |  | 3940 | 3807 | 2487 |
| ais_st_port_focus | P_Z | P_Z | propose | P_T | propose |  | P_T | propose |  | 4939 | 4167 | 4427 |
| ais_topk_dtw_2week | P_T | P_T | propose | P_T | propose |  | P_Z | propose |  | 4070 | 5818 | 3419 |
| ais_topk_frechet_wide | P_T | P_T | propose | P_T | propose |  | P_Z | propose |  | 6828 | 4330 | 4049 |


增量判断：仅当 off/shuffle 产生**不同合法选择**且配对执行收益为正、并扣除新增延迟时，才记 LLM 信息价值；否则归因 hint 锚定或信息不足。
