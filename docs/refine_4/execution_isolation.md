# execution_isolation

> comparative_refine_4.md §4 — 同计划独立 / 并行执行诊断。

配置：`fixed-plan` 臂；`KART_FIXED_PLAN_ID∈{P_T,P_TZ}`；alone=`PARALLEL_LLM=0`；par=`PARALLEL_LLM=1`（dummy short KEEP 与执行并行）。

| query | plan | mode | n | exec_med | exec_min | exec_max | plan_med | llm_med | n_ranges | fetch |
|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | P_T | alone | 3 | 22 | 14 | 30 | 2 | 0 | 26 | 130 |
| ais_st_control_small | P_T | par | 1 | 36 | 36 | 36 | 2 | 542 | 26 | 130 |
| ais_st_control_small | P_TZ | alone | 3 | 70 | 65 | 71 | 3 | 0 | 295 | 2 |
| ais_st_control_small | P_TZ | par | 1 | 156 | 156 | 156 | 9 | 566 | 295 | 2 |
| ais_st_control_tiny | P_T | alone | 3 | 13 | 9 | 14 | 2 | 0 | 13 | 144 |
| ais_st_control_tiny | P_T | par | 1 | 25 | 25 | 25 | 4 | 805 | 13 | 144 |
| ais_st_control_tiny | P_TZ | alone | 3 | 36 | 33 | 40 | 3 | 0 | 205 | 1 |
| ais_st_control_tiny | P_TZ | par | 1 | 69 | 69 | 69 | 3 | 532 | 205 | 1 |
| ais_st_port_focus | P_T | alone | 3 | 263 | 241 | 304 | 7 | 0 | 1732 | 793 |
| ais_st_port_focus | P_T | par | 1 | 646 | 646 | 646 | 8 | 940 | 1732 | 793 |
| ais_st_port_focus | P_TZ | alone | 3 | 366 | 351 | 369 | 10 | 0 | 2201 | 12 |
| ais_st_port_focus | P_TZ | par | 1 | 656 | 656 | 656 | 10 | 955 | 2201 | 12 |
| ais_topk_dtw_2week | P_T | alone | 3 | 1362 | 1306 | 1449 | 28 | 0 | 8634 | 4630 |
| ais_topk_dtw_2week | P_T | par | 1 | 1653 | 1653 | 1653 | 56 | 0 | 8634 | 4630 |
| ais_topk_dtw_2week | P_TZ | alone | 3 | 2692 | 2643 | 2722 | 44 | 0 | 11108 | 1360 |
| ais_topk_dtw_2week | P_TZ | par | 1 | 3396 | 3396 | 3396 | 85 | 0 | 11108 | 1360 |
| ais_topk_frechet_wide | P_T | alone | 3 | 734 | 670 | 743 | 17 | 0 | 3849 | 2770 |
| ais_topk_frechet_wide | P_T | par | 1 | 1517 | 1517 | 1517 | 51 | 173 | 3849 | 2770 |
| ais_topk_frechet_wide | P_TZ | alone | 3 | 2686 | 2481 | 2892 | 42 | 0 | 6254 | 868 |
| ais_topk_frechet_wide | P_TZ | par | 1 | 2995 | 2995 | 2995 | 117 | 0 | 6254 | 868 |

## 分支解读（开发诊断）

- `ais_st_control_small` / `P_T`: alone=22 par=36 Δ=14 → 独立/并行接近（计划本身主导）
- `ais_st_control_small` / `P_TZ`: alone=70 par=156 Δ=86 → 并行竞争显著
- `ais_st_control_tiny` / `P_T`: alone=13 par=25 Δ=12 → 独立/并行接近（计划本身主导）
- `ais_st_control_tiny` / `P_TZ`: alone=36 par=69 Δ=33 → 独立/并行接近（计划本身主导）
- `ais_st_port_focus` / `P_T`: alone=263 par=646 Δ=383 → 并行竞争显著
- `ais_st_port_focus` / `P_TZ`: alone=366 par=656 Δ=290 → 并行竞争显著
- `ais_topk_dtw_2week` / `P_T`: alone=1362 par=1653 Δ=291 → 并行竞争显著
- `ais_topk_dtw_2week` / `P_TZ`: alone=2692 par=3396 Δ=704 → 并行竞争显著
- `ais_topk_frechet_wide` / `P_T`: alone=734 par=1517 Δ=783 → 并行竞争显著
- `ais_topk_frechet_wide` / `P_TZ`: alone=2686 par=2995 Δ=309 → 独立/并行接近（计划本身主导）
