# PDF 实验设计对照审计与真实实验就绪性

审计日期：2026-09-25  
审计对象：当前工作树 `F:\Projects\LLM_KV`、`spec/副本1.pdf`、`spec/design.md`、`spec/requirement.md`、`spec/task.md`、`spec/comparative_experiment.md`

说明：PDF 对照按 15 页全文内容核对；本审计不把 PPT 的版式视觉检查当成实验完成证据。

## 1. 先给结论

当前系统**可以进入下一步的受控真实操作**，但**还不能诚实地宣称已经完成 PDF 所对应的正式对比实验**。

- 作为 KART MVP 的端到端可行性验证：有充分的现有证据支持继续做小规模真实实验。
- 作为论文/报告中的 E1-E3 正式对比实验：暂不就绪。对比脚本、冻结 workload、外部方法桥接、运行元数据和重复试验产物尚未形成可复现的实验包。
- 作为“完整按 PDF 索引范围实现”：不完整。PDF 列出 Quadtree，但当前设计明确把 Quadtree 排除在 MVP 之外；这只有在报告中明确声明 MVP 范围时才是诚实的。
- 当前 HBase 服务可连接，但客户端是 2.2.3、集群状态报告为 2.1.2；文档已记录这是 TMan-spatial 捆绑旧 HBase 类造成的类路径问题。正式性能实验前应统一版本或把它作为固定实验限制披露。

因此，建议把下一步定义为：先完成“实验基础设施和可复现性补齐”，再启动正式 E1-E3；在此之前可以继续做单查询、故障路径和小批量真实 HBase 验证，但不发布跨方法性能结论。

## 2. 本次实际核对到的证据

以下是本次审计实际读取或执行到的证据，未把设计文档中的计划当成完成证据：

- `mvn test`：154 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。其中包含 jqwik 随机差分测试：`PlanDiffPropertyTest` 1000 次通过；测试总耗时约 6 分 25 秒。该次测试针对 WSL 工作副本；由于同步路径问题，本次没有证明 Windows 工作树的全部文件与 WSL 副本完全相同。
- 当前 HBase `doctor`：连接成功，列出 93 张表；JDK 为 1.8.0_482；客户端版本约 2.2.3；HBase cluster status 为 2.1.2。
- `experiments/results/tdrive_smoke_report.json`：记录 `tdrive_v1_ready` 的 24/24 个 T-Drive smoke case 与 Oracle 一致，包含 DTW、FRECHET、HAUSDORFF；该文件是既有产物，本次没有重新跑完整 24 条 smoke。
- `runs/chat-easy/summary.json`：8 个真实 LLM 聊天验收 case 全部 PASS。
- `runs/chat-handbook/summary.json`：22 个 handbook case 全部 PASS，包含正常查询、澄清、拒绝类查询、DTW 和 FRECHET。
- `catalog/tdrive_v1_ready.manifest.json`：状态为 READY，记录 47,102 条轨迹、2,724,388 个点、21 个数据文件对应的 T-Drive 快照事实。
- 本次重新执行 `verify-snapshot tdrive_v1_ready` 时，程序进入全量读取阶段；处理到 400/47,102 条轨迹时估计还需约 7,500 秒。为避免长时间占用 HBase，审计中止了该命令。因此本报告不把本次校验写成通过。

## 3. 按 PDF 方法链路逐项判断

### 3.1 自然语言到 Typed IR

**判断：MVP 已实现并有真实 LLM 验收证据。**

- 有 DraftIR/BoundIR schema、Binder、多轮澄清、用户确认和不支持查询拒绝。
- 已验证时间、空间、属性、时空组合，以及 DTW/FRECHET/Hausdorff Top-K。
- 已验证 COUNT、连续线段、未知相似度量、未注册地点等拒绝路径。
- 真实 LLM 证据是已有 `chat-easy`/`chat-handbook` 产物；当前直接执行 `doctor` 的 shell 没有加载 `LLM_*` 环境变量，所以不能把当前 shell 说成已配置好实时 LLM。

### 3.2 候选计划搜索

**判断：MVP 已实现；正式实验接口还未形成。**

- 代码和测试覆盖 Rule、Best-first、LLM action policy、LLM direct、预算和非法动作回退。
- 既有运行产物包含候选计划、验证报告、CostCard、PhysicalPlan 和 trace。
- 但正式对比要求的统一入口 `scripts/bench-plan.sh` 不存在，也没有 `plan.jsonl` 和统一 `meta.json` 结果目录；目前不能直接按 E2 规范批量重复跑并汇总 P50/P95。

### 3.3 Schema、覆盖和物理安全验证

**判断：MVP 已实现，单元/性质证据充分；全量快照本次独立复核未完成。**

- 测试覆盖结构、语义、覆盖、物理安全、非法 RowKey/物理字段注入和失败不返回部分结果。
- 已有 24/24 smoke 报告和真实聊天运行产物支持端到端正确性。
- 不能把 `manifest` 中的“posting verify already OK”备注等同于本次审计重新完成了 47,102 条轨迹的全量复核。正式实验前应完成一次可记录、可重复、可在合理时间内结束的 `verify-snapshot`，或明确采用已签名的既有验证产物。

### 3.4 代价模型

**判断：模型代码已接入，校准证据需要整理后才能用于正式结论。**

- `config/planner.yaml` 标记 `cost.calibrated: true`，模型版本为 `cost_v2_rs_sched`。
- `experiments/results/cost_calibration_report.json` 有 13/4/5 的 train/validation/test 划分和 MAE，但其中导出的 `weights`/`namedCoeffs` 为 0；`config/planner.yaml` 又包含另一组非零系数，二者不是同一份可直接复核的发布产物。
- `runs/cost_calib` 当前不存在，无法从仓库现状重放这次校准的原始 feature/trace 对。
- 这不证明代价模型一定错误，但证明“校准已可独立复现”目前没有成立。正式实验前需重新运行权威 Java fitter，保存原始 pairs、拟合报告、实际合并的 YAML 和 git commit，并在实验期间冻结。

