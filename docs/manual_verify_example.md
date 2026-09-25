# KART 人工验证用例手册（chat 主路径 · 新编）

- Manifest：`tdrive_v1_ready` · 后端：HBase only  
- **怎么测：** `./scripts/kart.sh chat` → 粘贴「粘贴到 kart>」→ 按「请回复」填一行 → Confirm 输入 `y`  
- 真值：`experiments/workloads/tdrive_smoke.oracle.json` + 本目录 `evidence.json`  
- 工作目录：`cd /home/jaytang/projects/llm-kv`  
- 与 [`easy_query_example.md`](easy_query_example.md) / [`hard_query_example.md`](hard_query_example.md) 互补：本册按**简单 → 中等 → 复杂结果**编排，并覆盖澄清/拒绝。

## 人工交互约定（必读）

| 你输入什么 | 说明 |
|------------|------|
| **粘贴到 kart>** | 整句自然语言（优先用「完整句」） |
| **请回复（一行）** | 系统问 temporal / spatial / metric 时，粘贴表格中的**字面量** |
| **Confirm** | `y` |
| **禁止当作澄清答案** | `h_t_small`、`topk_st_3`、`st_ls_1` 等——那是 **query_id**，不是 `region_name` |

**注册区名（本册用到）：** `tdrive_smoke_anchor` · `tdrive_topk_box` · `tdrive_topk_s1` · `tdrive_topk_mid` · `tdrive_topk_wide`（见 `docs/regions.md`）。  
无注册区时，完整句里写 **lon/lat 矩形**（下表已写好）。

---

## 格式 1：可跑通并对照结果

### A. 简单结果（1～3 条）

### M1. 车辆 + 窄时间窗（唯一 1 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_14"]`** |

Confirm 摘要应见：`predicate=vehicle_id EQ 8857` 与上述 ISO 时间。

---

### M2. 时空 + 车辆 + 注册区（唯一 1 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00` |
| **若先试短句** | `Show me taxi 8857 around tdrive_smoke_anchor` |
| **请回复（问 temporal 时）** | `2008-02-03T20:50:00+08:00 to 2008-02-03T21:50:00+08:00` |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_14"]`** |

**禁止**用 `beijing_core` 替代区名后仍期望本结果。

---

### M3. 纯时空小盒（唯一 1 条，无车辆谓词）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List trajectory ids intersecting the rectangle lon 116.647633 to 116.656987 lat 40.130472 to 40.137708 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_14"]`** |

---

### M4. Top-K DTW（请求 k=3，实际 2 条有序）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 3 most similar to 8857-8857_14 using DTW` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["2406-2406_9","4920-4920_24"]`** |

摘要中**不应**出现 `predicate=vehicle_id EQ 8857`（参考 tid 数字不是候选过滤）。

---

### M5. Top-K 离散 Fréchet（同窗；序可与 DTW 相同）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 3 most similar to 8857-8857_14 using FRECHET` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["2406-2406_9","4920-4920_24"]`** |

（本候选池很小，序与 M4 相同；**距离数值**不同，勿与 M9 大窗 Top-K 混用。）

---

### M6. 纯空间 Top-K DTW（无时间谓词，3 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_s1, find 3 most similar to 8857-8857_14 using DTW` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["9630-9630_49","5793-5793_5","2951-2951_39"]`** |

---

### M7. Top-K DTW 中等空间窗（3 条，与 M4 不同）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_mid between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00, find 3 most similar to 8857-8857_14 using DTW` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["5340-5340_15","2376-2376_10","289-289_12"]`** |

**不得**用 M4 的列表验收本条。

---

### B. 中等结果（7～33 条）

### M8. 车辆 + 空间盒（13 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Which trajectories of taxi 8857 intersect tdrive_topk_box` |
| **请回复（问 spatial 时）** | `tdrive_topk_box` |
| **Confirm** | `y` |
| **参考结果** | **13 条：** |

```
["8857-8857_11","8857-8857_12","8857-8857_14","8857-8857_15","8857-8857_18",
 "8857-8857_19","8857-8857_20","8857-8857_21","8857-8857_22","8857-8857_23",
 "8857-8857_24","8857-8857_28","8857-8857_9"]
```

