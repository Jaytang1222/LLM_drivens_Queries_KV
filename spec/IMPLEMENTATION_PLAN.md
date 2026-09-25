# 面向 HBase 的 LLM 驱动轨迹查询系统：实现与实验交接方案

- 版本：v1.0，2026-09-21；状态栏更新 2026-09-25
- 项目目录：F:/Projects/LLM_KV。
- 建议代号：KART，暂定，未做名称查重。
- 目标：为其他 IDE/Agent 提供可讨论、可实现、可验收的完整技术方案。
- 当前状态：**MVP 已按本文与 `spec/design.md` 落地**（Java 8 + HBase 2.2.3；见 `spec/task.md` / `docs/environment-lock.md`）。对比实验 E1–E3（`bench-*.sh` / 第三方臂）见 `spec/comparative_experiment.md`，规范已锁、脚本另开。下文个别「首版」措辞若与 design / supported-semantics 冲突，以后两者为准。
- 主要依据：用户与导师讨论后的《LLM驱动的键值存储下轨迹数据查询-副本1.pptx》，14 页及备注。
- PPT 路径：C:/Users/jayta/Desktop/paper/PPT/LLM驱动的键值存储下轨迹数据查询-副本1.pptx。
- PPT SHA256：2EE98AC1C6F882EFA9948FE663B489B274576E3B6F2BD26971F02E478CCF5376。
- 本次核对：全部页的文本和备注，以及第 8–14 页的实际渲染图。
- 本文约定：“PPT 已定方向”“建议实现默认值”“待实验证明的假设”分开陈述。建议值不等于导师已确认，不等于论文最优设置。

## 0. 给接手 Agent 的执行摘要

先实现能正确执行轨迹查询的确定性 HBase 系统，再接入自然语言解析与 LLM 计划搜索。第一条验收链路使用合成数据与全扫描参考实现，不以聊天界面或 Mock 响应作为系统完成标准。

~~~text
自然语言 + 数据语义目录 + 查询上下文
  → LLM 生成 IR 草稿
  → Schema、字段绑定、语义约束校验；必要时澄清
  → 固定的 Typed IR
  → LLM 在合法动作集合中指导有限预算计划搜索
  → 增量可行性检查 + 快速代价反馈
  → 完整候选逻辑计划
  → 确定性预编译 + 完整语义/覆盖/物理安全检查
  → 最终代价估计，在安全候选集合内选择
  → 执行已验证的 HBase Scan/Get 物理计划
  → 精确过滤，按需重建完整轨迹，计算 DTW/Top-K
  → 结果、追踪日志与离线代价校准
~~~

必须遵守：

1. HBase 是唯一必需数据库。第一版不依赖 Redis、Phoenix 或额外空间索引服务。
2. LLM 不生成 startRow、stopRow、Region ID 或可执行代码。
3. Schema 合法不等于自然语言理解正确，也不等于索引覆盖完整。
4. 不实际执行所有候选计划来做在线选择，使用离线统计和确定性编译估价。
5. 部分计划只检查“是否可以安全完成”，不能标成已经验证可执行。
6. 覆盖和结果语义是硬约束，低成本不能补偿不正确。
7. chunk 是索引候选粒度，trajectory 是结果/相似度粒度。
8. 保留无 LLM 的确定性规划器与同搜索空间的代价搜索基线。
9. 所有新参数均为可配置的开发默认值，不是已测最优值。
10. 先跑通第 18 节的合成例子，再加载真实 T-Drive/CD-Taxi。

## 1. 与导师讨论版 PPT 的对应关系

| 页码 | PPT 内容 | 本方案落点 |
|---|---|---|
| 2–3 | 研究背景、问题定义 | 第 2 节，区分研究假设与已经证实的不足 |
| 4–6 | 三项挑战及解决方法 | 编译、预算化搜索、正确性三个核心模块 |
| 7 | 分层编译和主动交互 | 第 7–8 节 IR 与澄清协议 |
| 8 | LLM 动作、构造器、验证器、评估器 | 第 11 节搜索状态机 |
| 9 | Schema、索引、覆盖、物理安全 | 第 12 节验证器 |
| 10 | 整体框架和执行反馈 | 第 4、13、16 节 |
| 11 | HBase、数据、四类查询、三类索引 | 第 3、5、6 节 |
| 12 | Typed IR 字段与约束 | 第 7 节 |
| 13 | 算子、搜索、停止条件 | 第 9–11 节 |
| 14 | HBase 表、RowKey、物理映射 | 第 6、14 节 |

实现时必须解决的几处不一致：

- 第 10 页 Redis/ZREVRANGE 图按概念示意处理，落地统一为 HBase。
- 第 14 页 ZOrderRangeScan 对应 Scan(idx_time) 是目标表笔误，应为 idx_zorder。
- 第 11 页列出 Z-order、Quadtree、Hash，第 13 页算子和第 14 页表尚未补齐 Hash。本方案补齐，时间索引作为已有辅助访问路径保留。
- 第 12 页“物理隔离”实际指 IR 与存储细节解耦，不是数据库事务隔离。IR 不要求用户选索引。
- 第 7 页“早高峰车辆数”包含聚合，超出第 11 页四类查询。首版明确拒绝 COUNT，不把计数请求静默改成轨迹列表。
- 第 8 页“LLM+用户双验证”落实为必要的意图澄清和可选语义摘要确认。用户不审核查询计划；语义摘要确认不是形式化正确性证明。
- DTW/Fréchet/Hausdorff 在 PPT 中是候选能力。**已实现**离散 DTW、离散 Fréchet、对称 Hausdorff（`FULL_TRAJECTORY`）；未知 metric / 连续 Fréchet 等返回 `UNSUPPORTED_QUERY`，不能自动替换为 DTW。
- “不可能找到优化计划”只有在具备可靠下界和搜索空间定义时才能判断。首版使用预算和停滞条件，不声称全局最优。
- “只有一篇 NoSQL 工作”“KV 计划优化空白”等概括没有在本次做全面文献核验，不能作为实现前提。后续相关工作需要包括 Phoenix、KV 记录层等已有查询规划能力。

原 PPT 本次不改动；以上修正在本实现规格中生效。

## 2. 研究问题、输入输出与可检验假设

### 2.1 任务定义

给定自然语言轨迹查询、逻辑数据目录、固定数据/索引快照和统计信息，在有限规划预算内生成并执行满足查询语义和覆盖约束、预计成本较低的 HBase 访问计划，返回轨迹结果及可追踪的执行证据。

规划器输入：

- 已绑定并校验的 Typed IR。
- Index Catalog、算子能力集合、物理布局版本。
- StatsSnapshot、代价模型版本、可选 Region 元数据。
- 搜索预算和执行资源限制。

规划器输出：

- 逻辑 DAG、确定性物理计划。
- 验证报告及覆盖义务的检查结果。
- 成本特征、预测值和选择原因。
- 若没有安全计划，返回明确错误，或选择经验证的全扫描兜底。

端到端系统额外包含自然语言输入、澄清交互以及执行结果。

### 2.2 三个核心问题

| 研究问题 | 具体问题 | 方法 | 验证指标 |
|---|---|---|---|
| 语义到 KV 的衔接 | 用户不了解轨迹切块、表、RowKey、索引能力 | Typed IR、能力目录、分层确定性编译 | IR 语义准确率、可执行率、澄清率 |
| 有限预算计划搜索 | 时间/空间访问、集合合并、回表代价随查询变化 | LLM 指导合法扩展，共享代价模型反馈 | 端到端时间、规划时间、plan regret |
| 生成计划的正确性 | 合法 JSON 仍可能漏分片、漏边界、错误交集或提前 Top-K | 类型化算子、覆盖契约和物理检查 | 结果一致性、错误计划检出率、验证开销 |

### 2.3 不预设 LLM 一定优于传统规划器

只有两三个访问路径时，传统枚举非常便宜，LLM 的延迟可能超过执行收益。先验证：

1. 在不同数据和查询条件下，最快计划确实会变化。
2. 同一合法动作空间、代价模型与规划预算下，LLM 比规则或传统搜索找到更好的计划，或者更快找到同样好的计划。

若第 2 点不成立，不能宣称 LLM 查询优化已具备优势。可保留自然语言入口，重新定位优化部分。不要通过减少基线可用索引、隐藏模型调用延迟或制造无意义组合来证明创新。

## 3. 边界与建议技术栈

### 3.1 第一条可运行链路

以下是建议的实现顺序，不删除 PPT 的长期功能：

- HBase 为真实后端，另有基于字节序的内存 KV 后端用于单测。
- 先合成数据，再 T-Drive 和 CD-Taxi。
- 首版几何为闭边界轴对齐矩形。
- 时间为一个绝对区间 [start,end)，内部 UTC 毫秒。
- 首版语义为 OBSERVED_POINT：存在同一个采样点满足时间和空间条件。
- 先完成范围查询，再实现完整轨迹 DTW Top-K。
- 静态、不可变、已发布快照，查询期间不更新数据和索引。
- 先时间和 Z-order，再加入 Quadtree 和 Hash，最终对齐 PPT 的索引范围。

“存在采样点”不等于“物理车辆确实连续经过所有插值位置”。前端语义摘要必须说明采用采样点还是插值语义。

优先扩展是 LINEAR_SEGMENT 插值相交，必须新增跨 chunk 边界索引与过滤测试；不能直接用采样点实验的正确率为插值模式背书。

当前不做动态更新、Redis 缓存、索引自维护、动态修改 RBO 安全规则、复杂 Join、COUNT/GROUP BY、停留条件、三维轨迹和在线地理编码。

