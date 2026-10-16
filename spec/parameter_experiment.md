# KART 参数实验规范（Parameter）

- 版本：v0.1，2026-10-15
- 状态：实验设计已锁定（本文）；正式主表须 HBase 冷跑与 Oracle 门禁，不得以「配置可改」代替成对测量。
- 正交规范：
  - [`spec/comparative_experiment.md`](comparative_experiment.md)（外部方法 / HBase 基线横比）
  - [`spec/ablation_experiment.md`](ablation_experiment.md)（组件 leave-one-out）
- 数据与用例：与对比 / 消融同一 **复杂度分层** 工作负载——`bound_ir_simple_v2`（简单层）与 `bound_ir_complex_v2`（复杂层）；manifest 默认 `tdrive_v1_ready`

---

## 1. 目标与假设

### 1.1 目标

在**同一金标 BoundIR、同一 T-Drive 快照与同一 HBase 环境**下，对 Full KART（`base_arm=kart`）的规划超参与查询类型做灵敏度分析，回答：

1. Beam / 候选预算 / LLM 调用预算如何影响 **`t_plan`、`t_exec`、`t_e2e`**
2. 不同 **query type** 下，上述超参的效应是否一致（分层报告）
3. 相对实验默认点，增大预算是否继续换取执行收益，或仅抬高规划成本

### 1.2 假设与限制

- 语义：`OBSERVED_POINT`；时间半开区间；空间闭矩形；相似度量按 `design.md`
- 存储：单节点伪分布式 HBase；**结论只报相对趋势，不外推多 RegionServer**
- Manifest：实验期间固定（默认 `tdrive_v1_ready`），写入每次 `meta.json`
- **只扫运行期规划 / 用例因子**；不重建 layout（不改 chunk、时间桶、Z 层级、shard）
- **不把参数网格点写成消融臂**；不把外部对比臂（DIN/SAG/Bao 等）写入本表
- 从金标 BoundIR 起算；**NL→IR 不进主表**

### 1.3 与对比 / 消融的关系（正交）

| 规范 | 回答的问题 | 变化对象 |
|---|---|---|
| 对比实验 | KART 相对外部方法 / 基线如何 | 方法臂 |
| 消融实验 | KART 内部哪块组件贡献了什么 | 组件开关（leave-one-out） |
| **参数实验（本文）** | 同一 Full KART 下超参 / 查询类型如何影响延迟 | 规划预算旋钮 + query type |

三者共享 workload / Oracle / 计时边界，**结果表不得混写**。

### 1.4 数据流约定

```text
输入：金标 BoundIR（simple / complex，与对比 E3 相同）
每个 cell：钉死其余旋钮为实验默认 → 只改一个目标参数（或按 query type 分层）
       → SafePlan → 执行 → 结果
计时：t_plan + t_exec；t_e2e = t_plan + t_exec
```

- **不计** NL / LLM→IR 时间（与对比 E3 一致）
- 主阶段为 **e2e**（必须同时披露 `t_plan` 与 `t_exec`）

---

## 2. 参数定义（主表）

以下为参数实验主表因子。名称与图示一致；实现侧对应 `beam_width` / `max_candidates` / `max_llm_calls` 及用例形状标签。

| 参数 | 含义 | 实验默认 |
|---|---|---|
| **beamwidth** | Beam Search 宽度，控制每轮保留的最优候选动作数量 | **3** |
| **maxCandidates** | 进入最终代价模型估计的候选计划数 | **4** |
| **maxLLMcalls** | 查询优化时的 LLM 改写调用预算；耗尽则停止搜索 | **4** |
| **query type** | 查询类型：时间/空间范围（SRQ / ST-Ranges）、相似度（SS）、top-k | **空间范围查询（SRQ）** |

说明：

- 扫某一规划旋钮时，其余规划旋钮钉死为上表「实验默认」；`query type` 在结果中按 §4 分层披露。
- 本文实验默认点以本表为准（用于灵敏度曲线的原点）；写入 `meta.json` 的实际覆盖值须与每个 cell 一致。
- **query type 保留为参数实验的一部分**：不是外部对比臂，而是用例维度上的实验因子（见 §4.2）。

