# KART 需求文档（Requirement）

- 版本：v0.1，2026-09-21
- 状态：已与开发者确认关键决策，待实现
- 上游依据：`spec/IMPLEMENTATION_PLAN.md`（完整技术方案）、`spec/副本1.pdf`（导师讨论版 PPT）
- 配套文档：`spec/design.md`（设计）、`spec/task.md`（任务清单）
- 本文目标：用最少篇幅锁定 MVP 要做什么、不做什么、怎样算做完。细节以 IMPLEMENTATION_PLAN.md 为准，本文与其冲突时以本文（更新的决策）为准。

---

## 1. 目标

构建一个 **LLM 驱动的、面向 HBase 的轨迹查询系统 MVP**：

1. 用户用自然语言提出轨迹查询；
2. LLM 把查询理解为**类型化中间表示（Typed IR）**，信息不足时通过多轮对话澄清；
3. 系统在合法算子空间中搜索多个候选访问计划，由 **LLM 或规则策略**指导；
4. **确定性验证器**保证候选计划的覆盖与语义正确，只在安全计划中按代价估计选择；
5. 编译为 HBase Scan/Get 执行，精确过滤后返回轨迹结果或 DTW Top-K；
6. 结果与 **FullScan Oracle** 完全一致。

MVP 的定位是"尽快跑通一条端到端可行链路"，不是论文最终实验系统。

## 2. 范围

### 2.1 支持的查询（四类 + Top-K）

| 类型 | 说明 | 示例 |
|---|---|---|
| 时间范围 | 在 [start, end) 内有采样点的轨迹 | "2008-02-03 早上 8 点到 10 点有记录的轨迹" |
| 空间范围 | 在闭矩形 G 内有采样点的轨迹 | "经过区域 G 的轨迹" |
| 时空相交 | **同一个采样点**同时满足时间与空间条件 | "2 月 3 日 8–10 点经过 G 的轨迹" |
| 属性等值 | vehicle_id 等值过滤（可与上面组合） | "出租车 3644 在 2 月 3 日的轨迹" |
| 相似 Top-K | 先按上述条件筛候选，再对完整轨迹算 DTW，返回前 K | "……中与轨迹 R 最相似的 2 条" |

语义固定为：

- `OBSERVED_POINT`：以采样点存在性判定，不做线段插值；
- 时间半开区间 `[start, end)`，内部 UTC 毫秒，自然语言默认时区 **Asia/Shanghai**；
- 空间为**闭边界轴对齐矩形**，用户用 WGS84 经纬度描述，系统转换为 UTM 50N 米制坐标；
- DTW 按 `FULL_TRAJECTORY` 计算，欧氏局部距离，无归一化，结果按 `(distance, tid)` 升序；
- 参考轨迹默认从结果中排除。

### 2.2 明确不做（MVP）

- 插值线段相交（LINEAR_SEGMENT）、圆/多边形、三维轨迹
- COUNT / GROUP BY / 聚合、停留（dwell）条件、复杂 Join
- Fréchet / Hausdorff 相似度（请求时返回 UNSUPPORTED，不静默替换为 DTW）
- Quadtree 索引（PPT 中列出，MVP 只做时间 + Z-order + Hash）
- 动态数据更新、索引自维护、Redis 缓存、Phoenix、在线地理编码
- HTTP 服务（先 CLI，HTTP 后置）
- 基线对比实验（MVP 只要求与 Oracle 一致；规则规划器作为无 LLM 回退保留，但不做性能对比报告）
- 全局最优计划的任何声明；MVP 用预算与停滞条件结束搜索

## 3. 已确认的技术决策

