# 高级索引优势探索归档（2026-10-11）

旁路探索：在**不改冻结集与主规则**的前提下，挖掘 XZ / TS / XZT 等高级索引能否带来可上主表的 e2e 优势。

**结论（止损）**：exec 上存在局部真优，但 ADV=1 下 `t_plan`（约 10–15s）淹没 e2e；cold 四臂验收失败。**不宜并入主表**；本目录仅作 frontier 诊断归档。探索代码已自工作区回退到检查点 `checkpoint/2026-10-11-advanced-index-dual`（`de36675`）。

---

## 1. 动机与硬约束

### 动机
- 主表（complex / simple）优势主要来自经典 `P_TZ` 上的 dual rewrite（如 drop + MP），而非高级索引。
- 主路径默认 `KART_ADVANCED_INDEXES=0`，因 ADV=1 时 FastCost/beam 使 plan 秒级膨胀。
- 需单独验证：是否存在查询子集，使高级索引在 **exec 真优** 且能转化为 **kart e2e ≫ cbo**。

### 硬约束
| 项 | 约定 |
|----|------|
| 冻结集 | 不改 `bound_ir_complex_v1` / `bound_ir_simple_v1` |
| 规则 | 旁路 `dual_action_rules_adv_v1.json`，不覆盖主 `dual_action_rules.json` |
| ADV 开关 | 主表默认 0；高级轨 suite 显式 =1 |
| 验收 | 服务器 cold；kart_e2e 优于 cbo（p50 比 ≥1.15× 或 gap≥30ms）；尽量优于 fullscan；披露 `t_plan` 与高级 plan 占比 |

### 止损条件（预置）
- exec 全面差于经典，或 **plan 仍淹没 e2e** → 停止组卷、不宜进主表。

---

## 2. 方法与阶段

| 阶段 | 内容 | 关键产物 |
|------|------|----------|
| P0 挖查询 | 排除 train/holdout/冻结 ids，组矿池；ADV=1 普查 + force-plan 筛 exec 真优 | `bound_ir_adv_mine_v1`（n=37）；`adv-census-p0` |
| P1 校准 | 旁路 OLS benefit 模型；对照 CBO 选中高级 plan 的比率 | `online_benefit_model_adv_v1.json`；`adv-sel-compare.json` |
| P2 rewrite | `advancedPlan` 禁 alias；selectivity / features / admit when 适配 XZ/TS/XZT；beam 初始 diversify；`PlanBuilder` 补 `P_XZTZ`；ADV 候选 seed | 源码侧改动（已回退，见 §6） |
| P3 离线规则 | force-mine-diversity + admit（support≥3）写入旁路 rules | `dual_action_rules_adv_v1.json`（**rules=[]**） |
| P4 组卷验收 | `bound_ir_adv_v1` + `e2e-adv-eval` cold trials=3 四臂 | `adv-eval-cold-p4`；本目录 `frontier_summary.json` |

服务器：`tyq@10.242.104.108`（校园直连），结果目录 `experiments/results/adv-*`。

---

## 3. 关键发现（原因）

### 3.1 Beam 在 ADV=1 下几乎产不出高级候选
- `best_first` + `beam_width=3`：初始 frontier 按 FastCost 截断后，XZT/XZ/TS 常被挤出。
- 即便 diversify 保留高级 seed，单步对每个 legal action 估 FastCost，易触 `TIME_BUDGET` / `NO_ACTION`，日志常见 `candidates=2`（仅 `P_FULL`+经典）。
- **对策（探索期）**：beam 结束后对 `PlanBuilder` 高级族做 validate seed（`adv-seeded safe +6`），使 CBO 可选集含高级 plan。

### 3.2 FastCost 系统性高估高级 plan
- 例：同一查询上 `P_TZ`≈24k–50k ms，`P_XZ`/`P_XZT` 常 >300k ms → 无校准则 **0%** 选中高级。
- 旁路 `online_benefit_model_adv_v1`（对 force-plan 普查行 OLS：`log1p(n_ranges, fetch_chunks, index_rows)`）仅用于**重排高级 plan 的 estimated_ms**（显式排除 `P_FULL`/经典，避免 FULL 被误选）。

### 3.3 Exec 局部真优存在，但面窄
Force-plan 普查（warm，相对 CBO exec≥8ms）筛出 **9** 条，多为 `P_XZ` 优于 `P_TZ` 的 exec：

| query_id | adv_plan | adv_exec | cbo_plan | cbo_exec | gap_vs_cbo | gap_vs_classic |
|----------|----------|----------|----------|----------|------------|----------------|
| c3_tz_b | P_XZ | 1171 | P_TZ | 2130 | 959 | 275 |
| c3_tz_a | P_XZ | 1262 | P_TZ | 2118 | 856 | 296 |
| a2_tz_hard_d | P_XZ | 1191 | P_TZ | 1857 | 666 | 385 |
| a2_tz_hard_a | P_XZTXZ | 1083 | P_TZ | 1535 | 452 | 224 |
| st_ll_2 | P_XZ | 1029 | P_TZ | 1403 | 374 | 177 |
| r9_locked_td_1 | P_XZ | 935 | P_TZ | 1241 | 306 | 120 |
| mechv2_004 | P_XZT | 927 | P_TZ | 1185 | 258 | -73 |
| c3_tz_c | P_XZTZ | 165 | P_TZ | 422 | 257 | -48 |
| st_ll_1 | P_XZ | 902 | P_TZ | 1110 | 208 | -14 |

