# 下一步优化方向（依据 docs/refine 结果）

按 `comparative_refine.md` §10.2「依次改变、每步保留对照」：

| 顺序 | 改变项 | 依据 | 成功判据 |
|---|---|---|---|
| 1 | 压轻 speculative validate / `runFixed` | AIS validate ≫ exec（最高 ~24s） | `t_spec_validate_ms` 显著下降；frechet/dtw await↓ |
| 2 | 拟合 `S(q,p)` 或相对 CBO margin | 机会=candidate_miss；FastCost regret | holdout 组 regret↓；prefer 同轮 S 中位>0 |
| 3 | 采纳门控 `pred_S > κ·critical_path` | 多数查询 S < L+validate | LLM-on 子集系统性负 G 消失 |
| 4 | LLM 输入输出可归因（校准分+原因码） | LLM≈照搬 prefer | 相对纯校准器有增量，或如实记无增量 |
| 5 | 模型/投机参数 | 仅在 1–3 后 | E2E 均值/中位 G 改善且资源成本单列 |

停止条件：压完 validate 后最快合法候选仍盖不住开销 → 暂停调提示，转向执行/计划空间扩展。
