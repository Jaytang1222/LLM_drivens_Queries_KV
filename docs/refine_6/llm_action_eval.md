# llm_action_eval

> comparative_refine_6.md §8 — 受限动作提议合成测试（无 DB）。

## 汇总

| 指标 | 值 |
|---|---|
| format_ok | 8/8 |
| soft_ok（落在合理动作集） | 3/8 |
| gold_ok（精确金标） | 3/8 |
| 顺序一致性 (order_a vs order_b) | True |
| 负例避免 PROPOSE_P_Z | True |
| model | `qwen2.5:1.5b-instruct` |

## 逐例

| id | kind | gold | action | format | soft | gold_ok | ms |
|---|---|---|---|---|---|---|---|
| clear_time | propose_time | PROPOSE_P_T | PROPOSE_P_T | True | True | True | 1838 |
| no_opp | no_action | NO_ACTION | PROPOSE_P_T | True | False | False | 706 |
| uncertain_probe | probe | PROBE_JOINT_CANDIDATES | PROPOSE_P_T | True | False | False | 846 |
| illegal_only_cbo | keep | NO_ACTION | PROPOSE_P_T | True | False | False | 626 |
| order_a | order | PROPOSE_P_T | PROPOSE_P_T | True | True | True | 871 |
| order_b | order | PROPOSE_P_T | PROPOSE_P_T | True | True | True | 839 |
| neg_z | reject_bad | NO_ACTION | PROPOSE_P_T | True | False | False | 850 |
| joint_hint | probe | PROBE_JOINT_CANDIDATES | PROPOSE_P_T | True | False | False | 894 |

## 归因说明

- 合法动作不等于信息增益；若模型总提同一动作，增量应归统计/探测而非 LLM。
- 固定策略对照未新增正式比较臂；本文件仅作协议/语义合成验收。
