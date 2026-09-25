# 对比实验真实性、公平性与可运行性审计

- 审计日期：2026-09-25
- 适用范围：`spec/comparative_experiment.md` 定义的 E1（NL → IR）、E2（计划生成 → 选择）、E3（端到端）
- 结论状态：**原生 E3 smoke 已能闭环，FullScan/RBO 语义门禁已补齐；正式公平对比仍未达标。** 当前结果可以用于检查 harness、Oracle 和 HBase 连通性，不能直接写成论文中的四臂公平结论。

## 1. 本次检查得到的事实

### 1.1 已验证通过

| 项目 | 证据 | 状态 |
|---|---|---|
| Java 编译和打包 | `mvn -q -DskipTests package` 成功 | 通过 |
| 关键规划/验证测试 | `PlanValidatorTest`、`PhysicalSafetyAndMetricTest`、`QueryEnginePlannerModeTest`、`BeamSearchAcceptanceTest` 全部通过 | 通过 |
| WSL HBase 连通性 | `./scripts/kart.sh doctor`：connected、manifest 对应表存在、`doctor: OK` | 通过 |
| READY 快照 | `catalog/tdrive_v1_ready.manifest.json` 为 `READY`，47,102 条轨迹、2,724,388 个点 | 通过 |
| Oracle 对齐 | `bound_ir_v1.json` 与 `tdrive_smoke.json` 内容一致；两份 Oracle 各有 24 个答案，manifest 都是 `tdrive_v1_ready` | 通过 |
| 原生 E3 harness smoke | WSL `audit-e2e-native`：3 臂 × 3 trial，`oracle_fail=0`；fullscan/rbo/cbo 的结果文件和汇总均生成 | 通过（仅 smoke） |
| E2 Bao harness smoke | WSL `audit-plan-bao`：3 trial，`plan_ok=1.00` | 通过（仅 smoke） |
| 固定基线实现 | `FullScanArm` 只执行一个 `P_FULL`；`RboFixedArm` 按固定 `TZ → T → Z → H → FULL` 规则执行一个模板 | 代码已修正，需在 WSL 重建后复跑门禁 |
| 固定基线运行时门禁 | WSL `audit-fixed-gate`：FullScan/RBO 各 3 trial，`oracle_fail=0`；每行 `n_candidates=1`、`n_cost_cards=1`、`candidate_search=false`；FullScan `plan_id=P_FULL` | 通过（limit=1 smoke） |
| FullScan 物理计划 | WSL `audit-fixed-artifacts` 的 `physical.json`：4 个 scan task 全部为 `traj_raw_v1`；`candidates.json` 只有 `P_FULL` | 通过（单 query） |

### 1.2 已修正的实现问题

1. `scripts/kart-env.sh` 的默认 Windows 源目录已改为当前仓库 `/mnt/f/Projects/LLM_KV`；WSL 目录、ZooKeeper 和 HBase 目录改为基于 `$HOME`，避免登录名和 home 目录名不一致时失效。
2. E2 计划阶段的 `t_plan_ms` 现在对 `NativePolicyArm`、Bao 和 LLMOpt 记录完整 arm wall path；此前 Bao 的 probe 和 LLMOpt 的 G→S bridge 不会完整反映在汇总的 `t_plan_ms` 中。
3. 选中需要 LLM 的 arm 但 LLM 配置不可用时，harness 现在直接失败；不再静默回退为 RulePolicy 后把回退结果误当成 KART LLM 结果。

