# KART 消融实验真实性、公平性与可运行性审计（refine 副本）

- 审计日期：2026-09-26（文件名沿用 2026-09-25；本文件由 readiness 文档 refine）
- 适用范围：`spec/ablation_experiment.md` 规划侧 leave-one-out；不与外部对比臂混写
- 2026-09-26 历史结论：**当时的正式跑数门禁已完成**（cold 主表 / warm 附录 / 风险表；commit `6ef1794`，`git_worktree_dirty=false`，cold `cache_enforced=true`）。该批次的成功子集延迟与配对 delta、风险失败归因见 [`ablation-formal-findings-2026-09-26.md`](ablation-formal-findings-2026-09-26.md)。2026-09-27 当前修复版的起步阶段验收与未完成项见 §8；旧结果不能代表新代码的全量性能。

> 下文 §1–§6 保留历史 smoke 审计叙述；以本节结论与 `ablation-formal-findings-2026-09-26.md` 为准覆盖“是否放行正式跑数”。


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
| 正式 workload 定义 | BoundIR 65 条、NL 44 条；BoundIR catalog 覆盖 T/Z/TZ/H/TH/ZH/TZH，选择率含 small/medium/large/mixed/empty/boundary；NL 为 supported 16、reject 17、clarify 11 | 数据集定义已扩展；完整全量运行尚未完成 |
| HBase 环境元数据复核 | `audit-ablation-smoke/meta.json` / `audit-ablation-risk/meta.json` 已记录 `hbase_client_implementation_version=2.2.3`、`hbase_client_specification_version=2.2`、`hbase_client_version_source=classpath_pom_plus_lib_hbase_client_manifest_spec`、`region_server_count=1`；同时记录 cluster version `2.1.2` | 通过元数据完整性检查；仍是单节点混合 classpath，不能外推到同版本多 RegionServer 集群 |
| 消融套件主臂展开 | `audit-ablation-smoke` 产生 Full + `no_llm_rule` + `no_llm_best_first` + `llm_direct` + `no_fast_cost` + `no_final_cost` + `single_index` + `uncalibrated` 共 8 行；`no_coverage` 默认跳过 | 通过（limit=1 smoke） |
| 消融因子语义与 Oracle | 8/8 主臂行 `ok_oracle=true`；`no_final_cost` 产生 `plan_id=P_FULL` 且 `plan_regret_ms` 显著增大；`single_index` 未生成 INTERSECT；`uncalibrated` 使用独立冻结系数文件；风险臂 `audit-ablation-risk` 显式 `allow_unsafe=true` 且 1/1 Oracle 通过 | 通过语义 smoke；风险臂尚未证明会在完整 workload 上暴露漏查 |
| 第三方入口审计 | 两次运行的 `meta.json` 均记录 upstream commit、入口文件 SHA256、adapter SHA256，`missing_upstreams=[]`、`ready_for_external_arms=true` | 通过可追溯性检查；仍不是原系统复现 |

### 1.2 已修正的实现问题

1. `scripts/kart-env.sh` 的默认 Windows 源目录已改为当前仓库 `/mnt/f/Projects/LLM_KV`；WSL 目录、ZooKeeper 和 HBase 目录改为基于 `$HOME`，避免登录名和 home 目录名不一致时失效。
2. E2 计划阶段的 `t_plan_ms` 现在对 `NativePolicyArm`、Bao 和 LLMOpt 记录完整 arm wall path；此前 Bao 的 probe 和 LLMOpt 的 G→S bridge 不会完整反映在汇总的 `t_plan_ms` 中。
3. 选中需要 LLM 的 arm 但 LLM 配置不可用时，harness 现在直接失败；不再静默回退为 RulePolicy 后把回退结果误当成 KART LLM 结果。
4. `CacheProtocol` 现在按整次运行累计判断 `cache_enforced`，并在 E2/E3 记录 `arm_position`；本轮 18 行 E3 结果证明轮换生效。
5. LLMOpt 的 `t_plan` 现在覆盖 Python G→S 和 Java SafePlan 落地；本轮 9 行均有可核对的 `plan_start_ms`/`plan_end_ms`。
6. workload 已扩展为 65 条 BoundIR 和 44 条 NL；本次审计不再把它们描述为 24 条或 5+5 smoke 数据集。
7. 消融套件已按 `spec/ablation_experiment.md` 固定为 `base_arm: kart`（Full KART），主因子与风险因子均能展开；`no_coverage` 默认跳过，必须显式 `--allow-unsafe`。
8. 消融行已记录 `factor`、`unsafe`、`arm_position`、`t_plan_ms`/`t_exec_ms`/`t_e2e_ms`、Oracle 结果、计划候选与执行指标；`t_e2e_ms` 不包含 artifact 写入时间。
9. 本轮 WSL smoke 已验证：Full/7 个主因子为 8/8 Oracle，通过 `uncalibrated` 独立系数加载、`no_final_cost` 的 plan-id 选择、`single_index` 的 INTERSECT 禁止；`no_coverage` 风险行在显式授权下可运行。

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

