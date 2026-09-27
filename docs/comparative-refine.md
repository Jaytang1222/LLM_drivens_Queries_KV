# 对比实验真实性、公平性与可运行性审计

- 审计日期：2026-09-25；依据 2026-09-26 全量运行补充复核
- 适用范围：`spec/comparative_experiment.md` 定义的 E1（NL → IR）、E2（计划生成 → 选择）、E3（端到端）
- 结论状态：**起步阶段的代码逻辑、实验完整性和可运行性门禁已通过，可以开始跑诊断性对比实验。** 这不等于论文级最终对比：外部方法仍是明确标注的 control-flow transplant，warm/dirty/少量 trial 结果不能写成正式性能结论。历史全量诊断运行 `experiments/results/cmp-full-warm-20260925/` 暴露的问题已在代码和局部回归中关闭；正式 E1/E2/E3 批次、可证明 cold，以及干净提交上的锁定差分仍属于后续工作。§6.15 记录本次按“能跑起来并得到基本公平诊断结果”的验收结论。

## 1. 本次检查得到的事实

### 1.1 已验证通过

| 项目 | 证据 | 状态 |
|---|---|---|
| Java 编译和打包 | `mvn -q -DskipTests package` 成功 | 通过 |
| 关键规划/验证测试 | `PlanValidatorTest`、`PhysicalSafetyAndMetricTest`、`QueryEnginePlannerModeTest`、`BeamSearchAcceptanceTest` 全部通过 | 通过 |
| WSL HBase 连通性 | `./scripts/kart.sh doctor`：connected、manifest 对应表存在、`doctor: OK` | 通过 |
| READY 快照 | `catalog/tdrive_v1_ready.manifest.json` 为 `READY`，47,102 条轨迹、2,724,388 个点 | 通过 |
| Oracle 对齐 | `bound_ir_v1.json` 有 65 条 BoundIR，`bound_ir_v1.oracle.json` 有 65 个答案（59 个非空），manifest/semantics 均为 `tdrive_v1_ready`/`point_dtw_v1` | 通过 |
| 原生 E3 harness smoke | WSL `audit-e2e-native`：3 臂 × 3 trial，`oracle_fail=0`；fullscan/rbo/cbo 的结果文件和汇总均生成 | 通过（仅 smoke） |
| E2 Bao harness smoke | WSL `audit-plan-bao`：3 trial，`plan_ok=1.00` | 通过（仅 smoke） |
| 固定基线实现 | `FullScanArm` 只执行一个 `P_FULL`；`RboFixedArm` 按固定 `TZ → T → Z → H → FULL` 规则执行一个模板 | 代码已修正，且已在 WSL 重建后复跑门禁 |
| 固定基线运行时门禁 | WSL `audit-fixed-gate`：FullScan/RBO 各 3 trial，`oracle_fail=0`；每行 `n_candidates=1`、`n_cost_cards=1`、`candidate_search=false`；FullScan `plan_id=P_FULL` | 通过（limit=1 smoke） |
| FullScan 物理计划 | WSL `audit-fixed-artifacts` 的 `physical.json`：4 个 scan task 全部为 `traj_raw_v1`；`candidates.json` 只有 `P_FULL` | 通过（单 query） |
| FullScan/RBO/CBO 运行时复核 | WSL `audit-recheck-cold`：3 query × 3 arm × 1 trial，`oracle_fail=0`；FullScan/RBO 各行 `n_candidates=1`、`n_cost_cards=1`、`candidate_search=false` | 通过（仅首轮 smoke） |
| Bao E2 计时复核 | WSL `audit-plan-bao-recheck`：3 query × 3 trial，`plan_ok=9/9`；每行有 `plan_start_ms/plan_end_ms`，`t_plan_ms=plan_end-plan_start`，`bao_second_search=false`、`bao_pg_weights=false` | 通过（仅 smoke；移植臂） |
| LLMOpt E2 计时复核 | WSL `audit-plan-llmopt-recheck`：3 query × 3 trial，`plan_ok=9/9`；9 行 `t_plan_ms=plan_end_ms-plan_start_ms`，并记录 `llmopt_java_path=beam_search_then_forcePlanId` | 通过（仅 smoke；移植臂，含 Java 搜索路径） |
| E3 臂顺序轮换复核 | WSL `audit-recheck-rotation`：3 query × 3 arm × 2 trial，`oracle_fail=0`；`arm_position` 覆盖 0/1/2，query/trial 间顺序发生轮换 | 通过；不能消除同一 RegionServer 的缓存继承 |
| 正式 workload 定义与全量诊断运行 | BoundIR 65 条、NL 44 条；BoundIR catalog 覆盖 T/Z/TZ/H/TH/ZH/TZH，选择率含 small/medium/large/mixed/empty/boundary；NL 为 supported 16、reject 17、clarify 11；`cmp-full-warm-20260925` 产出 E1 880、E2 975、E3 1300 行 | 全量运行已完成；结果未达正式公平门禁 |
| HBase 环境元数据复核 | 旧 `audit-recheck-rotation` 缺 client 版本和 RegionServer 数；最新 `cmp-full-warm-20260925/meta.json` 已记录 client 2.2.3、cluster 2.1.2、`region_server_count=1` 和版本来源 | 元数据缺失项已补齐；仍是单节点混合版本环境 |
| 第三方入口审计 | 两次运行的 `meta.json` 均记录 upstream commit、入口文件 SHA256、adapter SHA256，`missing_upstreams=[]`、`ready_for_external_arms=true` | 通过可追溯性检查；仍不是原系统复现 |

### 1.2 已修正的实现问题

1. `scripts/kart-env.sh` 的默认 Windows 源目录已改为当前仓库 `/mnt/f/Projects/LLM_KV`；WSL 目录、ZooKeeper 和 HBase 目录改为基于 `$HOME`，避免登录名和 home 目录名不一致时失效。
2. E2 计划阶段的 `t_plan_ms` 现在对 `NativePolicyArm`、Bao 和 LLMOpt 记录完整 arm wall path；此前 Bao 的 probe 和 LLMOpt 的 G→S bridge 不会完整反映在汇总的 `t_plan_ms` 中。
3. 选中需要 LLM 的 arm 但 LLM 配置不可用时，harness 现在直接失败；不再静默回退为 RulePolicy 后把回退结果误当成 KART LLM 结果。
4. `CacheProtocol` 现在按整次运行累计判断 `cache_enforced`，并在 E2/E3 记录 `arm_position`；本轮 18 行 E3 结果证明轮换生效。
5. LLMOpt 的 `t_plan` 现在覆盖 Python G→S 和 Java SafePlan 落地；本轮 9 行均有可核对的 `plan_start_ms`/`plan_end_ms`。
6. workload 已扩展为 65 条 BoundIR 和 44 条 NL；本次审计不再把它们描述为 24 条或 5+5 smoke 数据集。