这些修改需要在 WSL 重新同步并重建 jar 后才会进入实验运行时：

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh sync
./scripts/kart.sh sync-check
./scripts/kart.sh rebuild
```

## 2. 不能直接当成公平结论的地方

### P0：E3 的 `fullscan` 和 `rbo` 语义（已修正，待运行时复核）

此前注册方式是：

- `fullscan` = `NativePolicyArm(RULE, forcePlanId=P_FULL)`。它仍先执行 BeamSearch、验证所有候选并计算代价，然后强制选择 `P_FULL`。
- `rbo` = `NativePolicyArm(RULE)`。`RulePolicy` 会生成多个候选，最终仍由 SafePlan cost selector 选择，不是“固定模板直接执行”的 RBO。
- `cbo` = `BestFirstPolicy`，目前更接近代价引导的搜索臂。

该问题已经通过 `QueryEngine.runFixed`、`FullScanArm` 和 `RboFixedArm` 修正。固定臂仍使用同一编译器、验证器和 Coordinator，保证安全检查与执行语义一致，但不再先枚举候选再强制选中一个计划。RBO 的公开规则是：同时有 T/Z 时用 `P_TZ`，否则依次使用可用的 T、Z、H，最后使用 `P_FULL`。

**实现与复核要求：**

1. WSL 重建 jar 后，对同一批 query 检查 `fullscan` 的 `n_candidates=1`、`n_validation_reject=0`、`plan_id=P_FULL`，且候选和 cost card 文件各只有一项。
2. 检查 `rbo` 的 `template_plan_id` 与公开规则一致，且 `candidate_search=false`；不得出现由 CostModel argmin 产生的第二个候选。
3. 两臂都必须通过 Oracle；若固定模板被 validator 拒绝，记录失败并修正规则，不能回退到搜索臂。

**验收门禁：** 对任意同一 query，fullscan 的候选数必须为 1、代价卡必须为 1、执行扫描表只能是 `traj_raw_v1`；RBO 的选择必须不读取 CostModel 的 argmin 结果。

### P0：`cold` 不是冷缓存协议

`NativePolicyArm` 在 `warm` 时有一次未计时预热，但 `cold` 只是不主动预热，不会清理 HBase BlockCache、RegionServer page cache、JVM 类加载或连接池。`trials: 3` 连续运行时，第二、第三次天然是热缓存。Bao 和 LLMOpt 也没有统一的 warm-up 实现。

**规范化方案（二选一，必须在 meta 中记录）：**

- `cold`：每个 trial 重启独立 RegionServer/HBase 或执行经过验证的 cache flush，并等待健康检查通过；每个 arm/query/trial 使用独立进程。
- `warm`：固定预热次数（建议 2 次），之后固定采样次数（建议至少 5 次），预热不计时；所有 arm 使用相同协议。

当前 harness 会在 `meta.json` 中明确写出 `cache_enforced=false`；它没有清空 HBase BlockCache 或 OS page cache。不能控制缓存时，正式主表应使用 `--cache cold --trials 1` 的 first-run 数据；现有 `trials=3` 结果只能标为 mixed-cache smoke，不能称为 cold P50/P95。需要 P50/P95 时使用 `--cache warm --trials 5` 或更高，并确保所有臂使用同一协议。

### P0：E2 的计时边界必须保持一致

规范要求 `t_plan` 从“生成开始”到“最终 SafePlan 选出”。当前代码已修正 E2 wrapper 的汇总字段，但仍要注意：

- Bao 的 probe、候选排序和最终选择都必须进入 `t_plan`。
- LLMOpt 的 Python Generator、Selector、Java 验证和 plan_id 强制落地都必须进入 `t_plan`。
- 写 artifact、打印日志、HBase 执行不得进入 `t_plan`；若开启 `--keep-artifacts`，应单独记录 artifact 写入时间。
- E3 的 `t_e2e=t_plan+t_exec`，NL 解析不得计入 E3。

**验收：** 每个 plan row 需要有 `plan_start/plan_end` 或等价的单调时钟证据；`t_plan_ms` 不能小于 bridge/selector 的总耗时。

### P1：E1 外部方法是“移植臂”，不是原论文完整系统

DIN-SQL、SAG、Bao、LLMOpt 的桥接说明已经披露了 SQL/Mongo/PG 被 DraftIR、KART plan family 和 KART validator 替代，这种设计可以做“控制流程移植比较”，不能写成原系统的原生复现实验。

正式报告必须同时给出：

- upstream URL、commit、入口文件、桥接文件的 SHA256；
- 保留了哪些控制流程、删除了哪些执行器/权重/模型；
- 使用的是同一通用 LLM endpoint，还是作者 checkpoint；
- 每个 arm 的调用次数、重试次数、输入/输出 token 和失败原因。

`experiments/adapters/clone-third-party.sh` 只负责浅克隆和记录当前 HEAD；正式运行前必须人工检查 `experiments/third_party/commits.json` 与桥接代码中的入口文件确实存在，不能只依赖目录存在。

### P1：E1 的 adapter 进程开销和重试策略还需拆分

DIN/SAG 每条 query 都通过 Python 子进程启动；KART 是同一 JVM 内调用。若把进程启动、Python import、首次 TLS 连接全部算入 `t_parse`，这是端到端服务开销比较，不是纯算法解析速度比较。

建议同时报告：

1. `t_parse_total`：用户实际经历的完整时间；
2. `t_parse_model`：LLM 请求和解析/校验时间；
3. `t_adapter_startup`：子进程启动与 import 时间。

所有臂还要固定最大尝试次数、最大 token、超时和 self-correction 次数。Python shared client 的 JSON-mode fallback 必须把第二次请求计入 calls/tokens；否则失败重试会被低估。

### P1：KART LLM 臂必须区分“LLM 成功”和“Rule fallback”

`LlmProposalPolicy` 在 LLM 异常、非法动作或无 LLM 时会走 RulePolicy。正式结果必须记录 `llm_requested`、`llm_calls`、`fallback_reason`；只要出现 fallback，就应单独归类为 `kart-rule-fallback`，不能与 KART LLM 主臂合并。

### P1：样本量仍是 smoke 级别

当前 E1 只有 5 个正例 + 5 个拒绝例，E2/E3 是 24 个固定查询，且当前 suite 默认每格只有 3 trial。它足以做 harness/Oracle 检查，不足以支持稳定的 P95、显著性或泛化结论。

完善 workload 时至少按以下维度分层并在汇总中分别报告：

- T、Z、H、TZ、TH、ZH、TZH；
- 小/中/大选择率；
- 空结果、边界命中、半开时间端点、空间边界包含；
- DTW、Fréchet、Hausdorff 和不同 k；
- 至少一组 RBO 容易误选的查询；
- E1 的可支持、应拒绝、需要澄清三类。

查询应固定 seed、版本和 SHA256；正式数据集不能只从成功案例挑选。

### P1：单节点和混合 HBase classpath 必须进入结论边界

当前 WSL `doctor` 显示客户端 2.2.3，但 HBase cluster status 为 2.1.2；环境锁定文档已记录这是安装 classpath 混入旧 jar 的结果。结论只能写：

> 在当前单节点、混合 classpath 的 HBase 环境中……

不能外推到干净的 HBase 2.2.3 集群、多 RegionServer 或生产负载。正式 run 的 `meta.json` 还应写入 `doctor` 输出摘要、hostname、JDK、HBase client/server 版本和 RegionServer 数量。

## 3. 真实性与公平性执行规范

### 3.1 运行前冻结

1. 冻结仓库 commit、`config/planner.yaml`、manifest、layout、regions、workload 和 Oracle SHA256。
2. 校验 manifest 为 `READY`，并确认 Oracle 的 `manifest_id`、`semantics_version` 与 workload 一致。
3. 运行 `doctor`，确认 ZooKeeper、HBase、目标表和 RegionServer hostname 均可解析。
4. 运行 `hbase-evidence`，把混合版本和表范围写入结果目录。
5. 克隆并冻结第三方 commit；禁止在同一批实验中自动拉取新 HEAD。
6. LLM arm 使用同一 endpoint、model、temperature、timeout、max token；密钥只从 `.env` 读取，不能写入 meta。

### 3.2 E1

- 同一冻结 NL 顺序；建议按 query 随机化并对所有 arm 使用相同顺序。
- 同一逻辑目录、DraftIR schema、早拒绝门禁和 Binder。
- 主表使用单轮协议；澄清/多轮作为单独附录。
- 正确性先判 `reject_expected`、schema validity、Binder 成功，再判 Oracle/EX；拒绝误报和漏报分开统计。
- 把 total/model/adapter 三类时间和 calls/tokens 一起写入 JSONL。

### 3.3 E2

- 输入只用金标 BoundIR；任何 E1 输出不得进入 E2 主表。
- 所有臂共享相同 candidate family、SearchBudget、CostModel、manifest 和执行器。
- Bao/LLMOpt 的移植臂必须把“生成 → 选择 → 验证”完整计入 `t_plan`，并披露没有使用作者 PG 扩展/训练权重。
- `plan_ok` 只表示产生 SafePlan，不把 Oracle 正确率当成 E2 主指标；执行质量放到 E3。

### 3.4 E3

- 输入只用金标 BoundIR，起点是 BoundIR 已就绪，终点是返回 ID/Top-K。
- FullScan、RBO、CBO、KART 只有在上面 P0 语义门禁通过后才能进入同一主表。
- 统计成功率、失败率、超时和 `RESOURCE_EXHAUSTED`；latency 只能在成功子集上给 P50/P95，同时披露分母。
- 输出 `t_plan_ms`、`t_exec_ms`、`t_e2e_ms`，并保留 ranges、index rows、fetch chunks、bytes、DTW cells。
- cold/warm 分开跑，不能混合；主结论固定一种协议，另一种放附录。

## 4. 建议的直接运行顺序

以下命令必须在 WSL canonical workspace 执行，不要在 PowerShell 直接运行 HBase bench：

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh sync
./scripts/kart.sh sync-check
./scripts/kart.sh rebuild
./scripts/kart.sh doctor
./scripts/kart.sh hbase-evidence
bash experiments/adapters/clone-third-party.sh
```