### 3.2 工程建议

建议 Java + Maven，单个应用进程按模块组织：

- 兼容性探测起点：JDK 11 + HBase 2.5 系列。M0 必须核对选定完整发行版本兼容矩阵，再锁定 Java/HBase/client/test-util 小版本。本文未验证本机安装或任何镜像可用。
- JSON DTO + JSON Schema + 显式语义检查。避免“只有 Schema 文件，没有跨字段逻辑”。
- 矩形判断自行实现即可；多边形扩展使用成熟几何库。
- Python 可做原始数据探查；实际索引生成、查询区间编码共用 Java codec，避免跨语言编码漂移。
- HBase 在 Linux/WSL2/受控容器运行。单节点验证功能；至少多 RegionServer 环境才评价跨机器通信。
- 必须验证客户端能访问 ZooKeeper 和 HBase 公布的 RegionServer 主机名，不能只验证端口映射。
- 优先 CLI，后提供薄 HTTP 接口。无需先开发复杂界面。
- LLM 通过模型无关客户端接入；配置模型 ID、端点、Schema、超时和调用上限。密钥只从环境读取。
- MockLlmClient 用于回归测试；真实实验必须标明模型、版本、调用次数与 token/延迟。
- 不引入 Phoenix 作为执行依赖，否则研究对象变成 Phoenix SQL 规划。可以作为额外应用层对照，但需单独实现等价语义。

## 4. 模块及输入输出契约

| 模块 | 输入 | 输出 | 不可越界的职责 |
|---|---|---|---|
| DatasetAdapter | 原文件、字段/时间/CRS 配置 | 标准点、拒绝记录报告 | 不猜不存在字段 |
| SnapshotBuilder | 点、切块/索引配置 | 表、目录、统计、manifest | 发布前验证索引 |
| SemanticParser | NL、逻辑目录、上下文 | DraftIR、来源映射、缺失项 | 不生成 HBase 字节 |
| IRValidator/Binder | DraftIR、语义目录 | BoundIR/澄清/拒绝 | 不保证任意 NL 理解正确 |
| SearchEngine | IR、目录、统计、预算 | 逻辑候选、搜索日志 | LLM 只能提交合法动作 |
| IncrementalValidator | 部分计划 | 可扩展性、未满足义务 | 不完整计划不执行 |
| Compiler/IndexAdapter | 完整逻辑计划、布局 | 物理计划、覆盖证据 | 统一确定性 codec |
| FullValidator | IR、逻辑/物理计划、manifest | SafePlanHandle/错误 | 检查完整语义与覆盖 |
| CostModel | 计划特征、统计、模型版本 | CostCard/延迟预测 | 不修改安全规则 |
| PlanSelector | 安全计划与预测 | 一条选定计划 | 不假定 HBase 自带通用 CBO |
| Coordinator/Executor | SafePlanHandle | 结果和运行统计 | 合并、读取、精确运算、重试 |
| FeedbackCalibrator | 执行日志 | 新模型版本 | 离线校准，实验期间冻结 |

数据版本、索引版本、语义预处理版本、统计/模型版本分开管理。统计过期可使性能变差；数据/索引不一致可造成漏查，不能用“过期一点没关系”统一处理。

## 5. 数据模型、预处理与语义

### 5.1 真实数据导入前的核验

本次没有读取实际 T-Drive/CD-Taxi 数据文件。T-Drive 常见包含车辆 ID、时间和经纬度，CD-Taxi 存在不同变体；实际文件列顺序、分隔符、坐标系、时区必须由 profiling 和配置确定。

必填配置：

| 字段 | 作用 |
|---|---|
| source_path、checksum | 原始文件与校验值 |
| field_mapping | 车辆 ID、时间、坐标真实列 |
| timestamp_format、source_timezone | 时间解析依据 |
| source_crs、target_crs | 源坐标系与米制投影 |
| segmentation_policy | 逻辑轨迹如何切分 |
| invalid_record_policy | 非法记录如何隔离、计数 |

北京和成都可以使用各自适宜的投影坐标系，不强行套一个投影区。不得把经纬度当米，或无依据标为 WGS84/GCJ-02。若坐标系无法确定，真实空间查询验收暂不成立。

### 5.2 规范化数据

~~~text
CanonicalPoint:
  vehicle_id        原始车辆 ID
  trajectory_id     外部稳定轨迹 ID
  tid               快照内部 uint64 轨迹 ID
  seq               轨迹内递增点序号
  timestamp_ms      UTC 毫秒
  x_m, y_m          平面米制坐标
~~~

建议逻辑轨迹划分为 vehicle_id + 本地日期 + gap session，阈值可配置。它是派生的逻辑轨迹，不伪称原始数据提供了真实行程 trip 标签。

顺序：解析、隔离非法值、坐标/时间统一、车辆内排序、重复点处理、同时间冲突处理、逻辑轨迹切分、分配 seq/tid、物理切块。公开每一步删除数量，不默认按异常速度大量删数据。

### 5.3 chunk 与跨块线段

建议开发默认每块最多 256 个 core 点。chunk_id 为轨迹内 uint32 递增编号，不是 Region。

~~~text
Chunk:
  tid, chunk_id
  seq_first, seq_last, core_point_count
  t_min_ms, t_max_ms
  min_x, min_y, max_x, max_y
  encoded_points
  halo_policy = NONE 或 NEXT_POINT
~~~

采样点首版每个点只属于一个 core chunk。为支持插值，可给前块附带下一块首点 halo，使跨块线段归前块所有。索引时间范围和 MBR 也必须包含 halo；只在执行阶段补读邻点却不扩大索引覆盖会漏查。

不跨 session/gap 连接线段；完整轨迹重建按 seq 去重 halo。空轨迹拒绝，单点轨迹可参加采样点查询。

### 5.4 四类查询语义

设 P(tau) 为逻辑轨迹 tau 的点集合，I=[a,b)，G 为闭矩形，缺失条件视为 TRUE：

~~~text
match(p,q) = temporal(p.t,I) AND spatial(p.position,G)
             AND attribute_predicates(tau)
A(q) = {tau | 存在 p ∈ P(tau)，match(p,q)}
~~~

时间范围、空间范围、时空相交使用同一公式，只是部分谓词为空。时空查询必须是同一个点同时满足条件；某轨迹 08:30 在区外、11:00 在区内，不满足 08:00–10:00 在区内。

Top-K：先求 A(q)，按 IR 排除参考轨迹，再对每条完整逻辑轨迹与参考轨迹计算 DTW，按 (distance,tid) 升序返回前 K 条。首版比较范围明确为 FULL_TRAJECTORY，不使用匹配 chunk 的局部距离冒充轨迹距离。

DTW 定义：

~~~text
D(i,j) = ||p_i-r_j||_2 + min(D(i-1,j), D(i,j-1), D(i-1,j-1))
D(0,0)=0，其他第 0 行/列为 +∞
distance = D(m,n)
~~~

首版不默认长度归一化、降采样、带宽或时间权重。后来改变必须更新 semantics_version，所有基线保持一致。复杂度 O(mn)，内存可滚动为 O(min(m,n))。

## 6. HBase 表、RowKey 与索引构建

### 6.1 固定编码契约

版本通过表名和 manifest 固定，不在首版 RowKey 再重复一个版本字段。

定义：

- U8、U32、U64：无符号固定宽大端编码，按无符号字节字典序比较。
- tid 为快照内唯一 uint64，chunk_id 为 uint32。
- shard(tid)=tid mod S，S 为快照固定配置，首版 4，仅为开发默认值。
- 字符串属性使用长度前缀/固定 hash 编码，禁止随意用可变宽十进制字符串比较数值。
- 时间桶 b=floor((t-epoch)/bucket_ms)，epoch 不晚于快照最早时间，保证 b 非负。
- raw_key = U8(shard) || U64(tid) || U32(chunk_id)，13 字节。
- prefixSuccessor(P)：从右向左找到非 FF 字节，加一并截断尾部；全 FF 前缀返回无有限上界，调用方须处理，不能溢出回 00。
- 所有 Scan 使用包含 start、不包含 stop 的半开区间；实现与所选 HBase 版本 API 行为一致并测试。
- 外部轨迹 ID、原始车辆字符串不直接混入数值 RowKey，由目录映射。

### 6.2 表结构

| 表 | RowKey | 内容 | 作用 |
|---|---|---|---|
| traj_raw_v1 | shard:1 + tid:8 + chunk:4 | d:payload、m:时间/MBR/seq/点数 | 保存完整原始块 |
| traj_meta_v1 | shard:1 + tid:8 | 外部 ID、vehicle_id、chunk_count、点数、时间 | 全轨迹重建与映射 |
| idx_time_v1 | shard:1 + bucket:8 + tid:8 + chunk:4 | i:raw_key | 时间候选 |
| idx_zorder_v1 | shard:1 + z_cell:8 + tid:8 + chunk:4 | i:raw_key | 空间候选 |
| idx_quad_v1 | shard:1 + leaf_id:8 + bucket:8 + tid:8 + chunk:4 | i:raw_key | Quadtree/时间候选 |
| idx_hash_v1 | shard:1 + field_tag:1 + hash128:16 + tid:8 + chunk:4 | i:raw_key、i:original_value | 真实字段等值候选 |

建议单列族起步也可；若拆 d/m 列族，必须说明是为只读元数据服务，不能随意增加大量列族。上述列族命名是应用设计，不是 HBase 内置字段。