这些修改已经在本轮 WSL 重新同步、重建 jar 后复核；以后修改 Java 或脚本仍必须在 WSL 重新同步并重建 jar：

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh sync
./scripts/kart.sh sync-check
./scripts/kart.sh rebuild
```

## 2. 不能直接当成公平结论的地方

### P0：E3 的 `fullscan` 和 `rbo` 语义（已修正并通过运行时复核）

此前注册方式是：

- `fullscan` = `NativePolicyArm(RULE, forcePlanId=P_FULL)`。它仍先执行 BeamSearch、验证所有候选并计算代价，然后强制选择 `P_FULL`。
- `rbo` = `NativePolicyArm(RULE)`。`RulePolicy` 会生成多个候选，最终仍由 SafePlan cost selector 选择，不是“固定模板直接执行”的 RBO。
- `cbo` = `BestFirstPolicy`，目前更接近代价引导的搜索臂。

该问题已经通过 `QueryEngine.runFixed`、`FullScanArm` 和 `RboFixedArm` 修正。固定臂仍使用同一编译器、验证器和 Coordinator，保证安全检查与执行语义一致，但不再先枚举候选再强制选中一个计划。RBO 的公开规则是：同时有 T/Z 时用 `P_TZ`，否则依次使用可用的 T、Z、H，最后使用 `P_FULL`。

本轮 `audit-recheck-cold` 已满足以下运行时证据：

- FullScan 的 3 条 query 均为 `P_FULL`，`n_candidates=1`、`n_cost_cards=1`、`n_validation_reject=0`，扫描表均为 `traj_raw_v1`，Oracle 为 3/3。
- RBO 的 3 条 query 均按 `TZ → T → Z → H → FULL` 规则落到 `P_T`，`candidate_search=false`，Oracle 为 3/3。
- CBO 的 3 条 query 保留 2 个候选并选择 `P_T`，Oracle 为 3/3；它仍是搜索臂，不能与固定基线混称。

**后续复核要求：**

1. WSL 重建 jar 后，对同一批 query 检查 `fullscan` 的 `n_candidates=1`、`n_validation_reject=0`、`plan_id=P_FULL`，且候选和 cost card 文件各只有一项。
2. 检查 `rbo` 的 `template_plan_id` 与公开规则一致，且 `candidate_search=false`；不得出现由 CostModel argmin 产生的第二个候选。
3. 两臂都必须通过 Oracle；若固定模板被 validator 拒绝，记录失败并修正规则，不能回退到搜索臂。

**验收门禁：** 对任意同一 query，fullscan 的候选数必须为 1、代价卡必须为 1、执行扫描表只能是 `traj_raw_v1`；RBO 的选择必须不读取 CostModel 的 argmin 结果。

### P0：缓存协议仍未达到正式公平门禁

本轮实现已经会在 E3 的 `cold` 模式逐 trial 尝试清理 HBase BlockCache，并按整次运行累计判断 `cache_enforced`。`audit-recheck-rotation/meta.json` 记录了 18 次 HBase 清理全部成功、18 次 OS drop 均未执行成功，因此 `cache_enforced=false`；这个聚合结果没有被最后一次成功掩盖。`arm_position` 已随 query/trial 轮换，但所有 arm 仍共享同一 RegionServer，`sequential_arm_cache_carryover=true`，后执行的 arm 可能继承前面 arm 的缓存。这个结果只能叫“BlockCache 已清理、OS page cache 未清理的轮换 smoke”，不能叫可证明的 cold latency。

E2 plan 不执行 HBase，因此 `audit-plan-bao-recheck` 不尝试 flush；其 3 trials 只能用于计划计时 smoke，不能从中推导 cold execution 结论。

**正式运行前的选择和验收：**

1. **首轮/冷启动主表：** 设置 `KART_DROP_PAGE_CACHE=1`，确认无密码 sudo 可以成功执行 page-cache flush；运行 `--cache cold --trials 1`，并检查每个结果目录的 `meta.json` 同时满足 `hbase_flush_ok == hbase_flush_attempts`、`os_page_cache_flushed == true`、`cache_enforced == true`。任一条件不满足，就标记为 first-run smoke，不进入 cold P50/P95 主表。
2. **稳定 warm 主表：** 对所有 arm 统一使用 2 次不计时预热、至少 5 次计时样本；每个 arm/query/trial 使用同一进程协议，记录 warmup 次数和采样次数。为降低顺序带来的缓存继承，至少做 arm 顺序轮换或拆分为独立 run，并在汇总中保留顺序。
3. `cold --trials 3` 在当前实现下仍是 mixed-cache smoke；不能把它解释成 3 个独立冷样本，也不能用它支持冷 P50/P95。

### P0：E2 的计时边界必须保持一致（Bao 和 LLMOpt smoke 已复核）

规范要求 `t_plan` 从“生成开始”到“最终 SafePlan 选出”。当前代码已修正 E2 wrapper 的汇总字段，但仍要注意：

- Bao 的 probe、候选排序和最终选择都必须进入 `t_plan`。
- LLMOpt 的 Python Generator、Selector、Java 验证和 plan_id 强制落地都必须进入 `t_plan`。
- 写 artifact、打印日志、HBase 执行不得进入 `t_plan`；若开启 `--keep-artifacts`，应单独记录 artifact 写入时间。
- E3 的 `t_e2e=t_plan+t_exec`，NL 解析不得计入 E3。

**本轮证据：** `audit-plan-bao-recheck` 的 9 行都有 `plan_start_ms`、`plan_end_ms`，且 `t_plan_ms` 与两者差值一致；`plan_ok=9/9`，没有执行阶段，`bao_second_search=false`。Bao 的 probe、候选生成、验证和 surrogate reward 选择均包含在这段计时内。

`audit-plan-llmopt-recheck` 的 9 行同样满足 `t_plan_ms=plan_end_ms-plan_start_ms`，并记录了 `t_bridge_ms` 和 `llmopt_java_path=beam_search_then_forcePlanId`。这证明计时边界已覆盖 Python G→S 和 Java 验证/落地，但也明确了该臂不是“只做 G→S、不跑 KART 搜索”的原生 LLMOpt 复现。

**仍需完成：** 最新全量运行已有逐行计时，但失败请求、JSON 模式重试、Python 子进程启动与 token 缺失仍需按第 6 节细分审计；不能仅凭成功响应的 `llm_calls` 判断实际请求次数。

**验收：** 每个 plan row 需要有 `plan_start/plan_end` 或等价的单调时钟证据；`t_plan_ms` 不能小于 bridge/selector 的总耗时。

### P1：E1 外部方法是“移植臂”，不是原论文完整系统

DIN-SQL、SAG、Bao、LLMOpt 的桥接说明已经披露了 SQL/Mongo/PG 被 DraftIR、KART plan family 和 KART validator 替代，这种设计可以做“控制流程移植比较”，不能写成原系统的原生复现实验。

正式报告必须同时给出：

- upstream URL、commit、入口文件、桥接文件的 SHA256；
- 保留了哪些控制流程、删除了哪些执行器/权重/模型；
- 使用的是同一通用 LLM endpoint，还是作者 checkpoint；
- 每个 arm 的调用次数、重试次数、输入/输出 token 和失败原因。

本轮 `meta.json` 已确认四个 upstream 入口均存在，`missing_upstreams=[]`，并记录了入口和 adapter SHA256；这解决了“只记录目录、不核验入口”的可追溯性问题。仍需在论文中明确这是控制流程移植比较，不能写成 DIN-SQL、SAG、Bao 或 LLMOpt 原生执行器的复现。

### P1：E1 的 adapter 进程开销和重试策略还需拆分

DIN/SAG 每条 query 都通过 Python 子进程启动；KART 是同一 JVM 内调用。若把进程启动、Python import、首次 TLS 连接全部算入 `t_parse`，这是端到端服务开销比较，不是纯算法解析速度比较。

建议同时报告：

1. `t_parse_total`：用户实际经历的完整时间；
2. `t_parse_model`：LLM 请求和解析/校验时间；
3. `t_adapter_startup`：子进程启动与 import 时间。

所有臂还要固定最大尝试次数、最大 token、超时和 self-correction 次数。Python shared client 的 JSON-mode fallback 必须把第二次请求计入 calls/tokens；否则失败重试会被低估。

### P1：KART LLM 臂必须区分“LLM 成功”和“Rule fallback”

`LlmProposalPolicy` 在 LLM 异常、非法动作或无 LLM 时会走 RulePolicy。正式结果必须记录 `llm_requested`、`llm_calls`、`fallback_reason`；只要出现 fallback，就应单独归类为 `kart-rule-fallback`，不能与 KART LLM 主臂合并。

### P1：workload 定义已扩展且已全量运行；确认性验证仍需独立查询集

此前“5 个正例 + 5 个拒绝例、24 个固定查询”的描述已经过时，当前文件实际为：65 条 BoundIR、65 个 Oracle 答案（59 个非空）和 44 条 NL。BoundIR catalog 的 family 分布为 `T=20`、`Z=11`、`TZ=23`、`H=2`、`TH=3`、`ZH=2`、`TZH=4`；选择率包含 small/medium/large/mixed/empty/boundary。NL 按 harness 分类为 supported=16、reject=17、clarify=11。seed、manifest 和 semantics_version 已写在 workload 文件中。

先前 `audit-*` 运行使用 `--limit 3`，只能作为 smoke；后续 `cmp-full-warm-20260925` 已去掉限制，覆盖全部 65 条 BoundIR 和 44 条 NL，各 5 次。这 65/44 条及其结果已经参与问题诊断，今后调算法时不能把它们当成未见过的确认性测试集。新留出集和复杂度分层按第 6 节建立；5 次重复是同一查询的重复测量，不能当作 325/220 条独立查询。

后续汇总仍应按 family、选择率、空结果/边界、DTW/Fréchet/Hausdorff、不同 k 和 E1 的 supported/reject/clarify 分层报告；不能只报告成功样例。

### P1：HBase 版本与 RegionServer 元数据（旧缺失已修，环境边界仍在）

早期 `audit-recheck-rotation` 使用的 fat jar 没有携带可用的 HBase manifest 版本，当时 client 版本与 RegionServer 数为空。后续 `cmp-full-warm-20260925/meta.json` 已写入 `hbase_client_implementation_version=2.2.3`、`hbase_client_specification_version=2.2`、`hbase_client_version_source=classpath_pom_plus_lib_hbase_client_manifest_spec` 和 `region_server_count=1`。这证明最新运行有可审计的版本来源与集群计数，但不等于单个 shade jar 的 manifest 直接证明运行时类来源；正式报告应原样披露该来源字符串及 `hbase_client_jar=target/kart.jar`。

环境锁定文档和 `hbase-evidence` 仍显示：HBase client 依赖为 2.2.3，但 cluster status 为 2.1.2，属于混合 classpath。结论只能写：

> 在当前单节点、混合 classpath 的 HBase 环境中……

不能外推到干净的 HBase 2.2.3 集群、多 RegionServer 或生产负载。后续正式运行仍须保存 `doctor`、`hbase-evidence`、版本来源和 RegionServer 数；若任一值再次缺失，停止将该批结果纳入正式报告。

### P1：运行 provenance（最新全量运行已记录 clean commit）

早期两个 smoke 结果的 `git_commit` 为 `b9e9ac6` 且 `git_worktree_dirty=true`。最新 `cmp-full-warm-20260925/meta.json` 记录 commit `b635465cf6cc1a9824dcf09a6c2db74edc2446aa`、`git_worktree_dirty=false`，说明这一旧缺口已在该批运行中关闭。后续改进会产生新 commit，必须重新冻结代码、workload、Oracle 和模型配置；不要把旧批次的 clean 标记挪用到新批次。

## 3. 真实性与公平性执行规范

### 3.1 运行前冻结

1. 冻结仓库 commit、`config/planner.yaml`、manifest、layout、regions、workload 和 Oracle SHA256。
2. 校验 manifest 为 `READY`，并确认 Oracle 的 `manifest_id`、`semantics_version` 与 workload 一致。
3. 运行 `doctor`，确认 ZooKeeper、HBase、目标表和 RegionServer hostname 均可解析。
4. 运行 `hbase-evidence`，把混合版本和表范围写入结果目录；正式 run 前还要检查 `meta.json.hbase_env` 的 client 版本和 `region_server_count` 非空。
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
git status --porcelain   # 正式 run 前必须为空
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

若要把 cold 结果作为正式主表，先验证 page-cache flush 权限，再运行：

```bash
export KART_DROP_PAGE_CACHE=1
./scripts/bench-e2e.sh \
  --run-id e3-native-cold-enforced \
  --arm fullscan,rbo,cbo,kart \
  --cache cold --trials 1