### 3.5 HBase 执行和真实数据

**判断：已有真实 HBase 可运行证据；环境还不适合直接作为正式性能基准。**

- `doctor` 当前成功，HBase 表可见，已有 smoke 和 live chat 结果。
- 客户端 2.2.3 与 cluster status 2.1.2 的差异已在 `docs/environment-lock.md` 记录，但正式性能数据应先统一服务端类路径，或在方法和结果中明确这是单节点、混合类路径环境。
- 当前 HBase 中存在很多历史/其他项目表。实验必须继续以 `tdrive_v1_ready` manifest 的表名为唯一范围，不能仅凭“表很多”推断快照隔离已经完成。

## 4. 对照正式 E1-E3 实验规范的未完成项

这些是当前仓库中可以直接确认的缺口，不是推测：

1. `scripts/bench-parse.sh`、`bench-plan.sh`、`bench-e2e.sh`、`bench-all.sh` 均不存在。
2. `experiments/workloads/nl_ir_v1.json` 和 `bound_ir_v1.json` 均不存在；现有 `tdrive_smoke.json` 是 smoke workload，不能自动当成 E1-E3 冻结 workload。
3. `experiments/third_party/` 及 DIN-SQL、SAG、Bao、LLMOpt 的 commit 固定和桥接产物不存在；因此不能声称已经完成外部方法对比。
4. 没有按规范生成的 `meta.json`、`parse.jsonl`、`plan.jsonl`、`e2e.jsonl` 和 `summary.md` 运行包。
5. 没有每个 query/arm 至少 3 次、P50/P95、cold/warm 分开、失败率单列的正式统计结果。
6. 没有完成规范要求的 E1/E2/E3 对比和消融实验；当前 chat 验收和 smoke 是系统验收证据，不是对比实验结果。
7. 没有 CD-Taxi 或多 RegionServer 实验。当前证据只支持单节点 T-Drive 结论，不能外推到 PDF 中列出的其他数据集或集群规模。
8. PDF 中列出 Quadtree；当前 MVP 设计和实现明确去掉 Quadtree。若论文实验问题要求比较 Quadtree，必须补实现；若不补，需在研究范围中明确“只验证时间、Z-order、Hash”。

## 5. 可复现性和文档问题

### 5.1 WSL 工作目录不一致（必须先修）

已确认：

- `scripts/sync-wsl-workspace.sh` 默认同步到 `/home/jaytang/projects/llm-kv`。
- `scripts/wsl-test.sh`、`docs/how-to-run.md`、`docs/environment-lock.md` 使用 `/home/jaytang/projects/llm-kv`。
- 当前 WSL 中实际用于本次测试的是 `/home/jaytang/projects/llm-kv`。

这会导致“Windows 已改代码，但 WSL 测试的是另一份副本”。解决指导：选定一个 canonical WSL 路径；统一上述脚本和文档；同步后比较整个工作树的 commit、关键文件 hash 和 `KART_SYNC_LATEST.txt`，再生成实验 run。

### 5.2 完成标记与待验证文字冲突

`spec/task.md` 中 P1 的若干项目标为 `[x]`，同时括号仍写着“全量 HBase 写入待用户机验证”。解决指导：在一次可追溯的全量验证后保留 `[x]` 并删除待验证措辞；如果验证尚未完成，就把标记改为未完成/部分完成，避免读者把计划状态当成实测事实。

### 5.3 工作树尚未冻结

本次审计开始时 `git status` 显示大量已修改、删除和未跟踪文件。正式实验前必须提交或打 tag，记录 `git_commit`；否则同一个 workload 可能在不同源码状态下运行，无法诚实比较。

## 6. 建议的下一步顺序

1. 统一 WSL 路径并重新同步；在同一 commit 上重跑 `mvn test`、`doctor`。
2. 解决或冻结 HBase 2.2.3/2.1.2 类路径差异，保存服务端和客户端版本证据。
3. 完成一次全量 snapshot posting 验证，保存带时间、manifest、commit 和退出码的日志；若耗时不可接受，先优化验证器再进入正式实验。
4. 重新生成代价校准原始 pairs，使用权威 Java fitter，保存可重放的校准包；实验窗口内冻结系数。
5. 冻结 E1/E2/E3 workload、Oracle、LLM endpoint/model（不保存密钥）和第三方 commit。
6. 实现四个 `bench-*.sh` 入口及 `meta.json`/JSONL 产物；先跑 KART、fullscan、RBO、CBO，再接外部方法桥。
7. 每个 query/arm 至少 3 次，cold/warm 分开，报告成功率、失败率、P50/P95、规划/执行拆分和 Oracle 一致率。
8. 在所有正式运行前明确 Quadtree、CD-Taxi、多节点是否属于研究问题；未实现或未测试的部分不得写成已完成。

## 7. 诚实性结论

可以如实写：**“KART MVP 已在 Java 8、单节点 HBase/T-Drive 环境完成代码级测试，并有 24/24 smoke 与真实 LLM handbook 验收证据；系统具备继续开展受控真实查询实验的基础。”**

目前不能如实写：**“已完成 PDF 要求的完整对比实验、所有索引、外部基线、多数据集/多节点验证，或已得到可发表的性能结论。”**

本审计文档只记录已经看到的证据和明确缺口；没有把未运行的实验、未存在的脚本或未复核的全量校验写成已完成。