traj_meta 是为轨迹级相似度补充的必要小表，PPT 尚未展示。获取元数据后，可按 shard||tid 前缀扫描完整轨迹，或根据 chunk_count 生成块 Get。

### 6.3 时间索引

为块时间范围 [t_min,t_max] 枚举从 b(t_min) 到 b(t_max) 的全部桶，写入相同 raw_key 的 posting。

查询 [a,b) 若时间以整数毫秒表示，枚举 b(a) 到 b(b-1)；a>=b 先拒绝。无时间条件则不得自己加入隐含时间窗口。

扫描每个 shard，使用桶区间和正确前缀。跨桶重复 posting 必须按 (tid,chunk_id) 去重。桶分辨率影响扫描/索引大小，不改变精确答案。

### 6.4 Z-order 索引

每个快照固定米制空间域 [xmin,xmax]×[ymin,ymax] 和层级 L，网格每轴 2^L 个单元。开发默认 L=8，真实实验调整。

- 将块 MBR 映射到所有相交单元，写入对应 Morton 编码 z_cell。
- 不能只用块中心点；长轨迹块可横跨许多单元。
- 点在网格线、域最大边界时遵守统一量化规则。查询矩形做闭边界保守覆盖，必要时多包括邻单元，精确阶段删除假阳性。
- 查询使用确定性四叉递归，完整覆盖的子树输出连续 Morton 区间，部分相交递归到底，合并相邻区间。
- 编译器对每个 shard 生成完整区间。若区间数量超过预算，只能合并成更宽范围或回退全域扫描，不能截断区间列表。
- 不在空间域内的数据必须在导入时报告并阻止发布，不能 silently clamp 到边缘；域外查询可与目录域求交。
- MBR 覆盖膨胀是需要测量的存储成本。若过大，可缩小 chunk；不能为省空间舍弃 posting。

### 6.5 Quadtree 索引

使用离线构建、叶单元互不重叠覆盖完整数据域的静态 Quadtree。Catalog 保存每个叶的边界、leaf_id、max_depth、划分版本。

块 MBR 与哪些叶相交，就在这些叶和相交时间桶的笛卡尔组合写 posting。查询遍历树得到全部相交叶；有时间条件扫描 leaf 前缀下的桶范围，无时间条件扫描整个 leaf 前缀。变深叶不得被当成固定长度路径前缀直接猜测。

此布局包含时间维度而 Z-order 表没有，实验必须说明区别。比较规划策略时使用相同 Catalog；如果比较“纯索引优劣”，需另做布局/复制因子控制。

### 6.6 Hash 索引

首版只对真实字段 vehicle_id 支持 equality。field_tag 固定字段 ID，hash128 使用稳定算法并记录版本，不能使用每进程变化的语言 hash()。

对车辆的每个 chunk 建 posting。查询必须扫全 shard 的 hash 前缀，并检查原值相等，或在精确过滤中保留相等谓词，以应对 hash 碰撞。唯一轨迹 ID 已知时可走 traj_meta/raw 前缀，不应额外强制走 Hash。

HashLookup 是应用层索引表访问，HBase 本身不是因为这个名字就提供 Hash 二级索引。

### 6.7 构建、发布与一致性

~~~text
读取/清洗 → 写新版本 raw/meta
  → 构建全部索引 → 写统计
  → 按同一 codec 检查 postings 与 raw
  → 运行全扫描对照和边界测试
  → 将 manifest 从 BUILDING 改为 READY
~~~

构建期间不得向用户暴露新快照。每次查询固定读取一个 manifest，不跨查询中途切换版本。READY 表禁止继续写入。HBase 跨表不自动提供本系统需要的事务快照；一致性来自离线构建和不可变发布协议。

完整检查应重算每块的期望 posting 并核对实际键；count/checksum 是辅助信息，计数相等不能证明没有漏项。若暂只完成抽样检查，应明确其证据强度，不能伪称已获得全量证明。

## 7. Typed IR：字段与完整样例

### 7.1 分层表示

- DraftIR：允许缺失项，附带来源片段与澄清问题，不能执行。
- BoundIR：引用已解析，类型/跨字段约束通过，不存在未绑定占位符。
- 用户确认状态单独记录为交互元数据，不把 CONFIRMED 当作数学正确性标志。
- 系统注入 snapshot，LLM 不凭空创造数据/索引版本。

最小必需字段：ir_version、source、semantics、result、snapshot；temporal/spatial/predicates/similarity 根据查询类型必填或为空。不会用到的谓词不强行补上。

### 7.2 完整 BoundIR 示例

以下为第 18 节合成数据的可解析示例，不冒充真实数据记录：

~~~json
{
  "ir_version": "1.0",
  "query_id": "q_fixture_001",
  "source": {
    "dataset_id": "fixture_v1",
    "entity": "trajectory"
  },
  "temporal": {
    "start": "2008-02-02T08:00:00+08:00",
    "end": "2008-02-02T10:00:00+08:00",
    "boundary": "[start,end)"
  },
  "spatial": {
    "crs": "LOCAL_METRIC",
    "geometry": {
      "type": "RECTANGLE",
      "min_x": 4.0,
      "min_y": 4.0,
      "max_x": 8.0,
      "max_y": 8.0
    },
    "relation": "INTERSECTS",
    "boundary": "INCLUDED"
  },
  "predicates": [],
  "semantics": {
    "mode": "OBSERVED_POINT",
    "coupling": "SAME_POINT"
  },
  "similarity": {
    "metric": "DTW",
    "reference_trajectory_id": "R",
    "scope": "FULL_TRAJECTORY",
    "exclude_reference": true,
    "local_distance": "EUCLIDEAN",
    "normalization": "NONE"
  },
  "result": {
    "mode": "TOP_K",
    "k": 2,
    "tie_breaker": "TID_ASC",
    "output_fields": ["trajectory_id", "distance"]
  },
  "snapshot": {
    "manifest_id": "fixture_v1_ready",
    "semantics_version": "point_dtw_v1"
  }
}
~~~

运行时 deterministic binder 将时间转换成 epoch_ms、参考 ID 转 tid、数据集映射到物理 Catalog，得到内部执行对象。公开 IR 仍然不包含任何 Scan/Get 字节。

### 7.3 Schema 与跨字段规则

所有对象默认 additionalProperties=false，算子和字段名用 enum，不允许任意表达式/脚本。

至少验证：

- 时间有效且 start<end；相对时间必须有固定 now/timezone 上下文。
- 坐标有限非 NaN，min<=max，CRS 可解析并与快照转换规则兼容。
- source 在目录中，属性在真实 schema 中。
- TIME_RANGE 必须有时间，SPATIAL_RANGE 必须有空间；可在内部推导 query kind。
- SAME_POINT 必须与 OBSERVED_POINT 配套。
- TOP_K 必须有已支持 metric、存在的 reference、正整数 k。
- result=TRAJECTORY_IDS 时 similarity 必须为空，不附加无意义 Top-K。
- 禁止 startRow/stopRow/table command/region 等字段递归出现在 IR。
- 快照 READY、算法能力启用。
- 带空间/时间的相似查询为“先限定候选，再比较”；不默认变成全库相似检索。
- 时间与数据域不相交可合法返回空结果，不是 Schema 错误。

## 8. 自然语言解析、澄清与失败逻辑

### 8.1 正常链路

1. 输入自然语言、数据集、已注册区域、日期/时区上下文。
2. 提供逻辑字段、查询能力和默认语义说明给 LLM，不提供 RowKey。
3. LLM 输出 DraftIR，并把字段绑定到原句或上下文来源。
4. JSON/Schema 检查后由确定性解析器规范化时间、ID、几何引用。
5. 检查缺失和冲突，必要时一次性提出最少澄清项。
6. 形成 BoundIR，给用户可读语义摘要；进入规划。

用户已经提供的上下文不重复询问。缺少日期、区域 G 未定义或“相似”未指定度量且没有公开默认配置时，不允许模型擅自填值。

### 8.2 有限修复协议

| 失败 | 行为 |
|---|---|
| 非法 JSON/额外字段/类型错误 | 带 validator 错误让 LLM 修复，最多 2 次 |
| 缺少用户事实 | NEED_CLARIFICATION，等待补充；无交互模式返回，不执行 |
| 目录找不到字段/参考轨迹 | 明确提示，不能随便替换成相似字段/ID |
| 未支持查询、COUNT、dwell、算法 | UNSUPPORTED_QUERY，列出实际支持范围 |
| 模型超时/不可用 | 有结构化 IR 则走确定性规划；只有 NL 则返回解析失败 |
| 预算耗尽 | 不再循环请求模型，保留错误和已完成信息 |

不同 LLM 互相投票、反向翻译和字段来源检查只能降低风险，不能证明任意自然语言理解完全正确。论文必须分别评价 NL→IR 语义准确率和“给定正确 IR 的执行正确性”。不得把两者合并宣称 100% 正确。

## 9. 算子集合、类型系统与物理计划

### 9.1 应用层类型

~~~text
ChunkRef      = (tid, chunk_id, raw_key)
ChunkRefSet   = 唯一 ChunkRef 集合，set 语义
ChunkBatch    = 已解码轨迹块
MatchedChunk  = 至少包含一个满足全部谓词的点/线段的块及匹配证据
TrajectoryIds = 去重 tid 集合
Trajectory    = 按 seq 排序、halo 去重后的完整逻辑轨迹
ScoredTraj    = (tid, distance)
~~~

候选运算始终按 (tid,chunk_id) 对齐。不能把索引 A 的 chunk_id 和索引 B 的 trajectory_id 直接求交。通过精确过滤后，才把“存在匹配块”提升为轨迹 ID。