| 项 | 决策 |
|---|---|
| 语言/构建 | Java 8 + Maven，单模块、按 package 分层 |
| 存储 | 现有 WSL Ubuntu-22.04 中的 HBase 2.2.3（伪分布式单节点，外置 ZooKeeper 3.4.10 @ localhost:2181，rootdir 为本地文件系统）；客户端锁定 `hbase-client 2.2.x` |
| 应用运行位置 | **在 WSL 内运行** Java 应用，代码放 `F:\Projects\LLM_KV`，通过 `/mnt/f/Projects/LLM_KV` 访问 |
| LLM 接入 | OpenAI 兼容 Chat Completions 协议；供应商与模型**暂不指定**，配置项 `LLM_BASE_URL`、`LLM_MODEL`、`LLM_API_KEY` 全部来自环境变量；`MockLlmClient` 用于回归测试 |
| 索引 | 时间索引 `idx_time`、Z-order 空间索引 `idx_zorder`、Hash 等值索引 `idx_hash`（仅 vehicle_id） |
| 坐标 | 导入时 WGS84 → UTM 50N (EPSG:32650)，使用 **Proj4J** |
| 数据 | 直接导入 `datasets/tdrive/` 全部 21 个 part 文件（约 106 MB） |
| 交互 | CLI 多轮对话；缺信息时终端提问，补全后继续 |
| 时区 | Asia/Shanghai |
| 开发默认参数 | chunk 最大 256 点；时间桶 10 分钟；Z-order 层级 L=8；shard S=4；空间域由构建期 profiling 得出并加边距后固定于 manifest。全部可配置，非最优值 |

## 4. 数据事实与假设

### 4.1 已核实的数据事实

- 路径 `datasets/tdrive/part-00000` … `part-00020`，Spark 文本输出。
- 每行一条轨迹：`<taxiId>-<segmentId>-MULTIPOINT Z((lon lat epoch_ms), (lon lat epoch_ms), ...)`。
  - 示例：`3644-3644_1-MULTIPOINT Z((116.37497 39.85789 1201930859000), ...)`
  - `segmentId` 形态不统一（`3644_1` 或 `33_1201930781`），只作为不透明字符串使用。
- 坐标为 WGS84 经纬度（lon, lat），时间已是 UTC 毫秒。
- 数据覆盖 2008-02 北京一周左右。
- 存在完全重复的点（同 t、同坐标）以及同一时间戳大量重复的静止点。

### 4.2 假设（写入 manifest，后续可推翻）

- A1：数据**已按行程切分**，MVP 直接把 `taxiId-segmentId` 作为 `trajectory_id`、`taxiId` 作为 `vehicle_id`，**不再做 gap-session 重切分**。
- A2：同一轨迹内按 timestamp 排序；完全重复点去重；同时间戳不同位置保留首个并计数报告。
- A3：坐标系为 WGS84（T-Drive 官方说明），投影到 UTM 50N 后单位为米。
- A4：空轨迹拒绝；单点轨迹允许参加范围查询，DTW 也允许（长度 1）。
- A5：数据静态不变；每次查询固定读取一个 READY manifest。

## 5. 功能需求

### FR-1 快照构建（SnapshotBuilder）

- FR-1.1 解析 T-Drive 格式，输出规范点（vehicle_id, trajectory_id, tid, seq, timestamp_ms, x_m, y_m）及拒绝记录报告。
- FR-1.2 按最多 256 点切块，写入 `traj_raw`、`traj_meta`。
- FR-1.3 构建 `idx_time`（块时间范围覆盖的所有桶）、`idx_zorder`（块 MBR 相交的所有 Morton 单元，不能只用中心点）、`idx_hash`（vehicle_id 稳定 128 位哈希）。
- FR-1.4 构建后做全量 posting 校验（重算每块期望 posting 并核对），通过后 manifest 从 BUILDING 变为 READY。
- FR-1.5 生成 StatsSnapshot（桶/单元 posting 计数、块大小分布、轨迹长度分布、一致抽样样本）。
- FR-1.6 提供 `build-fixture`：生成 IMPLEMENTATION_PLAN.md 第 18 节的确定性合成数据（R/A/B/C）。

### FR-2 自然语言到 IR（SemanticParser + Binder）