**仍需完成：** 需要在正式全量 workload 上重复该检查，并把 Python 子进程启动、LLM 请求、重试和 token 统计与其他 E1/E2 臂一起汇总。

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

### P1：workload 定义已扩展，正式结果仍需跑完整集合

此前“5 个正例 + 5 个拒绝例、24 个固定查询”的描述已经过时，当前文件实际为：65 条 BoundIR、65 个 Oracle 答案（59 个非空）和 44 条 NL。BoundIR catalog 的 family 分布为 `T=20`、`Z=11`、`TZ=23`、`H=2`、`TH=3`、`ZH=2`、`TZH=4`；选择率包含 small/medium/large/mixed/empty/boundary。NL 按 harness 分类为 supported=16、reject=17、clarify=11。seed、manifest 和 semantics_version 已写在 workload 文件中。

这项数据集覆盖整改已经完成，但本轮运行仍使用 `--limit 3`，所以运行证据还只是 smoke。正式比较必须在同一冻结 workload 上去掉 `--limit`，并对全部 65 条 BoundIR/44 条 NL 记录成功率、失败率、超时、Oracle 分母和分层统计；P50/P95 还要使用已选定的 cold 或 warm 协议，而不能从本轮 3 query 结果外推。

后续汇总仍应按 family、选择率、空结果/边界、DTW/Fréchet/Hausdorff、不同 k 和 E1 的 supported/reject/clarify 分层报告；不能只报告成功样例。

### P1：HBase 版本与 RegionServer 元数据（探测已修正，环境仍有限定）

本轮修复已经得到运行时证据：`audit-ablation-smoke/meta.json` 和 `audit-ablation-risk/meta.json` 均写入 `hbase_client_implementation_version=2.2.3`、`hbase_client_specification_version=2.2`、`hbase_client_version_source=classpath_pom_plus_lib_hbase_client_manifest_spec`、`hbase_client_jar=/home/jaytang/projects/llm-kv/target/kart.jar`，并写入 `region_server_count=1`。因此“版本字段为空”和“RegionServer 数量缺失”不再是当前实现的阻塞问题。

仍然存在的是环境事实，而非探测缺陷：`hbase_env` 同时记录 client 2.2.3 与 cluster 2.1.2，属于单节点混合 classpath；`hbase_client_jar` 指向应用 fat jar，版本来源已由探测器标明，不能将该字段误写成干净的独立 `hbase-client` 运行时证明。结论只能写：

> 在当前单节点、混合 classpath 的 HBase 环境中……

不能外推到干净的 HBase 2.2.3 集群、多 RegionServer 或生产负载。若要消除这一限定，需准备 client/server 版本一致的隔离环境，并重新运行 `doctor`、`hbase-evidence` 和全量消融；当前代码在探测失败时已经把字段写为 `null` 并输出 warning，正式门禁仍应拒绝缺失值。

### P1：运行 provenance 必须从 dirty 变为可复现

本轮两个结果的 `git_commit` 都是 `b9e9ac6`，但 `git_worktree_dirty=true`（WSL 同步时 `.vscode/settings.json` 发生换行差异）。`.vscode` 的换行差异已经恢复；本次文档更新本身会使工作树出现文档修改，因此已有 smoke 结果仍不能当作 clean-tree 正式数据。正式运行前应冻结包含实验文档和代码的 commit，确认 `git status --porcelain` 为空，再生成结果；若结果目录产生后工作区变化，应保留运行时的 dirty 标记并把该批次降级为审计 smoke。

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