### 9.2 算子定义

| 算子 | 输入→输出 | 物理实现/前提 |
|---|---|---|
| TimeRangeScan | 时间引用→ChunkRefSet | idx_time 上全 shard 范围 Scan |
| ZOrderRangeScan | 空间引用→ChunkRefSet | idx_zorder 上保守 Morton 区间 Scan |
| QuadtreeRangeScan | 空间及可选时间引用→ChunkRefSet | idx_quad 叶/桶前缀 Scan |
| EqualityLookup | 真实字段等值谓词→ChunkRefSet | idx_hash 前缀 Scan，加原值检查 |
| FullScanChunks | 快照→ChunkBatch | traj_raw 全域 Scan，兜底/Oracle 路径 |
| Intersect | 两个同粒度候选集合→ChunkRefSet | hash intersection，或已排序集合 merge |
| Union | 两个同粒度集合→ChunkRefSet | set union，所有分支完成才算完整 |
| Deduplicate | 候选流→ChunkRefSet | (tid,chunk_id) 去重，防重复 posting |
| FetchTrajectoryChunk | ChunkRefSet→ChunkBatch | 主表 Table.get(List<Get>)，分批 |
| ExactSTFilter | ChunkBatch→MatchedChunk | 执行器对原始点/段检查全部谓词 |
| ProjectTrajectoryIds | MatchedChunk→TrajectoryIds | 按 tid 去重 |
| ExcludeReference | TrajectoryIds→TrajectoryIds | 根据 IR 的 exclude_reference 执行 |
| BatchGetTrajectory | TrajectoryIds→Trajectory | meta Get + 全部 chunk Get/前缀 Scan，重建 |
| Similarity | Trajectory→ScoredTraj | 完整序列 DTW，参考轨迹读取一次 |
| TopK | ScoredTraj→有序结果 | 全部合格轨迹评分后用 heap 取 K |
| ReturnTrajectoryIds | TrajectoryIds→结果 | 按确定性 ID 顺序返回 |

HashLookup 可作为 EqualityLookup 的 API 别名，但序列化格式只能选一个规范名称。本文统一用 EQUALITY_LOOKUP。

这些算子都不是 HBase 原生空间 SQL 算子。HBase 提供 Scan/Get 等执行原语；我们实现类型、候选集合操作、轨迹重建和精确运算。非原生不代表不能优化，优化对象恰好是扫描、合并、回表和计算的数量及调度。

### 9.3 两次读取的区别

范围查询只需 FetchTrajectoryChunk 读取候选块并过滤。

FULL_TRAJECTORY DTW 还需 BatchGetTrajectory：某轨迹只在一个块命中查询区域，但比较对象是该轨迹全部块。可复用已读取块，但不能因为它们已在内存中就省略其他块。

必需后缀：

~~~text
候选访问子图
 → Deduplicate(tid,chunk)
 → FetchTrajectoryChunk
 → ExactSTFilter(全部 IR 谓词)
 → ProjectTrajectoryIds
 → [ExcludeReference]
 → BatchGetTrajectory(scope=FULL_TRAJECTORY)
 → Similarity(DTW)
 → TopK(k, tie=tid)
~~~

仅返回轨迹列表时，在 ProjectTrajectoryIds 后接 ReturnTrajectoryIds。

逻辑上 Similarity 只有在全部候选资格确定后才算完整；实现可流水计算已确认合格的轨迹，但不可提前终止或只对前 K 个命中进行计算。未证明下界的 DTW 剪枝不在首版范围内。

## 10. 实际候选计划格式

### 10.1 PlanEnvelope

受限 JSON DAG，nodes 是拓扑序，inputs 引用其他节点。禁止任意语言表达式、自由字符串谓词和任意 Java 方法名。

下面是第 7 节查询的完整规范候选。它是设计样例，不是本次真实模型调用产物；MockLlmClient 和契约测试可直接采用它。

~~~json
{
  "plan_version": "1.0",
  "plan_id": "p_time_z",
  "query_id": "q_fixture_001",
  "manifest_id": "fixture_v1_ready",
  "root": "n10",
  "nodes": [
    {
      "id": "n1",
      "op": "TIME_RANGE_SCAN",
      "inputs": [],
      "params": {"index_id": "idx_time_v1", "predicate_ref": "/temporal"}
    },
    {
      "id": "n2",
      "op": "ZORDER_RANGE_SCAN",
      "inputs": [],
      "params": {"index_id": "idx_zorder_v1", "predicate_ref": "/spatial"}
    },
    {
      "id": "n3",
      "op": "INTERSECT",
      "inputs": ["n1", "n2"],
      "params": {"key": ["tid", "chunk_id"]}
    },
    {
      "id": "n4",
      "op": "DEDUPLICATE",
      "inputs": ["n3"],
      "params": {"key": ["tid", "chunk_id"]}
    },
    {
      "id": "n5",
      "op": "FETCH_TRAJECTORY_CHUNK",
      "inputs": ["n4"],
      "params": {"mode": "BATCH_GET"}
    },
    {
      "id": "n6",
      "op": "EXACT_ST_FILTER",
      "inputs": ["n5"],
      "params": {"query_ref": "q_fixture_001"}
    },
    {
      "id": "n7",
      "op": "PROJECT_TRAJECTORY_IDS",
      "inputs": ["n6"],
      "params": {"key": ["tid"]}
    },
    {
      "id": "n8a",
      "op": "EXCLUDE_REFERENCE",
      "inputs": ["n7"],
      "params": {"similarity_ref": "/similarity"}
    },
    {
      "id": "n8",
      "op": "BATCH_GET_TRAJECTORY",
      "inputs": ["n8a"],
      "params": {"scope": "FULL_TRAJECTORY"}
    },
    {
      "id": "n9",
      "op": "SIMILARITY",
      "inputs": ["n8"],
      "params": {"similarity_ref": "/similarity"}
    },
    {
      "id": "n10",
      "op": "TOP_K",
      "inputs": ["n9"],
      "params": {"result_ref": "/result"}
    }
  ]
}
~~~

params 引用已固定 IR，而不复制一份可被 LLM 修改的时间/几何/K。编译和缓存使用规范化 IR 哈希；同一个 query_id 不能在中途绑定不同 IR。

### 10.2 候选结构的可比较差异

| 计划 | 候选子图 | 用索引处理什么 | 原文中的剩余精确处理 |
|---|---|---|---|
| P_T | TimeRangeScan | 时间必要条件 | 时间边界、空间、属性 |
| P_Z | ZOrderRangeScan | 空间必要条件 | 空间假阳性、时间、属性 |
| P_TZ | Intersect(Time, Z) | 两个必要条件的交集 | 同一点时空耦合、边界、属性 |
| P_Q | QuadtreeRangeScan(space,time) | Quadtree 叶和时间桶 | 同一点时空耦合及假阳性 |
| P_HZ | Intersect(Equality, Z) | 仅在 IR 真有等值谓词时合法 | 哈希碰撞、时空/属性精确值 |
| P_FULL | FullScanChunks | 无索引 | 全部精确语义 |

对照中所有计划使用相同精确后缀、同一 DTW 定义和快照。不能把一个候选比较完整轨迹、另一个比较匹配片段。

可扩展的几何分区计划：

~~~text
Union(
  ZOrderRangeScan(空间子域 G1),
  QuadtreeRangeScan(空间子域 G2)
)
~~~

仅在确定性构造器产生并检查 G ⊆ G1 ∪ G2 时允许。不能让 LLM 自由给出 G_interior/G_boundary 后直接相信它覆盖完整。首版关闭此动作，普通多索引计划跑通后再增加；它不是 LLM 优势已经成立的证据。

## 11. LLM 引导的有限预算搜索

### 11.1 RBO、LLM、代价模型的分工

- 规则层：枚举某状态允许的动作，执行安全重写和构造。是 RBO/规则系统的职责。
- LLM：决定先探索哪些合法动作，读懂成本瓶颈反馈，提出有预算限制的候选扩展。
- 代价模型：估计完整执行成本，不让 LLM 自己猜毫秒数。
- 搜索器：维护前沿、去重、预算和停止，最终候选用独立完整检查。
- 选择器：在 SafePlan 中最小化预测成本。这是我们应用层的 cost-based selection，不是 HBase 自带通用 CBO。

不动态修改安全规则，不把“LLM 反思并改写 RBO”加入当前交付。

### 11.2 搜索状态

~~~text
SearchState:
  state_id
  candidate_subgraph
  legal_actions[]
  necessary_conditions_covered[]
  pending_obligations[]
  query_hash, manifest_id
  depth
  fast_cost_card
  canonical_signature
~~~

必要条件覆盖不等于精确谓词已完成。空间索引命中后仍有 ExactSTFilter 的义务。

确定性补全器为每个可行访问子图添加第 9 节的正确后缀，从而得到用于比较的完整 roll-out plan。这样不会让“只有一个 Scan 的半截计划”因为遗漏回表和 DTW 而看起来特别便宜。

### 11.3 合法动作

MVP 使用粗粒度动作，避免为添加每个固定后缀发起一次 LLM 调用：

- START_ACCESS(index_id, predicate_refs)。
- INTERSECT_ACCESS(index_id, predicate_refs)。
- REPLACE_ACCESS(node_id, compatible_index_id)。
- CHOOSE_MERGE_IMPLEMENTATION(HASH_SET 或 SORT_MERGE)，仅在输入前提满足时。
- PARTITION_UNION：仅选择系统提供的可证明分区（TIME_BIPART / Z_QUAD）；已实现。
- FINISH：调用确定性补全器完成后缀。
- 无 LLM 时可用 RulePolicy（固定顺序）或 BestFirstPolicy（按 FastCost 选动作）。