---

### M9. 车辆 + 约一天时间窗（7 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Find trajectories of taxi 8857 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_11","8857-8857_12","8857-8857_4","8857-8857_6","8857-8857_7","8857-8857_8","8857-8857_9"]`** |

---

### M10. 大时间 × 小空间（16 条，跨车辆）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List trajectory ids intersecting the rectangle lon 116.647596 to 116.657023 lat 40.130472 to 40.137708 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **16 条：** |

```
["1052-1052_1","1697-1697_10","2376-2376_3","27-27_1201976230","2951-2951_15",
 "2955-2955_11","3336-3336_11","5793-5793_5","5793-5793_6","5793-5793_7",
 "5793-5793_8","5817-5817_2","715-715_11","7396-7396_13","8857-8857_11",
 "8857-8857_9"]
```

---

### M11. 纯空间小盒（33 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List every trajectory that intersects the rectangle lon 116.651719 to 116.657610 lat 40.130484 to 40.135007` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **33 条：** |

```
["1697-1697_10","2405-2405_45","27-27_1202149999","3336-3336_31","5340-5340_17",
 "5790-5790_70","5793-5793_10","5793-5793_17","5793-5793_20","5793-5793_21",
 "5793-5793_22","5793-5793_28","5793-5793_34","5793-5793_35","5793-5793_5",
 "5793-5793_6","5793-5793_7","5793-5793_8","6088-6088_28","7192-7192_70",
 "7396-7396_13","8857-8857_11","8857-8857_12","8857-8857_14","8857-8857_15",
 "8857-8857_18","8857-8857_19","8857-8857_20","8857-8857_21","8857-8857_22",
 "8857-8857_23","8857-8857_24","8857-8857_9"]
```

---

### M12. 车辆等值全集（24 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `List all trajectories belonging to taxi 8857` |
| **请回复** | 无（若 LLM 擅自加时间，可 `n` 后改贴本句） |
| **Confirm** | `y` |
| **参考结果（升序）** | 24 条： |

```
["8857-8857_11","8857-8857_12","8857-8857_13","8857-8857_14","8857-8857_15",
 "8857-8857_16","8857-8857_17","8857-8857_18","8857-8857_19","8857-8857_20",
 "8857-8857_21","8857-8857_22","8857-8857_23","8857-8857_24","8857-8857_25",
 "8857-8857_26","8857-8857_27","8857-8857_28","8857-8857_29","8857-8857_4",
 "8857-8857_6","8857-8857_7","8857-8857_8","8857-8857_9"]
```

---

### C. 复杂 / 大规模结果（用 count + 首末 + sha）

### M13. 纯空间稍大盒（92 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List every trajectory that intersects the rectangle lon 116.647596 to 116.657023 lat 40.130472 to 40.137708` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **92 条**（不在此全文粘贴） |

| 项 | 值 |
|----|-----|
| count | **92** |
| first5 | `["1052-1052_1","1052-1052_23","16-16_54","1697-1697_10","2376-2376_17"]` |
| last5 | `["8894-8894_23","9397-9397_32","9547-9547_31","9584-9584_11","9630-9630_49"]` |
| sha256 | `b9bf9b2460f1a64b4c255e84a3c25339d29dec21209e13f054b5c4025917c3f2` |

结果集应 ⊃ M11。

---

### M14. 小时 × 大空间（703 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List trajectory ids intersecting the rectangle lon 116.362254 to 116.940150 lat 39.870622 to 40.396848 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **703 条** |

| 项 | 值 |
|----|-----|
| count | **703** |
| first5 | `["100-100_10","10018-10018_6","10024-10024_10","10035-10035_9","10036-10036_4"]` |
| last5 | `["9959-9959_17","9962-9962_16","9975-9975_11","9989-9989_14","9998-9998_7"]` |
| sha256 | `60424dddc0da65d8fc767f86e3746ad80d5a35fd4e3ed921bb8c3a69e6906cb3` |

---

### M15. 纯时间 10 分钟窗（1301 条 · 最重 IDS）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List all trajectories with any sample between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **1301 条**（不在此全文粘贴） |

