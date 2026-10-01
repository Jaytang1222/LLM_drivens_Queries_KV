# summary — comparative_refine_6

## 闭环状态

| 阶段 | 状态 |
|---|---|
| A 决策证据链 | 完成：`llm_proposed_*` / `gate_*` / `final_*` / `speculation_*` / `probe_*` |
| B 执行反馈校准 | 完成：残差 MAE≈1331；上侧 p95=1128 用于 uncertainty |
| C 预算内探测 | 实现 `JointCandidateProbe`；本轮 E2E **未触发**（模型未提 PROBE） |
| D 受限动作 LLM | `cbo_llm_action_v10`；合成 format 8/8，语义偏好 `PROPOSE_P_T` |
| E2E | **0 胜 / 9 负**；2 次 `llm_adopt`（exec 更快，E2E 仍负） |

## 关键结论

可解释决策闭环已落地；在 Ollama `qwen2.5:1.5b-instruct`、逐条 LLM-on、indexed 验证下，**仍无相对原生 CBO 的可重复 E2E 净收益**。停止扩大查询集；负结果有效。

详见 `answers_six.md`、`timing_plan_exec.md`、`RUN_CONFIG.md`。
