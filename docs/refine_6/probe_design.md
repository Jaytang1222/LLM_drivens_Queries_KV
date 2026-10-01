# probe_design

> comparative_refine_6.md §7

## 动作

`PROBE_JOINT_CANDIDATES`（`kart.probe.JointCandidateProbe`）

- keys-only 索引片段扫描（columns 空列表）
- 固定种子分层抽样（shuffle by seed ⊕ plan_id），非仅取前 N RowKey
- 预算：`KART_PROBE_BUDGET_MS`（默认 50）、`KART_PROBE_MAX_ROWS`（默认 200）
- 超限：`ProbeBudgetStop`；记录 `probe_budget_exhausted` 与真实 wall

## 决策

探测更新 prefer 的软证据后，仍由确定性 adopt 门控（`S_hat - u`）决定是否切换。
探测不替代完整结果；暖缓存效果计入方法成本（`t_probe_ms` / probe_wall）。

## 候选配置

开发试过 25/50/100 ms 量级；本轮默认 50 ms。
