# summary — comparative_refine_5

## 已完成

| 阶段 | 状态 | 产物 |
|---|---|---|
| A 决策协议诊断 | 已测 | `decision_contract.md`, `decision_synthetic_eval.*` |
| B 收益残差 | 已测 | `saving_residual_validation.md`（MAE≈1331） |
| C 隔离计时修正 | 已测 | `isolation_timing_audit.*`（`llm_call_ms` vs await） |
| D KEEP/投机调度 | 已接入 | `cbo_parallel` / `speculate_candidate` |
| E 九条配对 | 已测 | `paired_e2e.jsonl`, `timing_plan_exec.md` |

## 主结果

- 提示缺陷：`{"choice":N}` → 模型输出字面 `N`；修正后合成 format_ok=8/8，正例召回 2/4，反例 KEEP 0/4。
- E2E（Ollama `qwen2.5:1.5b-instruct` 重跑）：**0 胜 / 9 负**；正/负采纳均为 0。
- AIS 机会：`S_hat` 仍为正，但常被 `gate_conservative_s`（u=1000）挡回。
- KEEP∥CBO 在 2 条无投机机会查询上生效；短查询仍受 LLM 下界限制。

## 构建

`refine5-20261001-201514` @ `startserver02` / `/home/tyq`；E2E 以 Ollama 重跑为准（见 `identity.txt` / `RUN_CONFIG.md`）。
