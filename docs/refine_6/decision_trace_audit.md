# decision_trace_audit

> comparative_refine_6.md §5

## 字段分离（不可覆盖）

| 字段 | 含义 |
|---|---|
| `llm_raw_response` | 模型原始文本（截断 512） |
| `llm_parse_status` | ok / invalid / timeout / error |
| `llm_proposed_action` / `llm_action_id` | 门控前原始提议 |
| `llm_proposed_plan_id` | 映射后的候选（若有） |
| `gate_decision` / `gate_reason` | accept / reject / keep |
| `final_plan_id` / `final_selection_source` | 最终选择与来源 |
| `speculation_*` | requested/started/completed/used/waste |
| `probe_*` / `t_probe_ms` | 预算内探测账本 |

旧字段 `llm_action` 保留为兼容摘要（可能反映最终调度标签），**不以门控覆盖** `llm_proposed_action`。

## 验收用例类型

1. 主动 NO_ACTION / KEEP
2. 合法提议后被拒（gate_decision=reject）
3. 提议被采用（final_selection_source=llm_adopt）
4. 解析失败（llm_parse_status≠ok）

逐行见 `ais_e2e.jsonl` / `td_e2e.jsonl` 与 `probe_results.jsonl`。
