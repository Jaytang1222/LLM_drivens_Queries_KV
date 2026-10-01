# validator_breakdown

> comparative_refine_3.md §4 — PlanValidator 内部分解（已实施/已测）。

Indexed 与 legacy 同套 AIS 开发查询、safety_only prepare、speculate=1。

| query | algo | safety | coverage | probe | decode | M_probes | decodes | compares | n_scan | legacy_safety | legacy_decodes | legacy_compares |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | indexed | 1 | 0 | 0 | 0 | 2728 | 560 | 24477 | 280 | 409 | 680080 | 679800 |
| ais_st_control_tiny | indexed | 0 | 0 | 0 | 0 | 12 | 24 | 58 | 12 | 1 | 168 | 156 |
| ais_st_port_focus | indexed | 4 | 3 | 3 | 0 | 15376 | 512 | 138393 | 256 | 2285 | 3951888 | 3951632 |
| ais_topk_dtw_2week | indexed | 56 | 28 | 5 | 44 | 8064 | 16128 | 112770 | 8064 | 30533 | 65044224 | 65036160 |
| ais_topk_frechet_wide | indexed | 23 | 9 | 0 | 11 | 3456 | 6912 | 44290 | 3456 | 8227 | 11950848 | 11947392 |


## 解读

- 若 indexed 下 `range_decode_count ≈ n_scan`（或 2×n_scan 含 physical），而 legacy 下 decode ≫ n_scan：B1 生效。
- 若 `range_compare_count` 从 O(M·N) 降到约 O(M log N)：B2 生效。
- `t_coverage_ms` / `t_coverage_probe_ms` 应主导 safety（与 refine_2 一致）。