```

运行后必须确认 `meta.json` 的 `cache_enforced=true`；否则结果仍只能标为 first-run smoke。

上述 smoke 命令带有 `--limit 3`；最新 `cmp-full-warm-20260925` 已全量运行。改进后的新正式运行仍须去掉 `--limit`，并使用新的 run-id 保存独立结果，不得覆盖旧 JSONL。

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
- [x] FullScan/RBO 已改为语义正确的独立臂；`audit-recheck-cold` 已通过运行时门禁（仅 smoke）。
- [x] Bao 和 LLMOpt E2 的 `plan_start_ms/plan_end_ms` 与 `t_plan_ms` 已复核；LLMOpt 的 Java 搜索路径已显式披露。
- [x] 第三方 upstream 入口和 adapter SHA256 已记录并核验；外部方法仍按移植臂披露。
- [ ] `cold`/`warm` 协议可证明，且所有 arm 一致。
- [ ] E1/E2 的 total/model/adapter 时间边界和 calls/tokens 可审计。
- [ ] LLM fallback 不与主臂混合；外部臂的 commit 和 intentional changes 已记录。
- [x] workload 已扩展到 65 条 BoundIR、44 条 NL，`cmp-full-warm-20260925` 已全量运行；新的复杂度留出集和公平整改仍待完成。
- [ ] 修复后，正式 workload 的各安全臂 Oracle 全部通过；失败率、超时和成功子集分母写入汇总。最新全量诊断运行仍有 E3 FullScan 325/325、CBO/KART 各 315/325 的差距，详见第 6 节。
- [ ] `meta.json` 可重放，且结论明确限定在单节点混合 classpath 环境。
- [ ] E1 的 DIN SQL 与 SAG MQL 中间流程、确定性翻译、执行反馈及状态判定达到第 6.2 节门禁；HTTP 402 不计为方法失败。
- [ ] E2 的请求失败、候选覆盖和同池选择可审计；条件式 LLM 与同信息非 LLM 控制臂在独立查询集上完成配对评估。
- [ ] 新复杂度留出集在调参前冻结，原 65/44 条只作为已观察的诊断/回归集。

在这些门禁全部通过前，当前系统的正确表述是：**“对比实验 harness 已完成 44/65 条查询的全量诊断运行，且发现明确的桥接、可用性与索引时间边界问题；尚未达到正式论文级多臂比较门禁。”**

## 6. 2026-09-26 全量结果复核与已确定的整改方案

本节落实已确定的两个方向：**E1 深入移植 DIN/SAG 的 SQL/Mongo 中间流程**；**E2/E3 先修正确性和计量问题，再评估条件式 LLM 规划**。同时重新设计能检验 LLM 增量价值的查询集。2026-09-26 复核时，部分新 adapter、实验臂、留出集和测试代码已出现在未提交工作树中；它们属于实现骨架和局部验收，不等于端到端公平实验已完成。当前具体验收结论见第 6.7 节。正式结果仍须从冻结的干净提交重新运行。

### 6.1 先保存原始证据，避免错误归因

证据目录：`experiments/results/cmp-full-warm-20260925/`，对应 clean commit `b635465cf6cc1a9824dcf09a6c2db74edc2446aa`。E1 为 44 条 NL × 5 次，E2 为 65 条 BoundIR × 5 次 × 3 臂，E3 为 65 条 × 5 次 × 4 臂。E3 使用 2 次进程内预热的 **warm** 协议；单节点、client 2.2.3 / cluster 2.1.2 混合环境。E1/E2 的 `cache` 字段为 cold，但并非已证明的冷执行延迟。旧 JSONL 和 meta 必须只读保存；修复后的 run-id、commit 与结果目录另起。

| 问题 | 已观察到的事实 | 目前可以得出的结论 |
|---|---|---|
| E1 支持类 | KART 60/80；DIN Spider/BIRD 各 0/80；SAG 0/80。DIN 的 160 次支持类失败均出现 DraftIR schema 错误；`experiments/adapters/llm_client.py` 给外部臂的提示用 `"v1"`，实际 schema 只接受 `"1.0"`，且提示漏 `source.entity` 和对象型 `semantics`。SAG 另有 54/80 次支持类记录 HTTP 402。 | **桥接契约缺陷与服务故障足以污染 E1；0/80 不能说明原 DIN/SAG 方法本身无效。** 当前 DIN 只有无 JSON 或显式 unsupported 时触发修复，schema 错误没有进入 DIN 修复环；SAG 的本地 `_gate` 也未检验完整 DraftIR。 |
| E1 拒绝/澄清 | DIN/SAG/KART 的拒绝类均至少有共享早拒绝命中的 40/85；DIN 澄清 0/55，SAG 6/55。 | 40/85 不能归功于方法自身。当前 `ProcessParseArm` 的一段澄清后处理依赖 `item.clarify_expected`，实际状态判定应脱离金标标签。 |
| E2 速度/可用性 | Bao 代理臂 315/325，成功行 `t_plan` 中位数约 15 ms；KART **含** 91 次 `kart-rule-fallback` 共 315/325，纯 KART 224/325，整体成功行规划约 2.95 s。89 次回退标为 `zero_llm_calls`，集中在前几个 trial；失败网络请求目前不一定进入成功响应的 usage。 | Bao 实现按 KART CostCard 和手设族权重选 argmin，**不是训练过的 Bao**。KART 的多次在线 LLM 交互产生显著开销；`zero_llm_calls` 不能直接解释为未尝试请求，须补 HTTP 失败事件。 |
| E3 正确性/延迟 | FullScan 325/325；CBO 与 KART（含 1 次规则回退）均 315/325，失败是相同的 `t_large_3`、`empty_far_3`（每条 5 次），Oracle 都是空集。共同成功的 315 个 query/trial 配对中，KART 执行时间快于 FullScan 244 次，但端到端仅 32 次；对 CBO 端到端 **0 次**更快。167 次与 CBO 选同一计划。 | 正确性差距来自索引时间边界共用路径，**不是 KART 特有的语义失败**。端到端劣势主要包含在线规划开销；其余要按候选覆盖、实际执行和成本估计分解。 |
| 复合查询候选 | CBO 成功行每次产出 2 个候选；KART 为 1–4 个。在 20 个 TZH 成功行中，KART 15 次只产出 `P_FULL`，CBO 20 次选 `P_H`。KART `plan_regret_ms=0` 只相对于各自已找到的候选。 | 需要排查 KART 搜索为何未保留安全索引计划；**不能**用局部 regret=0 证明全局选择最优。当前 65 条中 TZH 仅 4 条独立查询，不足以支撑稳健分层结论。 |

以上统计来自当前 JSONL，不是修复后的预测。五次重复用于衡量同一查询的波动；分析和置信区间应以独立 `query_id` 为配对单位，不能将 325 次视为 325 条独立查询。成功子集的延迟须同时列出成功率、失败类别和分母，避免仅在幸存样例上比较。

### 6.2 E1：深入移植 SQL/Mongo 中间流程，保留真实性边界

**共同输出契约与基础设施先修：**

1. 以 `schemas/draft-ir.schema.json` 为唯一来源给 KART、DIN 和 SAG 提供等价的字段定义、必需项、枚举、时区、`missing[]` 与拒绝/澄清约定；不得再维护错误的手写 `DRAFT_IR_HINT`。保留各方法自己的阶段模板和 few-shot 机制，但各臂看到的任务语义、可用区域和输出约束须一致。用不含测试答案的开发样例测试：正确 JSON 可以过 schema/Binder；错误版本、缺失 `source.entity`、字符串型 `semantics` 必须被明确拒绝并反馈。禁止靠事后静默补字段把不合法输出算成功。
2. 将外部臂的“模型输出 → schema → Binder → `UNSUPPORTED_QUERY` / `NEED_CLARIFICATION` / OK”做成**不读取 `reject_expected`、`clarify_expected` 或 gold 答案**的推理链；金标标签仅在链结束后评分。共享早拒绝命中和方法自己判断的拒绝率分别报告；对支持类/拒绝类分别报告误拒、漏拒和错误澄清。
3. 区分 HTTP 402/配额不足、限流/超时、格式不支持与模型产生无效 IR。Python 和 Java 的 JSON-mode 降级只在服务端明确拒绝 `response_format` 时触发，不能对 402 一概再次请求。每次**尝试**、HTTP 状态、耗时、重试及可用 token 计入审计；服务故障行保留为 infrastructure failure。恢复配额后重跑完整、平衡的 query × arm 区块，不能只补跑失败的 SAG 行再拼入旧表。

**DIN Spider/BIRD 深移植：**

1. 保留上游 schema linking → 复杂度分类 → easy/medium/hard（BIRD 对应分档）→ self-correction 的阶段次序和提示来源，仍使用冻结 upstream commit。不要在“SQL:”位置直接要求最终 DraftIR；先让生成阶段输出**公开、版本化的逻辑 SQL 子集**，再由确定性的 SQL AST 翻译器生成 DraftIR。翻译器不得调用 LLM，也不得读取 gold。
2. 定义只表达 KART 已支持语义的逻辑 `trajectory_point` 视图：同一观测点行的 `trajectory_id`、`vehicle_id`、事件时间、坐标；时间 `[start,end)`、空间闭矩形/注册区域、车辆等值都必须在同一行谓词上成立，再对轨迹 ID 去重。Top-K 的相似度、参考轨迹、`k` 与 tie-break 必须在方言中有显式、可解析的表示；若标准 SQL 无法表达，就写成**明示的 KART 逻辑扩展**，并在报告中单列，不能冒称原 DIN 原生 SQL 能力。
3. 只接受白名单 AST；拒绝聚合 COUNT、连续路径、未知地名、无界 join、物理 RowKey、隐式时间/区域默认值等不可安全翻译结构。对每种可翻译形式编写往返/语义测试：SQL → DraftIR → BoundIR → 共用 KART 执行，结果与 FullScan Oracle 一致。翻译失败记录 `untranslatable_sql`，不得偷偷切到直接 DraftIR 解码。
4. SQL 语法或翻译反馈可以进入 DIN 原有 self-correction，但每轮 prompt、请求、翻译错误、最终 SQL、DraftIR 和耗时都要记录；修复次数和预算事先固定。Spider/BIRD 上游模板中原有的无关数据库 few-shot 示例要审计和固定，不能在看到测试错误后逐题加入对应答案。

**SAG 深移植：**

1. 使用上游 `sag/runtime.py` 的 decode → `A_path`/`A_value`/limit gate → 有界执行反馈 → repair → `select_best`/`cluster_attempts` 流程，先输出**版本化逻辑 MQL pipeline**，再用确定性 MQL AST → DraftIR 翻译器落地。当前 `sag_bridge.py` 只是 DraftIR 本地 `_gate`，不能把它的结果称为完整 SAG。
2. 定义与同一 READY 快照一致的只读点文档视图，明确 `$match` 在 `$group` 之前对同一点施加时间/空间/车辆约束，之后按轨迹 ID 去重；不允许 `$limit`、投影或排序改变 Oracle 所需的集合/Top-K 语义。Top-K 如需 KART 专有阶段，必须与基础 MQL 子集分开报告。白名单之外的 pipeline 标为不可翻译，不能猜测或宽松执行。
3. 为 `WorldAccess` 提供真实、只读的执行/探测后端。可选方案是从同一 HBase 快照构建并核验 Mongo 镜像后使用上游 `MongoWorld`，或实现能忠实执行白名单 MQL 的 HBase-backed `WorldAccess`；两条路线都需证明采样、witness、gate 与反馈看到的集合来自同一冻结快照，且在样本查询上与 FullScan Oracle 等价。**没有可执行 world 时，不能宣称已移植 SAG 的执行反馈。** 若用 Mongo 镜像，解析阶段的探测和物化成本及独立运行环境必须披露，不可把 Mongo 查询延迟与 HBase E3 混为一谈。
4. 保留上游默认 `k`、repair rounds、gate 和 bisection 设定，或把每处改动列为冻结参数。记录每次 A_path/A_value 检查、执行尝试、空集细分、反馈消息和调用成本。分别报告“完整 SAG 反馈”和“无执行反馈”消融，解释反馈本身的贡献。

**E1 公平报告与验收：** 保留旧 DraftIR 直出臂作历史诊断；新 DIN/SAG 深移植另设 arm id，不能覆盖旧 id 的同名结果。增加同模型的“直接 DraftIR”参照臂，固定为单次输出加公开的 schema/Binder 校验；如允许修复轮次，另列预算匹配版本，不能在看到测试结果后换协议。主表明确比较 KART、直接 DraftIR、DIN-SQL→DraftIR、SAG-MQL→DraftIR 的**系统级** EX/拒绝/澄清与总耗时；分表列模型调用数、token、进程/翻译/反馈执行时间、服务错误率，以及可表示子集的分母。保持同模型、温度、可见目录、冻结查询顺序和资源上限；多阶段方法允许其原有调用流程，但调用成本不能隐藏。正式验收至少要求：示例输出对齐 schema、各适配器可处理支持/拒绝/澄清三类、所有成功结果过共用 Binder 与 Oracle、所有不可表示任务明确计入分母、没有 gold 驱动的状态推断、没有 402 混入模型失败。

### 6.3 E2：查明规划劣势，再实现条件式 LLM

1. **先补可观测性。** `LlmProposalPolicy` 在 HTTP 异常时进入规则回退，而 `LlmUsageAccumulator` 只在成功 `LlmResponse` 后记录 usage；将“请求已尝试/成功/失败/HTTP 状态/耗时”和“规则回退原因”分开。正式表至少分 `kart-llm-only`、`kart-with-fallback`、`cbo`、`bao-surrogate`；不能将前两个悄悄合并。保留 `bao` 的旧名称兼容时，表头/图例必须写明它是 KART CostCard + 手设族权重的 **Bao 控制流程代理**，不能称训练 Bao 模型。
2. **拆开候选生成与选择。** 对每条开发查询用同一 PlanBuilder/validator 离线枚举合法的安全候选并记录 `candidate_id`、family、特征和成本卡；同池选择实验让 LLM、CBO、Bao 代理面对完全相同的候选与信息，测选择增益。另保留原生整条搜索实验，测各臂实际候选覆盖、`n_safe`、搜索预算停止原因、最终选择和总规划时间。两类结果不能混用：前者隔离选择能力，后者检验完整系统。`plan_regret_ms` 要同时报告“本臂候选内”与“共享安全候选池内”的定义，不能拿前者声称全局最优。
3. **定位坏计划。** 对 TZ/TZH/TH/ZH，逐查询核对 KART 搜索日志和 CBO 候选列表；特别查 `P_FULL` 唯一候选时是合法动作、beam 裁剪、停滞/时间预算、LLM 无效动作还是验证器拒绝。修复时保证每个可用的基础安全索引计划不会因 LLM 决策而丢失；仍须过相同 validator。对同计划配对看执行 trace，对不同计划配对看估计成本、实际索引行、fetch、bytes 和执行耗时。若使用实际最优计划作诊断，不能在同一测试查询上调参数后再当作事前预测成绩。
4. **条件式 LLM 作为新臂。** 基础计划由 CBO 或冻结的安全候选集给出；只在查询前可见的信息提示代价不确定时调用 LLM，例如候选成本区间重叠、样本量不足、估计的索引交集/相关性风险或候选成本差接近。触发阈值、最大调用/毫秒预算、超时后回到哪个已验证 SafePlan，均只在训练/验证查询上确定，测试前冻结。LLM 与增强后的非 LLM 控制臂应看到同一统计特征；否则需要把“多给了信息”的收益单独消融。比较原始 KART、CBO、条件式 KART、同特征增强 CBO；报告触发比例、LLM 成功率、失败回退、规划/执行/端到端时间和 token 成本。
5. **明确收益门槛。** 每个 query/trial 的净收益需满足 `t_exec(CBO) - t_exec(新臂) > t_plan(新臂) - t_plan(CBO)`，不能只报执行更快。当前 315 个配对对 CBO 的端到端胜次为 0，这是既有事实；新方案若仍不能过门槛，应如实报告“LLM 未带来该环境下的端到端收益”。

### 6.4 E3：先关闭时间原点与正确性门禁

旧结果中，`t_large_3` 的 `[start_ms,end_ms)` 在 `epoch_ms=1201930200000` 前结束，`empty_far_3` 也完全在原点前；两者 Oracle 均为空。当前工作树的 `TimeBucket.bucketsCovering` 已实现查询索引访问区间裁剪：完全在原点前返回空桶，跨原点时只将索引访问起点裁到原点，精确过滤仍应使用原始 BoundIR 半开区间；`bucketOf` 对原点前的索引记录仍会拒绝，这是索引数据域假设的保护，不代表查询裁剪未生效。`ZOrderAndTimeBucketTest` 覆盖原点前、恰好结束于原点、跨原点和原点后区间，但尚无当前修复后 65 条 workload 的完整 Oracle 差分结果。先核验 READY 快照的索引时间域确实不含原点前记录：若含有，必须支持有符号桶或调整 epoch 并重建索引，不能裁剪而漏答案；若不含，须通过 65 条 FullScan/CBO/KART/RBO Oracle 差分证明空候选也经过覆盖验证。对纯 T、TZ、TH/TZH、Top-K 和空/边界情形都运行 validator 与 FullScan 差分，不得因空 scan task 绕过覆盖证明。

验收顺序：先跑原点前、端点恰等于原点、跨越原点、原点后 1 ms 等边界测试；再对 65 条全量 BoundIR 进行 FullScan/CBO/KART（及固定 RBO）差分，要求所有声称安全的臂对 Oracle 一致。RBO 的固定模板拒绝也要独立报告，不能用它掩盖 KART/CBO 的异常。正确性未全过之前，不发布相应 latency 胜率；保留失败行，不只统计成功行。warm 与真正可证明的 cold 分表，保持 `arm_position` 和缓存继承标记。

### 6.5 新查询集：检验 LLM 可能有用的条件，不为它挑题

现有 65 条有分层，但独立 family 数为 T=20、Z=11、TZ=23、H=2、TH=3、ZH=2、TZH=4；CBO 的实际搜索在本批成功行均只有 2 个候选。它适合回归与历史对照，却不足以回答“在复杂、多候选且成本估计不确定时 LLM 是否有增量价值”。**增加难题不会自动让 LLM 占优**：当前 LLM 主要看到合法动作和 FastCost，若没有 CBO 缺少的有效信号，多次调用只会增加规划开销。

1. 在看新测试结果之前写定生成器、seed、快照、Oracle 和查询清单。覆盖单索引、复合 T/Z/H、Top-K 的 DTW/Fréchet/Hausdorff、不同 k、时间/空间选择率交叉、空结果/边界、索引相关性与成本接近的候选；每层同时放明显容易和可能困难的实例。使用**查询输入与离线枚举特征**定义层（如合法候选数、冻结成本卡差距/不确定度），不可按“运行后 KART 获胜”选样本。拒绝和澄清类只进 E1，不混进 E2/E3。
2. 将旧 65/44 条标为**已观察的诊断/回归集**。新集合按轨迹/车辆、区域、时间窗、模板与参考轨迹分组切分训练、验证、锁定测试；同一模板的轻微数值变体和同一 query 的五次重复必须留在同一 split。既有代价系数的训练来源也要核查是否与新测试重叠；目前没有证据可宣称泄漏，但正式报告必须给出查询级 provenance。训练集可调成本模型和混合触发阈值，验证集选一次方案，锁定测试只评估一次；如再调参，应发布新的测试集。
3. 对每个 BoundIR 先由 FullScan Oracle 产生答案并冻结 SHA256，核查非空/空集比例、TOP_K 顺序和边界语义；复杂度样例要能被所有安全臂的共享 validator 检验。若某方法无法表达某子集，保留不可表示计数，不从分母删除。主报告同时给原 65 条的历史可比结果和新锁定集的确认性结果；每层给独立查询数、成功率、配对净延迟及不确定区间，且做 family/复杂度交互分析。单节点混合 HBase 结论仍不能外推到多 RegionServer。
4. 预先写出可证伪假设，例如“在冻结成本估计区间重叠且存在多个安全候选的查询层，条件式 LLM 的端到端延迟相对同信息 CBO 有净收益”，并指定全体查询的成功率和配对端到端延迟为主指标。用开发集的波动估计决定留出集所需的**独立查询数**，而不是用五次重复冒充样本量；若新测试不支持假设，完整报告零收益或负收益。

### 6.6 实施顺序和重新开跑门禁

以下门禁区分“实现已出现”和“公平实验已验收”；脚本参数与套件行为以当前文件为准：

1. P0（框架续作，2026-09-27）：§6.10 代码项含 **E2 共享候选池** 已落地（`plan-shared-pool.yaml`）。**仍开（环境/大规模，本轮不做）：** holdout FullScan oracle、干净提交 65-query Oracle 差分、正式 E1/E2/E3。下一步按用户安排再开跑。
2. P0：当前工作树没有可复核的 65-query Oracle 差分通过结果；`oracle-diff-65-current/e2e.jsonl` 为 0 行。需要在干净提交上重新运行并保存完整 260 行证据。
3. P1：条件式 LLM 阈值只在 holdout **v2** train/val 冻结；test 只评估一次。`plan.yaml` 默认仍为诊断 `bound_ir_v1.json`。
4. P1：v2 Oracle SHA256 冻结后，旧 65 与新集分开汇总。
5. 验收后 `sync`/`rebuild`/`doctor`，小规模门禁再全量。

正式发布的最小门禁：无服务错误混入模型失败；E1 各臂真实中间表示与转换过程可审计；所有标为 SafePlan 的结果对 Oracle 正确；E2/E3 不把规则回退记为纯 LLM；同池与原生搜索结果分表；cold/warm 协议可复核；锁定测试未用于调参；所有延迟带成功分母、query 级配对和 LLM 成本。任一门禁未通过，继续标注为诊断实验。

### 6.7 2026-09-26 框架完善后的验收状态（无大规模重跑）

**结论：§6.7 中可在无 HBase/无全量 bench 下关闭的局部阻塞已修复；holdout Oracle 冻结与端到端正式对比仍未完成，不得宣称公平正式实验已开跑。** 本轮未启动 formal warm / 消融 / 全量 E1。

| # | 原阻塞 | 状态 | 证据 |
|---|---|---|---|
| 1 | MQL 阶段顺序被错误归一化 | **已关** | `mql_to_draft_ir` 只接受 `$match→$group→可选$kart_topk`；`test_translators.py` 含乱序/重复/非法 group 反例 |
| 2 | SAG 无 world 时可能记 OK | **已关（fail-closed）** | `sag_mql_bridge._world_probe`：无 backend / `sample_json` 均 `unavailable`；feedback-on 直接 infrastructure failure，不 repair 成 OK。正式反馈仍需真实 HBase/Mongo WorldAccess |
| 3 | 条件 LLM 二次搜索 + 错误 `t_plan` | **已关（局部）** | `ConditionalLlmArm`：一次 CBO `planOnly` + `reselectByPlanId`；`t_plan_ms=plan_end-plan_start`，`planOnly` 不扣 exec；`ConditionalLlmArmTest` 覆盖 trigger/reselect/时钟恒等式 |
| 4 | 留出集无拆分/无 Oracle | **半关** | 已写 `bound_ir_holdout_v1_{train,val,test}.json` + `splits.json` + `fill-holdout-oracle.sh`；`oracle_status` 仍为 `pending_fullscan_fill`（需 HBase，本轮不跑） |
| 5 | 65 条 Oracle 差分 | **未形成当前可复核证据** | 当前 `oracle-diff-65-current/meta.json` 只有元数据，`e2e.jsonl` 为 0 行；旧完整诊断的 E3 仍有 RBO 145/325、CBO/KART 各 10/325 非成功行 |
| 6 | E1 新移植端到端证据 | **未做** | 本轮不跑 44×arm 全量 |
| 7 | 冻结 commit + 正式 cold/warm | **未做** | 工作树仍脏；正式结果须干净提交后另起 run-id |

**局部回归（本轮应跑、已替代大规模）：** `python -m pytest -q experiments/adapters/translate/test_translators.py experiments/adapters/sag/test_sag_world_failclosed.py`；`mvn -q '-Dtest=ConditionalLlmArmTest,ZOrderAndTimeBucketTest' test`。

**重新开跑顺序（不变）：** (1) `fill-holdout-oracle.sh` 冻结 SHA256；(2) 小规模 E1/E2/E3 门禁；(3) 干净 commit + provenance；(4) 正式 warm（非消融）。无真实 WorldAccess 时 E1 主表只用 `sag-mql-nofeedback`，`sag-mql` 标基础设施不可用。

### 6.8 2026-09-26 再次复核：局部修复通过，正式 E1/E2/E3 仍未就绪

**结论：局部代码和适配器测试通过，但当前工作树没有可复核的修复版 65-query E3 Oracle 差分结果；不能据此启动或发布正式的全套公平比较。** FullScan holdout oracle、E1 全量、干净 commit 正式 warm 仍未做。

**局部回归（本轮）：** Python `18 passed`（translator + SAG fail-closed + holdout integrity/v2）；`mvn -q '-Dtest=ConditionalLlmArmTest' test` 通过。

**本轮已关 / 半关：**

| # | 事项 | 状态 | 证据 |
|---|---|---|---|
| 2 | holdout 跨 split group 泄漏 | **v2 已关** | `generate_holdout_workload_v2.py` + `holdout_integrity.py` fail-closed；`bound_ir_holdout_v2*.json`；角色 `diagnostic_generalization_stress`（模板仍来自 v1 族，非全新参数空间）。v1 标为 `diagnostic_leaky` / superseded |
| 3 | oracle 冻结脚本可覆盖 | **半关** | `fill-holdout-oracle.sh --version v2` 在 `oracle_status=frozen` 或已有 `*.oracle.json` 时拒绝覆盖；**尚未跑 FullScan** |
| 4 | parse 默认含无 world 的 `sag-mql` | **已关** | `parse.yaml` 默认 `sag-mql-nofeedback`；`sag-mql` 注释为需真实 WorldAccess |
| 6 | keep-artifacts 污染 `t_plan_ms` | **已关** | `ConditionalLlmArm.planClockRunsRoot()` 恒为 null；`executeSelected` 才用 artifact 路径；单测 `planClockRunsRootAlwaysNullEvenIfArtifactsWanted` |

**仍阻止正式结论的事项：**

1. **当前 E3 Oracle 差分没有结果行。** `oracle-diff-65-current` 的 `e2e.jsonl` 为 0 行；其 `meta.json` 显示 `trials=1`、`cache=warm`、`cache_enforced=false`、`git_worktree_dirty=true`。它不能关闭正确性门禁，也不能提供延迟结论。旧 `cmp-full-warm-20260925` 是另一批、旧代码快照上的完整诊断，不能替代修复后差分。
2. ~~留出集 split 模板泄漏~~ → 见上表 v2；**v2 仍非 unused-parameter 确认性集**，且无 FullScan oracle。
3. **留出集还没有 FullScan Oracle，也未用于 E2。** `bound_ir_holdout_v2.provenance.json` 仍 `oracle_status=pending_fullscan_fill`；`plan.yaml` 默认仍为旧 65。跑 `scripts/fill-holdout-oracle.sh --version v2` 后才能在 train/val 调参、test 评一次。条数是 pilot，不是已论证样本量。
4. **新 E1 全量运行没有完成。** 当前工作树不存在可核验的 `cmp-formal-warm-20260926` 结果目录；不得当作 E1 结果。正式 E1 默认臂现为 no-feedback SAG。
5. **新 holdout 尚未证明能检验条件 LLM 的预设假设。** 层名 `multi_index_uncertain` 仍是意图标签；需在 train/val 上离线量 `n_safe`/CostCard gap 后再冻结触发阈值。
6. ~~条件式 LLM artifact 计时~~ → 见上表已关；正式 latency 仍建议不传 `--keep-artifacts`，并在 meta 记录。
7. **E1 是 control-flow transplant，不是原论文复现。** 命名与分母规则不变。

**正式开跑顺序：** (1) `fill-holdout-oracle.sh --version v2` 冻结三份 oracle SHA256；(2) train/val 核对候选池与触发条件；(3) 完整 E1（`sag-mql-nofeedback`）query×arm×trial；(4) clean commit 上重跑 E3 Oracle 再做 E2/E3 性能；(5) 检查行数、分母、退出码、哈希与 dirty。到此之前只可诊断，不可称公平正式对比完成。

### 6.9 2026-09-26 当前工作树复核：不具备正式开跑条件

本节只采用当前工作树能够直接读取的文件和本轮命令结果，修正上一节中已经不存在或无法复核的运行路径。

**已验证：**

- `python -m pytest -q experiments/adapters/test_holdout_integrity.py experiments/adapters/translate/test_translators.py experiments/adapters/sag/test_sag_world_failclosed.py`：18 passed。
- `mvn -q '-Dtest=ConditionalLlmArmTest,ComparativeHarnessTest,ZOrderAndTimeBucketTest' test`：退出码 0。
- `experiments/results/cmp-full-warm-20260925/` 的旧完整诊断行数为 parse=880、plan=975、e2e=1300；这批结果对应 clean commit `b635465cf6cc1a9824dcf09a6c2db74edc2446aa`，不是当前修复工作树的正式结果。

**当前仍未通过的门禁：**

| 门禁 | 当前事实 | 结论 |
|---|---|---|
| 修复后 E3 Oracle 差分 | `experiments/results/oracle-diff-65-current/e2e.jsonl` 为 0 行；`meta.json` 为 warm、`trials=1`、`cache_enforced=false`、`git_worktree_dirty=true` | 没有 65×4×1 的可核验正确性证据，不能开始 E3 正式性能比较 |
| 旧 E3 诊断结果 | FullScan 325/325；CBO 315/325；KART（含 1 条规则回退）315/325；RBO 180/325 | 只能定位旧快照问题，不能证明修复后安全臂全部正确 |
| v2 holdout Oracle | `bound_ir_holdout_v2.provenance.json` 仍为 `oracle_status=pending_fullscan_fill`；尚无 train/val/test 三份 oracle 文件 | 不能在 test 上做锁定评估，也不能冻结条件 LLM 阈值 |
| E1 新臂全量运行 | 没有可核验的 44 条 NL × 5 臂 × 5 trial 结果（期望 1100 行）；真实 WorldAccess 也未被证明可用 | 只能把 `sag-mql-nofeedback` 作为无反馈移植臂；不能声称完整 SAG feedback 已复现 |
| provenance | 当前 `git status --short` 非空，包含实验代码、适配器、测试和文档改动 | 不能把当前目录生成的结果称为 clean-commit 正式批次 |
| 条件 LLM 阈值 | **代码已修**：实例 `shouldTrigger` / 显式 thr；`fromRoot` 读 `conditional_llm_freeze.json`；`frozen_for_test` 忽略 env；行内记录有效阈值。单测 `constructorThresholdUsedNotEnvDefault` | 仍须在 v2 train/val 量候选池后把 freeze `status` 改为 `frozen_for_test` |

**具体补齐指导：**

1. 在 READY HBase 上执行 `bash scripts/fill-holdout-oracle.sh --version v2`。确认 `bound_ir_holdout_v2_{train,val,test}.oracle.json` 均生成，9/5/7 条 query 数与 split 一致，非空/空集、Top-K 顺序和 `[start,end)` 边界经过检查；确认 provenance 变为 `oracle_status=frozen` 并保存三份 SHA256。若脚本拒绝覆盖，提升 holdout 版本，不删除旧 oracle 伪造重算。
2. ~~修正 `ConditionalLlmArm.shouldTrigger` 配置来源~~ → **代码已关**。仍须：只在 v2 train/val 选择阈值，写入 `experiments/suites/conditional_llm_freeze.json` 并设 `status=frozen_for_test`，锁定 test 只跑一次。本地无 HBase 门禁：`bash scripts/check-comparative-framework.sh`；目录审计：`python experiments/adapters/audit_holdout_catalog.py --version v2`。
3. 在 clean commit 上运行 `bash scripts/oracle-diff-65.sh oracle-diff-65-formal-20260926`。验收 `e2e.jsonl` 恰为 260 行（65 query × 4 arm × 1 trial），每个 query×arm 一行、`ok_oracle=true`、脚本退出码为 0，`meta.json.git_worktree_dirty=false`；同时保留 doctor、HBase evidence、client/cluster 版本和 `region_server_count`。
4. 重新跑 E1 全量，不带 `--limit`，至少使用 `kart,direct-draftir,din-sql-spider,din-sql-bird,sag-mql-nofeedback`。期望每臂 220 行（44×5），服务 402/超时/配额必须保留为 infrastructure failure，不能当作模型失败或只补失败行拼表。`sag-mql` 只有在真实、只读、与同一快照对齐的 WorldAccess 通过 Oracle 对齐检查后才能加入。
5. E2 先在 v2 train/val 上固定候选池、触发阈值、LLM 调用和回退规则，再在 test 上一次性评估；旧 `plan.yaml` 默认的 `bound_ir_v1.json` 只能用于诊断回归，不能当锁定测试。E2/E3 正式 latency 仍须明确 warm 或可证明 cold 协议，并报告成功分母、规则回退和 HTTP 失败尝试。

完成上述门禁并重新生成独立 run-id 之前，当前最诚实的状态是：**框架局部修复和局部测试已通过（含条件 LLM 冻结配置与本地 framework check），但正式、诚实、公平的全套对比实验尚未具备开跑条件。**

### 6.10 2026-09-27 仅代码与实验逻辑复核：仍未达到公平正式比较门禁

本节只检查当前工作树中的代码逻辑和实验协议逻辑，不把 HBase、LLM/API、缓存清理、工作树状态或其他运行环境条件重复列为本轮问题。

**本轮已验证的局部修复**

- `bash scripts/check-comparative-framework.sh` 通过；其检查范围不依赖 HBase。
- Python translator、SAG fail-closed 和 holdout 完整性测试为 `18 passed`。
- `ConditionalLlmArmTest`、`ComparativeHarnessTest`、`ZOrderAndTimeBucketTest` 通过。
- MQL 已严格要求 `$match -> $group -> optional $kart_topk`；无可用 WorldAccess 的 SAG feedback-on 会返回基础设施失败；条件 LLM 的实例阈值不再在运行中重新读取环境变量；`--keep-artifacts` 不再进入条件 LLM 的计划时钟。

以上是局部回归证据，不等于 E1/E2/E3 的正式结果证据。以下问题仍能从当前代码直接复现或审阅确认。

#### 6.10.1 RBO 在尝试被拒绝模板时低估 `t_plan` 和 `t_e2e`

文件：`src/main/java/kart/bench/RboFixedArm.java`。

RBO 按 `TZ -> T -> Z -> H -> FULL` 逐个调用 `engine.runFixed()`。当早期模板被 validator 拒绝时，代码继续尝试下一个模板，但最后只把最后一次 `RunResult.t_plan_ms` 交给 `TrialResult.fromRun()`。`wall` 虽然包含了所有模板尝试，但 E3 的 `t_e2e_ms` 使用 `t_plan_ms + t_exec_ms`，因此早期被拒绝模板的 planning 时间被排除。plan-only 行还会出现 `t_e2e` 与 `t_plan` 口径不一致。

这会系统性地让 RBO 看起来更快，属于真实的速度公平性错误。

**修复和验收指导：**

1. 在 RBO 外层建立单一 `plan_start_ms/plan_end_ms`，累加每次模板编译、验证和选择的计划耗时；最终 `t_plan_ms` 必须覆盖整个模板循环。
2. 执行模式下定义 `t_e2e_ms = t_plan_ms + t_exec_ms`，plan-only 模式下至少保证 `t_e2e_ms == t_plan_ms`。
3. 将被拒绝模板耗时和拒绝原因写入 trace，不能只保留 `rbo_rejected_templates` 的计划 ID。
4. 增加“第一个模板被拒绝、第二个模板成功”和“所有候选均失败”的回归测试，检查 `plan_end - plan_start == t_plan_ms`。

#### 6.10.2 E1 parse 臂仍按连续 arm 区块执行，没有轮换顺序

文件：`src/main/java/kart/bench/SuiteRunner.java` 的 `parse` 分支。

当前流程是先运行一个 `cell` 的全部 query/trial，再运行下一个 `cell`。E2/E3 已按 query/trial 写入 `arm_position` 和 `arm_order_shift`，但 E1 parse 行没有对应字段，也没有轮换。相同 query 顺序虽然可复用，但服务端时间漂移、进程状态和请求序列效应仍会与 arm 混淆。

**修复和验收指导：**

1. 将 E1 循环改为 `trial -> query -> rotated arm`，例如 `shift = (trial - 1 + query_index) % n_arm`。
2. 每行写入 `arm_position`、`arm_order_shift`，保持每个 query/trial 对所有 arm 各执行一次。
3. 汇总按 `query_id` 配对比较；不能把连续区块的总耗时当作臂级样本。
4. 用一个固定小 workload 验收行数、每个 query/trial 的 arm 完整性以及轮换序列，再运行正式 E1。

#### 6.10.3 E2 仍没有共享候选池，不能隔离“选择能力”

文件：`experiments/suites/plan.yaml` 以及各计划臂实现。

当前 `kart`、`cbo`、`bao`、`llmopt` 和 `kart-conditional-llm` 的候选生成路径不同：KART 使用 LLM 搜索，CBO 使用 BEST_FIRST，Bao 是 KART CostCard 加手设族权重的代理，LLMOpt 是 Python G→S 后再走 Java RULE/`forcePlanId`。套件中没有离线枚举的共享 SafePlan 候选列表，也没有让各选择器读取同一组候选与特征的 shared-pool arm。

因此当前 E2 可以作为“完整系统流程”诊断，但不能把差异归因于选择器本身，也不能用各自候选集内的 `plan_regret_ms=0` 声称全局选择最优。

**修复和验收指导：**

1. 对同一 BoundIR 用同一 `PlanBuilder`、同一 validator 和固定 CostCard 离线枚举完整安全候选，保存 `candidate_id`、family、特征、估计代价和枚举版本。
2. 新增 shared-pool 选择实验，让 CBO、Bao surrogate、条件 LLM 和 LLM selector 面对完全相同的候选列表与可见信息。
3. 原生搜索与 shared-pool 结果分表；分别报告候选覆盖、`n_safe`、搜索预算停止原因和选择结果。
4. 将 regret 明确区分为“本臂候选内 regret”和“共享安全候选池 regret”，不得混用。

#### 6.10.4 LLMOpt 与其他臂的计划计时边界仍不一致

文件：`src/main/java/kart/bench/LlmOptPlanArm.java`。

`Files.createTempFile()` 和 BoundIR 序列化发生在 `t0` 之前，因此当前 `t_plan_ms` 排除了临时输入文件准备；其他臂从进入规划流程开始计时。当前实现可以诚实描述为“Python G→S 加 Java 验证搜索”，但还不能声称所有臂使用同一系统级计划延迟边界。

**修复和验收指导：**

1. 若比较系统级计划延迟，把 `t0` 移到临时文件创建之前，并让所有臂包含同类 adapter/input preparation。
2. 若研究问题只比较模型/选择核心，则所有臂统一排除 adapter preparation，并单独记录 `t_adapter_prepare_ms`。
3. 在协议中冻结 `t_plan` 的边界，逐臂记录 `plan_start_ms`、`plan_end_ms`，验收 `plan_end - plan_start == t_plan_ms`。
4. 继续披露 LLMOpt 的 Java 搜索时间包含在 `t_plan`，不能把它描述成只执行 G→S。

#### 6.10.5 Conditional LLM fallback 仍不能被统一汇总识别

文件：`src/main/java/kart/bench/ConditionalLlmArm.java`。

触发 LLM 后，如果返回计划为空、计划不在候选中或没有 LLM client，代码只把 `llm_fallback_cbo` / `no_llm_client` 追加到 `conditional_trigger_reason`，没有设置统一的 `llm_fallback=true` 和 `fallback_reason`。`SuiteRunner` 的 fallback arm 改名逻辑依赖 `llm_fallback=true`，所以当前汇总无法稳定区分：未触发、触发成功、触发失败后回退 CBO。

**修复和验收指导：**

1. 触发但未采用 LLM 计划时统一写 `llm_fallback=true`，并写结构化 `fallback_reason`（HTTP/解析/无 client/候选不存在/reselect 失败）。
2. 未触发时写 `conditional_not_triggered`，成功时写 `conditional_llm_success`，回退时写 `conditional_llm_fallback`。
3. 汇总和图表按这三类分组；回退行不能计入纯 LLM 选择成功率。
4. 为空响应、非法 plan_id、HTTP 失败和无 client 各加一条单测，检查 arm 标签和分母。

#### 6.10.6 DIN 深移植使用自定义翻译修复，不是完整上游 self-correction

文件：`experiments/adapters/din/din_sql_bridge.py`。

深 DIN 臂保留了上游 schema linking/classification/generation，但 `_translate_with_repair()` 使用本地最多 2 轮、自定义错误提示和自定义 repair 请求，没有调用上游 Spider `debuger`；BIRD 深臂也没有走上游 `SYSTEM_SELF_CORRECTION_PROMPT` 的完整流程。这可以是清楚标注的 `din-sql-kart-transplant`，但不能标成原论文原生 self-correction 复现。

**修复和验收指导：**

1. 要求原生复现时，调用冻结 upstream 的 correction 函数/模板，固定 repair 次数和停止条件。
2. 保留当前深臂时，将 arm id、README、结果 `provenance` 明确标为 Kart transplant，并列出 intentional changes。
3. 每轮记录 SQL、翻译错误、修复请求、最终 DraftIR、调用数和耗时；测试集不得根据错误逐题增补 prompt。
4. 原生 upstream 与 transplant 分表，不能合并成一个 DIN 结果。

#### 6.10.7 SQL/MQL translator 仍存在静默改变语义的接受路径

文件：`experiments/adapters/translate/sql_to_draft_ir.py`、`mql_to_draft_ir.py`。

当前 SQL translator 只检查 `SELECT DISTINCT trajectory_id` 的前缀，额外投影列可能被接受后静默丢弃；region 与 lon/lat 同时出现时 SQL 优先保留 region 并忽略矩形条件；重复 `event_time >=` 或 `<` 条件会被后一个覆盖；`ORDER BY` 的正则没有完整锚定，尾随 token 可能被接受并忽略。上述路径会让“模型输出的逻辑查询”与实际执行 DraftIR 不等价。

当前 MQL translator 已关闭阶段乱序、重复 group、region+矩形冲突和 `$match` 非 object 的主要问题，但 lon/lat range 中带有已知边界之外的额外操作符时可能被忽略；`$kart_topk.k` 只用 `int(k)`，浮点、负数或布尔值可能被接受或截断。

**修复和验收指导：**

1. SQL 只接受唯一投影 `trajectory_id`，完整匹配 SELECT/FROM/WHERE/ORDER BY/LIMIT，重复字段和尾随 token fail-closed。
2. SQL 同时出现 region 与矩形、重复时间边界或不完整矩形时返回 `untranslatable_sql`，不得选择一个条件静默丢弃。
3. MQL 对 lon/lat 只允许 `$gte/$lte`，拒绝额外操作符；`k` 必须是 JSON 正整数且不能由 `int()` 截断。
4. 增加反例测试：额外投影、region+矩形、重复时间、ORDER BY 尾随 token、未知 range 操作符、`k=1.5/0/-1/true`，均应得到明确的不可翻译/非法状态。

#### 6.10.8 当前逻辑结论（历史快照；落地状态见 §6.11–6.12）

本节写于共享池落地前。**代码侧 §6.10.1–6.10.7 现已关闭**（见 §6.11 表与 §6.12）。仍不能宣称“公平正式多臂对比可开跑”的原因是**环境/大规模门禁**未关：holdout FullScan oracle、干净提交 65-query Oracle 差分、正式 E1/E2/E3、freeze 实测、真实 SAG WorldAccess。

可运行并标注为：本地框架回归；标注 transplant 的 E1 诊断；原生搜索与 `plan-shared-pool` 分表的 E2 诊断。

不能发布：RBO latency 胜负（尚无修复后全量证据）；未跑 shared-pool 套件就声称同池选择能力；未按三类 fallback 汇总的纯 LLM 选择结论；将深 DIN/SAG 写成原论文完整复现。
### 6.11 2026-09-27 局部逻辑修复（无大规模 bench）

| § | 事项 | 状态 |
|---|---|---|
| 6.10.1 | RBO 被拒模板不计入 `t_plan` | **已关（代码+单测）** | `RboFixedArm` 累加每次 `t_plan_ms`，`applyPlanClock` 使 `plan_end-plan_start==t_plan`，有执行时 `t_e2e=t_plan+t_exec`，plan-only 时二者相等；拒绝写入 `rbo_rejected_trace` |
| 6.10.2 | E1 不轮换 | **已关（代码+单测）** | parse 改为 `trial → query → armShift/armIndex`；行含 `arm_position`、`arm_order_shift`；与 E2/E3 共用 `SuiteRunner.armShift` |
| 6.10.3 | E2 无共享候选池 | **已关（代码+单测）** | `SharedCandidatePool` + `pool-cbo`/`pool-bao`/`pool-conditional-llm`/`pool-llm`；套件 `plan-shared-pool.yaml` 与原生 `plan.yaml` 分表；`pool_regret_ms` / `plan_regret_scope=shared_pool`；catalog `enum_version=shared_pool_enum/v1` |
| 6.10.4 | LLMOpt 输入准备在时钟外 | **已关（代码）** | `t0` 在 `createTempFile` 之前；`t_adapter_prepare_ms` 单列；Java 搜索仍在 `t_plan` 内 |
| 6.10.5 | 条件 LLM fallback 不可汇总 | **已关（代码+单测）** | `conditional_not_triggered` / `conditional_llm_success` / `conditional_llm_fallback`，回退时 `llm_fallback=true` 与 `fallback_reason` |
| 6.10.6 | DIN 自定义 repair 冒充上游 self-correction | **已关（代码+单测）** | `din_sql_bridge` 修复轮次调用上游 Spider `debuger` 或 BIRD `SYSTEM_/HUMAN_SELF_CORRECTION_PROMPT`（最多 2 次），再经确定性翻译器；provenance `upstream_debuger_then_kart_translate` / `upstream_bird_self_correction_then_kart_translate`。方言约束是明示移植改动，仍无 Spider/BIRD SQL 执行器 |
| 6.10.7 | SQL/MQL 静默改语义 | **已关（代码+单测）** | 额外投影、region+矩形、重复时间、ORDER BY 尾随 token、lon/lat 额外操作符、非正整数 `k` 均 fail-closed |

局部回归：`pytest` translator/SAG/holdout；`ConditionalLlmArmTest,ComparativeHarnessTest,SharedCandidatePoolTest`。

**代码侧 §6.10 逻辑项已关完（含共享池与上游 DIN self-correction 提示）。** 环境证据与仍开的门禁见 §6.14。在正式全量与干净提交差分完成前，不能称为公平正式对比已可开跑。

### 6.12 2026-09-27 共享候选池落地（无大规模 bench）

- 枚举：`PlanBuilder.buildCandidates` → 逐候选 `QueryEngine.runFixed`（同一 validator/cost），不是 beam 搜索；catalog `enum_version=shared_pool_enum/v1`。
- 选择臂：`pool-cbo`（argmin）、`pool-bao`（族权重 surrogate）、`pool-conditional-llm`（条件触发）、`pool-llm`（同池常开 LLM，失败回退 argmin）。
- 行字段：`shared_pool=true`、`candidate_search=false`、`pool_regret_ms`、`plan_regret_scope=shared_pool`、`t_pool_enum_ms`、`shared_pool_catalog`。
- 协议：原生搜索与共享池**分套件/分表**；禁止用臂内 `plan_regret_ms=0` 声称共享池全局最优。

### 6.13 2026-09-27 验收路径分层（排除正式全量批次时）

| 层 | 内容 | 状态约定 |
|---|---|---|
| 代码已关 | §6.10 + `pool-llm` + SummaryMd 把 `llm_fallback` 拆成独立臂 + 上游 DIN repair 提示 | 见 §6.14 |
| 证据已有、非正式批次 | holdout v2 Oracle 冻结、`frozen_for_test`、260 行 warm Oracle 差分（工作树 dirty）、KartWorldAccess 样本对齐 | 见 §6.14；差分不能冒充干净提交 |
| 正式批次另开 | 44×arm×trial E1、v2 test 锁定 E2、warm/cold E3 全量与论文汇总 | 未做 |

样本 WorldAccess 对齐之后，正式 E1 仍默认 `sag-mql-nofeedback`；`sag-mql` 只有在该次正式运行显式加入时才进入主表。

### 6.14 2026-09-27 证据与汇总口径（仍非正式全量）

| 项 | 状态 | 证据 |
|---|---|---|
| WSL `mvn package` | **已关** | 依赖在 `/mnt/f/maven-repository`。原先 settings 的 `F:\maven-repository` 在 WSL 里变成项目相对路径，javac 看不到 Jackson。现已把 WSL `~/.m2/settings.xml` 的 `localRepository` 指到该目录，`KART_MAVEN_REPO` 同样默认它。2026-09-27 `BUILD SUCCESS`，并跑通 `ComparativeHarnessTest#llmFallbackArmsStaySplitFromPureArms` |
| LLM fallback 与主臂分列 | **已关（代码+单测）** | `SuiteRunner` 对 `kart` / `llm` / `llm_direct` / `kart-conditional-llm` / `pool-llm` / `pool-conditional-llm` 在 `llm_fallback=true` 时改名为 `*-rule-fallback`（`kart` 为 `kart-rule-fallback`）。`SummaryMd.summaryArmKey` 对未改名的旧行同样拆开，避免并进纯臂 |
| DIN 深移植 repair | **已关（移植边界内）** | 修复提示来自上游 debuger / BIRD self-correction；仍不是 Spider/BIRD 执行器复现。单测 `experiments/adapters/din/test_din_upstream_repair.py` |
| KartWorldAccess 样本对齐 | **已关（样本）** | `experiments/results/world-align-kart-world-20260927.json`：`KART_WORLD_BACKEND=hbase`，`query-draft` + `P_FULL`，5/5 `ok` 且 ID 集合与 Oracle 一致（`t_small_1/2`、`t_large_1/2`、`h_eq_1`）。空间矩形因 BoundIR 为 UTM、不能无失真回到 DraftIR，记入 `skipped`，不假装已覆盖 |
| holdout v2 Oracle | **已冻结** | `bound_ir_holdout_v2.provenance.json` 为 `oracle_status=frozen` |
| 条件 LLM 阈值 | **已冻结** | `experiments/suites/conditional_llm_freeze.json` 为 `status=frozen_for_test`（train/val 测量；未在 test 上再调） |
| 65×4 Oracle 差分 | **有 260 行，未达干净提交** | `experiments/results/oracle-diff-65-audit-20260927w`；`git_worktree_dirty=true`。不能当作 §3.1 干净树门禁 |