矿池 37 条中仅约 1/4 相对 CBO exec 有优势；相对经典 force-plan 严格更优的更少（早期阈值 6 条）。

### 3.4 Benefit 提高选中率，但不保证 e2e
在 9 条 force-pool 上（`adv-sel-compare.json`）：

| 条件 | 高级 plan 选中 |
|------|----------------|
| ADV=1，无 benefit 重排 | **0 / 9**（全 `P_TZ`） |
| ADV=1 + `online_benefit_model_adv_v1` | **7 / 9**（`P_TS`×5，`P_XZTTS`×2，`P_TZ`×2） |

选中的高级 plan 与 force-plan exec 最优（多为 `P_XZ`）并不总一致——模型按特征预测，存在误选（如偏好 `P_TS`）。

### 3.5 Dual rewrite / force-mine 无法形成旁路规则
- 对 9 条池跑 `force-mine-diversity`：kart force 的 e2e **无一**相对同轮 CBO 节省 ≥8ms（`wins_ge_8 = 0`）。
- 原因：ADV seed + FastCost 使 **`t_plan`≈8–15s**，rewrite 即便略降 exec，也被 plan 淹没；且部分 force（如 drop_spatial）落到 `P_FULL`。
- Admit（support≥3）结果：**rules 为空**。优势路径被认定为「benefit 选 plan」，而非 offline rewrite。

### 3.6 Cold e2e：plan 淹没（止损命中）
`adv-eval-cold-p4`：`bound_ir_adv_v1` n=9，四臂 × trials=3，共 108 trials，`oracle_fail=0`。

| arm | e2e p50 (ms) | exec p50 | plan p50 |
|-----|--------------|----------|----------|
| fullscan | 694 | 692 | 2 |
| rbo | 1472 | 1466 | 8 |
| cbo | 11163 | 1134 | 9819 |
| kart | 14182 | 1183 | 12509 |

- kart vs cbo **e2e** p50 比 ≈ **0.76×**（kart 更慢；目标 ≥1.15×）→ **不通过**
- kart vs cbo **exec** p50 比 ≈ **1.01×**（基本打平）
- fullscan ≪ kart e2e（kart 被 plan 拖死）
- kart 高级 plan 占比 **78%**（21/27）——选中了，但没用

---

## 4. 验收判定

| 标准 | 结果 |
|------|------|
| kart_e2e ≫ cbo（≥1.15× 或 gap≥30ms） | **失败**（比 0.76，gap −3559ms） |
| 尽量优于 fullscan | **失败** |
| 披露 t_plan 与高级 plan 占比 | 已披露（plan≈12.5s；高级 78%） |
| 进主表 | **否**（frontier only） |

---

## 5. 产物清单（归档时点）

### 本目录
- `summary.md`（本文）
- `frontier_summary.json` — cold 验收聚合
- `census_summary.json` — force-plan 普查 wins
- `adv-sel-compare.json` — benefit 选中率对照

### 服务器（未全部拉回；路径相对 `$KART_DST`）
- `experiments/results/adv-census-p0/`
- `experiments/results/adv-force-mine-p3/`
- `experiments/results/adv-eval-cold-p4/`
- `experiments/results/adv-sel-base/`、`adv-sel-adv/`
- `config/online_benefit_model_adv_v1.json`
- `experiments/workloads/bound_ir_adv_{mine_v1,v1,force_pool_v1}*`
- `experiments/suites/e2e-adv-{mine,force,eval}.yaml`
- `experiments/suites/dual_action_rules_adv_v1.json`

### 探索期改动过、已回退的代码面（勿再当主路径默认）
- `QueryEngine`：ADV seed + benefit 仅重排高级 plan
- `BeamSearch`：ADV 初始 frontier diversify
- `PlanBuilder`：`P_XZTZ` / `P_XZTZH`
- `DualRewritePolicy`：`advancedPlan` / `allowPlanAlias` / selectivity 含 `P_TZH`
- `LlmRewriteArm`：分支行数用 plan 自有索引族；xz/ts range features
- `admit_dual_rules.py`：xz/ts when；`assemble_adv_v1`

---

## 6. 回退说明

- GitHub 检查点分支：`checkpoint/2026-10-11-advanced-index-dual` @ `de36675`  
  （Checkpoint: advanced indexes (XZ*/TShape/XZT), dual rewrite, and experiment suites.）
- 归档后工作区代码与该检查点对齐；**仅保留本目录文档与 JSON 摘要**。
- 主表路径继续：`KART_ADVANCED_INDEXES=0` + 主 `dual_action_rules.json` + 冻结 complex/simple。

---

## 7. 若将来重开：最小条件

1. **先把 ADV 规划压到 ≪ exec**（否则任何选中率提升都变不成 e2e）。
2. FastCost / benefit 必须在 holdout 上校准到与 force-plan exec 同序，且避免误选 `P_TS`/`P_FULL`。
3. 矿池需显著大于 9，且 rewrite 的节省需相对 **exec** 成立（e2e 口径在 plan 未降前无意义）。
4. 通过后再考虑是否并入主表；在此之前保持旁路与 `ADVANCED=0` 默认。