- FR-2.1 LLM 只接收逻辑目录（字段、能力、默认语义）和用户上下文，输出符合 `draft-ir.schema.json` 的 DraftIR；**不得接触 RowKey、表名字节、Region**。
- FR-2.2 Schema 校验失败时带错误让 LLM 修复，最多 2 次。
- FR-2.3 缺少用户事实（日期、区域、参考轨迹、K、相似度度量）时，输出 NEED_CLARIFICATION 及最少问题列表；CLI 在终端提问，用户回答后合并上下文重新解析；用户已给的信息不重复问。
- FR-2.4 确定性 Binder：时区 → epoch_ms、经纬度矩形 → UTM 米制矩形、外部 trajectory_id → tid、数据集 → manifest；产生 BoundIR。
- FR-2.5 不支持的请求（COUNT、dwell、Fréchet 等）返回 UNSUPPORTED_QUERY 并列出支持范围。
- FR-2.6 生成可读语义摘要（含"采样点语义"说明）供用户确认；确认状态只是交互元数据。
- FR-2.7 LLM 超时/不可用：有结构化 IR 走确定性规划；只有 NL 则返回解析失败。

### FR-3 候选计划搜索（SearchEngine）

- FR-3.1 搜索状态包含当前访问子图、未满足义务、Fast Cost。
- FR-3.2 合法动作集合由规则层根据 IR 与目录生成（START 单索引、INTERSECT 另一索引、REPLACE 索引、FINISH）；LLM 只能返回 `action_id`，非法动作被拒绝并记录。
- FR-3.3 构造器自动补齐必需后缀：Deduplicate → FetchTrajectoryChunk → ExactSTFilter → ProjectTrajectoryIds → [ExcludeReference → BatchGetTrajectory → Similarity → TopK]。
- FR-3.4 保留无 LLM 的规则策略（同一动作空间）作为回退与测试路径。
- FR-3.5 预算：最大 LLM 调用数、最大候选数、最大规划时间；到达即停止；始终保留 FullScan 兜底计划。

### FR-4 验证（Validator）

- FR-4.1 结构校验：Plan JSON 符合 `plan.schema.json`，节点拓扑序、类型匹配。
- FR-4.2 语义校验：IR 的全部谓词都出现在 ExactSTFilter；Top-K 必须先过滤再重建再评分；ExcludeReference 与 IR 一致。
- FR-4.3 覆盖校验：每个索引访问节点的候选集合必须覆盖 IR 对应谓词的全部匹配块（时间桶枚举完整、Morton 区间保守覆盖、hash 前缀全 shard）。
- FR-4.4 物理安全：所有 Scan 区间 start < stop、位于正确表和 shard 前缀内、无区间截断。
- FR-4.5 只有通过全部校验的计划才能获得 `SafePlanHandle`；外部输入中的 `safe=true` 一律忽略。

### FR-5 代价估计与选择（CostModel + PlanSelector）

- FR-5.1 Fast Cost：搜索期对补全计划的粗估（区间数、posting 估计、候选块估计、回表字节、DTW cell）。
- FR-5.2 Final Cost：对 SafePlan 集合的精细估计，同一特征定义与系数。
- FR-5.3 输出 CostCard：估计值、主要成本项、样本量与低/中/高不确定性；不输出无校准依据的置信度。
- FR-5.4 只在 SafePlan 集合中选最低预测成本；统计只影响代价，不影响覆盖。
- FR-5.5 MVP 的系数允许使用手工初值，须标明"未校准"。

### FR-6 编译与执行（Compiler + Executor）

- FR-6.1 编译器根据 BoundIR、逻辑计划、manifest 生成 PhysicalPlan：每个 Scan 的表、start/stop 字节、列、caching；每个 Get 的 raw_key。
- FR-6.2 半开区间与 `prefixSuccessor` 语义正确，全 FF 前缀特殊处理。
- FR-6.3 执行器完成 Scan/Get/BatchGet、候选集合运算、块解码、ExactSTFilter、轨迹重建（按 seq 去重）、DTW、Top-K。
- FR-6.4 失败语义：超时/内存超限/数据缺失返回显式状态（RESOURCE_EXHAUSTED / EXECUTION_FAILED / DATA_INTEGRITY_ERROR），**不返回部分正确答案**。
- FR-6.5 全扫描兜底走相同精确后缀。

### FR-7 Oracle 与回归

- FR-7.1 内存 KV backend（字节序有序 Map）实现与 HBase 相同的 Scan/Get 语义，供单元测试无 HBase 运行。
- FR-7.2 FullScan Oracle：对任意 BoundIR 直接遍历全部块计算标准答案。
- FR-7.3 差分测试：`ExactFilter(IndexedCandidates(P,q)) == Oracle(q)`，覆盖边界点、跨桶、跨单元、多 shard、空结果、k 大于候选等情形。
- FR-7.4 MockLlmClient 能驱动 `query-nl` 完成端到端回归。

