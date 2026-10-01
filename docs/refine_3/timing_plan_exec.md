# plan / exec 时间对照表

列定义：

- **CBO**：第三轮新验证器基线（after）的原生 CBO
- **优化前 hybrid**：第二轮 refine_2（v7 + safety_only，旧 O(M×N) 验证）
- **优化后 hybrid**：第三轮 indexed 验证器 + 同 LLM 协议

单位：ms。`t_plan` / `t_exec` 为臂字段；hybrid 投机采用时 `t_e2e` 为墙钟，勿简单相加。

| dataset | query | cbo_plan | cbo_exec | before_hyb_plan | before_hyb_exec | after_hyb_plan | after_hyb_exec | cbo_e2e | before_hyb_e2e | after_hyb_e2e | G_before | G_after |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tdrive | a2_topk_dtw_wide | 30 | 202 | 4916 | 278 | 1743 | 355 | 232 | 4918 | 1744 | -4455 | -1512 |
| tdrive | a2_tz_hard_a | 49 | 1504 | 7238 | 3351 | 3593 | 2669 | 1553 | 7239 | 3596 | -4526 | -2043 |
| tdrive | a2_tz_hard_d | 66 | 1819 | 6917 | 2872 | 4211 | 3334 | 1885 | 6920 | 4211 | -3475 | -2326 |
| tdrive | topk_st_3 | 19 | 109 | 5158 | 105 | 1552 | 73 | 128 | 5158 | 1552 | -4930 | -1424 |
| ais | ais_st_control_small | 19 | 19 | 4042 | 138 | 4290 | 184 | 38 | 4043 | 4291 | -4013 | -4253 |
| ais | ais_st_control_tiny | 20 | 71 | 4189 | 13 | 3967 | 30 | 91 | 4189 | 3969 | -3962 | -3878 |
| ais | ais_st_port_focus | 33 | 358 | 5341 | 570 | 4975 | 580 | 391 | 5342 | 4975 | -2550 | -4584 |
| ais | ais_topk_dtw_2week | 194 | 2673 | 52350 | 1057 | 4306 | 2048 | 2867 | 67858 | 4306 | -34060 | -1439 |
| ais | ais_topk_frechet_wide | 226 | 3907 | 21711 | 1311 | 7039 | 2780 | 4133 | 21715 | 7049 | -6901 | -2916 |