不允许 LLM 改变 q 的时间、空间、粒度、metric、K，或删除必须的精确后缀。

### 11.4 真实 LLM 输出契约

LLM 输入是当前状态列表、每个状态的 legal_actions ID、简短成本卡和预算。输出只选动作 ID：

~~~json
{
  "response_version": "1.0",
  "proposals": [
    {
      "state_id": "s_z",
      "action_id": "a_intersect_time",
      "reason_code": "REDUCE_RAW_FETCH"
    },
    {
      "state_id": "s_z",
      "action_id": "a_replace_with_quad",
      "reason_code": "REDUCE_SCAN_RANGES"
    }
  ]
}
~~~

action_id 已由规则层绑定到具体可执行变换，reason_code 只用于可解释性，不构成验证依据。这种接口仍由 LLM 指导结构搜索，而不是让它输出无法约束的完整代码。

可另外实现“LLM 一次生成完整 PlanEnvelope”作为基线，仍受相同验证器约束。

### 11.5 搜索伪代码

~~~python
def search(ir, catalog, stats, budget):
    fallback = build_and_validate_full_scan(ir, catalog)
    frontier = deterministic_seed_states(ir, catalog)
    completed = {fallback}
    seen = set()

    while frontier and not budget.exhausted():
        cards = [fast_cost(complete_tail(s, ir), stats) for s in frontier]
        proposals = policy.propose(frontier, cards, budget)  # LLM 或基线策略
        next_states = []
        for proposal in proposals:
            if not action_exists_and_allowed(proposal, frontier):
                log_rejection("ILLEGAL_ACTION")
                continue
            state = deterministic_apply(proposal)
            if not incremental_check(state, ir, catalog):
                log_rejection("INFEASIBLE_EXTENSION")
                continue
            signature = canonicalize(state)
            if signature in seen:
                continue
            seen.add(signature)
            complete = complete_tail(state, ir)
            # 先廉价检查；最终物理验证在下方，不能先执行
            if logical_complete_check(complete, ir, catalog):
                completed.add(complete)
            next_states.append(state)
        frontier = select_beam_with_diversity(next_states, stats, budget)

    safe = []
    for plan in deduplicate(completed):
        physical, evidence = deterministic_compile(plan, catalog)
        report = full_check(ir, plan, physical, evidence, catalog)
        if report.safe:
            safe.append((plan, physical, final_cost(physical, stats)))
    return select_lowest_predicted_cost(safe)
~~~

这里的全扫描 fallback 必须同样包含精确后缀并使用可信 raw 快照。即使 LLM 只提出非法动作，也可以有正确计划返回；但若 full scan 超出执行预算，返回预算不足，不报告不完整结果。

### 11.6 开发默认预算与停止

建议：beam_width=4，每轮最多 3 个动作，max_depth=4，max_llm_calls=3，总规划预算 10 秒，连续 2 轮无预测改善停止。所有值是可调开发值，并非实验结论。

记录停止原因：NO_ACTION、TIME_BUDGET、CALL_BUDGET、DEPTH_LIMIT、STAGNATION。

只具备估计成本时，Beam 丢弃状态是启发式预算取舍，不叫“证明它不可能更优”。可靠下界可用时才做 branch-and-bound 式证明剪枝。

canonical signature 规范化可交换 Intersect/Union 子节点、索引集合与参数，避免反复探索等价排列。保留不同访问家族，提高多样性；无谓重复添加同一索引应被拒绝。

若同样预算下合法候选只有 3 个，直接完成确定性枚举可作为部署快路径。论文同时报告强制 LLM 与快路径混合版，不能把跳过 LLM 的收益全部归因于 LLM。

## 12. 正确性验证与可信边界

### 12.1 两个不同保证

- 意图正确性：BoundIR 是否准确表达用户要求。不能仅靠 Schema 形式化保证，采用来源绑定、澄清和独立语义测试衡量。
- 执行正确性：在 BoundIR 正确、数据快照完整、索引契约和确定性算子实现正确的条件下，输出是否等于 IR 定义的结果。

论文可以证明受限 DSL 的条件化执行正确性，不能证明任意自然语言到答案的无条件正确性。

### 12.2 候选完备与结果精确

定义 M(q) 为包含真实匹配点/归属线段的 chunk 集合，C(P,q) 为索引候选集合。

~~~text
M(q) ⊆ C(P,q)                         候选不能漏掉真正匹配块
ExactFilter(C(P,q), q) = M(q)         精确过滤排除假阳性
ProjectTid(M(q)) = A(q)              存在语义得到轨迹集合
~~~

Top-K 还要求每条 A(q) 轨迹按 FULL_TRAJECTORY 读取完整序列、计算相同 DTW 和 tie breaker。只证明候选块覆盖不足以证明最终 Top-K。

### 12.3 验证层与检查内容

| 层 | 检查 | 失败例子 |
|---|---|---|
| 结构 | JSON、DAG 无环、节点 ID、可达性、算子参数 | inputs 引用不存在节点 |
| 类型 | 输入输出类型、合并粒度 | ChunkRef 与 TrajectoryIds 求交 |
| 谓词绑定 | 节点引用固定 IR、禁止谓词变窄 | 把 08–10 改成 08–09 |
| 能力/版本 | 索引支持相应谓词、READY、布局版本 | HashLookup 处理范围比较 |
| 逻辑覆盖 | 所有必要覆盖义务由受信访问模板满足 | Union 分区少了一条边界带 |
| 必需后缀 | 精确过滤、完整重建、评分/TopK 顺序 | 先取候选前 K 再精确过滤 |
| 物理安全 | 全 shard、所有桶/单元、正确编码/边界 | 只扫 shard=0 或 stopRow 少 1 |
| 执行完整性 | Scanner 全消费、无遗漏失败批次 | 某 Region 超时却返回成功 |

### 12.4 可组合规则

规则 T：完整时间索引返回所有时间范围重叠块，因此 M(q) ⊆ C_T。

规则 Z：块 MBR 的完整单元写入 + 查询几何的保守单元覆盖，使 M(q) ⊆ C_Z。

规则 H：查询真实等值字段，完整 posting 保证匹配块在 C_H；哈希碰撞可过包含，后缀必须精确核对原值。

规则 I：若分别已经证明 M(q) ⊆ C_A 且 M(q) ⊆ C_B，则 M(q) ⊆ C_A ∩ C_B。任何无法满足此前提的集合，不得随意拿来交集。

规则 U：若 q 分解为并集 q1、q2 且 M(q) ⊆ M(q1)∪M(q2)，对应保守候选的 Union 完整。对于原本都覆盖全 q 的两条路径，Union 也安全但未必有收益。

规则 D：按 (tid,chunk) 去重不丢掉匹配块。过滤前按 tid 随意保留一个 chunk 会丢掉该轨迹的另一个匹配块，禁止。

### 12.5 “覆盖证明”如何实现

不让 LLM 输出一段自然语言证明后就放行。每个 IndexAdapter 具有受信契约和版本：

~~~text
CoverageCertificate:
  query_hash, manifest_id, layout_hash, compiler_version
  index_id, predicate_binding
  required_shards
  required_bucket_or_cell_cover
  emitted_physical_ranges
  unresolved_obligations
~~~

certificate 由确定性编译器生成。独立 verifier 检查是否满足 shard/区间并集/谓词绑定等义务，再结合适配器契约推导整体安全。不能由计划中的 safe=true 自报通过。

对于任意大数据集，每次查询不会重新扫描索引验证无漏项。信任根是：索引构建器、已发布清单、写入不可变约束、RowKey codec、几何库/精确算子和覆盖算法。因此该机制不是通用形式化验证所有 HBase/Java 代码，而是有限 DSL 上的契约检查与可组合证明。

必须配合全量构建检查、性质测试、差分测试和错误注入来支持实现正确性。计数一致、测试通过和 SMT 定理证明是不同强度的证据，不混用术语。

### 12.6 失败及回退

- 计划非法：拒绝该候选，带错误返回搜索器，不修改语义。
- 物理范围无法保守生成：换适配器或全域扫描，不截断。
- 索引不完整/不可用：禁用该索引，重新规划；raw 快照可信时可 full scan。
- IR 引用不存在实体/数据未发布：拒绝，不用另一份数据冒充。
- 执行超时/资源超限：返回 FAILED/RESOURCE_EXHAUSTED；部分数据仅可标 PARTIAL 且不宣称正确完整 Top-K，首版默认不返回部分答案。
- HBase 重试：依赖客户端重定位，加应用层幂等去重；未完成的范围必须最终完成或整体失败。

## 13. 共享代价模型：搜索期与最终选择

### 13.1 两次调用不是两套互相冲突的系统

| 阶段 | 输入 | 工作 | 输出与去向 |
|---|---|---|---|
| Fast | 搜索状态的完整补全计划、粗统计、目录 | 近似访问/候选/回表/计算规模 | CostCard 给 LLM 和 frontier 排序 |
| Final | 已通过完整验证的编译计划、细统计、Region map、模型版本 | 精细估计执行阶段和资源消耗 | 选择 SafePlan 内最低预测成本 |
| Feedback | 已执行的一条计划及真实 trace | 校准系数/选择率误差 | 新模型版本，下轮实验或后续查询使用 |

