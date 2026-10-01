# answers_six_questions

> comparative_refine.md §11 — 基于本包实测（`refine-20261001-155001` E2E + `opportunity-refine-*-20261001-160034` 上界）。开发诊断，非全量未知测试集结论。

## 1. 当前候选池中，多少查询存在足以覆盖 LLM 成本的机会？

| 集合 | 稳定机会（3/3 更快） | 中位 S | 同轮观测 L（hybrid） | 能否盖住 LLM |
|---|---:|---:|---:|---|
| T-Drive 4q refine census | **4/4** | 58–451 ms | ~0.3–1.8 s | **串行几乎不能**；需重叠且 L 压到低于 S |
| AIS 5q refine census | **4/5**（control_small 无） | 39–2881 ms | ~0.3–1.4 s | frechet/dtw 的 S（~1.5–2.9 s）**有可能**；但 validate 税常更大 |

**结论**：机会存在，但“足以覆盖当前 LLM+validate 临界路径”的查询很少；AIS 大机会查询被 validate 等待吃掉。

## 2. 机会主要来自 CBO 候选遗漏还是成本排序失准？

- 上界标签均为 **`candidate_miss` / search_miss**：CBO safe 未含更快计划（多为 `P_T`；本轮 `a2_tz_hard_d` 稳定赢家也可为 `P_Z`）。
- 在线 v6 FastCost：T-Drive 多条 `prefer=P_Z`，与部分实测一致、与部分历史 `P_T` 机会不一致。
- **主因是候选遗漏**；**次因是 novel 间相对排序不稳/失准**。

## 3. AIS 的长等待究竟花在验证、执行还是调度/清理？

本包投机侧线程拆分（`t_spec_validate_ms` / `t_spec_exec_ms` / `t_spec_await_ms`）：

| query | validate | exec | await | llm |
|---|---:|---:|---:|---:|
| ais_topk_frechet_wide | **4579** | 543 | 4029 | 1093 |
| ais_topk_dtw_2week | **24453** | 1034 | 24630 | 857 |
| ais_st_port_focus | **1746** | 242 | 551 | 1437 |

**结论：长等待主要在 `runFixed`/validate（编译+校验+成本特征），不是 Coordinator 扫描执行，也不是清理。**

## 4. 校准后的排序是否在未知查询上减少 regret？

**尚未**：未交付 holdout 隔离训练的 ranker/回归。仅有 FastCost 绝对分排序。

## 5. LLM 是否在已有排序之上产生额外决策价值？

本包行上 `selected` 基本等于 FastCost `prefer`。**未观察到 LLM 相对排序器的额外正向决策价值**；LLM-on 成本仍全额计入。

## 6. 收益是否转化为真实 E2E 改善，代价是什么？

见 `paired_e2e_summary.md`：**本包开发配对 E2E 未净胜 CBO**（T-Drive 4/4 负，AIS 5/5 负）。

代价：LLM 延迟；投机 validate（AIS 可达数十秒）；选错时的浪费工作；Ollama+HBase warm 资源（计划决策缓存关闭）。
