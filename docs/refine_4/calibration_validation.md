# calibration_validation

> comparative_refine_4.md §5.4

## 开发集门控（本轮配对）

| 指标 | 值 |
|---|---|
| 查询数 | 9 |
| 正收益采纳 | 0 |
| 负收益误采纳 | 0 |
| KEEP/门控回退行 | 9 |
| min_spec_s_ms | 500 |
| min_adopt_s_ms | 200 |

负收益误采纳：无

AIS Top-K 正收益保留：无（或未采纳异于 CBO）

## 缺口

- 拟合用 opportunity-dev 普查，非分组 holdout；泛化未证明。
- 并行竞争惩罚未单独建模；若 isolation 显示显著竞争，投机标签需重校准。
