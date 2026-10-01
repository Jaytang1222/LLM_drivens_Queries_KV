# decision_contract

> comparative_refine_5.md §4.1–4.2

## 字段

| 字段 | 含义 |
|---|---|
| `exec_saving_ms` | 预测 `E_cbo - E_candidate`（ms）；正值=候选执行更快；**未扣** LLM/准备关键路径 |
| `candidate_exec_ms` / `cbo_exec_ms` | 执行时间预测，非实测答案 |
| `saving_uncertainty_ms` | 校准不确定性；`unknown` 不得当作 0 |
| `schedule_mode` | `speculate_candidate` 或 `cbo_parallel` |
| `estimated_extra_critical_path_ms` | 相对 CBO 的额外关键路径估计（含调用量级提示） |

## 输出

`{"choice":"KEEP"}` 或 `{"choice":N}`；N 绑定当次 whitelist；解析后校验范围。

## 决策规则（门控确定性部分）

- 投机：`S_hat >= min_spec_s`（默认 500）且特征非 missing。
- 采纳：`S_hat >= min_adopt_s` 且 `S_hat - uncertainty >= min_adopt_s`。
- 证据不足或预计净收益非正 → KEEP。
- LLM 不能绕过安全验证与硬预算。

提示版本：`cbo_llm_short_v9`。
