# 消融正式跑数发现（2026-09-26）

- 状态：**跑数门禁已完成**（不含三次独立 cold 重复、不含独立进程隔离）
- 冻结 commit：`6ef179445512336b2f41259aa331589476dd1d74`（`git_worktree_dirty=false`）
- 机器可读汇总：[`ablation-formal-findings-2026-09-26.json`](ablation-formal-findings-2026-09-26.json)
- 结果目录（WSL）：
  - `experiments/results/abl-main-cold-20260926`
  - `experiments/results/abl-main-warm-20260926`
  - `experiments/results/abl-risk-no-coverage-20260926`

## 1. 环境限定（必须写入任何公开结论）

> 在当前**单节点、混合 classpath** 的 HBase 环境中（client `2.2.3` / cluster `2.1.2`，`region_server_count=1`）……

不得外推到干净同版本集群或多 RegionServer。`sequential_arm_cache_carryover=true` 仍成立；本次**未**做三次独立 cold 重复，也**未**按臂拆独立进程。

## 2. 跑数门禁核对

| Run | bound | trials | cache_enforced / protocol | pair | Oracle |
|---|---:|---:|---|---|---|
| cold 主表 | 65 | 1 | `true`（HBase+OS `520/520`，`drop_caches_ok_helper`） | `true` | 504/520（每因子 63/65） |
| warm 附录 | 65 | 5 | `warm`，预热轮换 `rotate_by_query_and_warmup_pass` | `true` | 2520/2600 |
| 风险 `no_coverage` | 65 | 1 | `true`（`65/65`） | `false` | 63/65；artifacts 已保留 |

- 主表无 `unsafe=true` 行；`uncalibrated.cost_calibrated=false`。
- 每行均有 `llm_calls` / `tokens_in` / `tokens_out`（无 LLM 为 JSON `null`）。
- 延迟只在 **成功子集** 上报告；失败必须披露。

## 3. Cold 主表：成功子集延迟（ms）

分母：每因子 `n=65`，成功 `63`（失败均为 `t_large_3`、`empty_far_3` 的 `timestamp before epoch`）。

| factor | oracle_ok | fail_rate | t_e2e p50 | t_e2e p95 | t_plan p50 | t_exec p50 |
|---|---:|---:|---:|---:|---:|---:|
| full | 63/65 | 0.031 | 3276 | 32377 | 2808 | 443 |
| no_llm_rule | 63/65 | 0.031 | 723 | 31885 | 15 | 672 |
| no_llm_best_first | 63/65 | 0.031 | 498 | 14914 | 17 | 461 |
| llm_direct | 63/65 | 0.031 | 1353 | 17827 | 768 | 672 |
| no_fast_cost | 63/65 | 0.031 | 3957 | 32622 | 3264 | 556 |
| no_final_cost | 63/65 | 0.031 | 4910 | 34581 | 2460 | 1724 |
| single_index | 63/65 | 0.031 | 2864 | 17117 | 1568 | 1474 |
| uncalibrated | 63/65 | 0.031 | 2863 | 31917 | 2357 | 593 |

## 4. Cold 配对相对 Full（同一 query，双方均 Oracle 成功；n=63）

`ratio = factor / full`；`delta = factor − full`（ms）。ratio&lt;1 表示该因子更快。

| factor | paired_n | e2e ratio p50 | e2e delta p50 | 解释要点 |
|---|---:|---:|---:|---|
| no_llm_rule | 63 | 0.31 | −1727 | 去掉 LLM 提案后规划极快；正确性在成功子集上与 Full 同为 63/65 |
| no_llm_best_first | 63 | 0.09 | −2798 | 同上，Best-first 无 LLM |
| llm_direct | 63 | 0.60 | −924 | 一次吐计划通常比动作搜索更快；需与正确性/候选质量一起叙述 |
| no_fast_cost | 63 | 1.06 | +244 | 关闭 Fast Cost 后 e2e 略慢 |
| no_final_cost | 63 | 1.34 | +1119 | 关 Final Cost（plan_id 选取）回退最明显 |
| single_index | 63 | 0.94 | −284 | 本 workload 上中位未必更慢；联合索引收益需按 family 分层看 |
| uncalibrated | 63 | 0.92 | −312 | 未校准系数未表现为系统性变慢；不能据此否认校准价值 |

**论文表述注意：** `no_llm_*` 更快不等于“可去掉 LLM”；它们验证的是 LLM 提案相对规则/Best-first 的**增量成本与行为**，正确性分母相同。

## 5. 失败与风险臂（CoverageCheck）

### 5.1 Cold 主表失败（16 = 8 因子 × 2 query）

| query_id | 错误 | 涉及因子 |
|---|---|---|
| `t_large_3` | `IllegalArgumentException: timestamp before epoch` | 全部 8 个安全因子 |
| `empty_far_3` | 同上 | 全部 8 个安全因子 |

这是**运行时时间戳/epoch 校验**问题，不是 Oracle 语义分歧，也不是某个 leave-one-out 特有错误。

### 5.2 风险臂 `no_coverage`（63/65）

| query_id | risk | Full cold | 分类 |
|---|---|---|---|
| `t_large_3` | fail（timestamp） | fail（同一错误） | `runtime_timestamp_epoch_bug` |
| `empty_far_3` | fail（timestamp） | fail（同一错误） | `runtime_timestamp_epoch_bug` |
| 其余 63 | ok | ok | 未暴露 Coverage 漏查 |

**结论：** 本 workload **没有**提供“关闭 CoverageCheck → 漏查/假阴性”的证据。不得写成“CoverageCheck 不重要”；只能写：在现有 65 条 BoundIR 上，风险臂未触发可归因于覆盖检查的漏查，失败与 Full 共享 timestamp bug。artifacts 目录已保留供复核。

## 6. Warm 附录

- `abl-main-warm-20260926`：`trials=5`，`warmup_arm_order_policy=rotate_by_query_and_warmup_pass`
- Oracle 2520/2600；失败模式与 cold 同类（timestamp）占主导
- 正式延迟主结论若选 cold，则 warm 仅附录；二者不可混进同一主表

## 7. 仍明确不做 / 未做

1. 三次独立 cold 重复（多样本 cold P50/P95）
2. 按臂独立进程以消除 `sequential_arm_cache_carryover`

## 8. 诚实表述模板

**可用：**
“在单节点混合 classpath HBase 上，对冻结 BoundIR 65 条完成 leave-one-out 消融；cold 主表 `cache_enforced=true`，成功子集 63/65 上报延迟与相对 Full 的配对比；风险臂未暴露 Coverage 漏查，失败归因于共享 timestamp 运行时错误。”

**不可用：**
“OS-cold 多样本 P50 已由三次独立重复证明 / 已消除 RegionServer 缓存继承 / CoverageCheck 已被本实验证伪或不重要 / 可外推多 RS 生产集群。”