### 2.1 明确不进本主表

| 名称 | 原因 |
|---|---|
| `cbo_only` / `no_online_llm` / `no_fast_cost` 等 | 属消融 leave-one-out |
| fullscan / rbo / cbo / DIN / SAG / Bao | 属对比实验 |
| chunk / `bucket_ms` / Z 层级 L / shard | 属 layout / 快照重建，另立实验 |
| `cache` cold/warm | 属协议；主表固定 **cold** |
| 代价系数 α/β/γ、`calibrated` | 属标定 / 消融，不是本网格 |

---

## 3. 参数配置（扫描网格）

### 3.1 扫描方式

- **一维扫描（OAT）**：每次只改一个规划旋钮，其余钉死实验默认；禁止三轴全因子交叉作主结论。
- **query type**：作为参数实验的用例因子，按 §3.2 取值做分层统计与分曲线报告（见 §4.2）；扫规划旋钮时，在同一 workload 上按类型聚合，而不是另造互不重叠的「仅某类型」专用全集（除非附录明确声明子集）。

### 3.2 Settings

| Parameters | Settings |
|---|---|
| **beamwidth** | 1, 3, 5, 7 |
| **maxCandidates** | 4, 6, 8, 10 |
| **maxLLMcalls** | 0, 4, 8, 12 |
| **query type** | SRQ, SS, top-k |

- `maxLLMcalls=0`：搜索期不消耗 LLM 改写预算（用于对照「无在线 LLM 调用」时的规划/执行表现）。
- 每个 Settings 点须在 `meta.json` / 结果行中记录实际生效值。

### 3.3 固定条件

| 项 | 设定 |
|---|---|
| 基线臂 | `kart`（Full：CBO + dual rewrite） |
| Manifest | `tdrive_v1_ready` |
| Workload | `bound_ir_simple_v2`、`bound_ir_complex_v2`（与对比实验复用） |
| Oracle | 各层冻结 FullScan Oracle |
| 缓存 | 主表 **cold**；warm 仅附录 |
| 分层 | simple / complex **分表**，禁止混池 P50 |
| trials | cold 正式首轮建议 1；若报稳定 P50/P95 可另跑 warm×≥5（附录） |

---

## 4. 数据集与 query type

### 4.1 数据集

- **T-Drive**：北京出租车轨迹（单机实验默认数据源）；实验绑定 manifest **`tdrive_v1_ready`**（快照与索引版本写入 meta；论文叙述可同时注明原始周数据规模与本实验就绪快照规模）。
- **Query Workload**：与对比实验相同的难度分层（simple / complex），含冻结 BoundIR 与预先 FullScan 得到的金标答案。
  - Simple：`experiments/workloads/bound_ir_simple_v2.json`（+ 对应 `.oracle.json`）
  - Complex：`experiments/workloads/bound_ir_complex_v2.json`（+ 对应 `.oracle.json`）

**不另建参数专用查询全集**；保证默认点可与对比 / 消融主文直接对照。

### 4.2 query type（参数实验因子）

| 标签 | 含义 | 在 workload 中的来源 |
|---|---|---|
| **SRQ** | 空间（或时空）范围查询，结果为轨迹 ID 集合 | simple / complex 中带 spatial（及可选 temporal）且非 Top-K / 非相似度的查询 |
| **SS** | 相似度查询（DTW / Frechet / Hausdorff 等，按 IR） | simple 等集合中带 `similarity` 的查询 |
| **top-k** | Top-K 结果模式 | `result.mode=TOP_K` 的查询 |

- 主表披露：对每个规划旋钮扫点，除 overall 外，按 **SRQ / SS / top-k** 分层给出 `t_plan` / `t_exec` / `t_e2e`。
- 默认 query type（空间范围）对应曲线原点叙事；SS / top-k 用于检验超参效应是否随查询形态变化。
- 某层某类型样本过少时：仍报告该层样本数 **n**，不得与其它类型混池充数。