仍未关闭、且不在本节声称完成的：正式 E1/E2/E3 全量、v2 test 只评一次、可证明 cold（`cache_enforced=true` 与 OS page cache flush）、干净工作树上重跑 65-query Oracle 差分。

### 6.15 2026-09-27 起步阶段验收（本次复核）

本节采用用户当前目标：检查代码逻辑、实验完整性和能否启动对比流程；不把未做的正式多次实验、cold 延迟或论文汇总当成已完成。

| 检查 | 当前证据 | 结论 |
|---|---|---|
| 本地框架门禁 | `bash scripts/check-comparative-framework.sh`；套件、holdout 文件、条件 LLM freeze schema、catalog 分层均通过 | 通过 |
| Python 适配器/翻译器 | `pytest experiments/adapters/translate experiments/adapters/din/test_din_upstream_repair.py experiments/adapters/sag/test_align_draft.py experiments/adapters/sag/test_sag_world_failclosed.py experiments/adapters/test_holdout_integrity.py -q`：`26 passed` | 通过 |
| Java 关键逻辑 | `ConditionalLlmArmTest`、`ComparativeHarnessTest`、`SharedCandidatePoolTest`、`ZOrderAndTimeBucketTest` 通过 | 通过 |
| 构建 | `mvn -q -DskipTests package` | 通过 |
| E1 smoke | `experiments/results/d1-smoke-parse/parse.jsonl`：4 行，含 `arm_position`/`arm_order_shift` | harness 可运行 |
| E2 smoke | `experiments/results/d1-smoke-plan/plan.jsonl`：6 行，`plan_ok=6/6`；每行 `plan_end-plan_start=t_plan` | 计时与选择路径可运行 |
| E3 smoke | `experiments/results/d1-smoke-e2e/e2e.jsonl`：4 行，Oracle `4/4`；`t_e2e=t_plan+t_exec` | 端到端路径可运行 |
| 原生固定臂轻量差分 | `experiments/results/oracle-diff-65-audit-20260927w/e2e.jsonl`：`fullscan/rbo/cbo/kart` 共 260 行，Oracle `260/260` | 当前代码快照的正确性证据；不是正式性能批次 |