先跑不依赖 LLM 的原生 smoke：

```bash
./scripts/bench-e2e.sh \
  --run-id e3-native-smoke \
  --arm fullscan,rbo,cbo \
  --cache cold --trials 1 --limit 3
```

可重复 warm-cache 采样：

```bash
./scripts/bench-e2e.sh \
  --run-id e3-native-warm \
  --arm fullscan,rbo,cbo,kart \
  --cache warm --trials 5
```

再跑 E2：

```bash
./scripts/bench-plan.sh \
  --run-id e2-plan-smoke \
  --arm bao,kart \
  --limit 3
```

最后跑 E1 和综合流程：

```bash
./scripts/bench-parse.sh \
  --run-id e1-parse-smoke \
  --arm kart,din-spider,din-bird,sag

./scripts/bench-all.sh \
  --run-id all-smoke \
  --parse-arm kart,din-spider,din-bird,sag \
  --plan-arm bao,llmopt,kart \
  --e2e-arm fullscan,rbo,cbo,kart
```

每次运行后必须检查：

```text
experiments/results/<run_id>/meta.json
experiments/results/<run_id>/parse.jsonl
experiments/results/<run_id>/plan.jsonl
experiments/results/<run_id>/e2e.jsonl
experiments/results/<run_id>/summary.md
```

如果 `meta.json` 没有 workload/oracle SHA256、manifest、git commit、工作树 dirty 状态、JDK/主机、HBase site/evidence SHA256、LLM model、third-party commits、cache 协议和 trial 数，运行只能标记为不可复现。

## 5. 正式实验通过条件

- [ ] WSL `sync-check`、`doctor`、HBase hostname 和 manifest READY 全部通过。
- [x] FullScan/RBO 已改为语义正确的独立臂；WSL 重建后的运行时门禁仍需复核。
- [ ] `cold`/`warm` 协议可证明，且所有 arm 一致。
- [ ] E1/E2 的 total/model/adapter 时间边界和 calls/tokens 可审计。
- [ ] LLM fallback 不与主臂混合；外部臂的 commit 和 intentional changes 已记录。
- [ ] workload 覆盖分层、边界、空结果和失败样例，样本量不再是 smoke 级别。
- [ ] Oracle 全部通过；失败率、超时和成功子集分母写入汇总。
- [ ] `meta.json` 可重放，且结论明确限定在单节点混合 classpath 环境。

在这些门禁全部通过前，当前系统的正确表述是：**“对比实验 harness 与 Oracle smoke 可运行，公平性整改进行中。”**
