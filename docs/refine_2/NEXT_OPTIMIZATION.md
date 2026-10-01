# 下一步优化方向（依据 docs/refine_2）

按 comparative_refine_2 §10「依次改变、保留对照」：

| 顺序 | 改变项 | 依据 | 成功判据 |
|---|---|---|---|
| 1 | **压缩 PlanValidator / 覆盖证明**（同请求内复用已验证 PhysicalPlan；分析 8k scanTasks 的证明热点） | FULL 分解：DTW safety≈26s，features≈0 | `t_fixed_safety_ms` 显著下降且答案/安全不变 |
| 2 | **缩短独立 LLM 提示与输出**（v7 合法但 llm_ms 升到 3–21s） | 9/9 与 rank_hint 一致却更慢 | 稳态 llm_ms 回到 ~1s 量级且合法率不塌 |
| 3 | 校准 `S(q,p)` + 门控（predict_S > κ·critical_path） | 无净胜；短查询 LLM 税盖不住 | 负 G 子集可跳过投机/采纳 |
| 4 | 模型对比（仍本地小模型优先） | 仅在 1–2 后 | 合法率 / regret / E2E 联合不退化 |

停止条件：safety 压完后最快合法候选仍盖不住 `max(L,V+E)` → 不宜扩全量；如实报告边界。