复用相同特征定义、成本组件和版本。Fast 可省略精细 Range–Region 切分；Final 再精算。最终选定后执行已有物理计划，若缓存/版本改变必须重新验证，不生成一套未经检查的新字节。

### 13.2 不执行查询时的信息来源

| 信息 | 来源 | 精确/估计 |
|---|---|---|
| 桶/单元/RowKey 区间个数 | IR + 确定性适配器 | 编译后的确定值 |
| 每个区间交叉 Region | RegionLocator 元数据缓存 | 当前快照值，可变化 |
| 索引 posting 数、字节 | 构建期直方图/计数、平均长度 | 对齐桶可精确计数，其余估计 |
| 去重候选块数 | 每单元 KMV/MinHash/样本、历史统计 | 估计 |
| 时间空间交集 | 联合统计或一致抽样 | 估计，禁止默认独立 |
| BatchGet 条目、原文字节 | 候选基数 × 块大小分布 | 估计 |
| RPC 次数 | 范围–Region 数、Scanner 分页、批量限制 | 估计，不等于区间数 |
| DTW 规模 | 合格轨迹估计、完整长度分布、参考长度 | 估计 |
| 延迟 | 校准系数、并发与负载 | 预测，非保证 |

“预估不执行”指不执行候选的数据 Scan/Get，并不禁止读取目录、构建期统计、Region 元数据。参考轨迹长度可从快照目录获得，不需为了每个候选先读完整轨迹。

### 13.3 基数估计

设 N_T、N_Z 是时间/空间去重块数，N 是总块数。不能默认 N_TZ=N_T*N_Z/N，因为出租车时空分布通常相关。

MVP 采用一致的 chunk hash 抽样：构建时按 stable_hash(tid,chunk)<阈值选块，在样本上记录各索引候选关系与 MBR/时间元数据。规划时对同一小样本求交，按抽样率扩展，并返回样本数和不确定性。

样本交集为 0 不代表真实结果为 0，不据此生成空计划；代价模型可以使用平滑或上界。也可采用时间桶×粗空间格的联合统计；更大规模后再引入合并 sketch。

### 13.4 数学模型

目标是在安全计划集合中最小化预测执行延迟：

~~~text
P* = argmin_{P ∈ P_safe(q)} L_hat_exec(P | q, stats, environment)

L_hat_exec =
    L_hat_index
  + L_hat_set
  + L_hat_fetch_chunks
  + L_hat_exact
  + L_hat_reconstruct
  + L_hat_dtw
  + L_hat_topk
~~~

定义 index 分支 a、其涉及 Region r，服务工作量：

~~~text
W_ar = alpha_rpc * R_hat_ar
     + alpha_seek * K_ar
     + alpha_byte * B_hat_ar
     + alpha_decode * N_hat_ar

L_hat_index = ScheduleEstimate({W_ar}, concurrency_index, region_mapping)
~~~

ScheduleEstimate 首版用受限并发的确定性 list scheduling，将任务分配到 worker，并约束同 RegionServer 的共享并发；不可简单把所有并行分支的串行耗时相加声称墙钟时间。第一版若执行确实串行，则可以使用 sum，并在实验中标明。

K_ar 是区间 seek 数。R_hat_ar 可按打开 scanner、预估返回行/字节和分页大小估计，不与 K_ar 直接画等号。HBase 不同版本客户端行为需用真实 trace 校准。

~~~text
L_hat_set =
    beta_hash * sum_a N_hat_a
  + beta_emit * N_hat_candidate
  + beta_spill * B_hat_spill

L_hat_fetch_chunks =
  ScheduleEstimate({
      gamma_rpc * G_hat_r
    + gamma_byte * B_hat_raw_r
  }, concurrency_get, region_mapping)
  + gamma_decode * P_hat_decoded

L_hat_exact =
    delta_point * P_hat_tested
  + delta_geometry * E_hat_segment_tests

L_hat_reconstruct =
    cost(meta Get + 未缓存完整轨迹块读取)
  + rho_sort * sum_tau (m_hat_tau * log(max(2,m_hat_tau)))

L_hat_dtw =
    eta_cell * sum_tau (m_hat_tau * m_reference)

L_hat_topk =
    theta_heap * N_hat_scored * log(max(2,k))
~~~

若按 seq 顺序读取完整轨迹，不需排序，可将排序项替换为线性重建成本，不能同时计两个重复步骤。已读块的复用必须反映在 reconstruct 未缓存块数中；未启用缓存就不能凭空扣成本。

系数单位统一成毫秒/操作或毫秒/字节；基数/字节可由统计得到，系数必须通过微基准或训练集测量拟合。可先用非负线性回归，后做残差校准。

单查询的总时间另行报告：

~~~text
L_end_to_end =
  L_NL_parse + L_clarification_processing
  + L_plan_search + L_validate_compile_cost
  + L_exec
~~~

不把用户等待答复的时间混入服务器延迟；独立报告交互轮数和用户等待。选择时已消耗的规划时间是沉没成本；继续调用 LLM 是否值得，应与预计执行收益比较。

### 13.5 CostCard 的实际形式

以下数值仅为单元测试用的假设预测，不是 HBase 实测结果：

~~~json
{
  "plan_id": "p_z",
  "stage": "FAST",
  "model_version": "cost_fixture_v1",
  "estimated_execution_ms": 180.0,
  "features": {
    "scan_ranges": 16,
    "estimated_index_rows": 12000,
    "estimated_candidate_chunks": 900,
    "estimated_raw_bytes": 7200000,
    "estimated_eligible_trajectories": 35,
    "estimated_dtw_cells": 350000
  },
  "main_cost_drivers": ["RAW_FETCH", "EXACT_FILTER"],
  "uncertainty": {
    "method": "SAMPLE_COVERAGE",
    "sample_size": 200,
    "label": "HIGH"
  },
  "legal_alternatives": ["a_intersect_time", "a_replace_with_quad"]
}
~~~

不能凭空输出 confidence=0.99 或 P99=某值而没有校准定义。初版输出估计值、样本量和低/中/高不确定性即可，分位预测要在有误差分布之后另行实现。

LLM 根据瓶颈选择下一步合法动作；构造器应用动作后重新计算成本，不接受模型自报“降低 50%”作为选择依据。

### 13.6 反馈和实验隔离

执行记录每算子的 rows、returned bytes、耗时、候选数、去重数、读块数、过滤通过量、DTW cell 数。RPC/真实扫描存储字节只有拿到客户端或服务端可靠指标才记录为实测，否则记 unknown/estimate，不能把发出的 scan 数当作 RPC。

使用训练查询离线校准代价模型，验证集调参数，测试集冻结模型；不把测试查询最优计划的实测成本喂回同一次测试再报告“事前预测”。可额外设计在线校准实验，单独声明 chronological split。

## 14. 确定性编译与 HBase 执行

### 14.1 编译对象

~~~text
PhysicalPlan:
  query_hash, manifest_id, layout_hash, compiler_version
  scan_tasks[]:
    table, start_row_bytes, stop_row_bytes
    required_columns, caching_rows, max_result_size
    source_node_id, shard, coverage_ref
  fetch_spec
  local_operator_dag
  validation_report_hash
~~~

物理字节只存在于此层。解释接口可显示十六进制边界与可读字段，但不能让 LLM 填写覆盖证据中的字节。

IndexAdapter.lower(ir,catalog) 输出覆盖单元和请求；Verifier 检查其全部区间。缓存 key 至少包含 IR hash、manifest、layout 和 compiler version。

### 14.2 执行器与协调器

协调器负责调度 DAG、并发队列、集合操作、内存预算、结果汇总和 trace。执行器负责 HBase Client 请求、解码、精确几何/时间、轨迹重建和 DTW。首版同 JVM，不强制拆网络服务。

建议接口：

~~~java
interface QueryCompiler {
    CompileResult compile(BoundIr ir, LogicalPlan plan, CatalogSnapshot catalog);
}
interface PlanValidator {
    ValidationReport validate(BoundIr ir, LogicalPlan plan,
                              PhysicalPlan physical, CatalogSnapshot catalog);
}
interface QueryExecutor {
    QueryResult execute(SafePlanHandle plan, ExecutionContext context);
}
interface CostModel {
    CostEstimate estimate(CostFeatures features, ModelSnapshot model);
}
interface ProposalPolicy {
    List<ActionSelection> propose(List<SearchState> frontier, SearchBudget budget);
}
~~~

SafePlanHandle 只能由验证模块创建，不能从外部请求直接反序列化得到。请求中伪造 safe=true 必须忽略/拒绝。

### 14.3 HBase 具体优化对象

当前规划优化的是：

- 用时间、Z-order、Quadtree、Hash 哪些访问路径。
- 是否先交候选减少回表，还是单索引回表后精确过滤。
- Union/Intersect 的结构与局部执行实现。
- 相邻 RowKey 区间合并，权衡多扫记录与减少 scanner/seek。
- 按 Region/RegionServer 分组批量读取，控制并发和批大小。
- 复用已读取轨迹块，避免 DTW 阶段重复回表。

不改变物理数据布局，不在每次查询时把数据移动到同一 Region。Region 是表 RowKey 范围的动态分区，不是关系表 Join 操作。上述优化不会要求 HBase 原生理解空间谓词或跨索引 Join。

应用层 Intersect 是两组 chunk ID 的集合交，不是 HBase 原生关系连接。where 语义由访问范围、可安全下推的 HBase Filter 与应用层精确过滤共同实现。

### 14.4 读取失败与资源管理