因此，**现在可以开始起步阶段对比实验**。建议按下面三张表分开运行和解读：

1. E3 先跑 `fullscan,rbo,cbo,kart`；若加入 `kart-conditional-llm`，把 LLM 成功、未触发和规则回退分开汇总。
2. E2 将原生搜索套件 `plan.yaml` 与共享候选池套件 `plan-shared-pool.yaml` 分表；原生 `plan_regret_ms` 和共享池 `pool_regret_ms` 不混用。
3. E1 使用 `kart,direct-draftir,din-sql-spider,din-sql-bird,sag-mql-nofeedback` 作为移植诊断表；`sag-mql` 只有真实、快照对齐的 WorldAccess 通过后才加入。

本次没有发现会阻止上述起步运行的代码或实验逻辑问题。以下是已确认、但不阻塞起步的后续增强项：

- `scripts/oracle-diff-65.sh` 已加强为 65 条完整 query-id 集合、四臂及每次 trial 一一覆盖；干净提交上的差分仍须实际重跑。
- holdout v2 的 train/val/test Oracle 文件均已存在；本轮核对了 9/5/7 条 query-id、快照与语义版本、SHA256，并将 provenance 与 split 元数据同步为 `oracle_status=frozen`。test 仍须按冻结阈值只评一次；v2 的角色仍是泛化压力诊断集。
- 当前 smoke 结果工作树为 dirty、主要采用 warm 且 trial 很少；只能用于确认流程、正确性和诊断口径，不能解释为冷延迟或论文级统计结论。

