# saving_residual_validation

> comparative_refine_5.md §5 — `S_actual = E_cbo - E_p`, `residual = S_pred - S_actual`.

## 数据

- 来源：`experiments/opportunity/opportunity-dev-v1/measurements.jsonl`
- OK 实测行：958；含 CBO 选择的查询：79
- 候选对：405
- 特征缺失计数（任一 n_ranges/fetch_chunks/index_rows 为空）：0

线上 FastCost 使用同名驱动的估计值；本残差用普查**实测** ranges/fetch/index 代入同一 log-linear 式，用于开发模型选择，**不是未知 holdout**。

## 总体残差

n=405 mae=1331.4 med=-987.7 p90=2552.2

| 分组 | 残差摘要 |
|---|---|
| other | n=208 mae=1526.7 med=-1258.3 p90=2709.6 |
| td | n=197 mae=1125.2 med=-576.4 p90=2328.0 |

## 误差方向

| 指标 | 值 |
|---|---|
| 正收益上高估次数 | 13 |
| 正收益上低估次数 | 27 |
| 负收益却 S_pred≥200（潜在误采纳） | 1 |

## 门槛依据

- 维持 `min_spec_s=500`、`min_adopt_s=200`（不因想获胜而降到 0）。
- 建议 `saving_uncertainty_ms≈1331`（≈残差 MAE），采纳使用保守量 `S_hat - u`。
- 缺失特征：线上标记 `features_missing`，禁止当作 measured zero 打开门控。
- 九条开发查询不得改名为 holdout。
