# feedback_calibration

> comparative_refine_6.md §6 — feature contract uses census measured ranges/fetch/index
> into the same log-linear predictor as online FastCost drivers (not a holdout).

## 数据

- 来源：`experiments/opportunity/opportunity-dev-v1/measurements.jsonl`
- OK 行：958；候选对：405
- 特征缺失计数：0
- 实际值仅作离线标签；不泄漏到同次在线决策

## 残差 `r = S_pred - S_actual`

总体：n=405 mae=1331.4 med=-987.7 p95=1128.3

| 分组 | 残差摘要 |
|---|---|
| other | n=208 mae=1526.7 med=-1258.3 p95=960.3 |
| td | n=197 mae=1125.2 med=-576.4 p95=1169.1 |

## 单侧高估余量（预先固定分位）

| 项 | 值 |
|---|---|
| 分位水平 | p95（事先固定，全体残差上侧） |
| 全体残差 p95 | 1128.3 ms（用于 `KART_CALIB_OVERESTIMATE_P95_MS`） |
| 正高估子集 p95（诊断） | 7428.7 ms |
| 经验覆盖率 `r ≤ margin` | 384/405 = 0.948 |
| MAE | 1331.4 ms |

**注意：** 经验分位数不是无条件概率保证；同查询候选/重复应同组留出。开发集不可当未知 holdout。

## 机会与误采纳（门槛 200 ms，未扣 uncertainty）

| 指标 | 值 |
|---|---|
| 正机会对 | 40 |
| 正机会召回 (S_pred≥200) | 7/40 |
| 负收益误采纳 (S_actual≤0 ∧ S_pred≥200) | 1 |

## 特征契约（摘要）

见同目录 `feature_contract.md`。线上用 FastCost 估计特征；本报告用普查实测代入同式，用于选择单侧余量，**未完整计入线上特征估计误差**。

建议环境变量：`KART_CALIB_OVERESTIMATE_P95_MS=1128`