这三项不影响当前起步实验启动；正式冻结 workload、汇总性能或写论文前再处理即可。

### 6.16 2026-09-27 默认 E2/E3 切到优势工作区切片

条件 LLM 与共享池选择都需要多个 SafePlan。BoundIR-65 里大量是单索引 T/Z/H、empty/boundary 陷阱和空间-only Top-K，适合回归与 Oracle 差分，不适合检验交织索引上的选择。

因此 `plan.yaml` / `plan-shared-pool.yaml` / `e2e.yaml` 的默认 workload/oracle 改为 `bound_ir_advantage_v1`（当前 40 条，`evaluation_role=kart_operating_region`）：从 BoundIR-65 取 `family ∈ {TZ,TH,ZH,TZH}`（含 `topk_st_*`），再并入 holdout v2 的 `train/val` 中的 `multi_index` / `multi_index_uncertain` / `topk_metric`。Oracle 从已有 FullScan 缓存按 `query_id` 合并，不重跑 FullScan；holdout v2 `test` 不进入默认切片。

角色边界：

- 该切片检验「多候选、多索引、不确定代价」下的选择/端到端，**不是**确认性留出集，也不是论文公平主表。
- BoundIR-65 仍用于回归、`oracle-diff-65.sh`、WorldAccess 对齐。
- holdout v2 **test** 仍是锁定确认集（本次不切为套件默认）。
- `parse.yaml` 与 `plan-shared-pool-freeze-measure.yaml` 不变。

