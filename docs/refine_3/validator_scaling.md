# validator_scaling

> comparative_refine_3.md §6 — 同输入旧/新验证算法墙钟与计数（已测）。

| query | n_scan | M_probes | safety_legacy | safety_indexed | spec_val_leg | spec_val_idx | dec_leg | dec_idx | cmp_leg | cmp_idx |
|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | 280 | 2728 | 409 | 1 | 410 | 3 | 680080 | 560 | 679800 | 24477 |
| ais_st_control_tiny | 12 | 12 | 1 | 0 | 2 | 2 | 168 | 24 | 156 | 58 |
| ais_st_port_focus | 256 | 15376 | 2285 | 4 | 2286 | 6 | 3951888 | 512 | 3951632 | 138393 |
| ais_topk_dtw_2week | 8064 | 8064 | 30533 | 56 | 30538 | 62 | 65044224 | 16128 | 65036160 | 112770 |
| ais_topk_frechet_wide | 3456 | 3456 | 8227 | 23 | 8229 | 66 | 11950848 | 6912 | 11947392 | 44290 |


状态：**已测**（开发 AIS 包，1 trial）。不是合成 1k–8k 曲线；合成随机差分见单元测试 `RangeCoverageIndexTest`。