### FR-8 CLI

必须提供的子命令：`doctor`、`profile-data`、`build-fixture`、`build-snapshot`、`verify-snapshot`、`query-ir`、`query-nl`（多轮）、`explain`。

## 6. 非功能需求

- NFR-1 **正确性优先**：覆盖与语义是硬约束，低成本不能补偿不正确。
- NFR-2 **确定性**：同一 BoundIR + manifest + 计划 → 相同物理字节；同一规则策略 → 相同计划。
- NFR-3 **可观测**：每次查询保存 IR、候选计划、验证报告、CostCard、物理计划摘要、逐算子 trace（rows/bytes/elapsed）、LLM 调用次数与 token；拿不到的实测指标记 `null` 而非 0。
- NFR-4 **可复现**：数据版本、索引版本、语义版本、统计/模型版本分开记录在 manifest。
- NFR-5 **安全**：密钥只从环境变量读取；日志不打印密钥；不把敏感真实数据写入物理请求摘要。
- NFR-6 **可配置**：所有参数（chunk、桶、L、S、预算、并发、超时）来自配置文件，代码中只有默认值。
- NFR-7 **兼容**：编译级别 Java 8；依赖库版本须与 Java 8 兼容（见 design.md）。

## 7. Open Issues（已关闭 / 仍开放）

| # | 议题 | 状态 |
|---|---|---|
| OI-1 | LLM 供应商与模型 | **已关闭（2026-09-23）**：DeepSeek `https://api.deepseek.com/v1`，模型 `deepseek-chat`；见 `docs/environment-lock.md` |
| OI-2 | 投影实现 | 已关闭：Proj4J |
| OI-3 | 运行位置 | 已关闭：WSL 内运行 |
| OI-4 | 数据规模 | 已关闭：全量 21 个文件 |
| OI-5 | 默认参数 | 已关闭：256 / 10 min / L=8 / S=4，可配置 |
| OI-6 | 时区 | 已关闭：Asia/Shanghai |
| OI-7 | 基线对比 | 已关闭：MVP 不做 |
| OI-8 | HBase 2.2.3 与 Java 8 下 `hbase-client` 传递依赖冲突（Guava/Netty） | **开放**：P0 `doctor` 阶段验证 |
| OI-9 | T-Drive 空间域外/异常坐标的比例 | **已关闭（2026-09-22）**：见 `docs/tdrive-profile.md`；粗北京框外点 0%；**域外点拒绝整条轨迹**（domain = profile min/max ±1 km），不做 clamp |
| OI-10 | 区域名称（如"中关村"）到矩形的映射 | **已关闭（2026-09-23）**：`config/regions.yaml` 预置公开坐标近似框（zhongguancun / wangjing / guomao 等）；见 `docs/regions.md`。非行政区划；可按实验再改 |

## 8. 验收标准（MVP Done）

全部满足才算"跑通一个可行方案"：

1. `doctor` 通过：能从 WSL 内连接 ZooKeeper 与 HBase 2.2.3，列出表。
2. `build-snapshot` 对全量 T-Drive 成功发布 READY manifest，`verify-snapshot` 全量 posting 校验通过。
3. `query-ir` 对第 18 节 fixture 与 T-Drive 上一组固定查询（四类 + Top-K，至少 20 条），结果与 FullScan Oracle **完全一致**。
4. `query-nl` 使用 MockLlmClient 能完成端到端；使用真实 API 时能对至少 5 条示例句生成合法 BoundIR，缺信息时正确发起澄清而非编造。
5. 至少产生两个结构不同的安全候选计划（如 P_T、P_Z、P_TZ），非法动作不进入成本选择。
6. `explain` 能输出候选、验证报告、CostCard、选中计划与物理请求摘要；IR 与 Plan JSON 中不含任何 RowKey 字节或 HBase 命令。
7. 失败路径可演示：超预算 / 数据缺失 / 不支持查询分别返回对应状态，不返回部分结果。
8. 单元测试与差分测试在无 HBase 环境（内存 backend）下全部通过。