### 6.17 2026-09-27 更新测试集的适用性复核

本次只调整 E2/E3 的默认 workload 适配，不运行正式实验。复核结果如下：

| 检查项 | 结果 |
|---|---|
| E2/E3 是否已切换 | `plan.yaml`、`plan-shared-pool.yaml`、`e2e.yaml` 均引用 `bound_ir_advantage_v1.json` 与对应 Oracle |
| Oracle 是否一一覆盖 | 40 条 workload query 与 40 条 Oracle answer 的 query-id 集合相等 |
| 是否覆盖 LLM 规划的目标区域 | 包含 `TZ/TH/ZH/TZH` 与 `TOPK`；catalog 层包含 `multi_index`、`multi_index_uncertain`、`topk_metric` |
| 是否仍有单索引/空边界干扰 | 默认切片排除单谓词 `T/Z/H`、`empty/boundary`、空间-only Top-K |
| 是否污染锁定测试 | 已修正：v2 `split=test` 的 4 条查询不再进入默认切片；它们只能通过显式 workload override 一次性评估 |

这个切片比原始 65 条回归集更适合观察 KART 的候选生成、索引交织和条件 LLM 选择行为；旧 65 条仍必须保留用于回归和全量 Oracle 差分。它不能被称为“对所有查询公平的主测试集”，原因是它有意排除了单索引、空结果和边界查询，且 40 条中 `TZ` 占 27 条。若只报告这 40 条，可能高估系统在其目标工作区的优势。