- 连接池/Connection 复用，按客户端规范管理 Table/Scanner；每个 scanner 必须关闭。
- 若启用 partial results，必须组装完整行再解码；首版可关闭以简化。
- 批量 Get 中某项缺失且目录声称该块存在：报 DATA_INTEGRITY_ERROR，不视作“不满足查询”。
- 扫描遇 Region split/move，客户端重定位重试；正确性来自完整字节范围，不固定 Region ID。
- 候选超内存时先 RESOURCE_EXHAUSTED；后续实现 spill-to-disk。绝不能只保留前 N 个候选继续宣称精确答案。
- 设置超时、取消、最大并发和队列上限，所有失败在 QueryResult 中显式返回。
- DTW 超预算时失败/建议缩小查询，不擅自降采样或返回近似 Top-K。
- 全扫描也必须遵守同样失败语义，不能使用截断全扫描作为 Oracle。

## 15. 工程结构、接口与可观测性

### 15.1 建议目录

~~~text
LLM_KV/
  IMPLEMENTATION_PLAN.md
  README.md
  pom.xml
  config/
    dataset-tdrive.example.yaml
    dataset-cdtaxi.example.yaml
    index-layout.yaml
    planner.yaml
    environment.example.yaml
  schemas/
    draft-ir.schema.json
    bound-ir.schema.json
    plan.schema.json
    action-selection.schema.json
  core/
    src/main/java/.../catalog/
    src/main/java/.../ir/
    src/main/java/.../plan/
    src/main/java/.../validation/
    src/main/java/.../search/
    src/main/java/.../cost/
  storage/
    src/main/java/.../codec/
    src/main/java/.../index/
    src/main/java/.../hbase/
    src/main/java/.../memory/
  executor/
    src/main/java/.../operators/
    src/main/java/.../trajectory/
  llm/
    src/main/java/.../gateway/
    src/main/java/.../mock/
  app/
    src/main/java/.../cli/
    src/main/java/.../http/
  tools/
    profile-data/
    build-snapshot/
  testdata/fixture-v1/
  experiments/
    workloads/
    configs/
    metrics/
  docs/
    environment-lock.md
    supported-semantics.md
    experiment-protocol.md
~~~

可以先用一个 Maven 模块加 package，规模增长后再拆；模块边界比模块数量重要。不生成尚未实现的脚手架来冒充功能完成。

### 15.2 CLI 能力

接手 Agent 实现 CLI 后，在 README 填入实际验证过的命令。必须支持以下子命令语义：

- doctor：检查 Java/HBase/网络/权限，输出版本。
- profile-data：只读探查真实数据格式。
- build-fixture：生成第 18 节确定性样例。
- build-snapshot：清洗、raw/meta、索引、统计、验证、发布。
- query-ir：不调用 LLM，执行 IR。
- query-nl：自然语言入口。
- explain：返回候选、验证、成本、选中计划和物理请求摘要。
- benchmark：读取冻结 workload，执行指定策略，保存结果。
- verify-snapshot：验证 postings 与 raw 一致。

### 15.3 HTTP 可选接口

~~~text
POST /v1/query             NL + context，可能返回澄清
POST /v1/query-ir          已有 IR 的系统/实验入口
POST /v1/explain           规划但不执行数据查询
POST /v1/validate-plan     调试候选验证
GET  /v1/catalog          逻辑能力摘要
GET  /v1/runs/{run_id}     trace 与指标
~~~

状态：OK、NEED_CLARIFICATION、UNSUPPORTED_QUERY、INVALID_IR、NO_SAFE_PLAN、RESOURCE_EXHAUSTED、FAILED（历史文档名 EXECUTION_FAILED）、DATA_INTEGRITY_ERROR、PLAN_ONLY（`--plan-only`）。

### 15.4 日志与指标

每次查询保存 query_hash、NL/IR（按数据权限）、manifest、模型 ID/设置、LLM 原始结构化输出、所有接受/拒绝动作、完整候选、验证错误、成本特征、选择结果、物理请求摘要和逐算子 trace。

区分：

- elapsed_plan_ms、elapsed_execute_ms、elapsed_total_server_ms。
- llm_calls、llm_tokens、llm_latency_ms。
- emitted_range_count 与 actual_rpc_count。
- returned_index_rows 与 server_scanned_rows。
- returned_bytes 与 storage_bytes_read。
- candidate_chunks、fetched_chunks、matched_trajectories、dtw_cells。
- 数据来自客户端、服务器还是估计；拿不到的实测指标用 null，不填 0。

记录启动环境、缓存状态、并发、seed、工作负载版本，便于复现实验。



## 16. 运行反馈是否改变系统

第一版默认执行反馈只校准代价模型，不修改 IR 语义、索引覆盖契约、RowKey codec、验证规则或计划安全条件。

执行 trace 结构：

~~~json
{
  "run_id": "run_001",
  "plan_id": "p_time_z",
  "manifest_id": "fixture_v1_ready",
  "operator_metrics": [
    {
      "node_id": "n1",
      "estimated_rows": 120,
      "actual_rows": 118,
      "estimated_bytes": 4800,
      "actual_bytes": 4720,
      "elapsed_ms": 3.2
    }
  ],
  "candidate_chunks": 14,
  "matched_chunks": 4,
  "matched_trajectories": 2,
  "dtw_cells": 320,
  "status": "OK"
}
~~~

反馈处理：

1. 对运行成功且指标可信的记录打标签。
2. 按 workload 的时间顺序划分训练、验证、测试，防止测试泄漏。
3. 更新桶选择率、重复率、过滤通过率、单位 RPC/字节/点的成本和残差。
4. 发布新 Stats/Model Snapshot。
5. 新模型只进入后续查询，已运行结果不重算。
6. 若发现索引构建错误，停止发布该 manifest，走数据完整性修复，而不是训练代价模型掩盖问题。

动态模型更新和动态索引维护属于后续工作，MVP 不实现。

## 17. 测试与验收

### 17.1 单元测试

必须覆盖：

- JSON Schema、additionalProperties=false、字段类型和枚举。
- 时间边界、时区、相对时间上下文。
- 坐标转换与边界判断。
- Z-order 编解码互逆及边界量化。
- Quadtree 覆盖和叶路径。
- RowKey 编码、prefixSuccessor、半开区间。
- 时间桶枚举、跨桶块、空区间拒绝。
- 轨迹块 MBR、多 posting、多块重复。
- 候选集合 Intersect/Union/Deduplicate。
- 执行算子输入输出类型。
- ExactSTFilter、属性等值、DTW、Top-K tie breaker。
- 物理编译和物理安全检查。
- CostFeatures 单位和缺失统计处理。

### 17.2 差分与性质测试

对随机轨迹、矩形和时间区间验证：

~~~text
FullScanCandidates(q) ⊆ IndexedCandidates(P,q)
ExactFilter(IndexedCandidates(P,q), q) == FullScanAnswers(q)
~~~

必须覆盖：

- 查询边界正好落在点上。
- 轨迹块跨时间桶。
- MBR 跨多个 Z-order cell。
- 同一块被多个区间命中。
- Z-order/Quadtree 的边界 cell。
- 空间假阳性。
- 空结果、多 shard。
- 参考轨迹不存在或被命中，k=1，k 大于候选。
- 两块之间的 halo 以及 gap 断点。
- 物理 RowKey 字段顺序错误注入。

“索引结果相等”要在 ExactSTFilter 后比较；原始候选通常允许多于全扫描答案。

### 17.3 HBase 集成测试

使用 HBase 本地测试集群或等价受控环境：

1. 建表并写入小型 fixture。
2. 构造 READY manifest。
3. 用真实 HBase Client 执行 Scan/Get/BatchGet。
4. 保存物理请求摘要，不能保存真实敏感数据。
5. 与同快照的 FullScan Oracle 比较。
6. 模拟 Region split/move、批次重试和部分失败；最终要么完整成功，要么显式失败。

单节点只能证明功能，不能证明跨 RegionServer 延迟结论。集群实验必须报告 Region 数、RegionServer、数据规模和缓存状态。

## 18. 贯穿全流程的确定性查询示例

### 18.1 Fixture 数据

这是用于开发的合成 fixture，不代表 T-Drive/CD-Taxi 真实记录：

~~~text
参考轨迹 R:
  08:00 (1,1), 08:05 (2,2), 08:10 (3,3)

轨迹 A:
  08:00 (1,1), 08:05 (5,5), 08:10 (9,9)

轨迹 B:
  08:00 (20,20), 08:05 (6,6), 08:10 (20,20)

轨迹 C:
  08:00 (30,30), 08:05 (31,31)

查询：
  08:00 <= t < 08:10
  空间 G = [4,4] × [8,8]
  返回与 R 最相似的 Top-2
~~~

A、B 有采样点同时满足时间和空间，C 不满足。R 是参考轨迹并被排除。过滤后对 A、B 计算完整轨迹 DTW。

### 18.2 输入到 IR

用户输入：

~~~text
查询 2008-02-02 08:00 到 08:10 期间经过区域 G 的轨迹，返回与 R 最相似的 2 条。
~~~

确定性绑定后的关键 IR：

~~~json
{
  "source": {"dataset_id": "fixture_v1", "entity": "trajectory"},
  "temporal": {
    "start": "2008-02-02T08:00:00+08:00",
    "end": "2008-02-02T08:10:00+08:00",
    "boundary": "[start,end)"
  },
  "spatial": {
    "geometry": {"type": "RECTANGLE", "min_x": 4, "min_y": 4, "max_x": 8, "max_y": 8},
    "relation": "INTERSECTS",
    "boundary": "INCLUDED"
  },
  "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
  "similarity": {
    "metric": "DTW",
    "reference_trajectory_id": "R",
    "scope": "FULL_TRAJECTORY",
    "exclude_reference": true
  },
  "result": {"mode": "TOP_K", "k": 2, "tie_breaker": "TID_ASC"},
  "snapshot": {"manifest_id": "fixture_v1_ready", "semantics_version": "point_dtw_v1"}
}
~~~

