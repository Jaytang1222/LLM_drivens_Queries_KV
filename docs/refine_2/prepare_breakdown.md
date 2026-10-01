# prepare_breakdown

> comparative_refine_2.md §4 — fixed-candidate prepare sub-stages.

Source: `prepare_full_ais.jsonl`（`KART_CBO_LLM_PREPARE_MODE=full`，run `refine2-20261001-171119`）。

## Fields

- `t_fixed_compile_ms` / `t_fixed_safety_ms` / `t_fixed_features_ms` /
  `t_fixed_region_locate_ms`（features 子区间）/ `t_fixed_cost_ms` /
  `t_fixed_prepare_ms`（prepare 墙钟）。
- Region locate **不**在总和里与 features 再加一次。
- `t_spec_validate_ms` 为投机线程 prepare 墙钟（本包约等于 prepare）。

## AIS focus rows

| query | mode | compile | safety | features | region | prepare | spec_val | spec_exec | n_scan | loc_calls | loc_hits |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ais_topk_dtw_2week | full | 2 | 26649 | 7 | 5 | 26660 | 26660 | 1090 | 8064 | 8064 | 0 |
| ais_topk_frechet_wide | full | 1 | 5856 | 2 | 2 | 5860 | 5861 | 1468 | 3456 | 3456 | 0 |
| ais_st_port_focus | full | 0 | 2379 | 1 | 0 | 2380 | 2381 | 594 | 256 | 256 | 0 |
| ais_st_control_small | full | 0 | 264 | 0 | 0 | 265 | 266 | 124 | 280 | 280 | 0 |
| ais_st_control_tiny | full | 0 | 0 | 0 | 0 | 1 | 2 | 21 | 12 | 12 | 0 |

## Interpretation（修正先验）

1. **主导阶段是 `t_fixed_safety_ms`（PlanValidator），不是 Final features / Region locate。**  
   DTW：safety ≈ 26.6 s，features/region ≈ 7/5 ms。Fréchet：safety ≈ 5.9 s，features ≈ 2 ms。
2. 先前把复合字段 `t_spec_validate_ms` 整段说成“validate/特征提取”时，**不能**再默认归因到 Region 定位；本包测量否定了“特征提取吃掉 24 s”的假设。
3. `n_scan_tasks` 与 safety 同向上涨（DTW 8064、Fréchet 3456）：安全证明/覆盖检查随物理扫描任务规模放大。
4. `compile` 墙钟极短（≤2 ms）——范围展开成本要么很低，要么被计入后续 safety（若需更细可再拆 validator 内部）。

## safety_only 对照（同 stamp 的 AIS E2E）

| query | prepare_mode | safety | features | prepare | notes |
|---|---|---:|---:|---:|---|
| ais_topk_dtw_2week | safety_only | 35796 | 0 | 35796 | features 已去掉；safety 仍主导且有波动 |
| ais_topk_frechet_wide | safety_only | 5662 | 0 | 5664 | 与 full 的 safety 同量级 |

**结论：** 跳过 Final extract 是真实减负，但对 AIS 相似度大查询的 prepare 墙钟不是主杠杆；下一刀应针对 PlanValidator / 覆盖证明路径的可复用，而非再猜特征提取。