| 项 | 值 |
|----|-----|
| count | **1301** |
| first5 | `["100-100_10","10018-10018_6","10020-10020_8","10022-10022_11","10023-10023_16"]` |
| last5 | `["9975-9975_11","9977-9977_9","9988-9988_8","9989-9989_14","9998-9998_7"]` |
| sha256 | `bc9210e652f9838ea65584f4208e667d5d5c043ae6febd69b319af9fe35fdb17` |

本条比 M14 更大：无空间过滤。验收用 count + first5 + last5 + sha，勿肉眼对全文。

---

### M16. 故意省略 metric → 澄清后对齐 M4

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `Find k=3 neighbors of 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00` |
| **请回复（问 metric 时）** | `DTW` |
| **若还问 spatial** | `tdrive_topk_box` |
| **Confirm** | `y` |
| **参考结果** | 同 **M4** |

---

## 格式 2：应被拒绝 / 澄清

### R1. COUNT 聚合

| **粘贴到 kart>** | `How many taxis passed tdrive_topk_box between 21:00 and 22:00 on 2008-02-03` |
| **期望** | `UNSUPPORTED_QUERY`（COUNT / 聚合）；**不得**静默变成 IDS 列表 |

### R2. 速度谓词

| **粘贴到 kart>** | `Trajectories of taxi 8857 with average speed under 10 km/h between 21:00 and 22:00` |
| **期望** | `UNSUPPORTED_QUERY: derived attributes (speed / dwell / stay-point)...` |

### R3. 连续路径

| **粘贴到 kart>** | `Which trajectories continuously crossed the 2nd Ring as a polyline, not just GPS samples inside a box` |
| **期望** | `UNSUPPORTED_QUERY`（continuous path / LINEAR_SEGMENT） |

### R4. 未知 metric

| **粘贴到 kart>** | `Top-50 trajectories most similar to 8857-8857_14 using LCSS` |
| **期望** | `UNSUPPORTED_QUERY: unsupported metric=LCSS; supported: DTW, FRECHET, HAUSDORFF` |

### R5. 未注册地名

| **粘贴到 kart>** | `Dump all trajectories through 火星广场 yesterday` |
| **请回复（问 spatial）** | `I do not know a registered region` |
| **期望** | 澄清；**不得**臆造大框后返回 M14/M15 量级假全集 |

### R6. 成对邻近

| **粘贴到 kart>** | `Find pairs of taxis that were within 50m of each other in beijing_core tonight` |
| **期望** | `UNSUPPORTED_QUERY`（pair / co-location） |

---

## 场景矩阵

| ID | 类型 | 规模 | 备注 |
|----|------|------|------|
| M1 | 车辆+时间 | **1** | 最简正确性 |
| M2 | 车辆+时空+区名 | **1** | 注册区 |
| M3 | 纯时空 lon/lat | **1** | 无车辆谓词 |
| M4 / M5 | Top-K DTW / Fréchet | **2** | k=3 但候选不足 |
| M6 | 纯空间 Top-K | **3** | 无时间 |
| M7 | Top-K 中等窗 | **3** | 与 M4 不同序 |
| M8 | 车辆+空间 | **13** | |
| M9 | 车辆+一天 | **7** | |
| M10 | 时空跨车 | **16** | |
| M11 | 纯空间 | **33** | |
| M12 | 仅车辆 | **24** | |
| M13 | 纯空间 | **92** | count+sha |
| M14 | 时空大框 | **703** | count+sha |
| M15 | 纯时间 | **1301** | 最重 IDS |
| M16 | 澄清 metric | 同 M4 | |
| R1–R6 | 拒绝/澄清 | 无假列表 | |

---

## 旁证（可选，非人工 chat）

```bash
./scripts/kart.sh smoke
# 例：与 M4 同语义的冻结 IR（smoke topk_st_1）
./scripts/kart.sh run query-ir \
  --ir experiments/workloads/tdrive_smoke.json \
  --manifest tdrive_v1_ready
```

结构化摘要：[`manual_verify_example/evidence.json`](manual_verify_example/evidence.json)。  
另见：[`easy_query_example.md`](easy_query_example.md)、[`hard_query_example.md`](hard_query_example.md)。