因此建议结果分三层：

1. `bound_ir_advantage_v1`：E2/E3 operating-region 诊断，检验多候选选择能力。
2. `bound_ir_v1`：65 条回归/Oracle 差分，检查安全性、空结果和边界正确性。
3. `bound_ir_holdout_v2_test`：锁定测试，完成阈值冻结后只评估一次，不并入前两层的调参或重复汇总。

本次静态适配检查未发现会阻止切换的 workload 或套件引用错误；没有启动任何正式 E2/E3 运行。

锁定 v2 test 时使用显式 workload override，并单独创建 run-id；不要把它追加到默认 `bound_ir_advantage_v1` 结果中：

```bash
./scripts/bench-plan.sh --run-id plan-v2-test-once \
  --workload experiments/workloads/bound_ir_holdout_v2_test.json \
  --arm pool-cbo,pool-conditional-llm

./scripts/bench-e2e.sh --run-id e2e-v2-test-once \
  --workload experiments/workloads/bound_ir_holdout_v2_test.json \
  --oracle experiments/workloads/bound_ir_holdout_v2_test.oracle.json \
  --arm fullscan,rbo,cbo,kart,kart-conditional-llm \
  --cache warm --trials 1
```

以上命令仅说明数据集边界；本次复核没有执行它们。

### 6.18 2026-09-27 优势高难度扩集 v2（路径 B）

在 §6.16/§6.17 切片之上，新造 28 条 `a2_*` BoundIR（不触碰 holdout v2 locked test），FullScan 填 Oracle 后与 advantage_v1 合并为 `bound_ir_advantage_v2`。合并时发现并移除 10 条完全相同的 BoundIR 指纹，当前默认集合为 58 条，并切为 E2/E3 默认。

| 层 | 条数 | 假设 |
|---|---|---|
| `tzh_rbo_trap` | 8 | RBO 固定 `T+Z+H→P_TZ`；KART/条件 LLM 可在更多 SafePlan 中选 |
| `tz_hard_intersect` | 8 | 大时空交集 → `n_safe≥2`、共享池选择有意义 |
| `th_zh_uncertain` | 4 | 双索引 + 代价不确定，利于条件 LLM 触发 |
| `topk_st_metric` | 8 | ST Top-K（DTW/Fréchet/Hausdorff，不同 k）度量代价不确定 |

实现：

- 生成：`experiments/adapters/generate_advantage_hard_v2.py` → `bound_ir_advantage_hard_v2.json`
- Oracle：`scripts/fill-advantage-hard-oracle.sh`（FullScan，28/28 非空，`oracle_status=frozen`）
- 合并：`python3 .../generate_advantage_hard_v2.py --merge` → `bound_ir_advantage_v2.{json,oracle.json,provenance.json}`；按 `exact_bound_ir_fingerprint_keep_first` 去重
- 套件：`plan.yaml` / `plan-shared-pool.yaml` / `e2e.yaml` 默认 → advantage_v2
- **未做**：正式 E2/E3 全量、`oracle-diff-65`、条件 LLM freeze 重测

角色仍是 `kart_operating_region`：发挥系统多候选/交织索引优势的工作区诊断集，不是确认集或论文公平主表。`conditional_llm_freeze.json` 不在此集上重调。

### 6.19 2026-09-27 扩集去重后的静态验收

| 检查 | 当前结果 |
|---|---|
| 默认套件引用 | `plan.yaml`、`plan-shared-pool.yaml`、`e2e.yaml` 均使用 `bound_ir_advantage_v2.json` 及其 Oracle |
| 数据规模 | 68 条原始合并行，去除 10 条精确 BoundIR 重复后保留 58 条 |
| Oracle 覆盖 | 58/58 query-id 一一对应，Oracle 全部非空 |
| 目标层 | `tzh_rbo_trap=8`、`tz_hard_intersect=8`、`th_zh_uncertain=4`、`topk_st_metric=8`，另含 v1 的多索引层 |
| 锁定测试隔离 | hard 生成器拒绝 holdout v2 locked test；默认集合未包含 v2 `split=test` |
| 正式实验状态 | 本次只生成/合并 workload 并做静态检查，未运行 E2/E3 |

该集合更能触发 KART 的候选搜索和条件 LLM 选择路径，但仍是有意聚焦的 operating-region 集合；`bound_ir_v1` 的 65 条回归集和 holdout v2 test 仍需独立报告，不能把 58 条结果冒充全 workload 公平结论。另一个尚未由静态文件证明的假设是每个 `tz_hard_intersect`/`th_zh_uncertain` 查询实际都有至少两个 SafePlan；这必须由后续 plan-only 结果中的 `n_safe`/候选池记录验证，不能仅凭层名推断。