若 G 或日期没有绑定，系统先 NEED_CLARIFICATION，而不是编造。

### 18.3 候选生成和选择

合法计划：

- P_T：时间索引，回表后精确空间/时间过滤。
- P_Z：Z-order 索引，回表后精确空间/时间过滤。
- P_TZ：时间索引与 Z-order 索引按 tid+chunk 求交，再回表。
- P_FULL：原表全扫描兜底。

搜索器不把所有算子排列发给 LLM。规则层建立 legal_actions，LLM 只提出有限的 start/intersect/replace/finish 动作。构造器自动添加去重、回表、精确过滤、完整重建、DTW 和 Top-K。

### 18.4 覆盖检查

若 A 的命中点在 chunk A-0，B 的命中点在 chunk B-0：

- 时间构建器保证两个块的时间桶 posting 存在。
- Z-order 构建器保证两个块 MBR 相交的 cell posting 存在。
- P_TZ 的交集键是 tid+chunk，两个块均保留。
- 原始块读取后，ExactSTFilter 保留 A/B，删除 C。
- 轨迹级去重得到 {A,B}。
- 排除 R，读取 A/B 的完整 chunk 序列。
- 对 A/B 计算 DTW，排序并返回前 2。

P_TZ 正确的原因是覆盖适配器完整生成候选，应用层精确过滤定义最终语义，执行器完整消费请求，而不是 HBase 自动理解空间语义。

### 18.5 物理编译示意

逻辑节点：

~~~text
TIME_RANGE_SCAN(idx_time_v1)
   ∩ ZORDER_RANGE_SCAN(idx_zorder_v1)
   → DEDUP(tid,chunk)
   → BATCH_GET(traj_raw_v1)
   → EXACT_ST_FILTER
   → PROJECT_TID
   → EXCLUDE_REFERENCE(R)
   → BATCH_GET_TRAJECTORY(FULL)
   → DTW
   → TOP_K(2)
~~~

物理编译器根据快照和 codec 生成类似以下请求：

~~~text
idx_time_v1:
  shard 0..S-1
  bucket range for [08:00,08:10)

idx_zorder_v1:
  shard 0..S-1
  Morton intervals covering G conservatively

traj_raw_v1:
  Get(raw_key(shard,tid,chunk_id)) for verified ChunkRefs
~~~

这只是请求结构示意，不是虚构的十六进制 RowKey。实际字节由编译器根据 epoch、空间域、层级、shard 和 tid 计算，并在单元测试中与 codec 反解一致。

## 19. 实验最小集合

### 19.1 必做对比

1. FullScan + ExactFilter。
2. 固定单索引规则。
3. 固定时间+空间交集规则。
4. 规则/Best-first 代价搜索，无 LLM。
5. LLM 直接完整计划，受同一验证器约束。
6. 本系统：LLM 动作引导 + 增量检查 + Fast/Final Cost + 完整验证。

所有方案使用同一 HBase 表、索引、精确语义、快照和缓存策略。

指标：

- 结果正确率/与 Oracle 一致率。
- 规划时间、LLM 调用次数与 token。
- 执行时间 P50/P95/P99。
- 扫描区间、索引行/字节。
- 实际 RPC 和 RegionServer 数；拿不到时标为 unknown。
- 候选 chunk、回表 chunk、精确过滤通过量。
- DTW cell 数、完整轨迹读取量。
- plan regret：相对候选计划实测最佳方案的代价差。

### 19.2 必做消融

逐一去除：

- LLM 动作策略，改规则/Best-first。
- 覆盖验证。
- Fast Cost。
- Final Cost。
- 联合索引访问，只保留单索引。
- 执行反馈校准。

消融必须报告正确性和规划/执行开销。去掉验证器的版本若产生漏查，只能作为错误风险对照，不能作为可部署基线。

### 19.3 必做参数

- 时间桶大小。
- Z-order 层级 L。
- chunk 最大点数。
- Beam width。
- 最大规划时间/LLM 调用数。
- Top-K。
- HBase Scan/Get batch 和并发。

参数实验使用冻结 workload，按查询类别分层，报告平均值和长尾，避免只使用一种选择率。

### 19.4 数据和查询 workload

T-Drive/CD-Taxi 的真实列、坐标系、时间范围以 profile 报告和配置为准。工作负载包含：

- 小/大时间窗口。
- 小/大矩形空间范围。
- 短/长时空联合窗口。
- 跨多个 Z-order 单元的边界查询。
- 空间、时间、时空三类和 DTW Top-K。
- 空结果、全域范围、边界点和高重复 posting。

数据切分：代价模型训练/校准查询、验证查询、测试查询严格分开。测试查询的实测成本不能回灌后再报告事前预测。

## 20. 分阶段开发计划

### P0：协议与 fixture

- 建立 Java/Maven 项目、版本锁定和 doctor。
- 定义 IR、Plan、Action JSON Schema。
- 实现 Catalog、Manifest、IndexDescriptor、StatsSnapshot。
- 生成确定性 fixture、FullScan Oracle、内存 backend。
- 完成 RowKeyCodec 性质测试。

验收：不连接 LLM/HBase，fixture 的 IR、计划和 codec 全部可测。

### P1：静态快照和索引

- DatasetAdapter 和 profile 工具。
- chunk 构建、raw/meta 写入。
- time、Z-order 索引和全量 posting 校验。
- Quadtree/Hash 对照索引。
- stats 生成与 manifest READY 发布。

验收：索引候选覆盖 Oracle 的匹配块；错误 posting 可被检测。

### P2：确定性计划执行

- 逻辑算子类型系统。
- 编译器和 PhysicalPlanSafetyCheck。
- Coordinator/Executor、Scan/Get/BatchGet。
- ExactSTFilter、轨迹重建、DTW、Top-K。

验收：HBase 结果与 FullScan Oracle 相同；失败不返回部分正确答案。

### P3：IR 入口

- DraftIR 结构化输出。
- 缺失信息检测、澄清协议。
- BoundIR binder。
- MockLlmClient 和有限修复。

验收：模型输出不能改变 snapshot、RowKey 和 HBase 命令；不支持查询被拒绝。

### P4：候选计划搜索

- SearchState、合法动作、构造器、规范化签名。
- LLM policy 与 rule policy。
- Beam/Best-first、预算、停滞和日志。
- Fast Cost、frontier 保留和完整安全检查。

验收：产生结构不同的安全计划；非法动作不会进入成本选择。

### P5：代价模型和实验

- Final Cost、SafePlanSelector。
- 微基准测成本系数。
- 执行 trace 和离线 residual calibration。
- 完成对比、消融和参数实验。

验收：所有结果可由固定 workload、manifest、model snapshot 重现。

### P6：可选扩展

- 插值线段、圆/多边形。
- 更好的联合统计和在线残差。
- 更丰富索引组合。
- 动态更新或索引维护。

每个扩展必须有独立 semantics_version、覆盖证明和对照实验。

## 21. 接手 Agent 的硬性开发规则

1. 先实现 FullScan Oracle、fixture 和确定性 IR 查询，再接 HBase 和 LLM。
2. LLM 只能输出符合 Schema 的 IR 草稿或合法 action_id。
3. RowKey、start/stop、Region、HBase Java 命令只由确定性代码产生。
4. 统计信息只能影响代价，不可影响语义覆盖。
5. 不存在的字段不加入 IR；T-Drive/CD-Taxi 的“出租车”不能自动变成 vehicle_type。
6. 不以轨迹中心点替代 MBR 的保守覆盖。
7. 每次查询保存 IR、候选计划、验证报告、CostCard、物理计划摘要和 trace。
8. 任何 RowKey/索引覆盖变更都重新运行差分、边界和错误注入测试。
9. 真实 LLM API 不可用时，MockLlmClient 必须跑完整测试。
10. 先实现静态快照，不在 MVP 偷加动态维护。
11. 不能用完整性未知的索引或不完整 Scanner 结果继续返回正确答案。
12. 任何未测 HBase 延迟、RPC、吞吐数字必须标为待测，不写成系统事实。

## 22. MVP 完成定义

系统满足以下条件，才算“跑通一个可行方案”：

- 完整自然语言查询能进入合法 BoundIR，信息不足时能明确澄清。
- Typed IR 不包含物理 RowKey/HBase 命令。
- 至少两个结构不同的 KV 候选计划可以生成。
- 候选经过 Schema、能力、覆盖、版本和物理编译检查。
- 代价模型在不执行候选查询时能输出带来源和不确定性的估计。
- 只在 SafePlan 集合中选择计划。
- 计划可编译为 HBase Scan/Get/BatchGet。
- ExactSTFilter 后与 FullScan Oracle 一致。
- 轨迹级 Top-K：先过滤、再重建、再按 DTW / 离散 Fréchet / 对称 Hausdorff 评分。
- 返回规划、执行、扫描、候选、回表、过滤、RPC 等可观测指标。
- 无真实 LLM API 时，Mock 模式也能完成端到端回归。
- 数据和索引 manifest 能被固定版本复现。

完成 MVP 后，再由实测结果决定是否增加在线反馈、更多索引和动态更新。不要把可选工作写进 MVP 的正确性或性能承诺。

