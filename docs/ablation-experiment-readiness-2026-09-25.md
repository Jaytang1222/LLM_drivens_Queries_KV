# KART 消融实验 readiness（门禁结论指针）

- 最近复核：2026-09-27（文件名保留 2026-09-25）
- **当前起步阶段验收：通过，可启动诊断性消融比较。** 新修复的时间边界已通过单测与实际 HBase 小批量复核；当前代码尚无新的 65 条全量结果，不能沿用旧批次延迟作为修复后的结果。

详细历史审计与 checklist 见 [`ablation-refine.md`](ablation-refine.md)。
旧冻结版本 `6ef1794` 的 cold/warm/风险汇总、成功子集延迟与配对 delta、失败归因见 [`ablation-formal-findings-2026-09-26.md`](ablation-formal-findings-2026-09-26.md)（机器可读：[`ablation-formal-findings-2026-09-26.json`](ablation-formal-findings-2026-09-26.json)）。

## 2026-09-27 本轮验收

- WSL 已重建当前 jar；`AblationHarnessTest`、`ZOrderAndTimeBucketTest`、`PlanValidatorTest` 通过；`kart.sh doctor` 与 `sync-check` 通过。
- 对旧批次失败的 `t_large_3`、`empty_far_3`，当前代码的 Full + 7 个安全因子共 16/16 行通过 Oracle，不再出现 `timestamp before epoch`；`no_coverage` 单独 2/2 通过 Oracle，并保留 artifacts。
- 两条 `llm_direct` 行均标记为 `llm_direct-rule-fallback`，不能纳入纯直接规划臂的延迟统计。本轮小批量是脏工作树、2 条查询、诊断性缓存协议（`cache_enforced=false`）；其耗时不作正式比较。

## 当前未完成

1. 在当前修复版上重跑完整 65 条、核对每因子 Oracle 分母与回退分层；旧批次的 63/65 和延迟表只代表 `6ef1794`。
2. 若要将新结果作为冻结性能表，再使用干净提交和可核对的统一缓存协议；这不影响现在启动诊断性比较。
3. `scripts/kart-env.sh` 的内置 `KART_SRC` 默认值已统一为 `/mnt/f/Projects/LLM_KV`；正式运行前仍需执行同步检查。

## 2026-09-26 旧冻结批次（历史记录）

- BoundIR 65；Full + 7 安全因子；风险 `no_coverage` 单独 + artifacts
- cold `cache_enforced=true`（`abl-main-cold-20260926`）；warm trials=5 附录
- clean tree 冻结 commit `6ef1794`
- 成功子集表 + 相对 Full 配对；失败均为共享 `timestamp before epoch`（非 Coverage 漏查）

## 旧批次仍未做（不作为当前起步验收阻塞）

1. 三次独立 cold 重复
2. 按臂独立进程以消除 `sequential_arm_cache_carryover`

## 表述边界

单节点混合 classpath（client 2.2.3 / cluster 2.1.2）。不得把 `no_llm_*` 更快写成可部署优势；不得把风险臂未漏查写成 CoverageCheck 不重要。