本轮复核命令使用了 `--limit 3`。正式 workload 运行必须去掉 `--limit`，让 E2/E3 覆盖全部 65 条 BoundIR；E1 使用完整 `nl_ir_v1.json` 的 44 条 NL，并在结果中保留各层分母。

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
- [x] workload 定义已扩展到 65 条 BoundIR、44 条 NL，并包含 family/选择率/空结果/边界与 reject/clarify 分层；完整全量运行仍待完成。
- [ ] 正式 workload 的 Oracle 全部通过；失败率、超时和成功子集分母写入汇总（当前 smoke 证据为 E3 18/18、Bao E2 9/9、LLMOpt E2 9/9）。
- [ ] `meta.json` 可重放，且结论明确限定在单节点混合 classpath 环境。

在这些对比实验门禁全部通过前，当前对比系统的正确表述是：**“对比实验 harness、固定基线 E3 smoke、Bao/LLMOpt E2 smoke 和轮换机制可运行，公平性整改进行中；尚未达到正式论文级多臂比较门禁。”** 消融专项的放行条件与当前阻塞项见第 6 节。

## 6. 消融实验专项复核（2026-09-25）

本节针对 `spec/ablation_experiment.md`、`experiments/suites/ablation.yaml`、`scripts/bench-ablation.sh` 及实际 WSL 产物复核。它不把消融结果与 E1/E2/E3 外部对比臂混写。

### 6.1 已确认正确的实现

| 检查项 | 当前证据 | 结论 |
|---|---|---|
| Full 基线 | suite 的 `base_arm: kart`；`audit-ablation-smoke` 的 `full` 行为 `planner_mode=kart`、`llm_requested=true` | 是 Full KART，不是 RulePolicy 冒充 |
| 主因素展开 | Full + `no_llm_rule`、`no_llm_best_first`、`llm_direct`、`no_fast_cost`、`no_final_cost`、`single_index`、`uncalibrated` 共 8 个 cell | 与规范 §4.2 对齐 |
| 风险因素 | `no_coverage` 在默认 smoke 中跳过；显式 `--allow-unsafe --factor no_coverage` 后 `unsafe=true` | 默认不会把不安全臂混入主表 |
| 因子语义 | `no_final_cost` 使用 `plan_id` 选择并出现较大的 `plan_regret_ms`；`single_index` 没有 INTERSECT；`uncalibrated` 读取独立冻结系数 | 不是通过缩小 beam 等近似替代 |
| 正确性门禁 | 主 smoke 8/8 Oracle；风险 smoke 1/1 Oracle | 只能作为 smoke 正确性证据 |
| 计时与审计字段 | 每行有 `factor`、`unsafe`、`arm_position`、`t_plan_ms`、`t_exec_ms`、`t_e2e_ms`、候选/执行指标；`t_e2e=t_plan+t_exec`，artifact IO 单独记录 | 字段边界基本符合规范 |
| HBase 环境记录 | 两个消融 smoke 的 `meta.json` 都有 client 版本、cluster 版本和 `region_server_count=1` | 探测字段完整；环境仍受混合 classpath 限制 |

已执行的最小复核命令与结果：

```bash
# Java 单元门禁（当前 Windows checkout 可复现）
mvn -q -Dtest=AblationHarnessTest,ComparativeHarnessTest test
# 结果：exit 0

# WSL 单查询消融 smoke
./scripts/bench-ablation.sh \
  --run-id audit-ablation-smoke \
  --limit 1 --trials 1 --cache cold
# 结果：8 行，Oracle 8/8；no_coverage 默认跳过

# WSL 风险 smoke
./scripts/bench-ablation.sh \
  --run-id audit-ablation-risk \
  --factor no_coverage --allow-unsafe \
  --limit 1 --trials 1 --cache cold
# 结果：unsafe=true、allow_unsafe=true，Oracle 1/1
```

### 6.2 正式公平门禁事项（2026-09-26 状态）

