# calibration_dataset

> comparative_refine_4.md §5 — 标签与在线特征。

## 标签来源

- 主标签：`experiments/opportunity/opportunity-dev-v1/measurements.jsonl` 实测 `t_exec_ms`
- 独立/并行标签：本轮 `execution_isolation.jsonl`（fixed-plan alone vs parallel LLM）
- 保留正/负/无收益；超时缺失不计为赢家

## 在线特征（执行前）

FastCost `CostCard.features`：

- `scan_ranges` / `seek_ranges`
- `fetch_gets`（或 `estimated_candidate_chunks` 回退）
- `estimated_index_rows` / `decode_rows`

模型：`pred = b0 + bR·log1p(ranges) + bF·log1p(fetch) + bI·log1p(index)`  
`S_hat = pred(cbo) - pred(p)`（截距在差分中抵消）

## 系数（开发拟合，非 holdout）

| coef | value |
|---|---|
| b0 | -4368.954 |
| b_ranges | 616.587 |
| b_fetch | 211.029 |
| b_index | 142.476 |
| n | 958 |
| MAE (abs exec) | ≈1043 ms |

版本串：`calib_loglin_v1`。九条开发查询不得改名为未知 holdout。