---

## 5. 评估指标

主指标（每 cell、每层、每 query type 分层均须具备）：

| 指标 | 定义 |
|---|---|
| **`t_plan` (ms)** | 规划开始 → 选出最终 SafePlan（不含执行与制品落盘） |
| **`t_exec` (ms)** | 物理计划执行墙钟 |
| **`t_e2e` (ms)** | `t_plan + t_exec` |

附加披露（正式表建议同时给出）：

- **Oracle 正确性**（相对冻结 FullScanOracle）
- 选中 `plan_id`（及相对默认点的计划是否变化）
- 实际 `llm_calls`（对照 `maxLLMcalls` 是否打满；`maxLLMcalls=0` 时期望为 0 / null）

汇总：以成功子集上的 **P50** 为主；失败（超时、`NO_SAFE_PLAN`、`RESOURCE_EXHAUSTED` 等）单独计数并披露失败率。  
**禁止只报 e2e 不报 `t_plan`/`t_exec`。**

---

## 6. 运行与产物（设计约定）

### 6.1 入口（承诺）

| # | 入口 | 作用 |
|---|---|---|
| 1 | `scripts/bench-param.sh`（或等价 `bench-suite` + param 套件） | 按 §3 网格生成 cell，跑 e2e |

实现可对 `config/planner.yaml` 做 **cell 级覆盖**（不永久改写冻结文件）；每个 cell 的覆盖写入 meta。

### 6.2 最小 CLI 语义

| 参数 | 含义 | 默认 |
|---|---|---|
| `--run-id` | 结果目录名 | 必填或时间戳 |
| `--suite` | param 套件 | 如 `experiments/suites/param.yaml`（正式网格须对齐本文 §3.2） |
| `--workload` | 覆盖 workload | simple / complex 套件默认 |
| `--cache` | `cold` \| `warm` | 主表 `cold` |
| `--grid` / 单轴覆盖 | 指定本 run 扫描的轴与取值 | 本文 §3.2 |

### 6.3 结果

建议目录：`experiments/results/<run_id>/`

| 文件 | 内容 |
|---|---|
| `meta.json` | manifest、suite、实验默认、本 run 网格、cache、代码版本 |
| `param.jsonl` 或 `e2e.jsonl` | 每行一个 (query_id, 旋钮取值, query_type, trial, …) |

行字段最小集：  
`run_id, query_id, eval_level, query_type, beam_width, max_candidates, max_llm_calls, arm, ok_oracle, t_plan_ms, t_exec_ms, t_e2e_ms, plan_id, llm_calls, error, trial`

---

## 7. 报告结构（建议）

1. **默认点**：实验默认 `(beamwidth=3, maxCandidates=4, maxLLMcalls=4)` 在 simple / complex 上的 `t_plan`/`t_exec`/`t_e2e`（overall + 按 query type）
2. **一维曲线**：分别固定另两轴为默认，横轴为 Settings，纵轴为三延迟；每张图分 SRQ / SS / top-k（或分面）
3. **简短结论**：哪一轴对 `t_plan` 最敏感；是否存在「预算↑ 但 e2e 不降」的饱和区

---

## 8. 公平性与禁止项

1. 不得用 smoke / 临时查询冒充正式主表 workload  
2. 不得在扫参时悄悄开关高级索引菜单或消融因子，除非写入 meta 并移出「纯参数」主表  
3. 不得只改本地 `planner.yaml` 却不在结果中记录 cell 覆盖值  
4. 不得将 warm 缓存结果与 cold 主表混排  
5. simple 与 complex、不同 query type **不得混池** 报单一 P50  

---

## 9. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-10-15 | 初稿：锁定四因子（含 query type）、OAT 网格、复用对比 simple/complex、主指标 `t_plan`/`t_exec`/`t_e2e` |