以下 A–F 在 **正式跑数**（`abl-main-cold/warm-20260926`、`abl-risk-no-coverage-20260926`）上已核验通过；历史 smoke（`--limit 1`、`cache_enforced=false`、dirty tree）叙述保留作对照。仍未闭合的仅两项可选增强：三次独立 cold 重复、按臂独立进程隔离。

#### A. P0：全量 65 BoundIR — **已完成**

正式主表无 `--limit`；`suite_ablation_bound_query_count == 65`。每安全因子 Oracle 63/65；失败 query 为 `t_large_3` / `empty_far_3`（`timestamp before epoch`）。风险臂完整 65 + artifacts；未暴露 Coverage 漏查——不得改写为“CoverageCheck 不重要”。

#### B. P0：可证明 cold latency — **本批 cold 已 `cache_enforced=true`；多样本/进程隔离未做**

正式 cold：`KART_DROP_PAGE_CACHE` + helper，`hbase_flush`/`os_flush` = `520/520`，`cache_enforced=true`。`sequential_arm_cache_carryover=true` 仍成立。三次独立 cold 重复与按臂独立进程**未做**（用户显式排除）。

#### C–E. P1：cost meta / JSONL 键 / `--factor` 配对 Full — **已落地并经正式 run 复核**

`factors[]` 含校准路径与 SHA；`uncalibrated.cost_calibrated=false`；每行有 `llm_calls`/`tokens_*`；safe `--factor` 自动配对 Full。

#### F. P1：clean worktree — **正式 run 时已满足**

冻结 commit `6ef1794`，`git_worktree_dirty=false`。

### 6.3 可直接执行的正式消融流程

以下命令在 WSL canonical workspace 执行；PowerShell checkout 只用于查看文档，避免两个 workspace 的 jar/脚本不同步。

```bash
cd /home/jaytang/projects/llm-kv
git status --porcelain                 # 正式 run 前必须为空
./scripts/kart.sh sync
./scripts/kart.sh sync-check
./scripts/kart.sh rebuild
./scripts/kart.sh doctor
./scripts/kart.sh hbase-evidence

# 1) 主表：Full + 7 个安全主因素，完整 65 条 BoundIR；默认跳过 no_coverage
./scripts/bench-ablation.sh \
  --run-id abl-main-cold-20260926 \
  --cache cold --trials 1

# 2) warm 附录：同一 workload、同一 suite、5 个计时样本
./scripts/bench-ablation.sh \
  --run-id abl-main-warm-20260926 \
  --cache warm --trials 5

# 3) 风险表：完整 65 条，显式授权，单独保存 artifact
./scripts/bench-ablation.sh \
  --run-id abl-risk-no-coverage-20260926 \
  --factor no_coverage --allow-unsafe \
  --cache cold --trials 1 --keep-artifacts
```

冷主表命令只有在 `KART_DROP_PAGE_CACHE=1` 且每次 page-cache drop 成功时才是正式 cold；否则按 `meta.json.cache_enforced=false` 降级为 smoke。正式汇总前至少检查：

```text
suite_ablation_bound_query_count == 65
git_worktree_dirty == false
cache_enforced == true（cold 主表）或 cache == warm（warm 附录）
主表无 unsafe=true 的行
每个 (query_id, factor) 均有预定 trials，且 ok_oracle/fail_class 可统计
```

### 6.4 消融 readiness 结论（2026-09-26 更新）

- **正式跑数已完成**：`abl-main-cold-20260926`（65×8，`cache_enforced=true`）、`abl-main-warm-20260926`（trials=5）、`abl-risk-no-coverage-20260926`（65，artifacts）。汇总见 `docs/ablation-formal-findings-2026-09-26.md`。
- **成功子集**：每安全因子 Oracle 63/65；失败 query 为 `t_large_3` / `empty_far_3`（`timestamp before epoch`），与 Coverage 漏查无关。
- **风险臂**：未暴露 CoverageCheck 漏查；不得改写为“CoverageCheck 不重要”。
- **仍未做（显式排除）**：三次独立 cold 重复；按臂独立进程以消除 `sequential_arm_cache_carryover`。
- **表述边界**：单节点混合 classpath；延迟仅成功子集；`no_llm_*` 更快只说明去掉 LLM 后规划开销下降。

