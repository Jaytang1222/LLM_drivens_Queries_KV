# feature_contract

> comparative_refine_6.md §6.1

## 执行前估计（在线决策可用）

| 特征 | 来源 | 单位 |
|---|---|---|
| scan_ranges / seek_ranges | FastCost / CostFeaturesExtractor | count |
| fetch_gets / estimated_candidate_chunks | FastCost | count |
| estimated_index_rows / decode_rows | FastCost | count |

缺失记 `unknown`，不得当 0。`BenefitCalibrator.hasMissingFeatures` 会阻断投机。

## 执行后实际（仅离线标签）

| 标签 | 来源 |
|---|---|
| n_ranges / fetch_chunks / index_rows | opportunity measurements |
| t_exec_ms | 独立执行测量 |

训练若只有实测特征，需可重建同快照执行前估计；无法重建的样本仅诊断。

## 校准版本

`calib_loglin_v1`；单侧余量 `KART_CALIB_OVERESTIMATE_P95_MS`（默认≈1100）。