## 7. 最终放行清单（跑数门禁）

- [x] Full/7 安全因素/`no_coverage` 语义与默认跳过
- [x] `factors[]` cost 路径/SHA256；`uncalibrated=false`
- [x] JSONL 统一 `llm_calls`/`tokens_in`/`tokens_out`
- [x] safe `--factor` 配对 Full；风险不配对；`ablation_pair_full` 按实际 cell
- [x] warm 预热轮换写入 meta
- [x] 主表去掉 `--limit`，65 BoundIR
- [x] 风险表去掉 `--limit` + artifacts
- [x] cold `cache_enforced=true`（本批正式 cold）
- [x] warm 附录 trials=5
- [x] 运行时 clean tree（`6ef1794`）
- [x] 成功子集表 + 配对 delta + 失败/风险归因成文
- [ ] 三次独立 cold 重复（可选，未做）
- [ ] 独立进程隔离缓存（可选，未做）

## 8. 2026-09-27 当前修复版起步阶段验收

**结论：可以启动诊断性消融比较。** 验收范围是代码逻辑、运行入口、Oracle 正确性与基本公平标记；不把本次小批量当作论文最终性能表。2026-09-26 冻结批次的结论仍按其旧代码版本保留。

### 已核验

1. WSL 当前源与 Windows 工作目录的关键文件一致，`./scripts/kart.sh sync-check` 通过；重新打包 jar 后，`AblationHarnessTest`、`ZOrderAndTimeBucketTest`、`PlanValidatorTest` 通过，`./scripts/kart.sh doctor` 显示 HBase 连通且 `doctor: OK`。
2. 将旧批次失败的 `t_large_3`、`empty_far_3` 单独组成 2 条查询的诊断 workload，运行 `audit-ablation-edge-all-20260927`：Full + 7 安全因子共 16/16 行 `ok_oracle=true`，无 `timestamp before epoch`。`audit-ablation-edge-risk-20260927` 仅运行显式授权的 `no_coverage`，2/2 行通过 Oracle，`unsafe=true`，artifacts 已保留。
3. 新的 `TimeBucket.bucketsCovering` 对 epoch 前窗口返回空桶、跨 epoch 窗口截取索引访问起点；原 BoundIR 精确过滤保持不变。两条实际 HBase 查询确认原异常路径已消除。
4. `llm_direct` 的两条边界行均为 `llm_direct-rule-fallback`，且与纯直接规划臂分组；本次只证明回退后正确，不能用这两行推断直接规划的性能。其余被请求的 LLM 安全因子在这两条查询上未标记规则回退。
5. 本次诊断运行的 `formal_workload_complete=false`、`git_worktree_dirty=true`、`cache_enforced=false`；这些元数据与实际 2 条查询及诊断缓存设置一致。旧冻结批次的冷延迟和 63/65 分母不迁移到本次代码。

### 当前未完成与边界

1. **当前代码的全量结果未重跑。** 若要报告修复后 65 条每因子的正确率、失败率或延迟，需另起 run-id 跑完整 workload，核对 Full + 7 因子的分母、Oracle、回退行和风险臂；不能将旧 `6ef1794` 的延迟与本轮 2 条修复结果拼成一张表。
2. **冻结性能结论仍需新证据。** 当前工作树未提交，诊断运行没有执行可证明的 cold 缓存清理。正式发布新版本的性能表时再冻结提交并统一缓存协议；三次独立 cold 重复与按臂进程隔离仍是后续增强，不是本次起步阶段的阻塞项。
3. **同步脚本默认源目录不一致。** `scripts/kart-env.sh` 的内置默认值不是当前仓库目录，但 `scripts/sync-wsl-workspace.sh` 的注释仍写当前仓库位置。本机通过环境变量指向当前仓库，`sync-check` 已通过，故本轮运行不受影响；后续在无该环境变量的机器上复现前应统一默认值和文档。

以上证据足以确认消融入口能运行、边界错误已在小批量中消除、失败与回退不会静默混入纯臂。新版本全量结果须保留同一 workload、Oracle、缓存协议和逐查询配对，且延迟只在 Oracle 成功子集上统计并披露失败分母。
