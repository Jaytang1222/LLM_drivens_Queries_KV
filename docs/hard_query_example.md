# KART 困难验证用例手册（chat 主路径）

- Manifest：`tdrive_v1_ready` · 后端：HBase only  
- **怎么测：** `./scripts/kart.sh chat` → 粘贴「粘贴到 kart>」→ 按「请回复」填一行 → Confirm 输入 `y`  
- 真值：`experiments/workloads/tdrive_smoke.oracle.json` + 本目录 `evidence.json` / `*.oracle.json`  
- 工作目录：`cd /home/jaytang/projects/llm-kv`

## 人工交互约定（必读）

| 你输入什么 | 说明 |
|------------|------|
| **粘贴到 kart>** | 整句自然语言（优先用「完整句」） |
| **请回复（一行）** | 系统问 temporal / spatial / metric 时，粘贴表格中的**字面量**（区名、ISO 时间、`DTW` 等） |
| **Confirm** | `y` |
| **禁止当作澄清答案** | `h_s_small`、`st_ls_1`、`topk_st_2` 等——那是 **query_id / 文件名**，不是 `region_name` |

**注册区名（chat 可填）：** `tdrive_smoke_anchor` · `tdrive_topk_box` · `tdrive_topk_wide` · `tdrive_topk_s1`（见 `docs/regions.md`）。  
无注册区覆盖时，完整句里写 **lon/lat 矩形**（下表已写好，可直接粘贴）。

`query-ir` 冻结文件仅作旁证，**不是**你在 chat 里要输入的内容；旁证命令见文末。

---

## 格式 1：可跑通并对照结果

### H1. 车辆等值全集（24 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `List all trajectories belonging to taxi 8857` |
| **请回复** | 无（若 LLM 擅自加时间，可 `n` 后改贴本句或 H2 完整句） |
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

### H2. 大时间窗 + 车辆（7 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Find trajectories of taxi 8857 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00` |
| **若先试短句** | `Find taxi 8857 trajectories over about one day around early February 2008` |
| **请回复（问 temporal 时）** | `2008-02-02T13:30:00+08:00 to 2008-02-03T13:30:00+08:00` |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_11","8857-8857_12","8857-8857_4","8857-8857_6","8857-8857_7","8857-8857_8","8857-8857_9"]`** |

---

### H3. 空间盒 + 车辆（13 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Which trajectories of taxi 8857 intersect tdrive_topk_box` |
| **若先试含糊短句** | `Which chunks of taxi 8857 intersect the small box around the sample anchor` |
| **请回复（问 spatial 时）** | `tdrive_topk_box` |
| **Confirm** | `y` |
| **参考结果** | **13 条：** |

```
["8857-8857_11","8857-8857_12","8857-8857_14","8857-8857_15","8857-8857_18",
 "8857-8857_19","8857-8857_20","8857-8857_21","8857-8857_22","8857-8857_23",
 "8857-8857_24","8857-8857_28","8857-8857_9"]
```

**错误：** 澄清时填 `h_s_small` → `unknown region_name`（那是 query_id）。

---

### H4. 大时间 × 小空间（16 条，跨车辆）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List trajectory ids intersecting the rectangle lon 116.647596 to 116.657023 lat 40.130472 to 40.137708 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00` |
| **请回复** | 无（完整句已含时间与 lon/lat） |
| **Confirm** | `y` |
| **参考结果** | **16 条：** |

```
["1052-1052_1","1697-1697_10","2376-2376_3","27-27_1201976230","2951-2951_15",
 "2955-2955_11","3336-3336_11","5793-5793_5","5793-5793_6","5793-5793_7",
 "5793-5793_8","5817-5817_2","715-715_11","7396-7396_13","8857-8857_11",
 "8857-8857_9"]
```

---

### H5. 纯空间小盒（33 条）

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

### H6. 纯空间稍大盒（92 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List every trajectory that intersects the rectangle lon 116.647596 to 116.657023 lat 40.130472 to 40.137708` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **92 条**（验收用 count + 首末 5 + sha，全文见 `evidence.json` → `s_small_1`） |

| 项 | 值 |
|----|-----|
| count | **92** |
| first5 | `["1052-1052_1","1052-1052_23","16-16_54","1697-1697_10","2376-2376_17"]` |
| last5 | `["8894-8894_23","9397-9397_32","9547-9547_31","9584-9584_11","9630-9630_49"]` |
| sha256 | `b9bf9b2460f1a64b4c255e84a3c25339d29dec21209e13f054b5c4025917c3f2` |

结果集应 ⊃ H5。

---

### H7. 小时 × 大空间（703 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `List trajectory ids intersecting the rectangle lon 116.362254 to 116.940150 lat 39.870622 to 40.396848 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | **703 条**（不在此全文粘贴） |

| 项 | 值 |
|----|-----|
| count | **703** |
| first5 | `["100-100_10","10018-10018_6","10024-10024_10","10035-10035_9","10036-10036_4"]` |
| last5 | `["9959-9959_17","9962-9962_16","9975-9975_11","9989-9989_14","9998-9998_7"]` |
| sha256 | `60424dddc0da65d8fc767f86e3746ad80d5a35fd4e3ed921bb8c3a69e6906cb3` |

---

### H8. Top-K DTW k=5

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using DTW` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["4706-4706_8","5781-5781_10","2406-2406_9","4920-4920_24","529-529_7"]`** |

---

### H9. Top-K 离散 Fréchet（序 ≠ DTW）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using FRECHET` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["4706-4706_8","2406-2406_9","5781-5781_10","476-476_8","4920-4920_24"]`** |

**不得**用 H8 的 DTW 列表验收本条。

---

### H10. Top-K 对称 Hausdorff

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using HAUSDORFF` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["4706-4706_8","2406-2406_9","5781-5781_10","476-476_8","4920-4920_24"]`** |

（与 H9 **同序、距离不同**。）

---

### H11. 故意省略 metric → 澄清

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `Find k=5 neighbors of 8857-8857_14 intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复（问 metric 时）** | `DTW` 或 `FRECHET` 或 `HAUSDORFF`（三选一） |
| **若还问 spatial** | `tdrive_topk_wide` |
| **Confirm** | `y` |
| **参考结果** | 分别对齐 H8 / H9 / H10；**不可混用** |

---

## 格式 2：应被拒绝 / 澄清

### R1. 成对邻近

| **粘贴到 kart>** | `Find pairs of taxis that were within 50m of each other in beijing_core tonight` |
| **期望** | `UNSUPPORTED_QUERY`（pair / co-location）；无结果列表 |

### R2. 速度谓词

| **粘贴到 kart>** | `Trajectories of taxi 8857 with average speed under 10 km/h between 21:00 and 22:00` |
| **期望** | `UNSUPPORTED_QUERY: derived attributes (speed / dwell / stay-point)...`；**不得**静默变成车辆+时间 IDS |

### R3. 连续路径

| **粘贴到 kart>** | `Which trajectories continuously crossed the 2nd Ring as a polyline, not just GPS samples inside a box` |
| **期望** | `UNSUPPORTED_QUERY`（continuous path / LINEAR_SEGMENT） |

### R4. 未知 metric

| **粘贴到 kart>** | `Top-50 trajectories most similar to 8857-8857_14 using LCSS` |
| **期望** | `UNSUPPORTED_QUERY: unsupported metric=LCSS; supported: DTW, FRECHET, HAUSDORFF` |

### R5. 未注册地名

| **粘贴到 kart>** | `Dump all trajectories through 火星广场 yesterday` |
| **请回复（问 spatial）** | `I do not know a registered region`（或填合法区名做行为观察） |
| **期望** | 澄清；**不得**臆造大框后返回 H7 量级假全集 |

---

## 场景矩阵

| ID | 你粘贴的关键信息 | 规模 |
|----|------------------|------|
| H1 | 仅 taxi 8857 | 24 |
| H2 | 8857 + 一天时间窗 | 7 |
| H3 | 8857 + `tdrive_topk_box` | 13 |
| H4 | lon/lat + 一天时间窗 | 16 |
| H5 / H6 | 仅 lon/lat | 33 / 92 |
| H7 | 大 lon/lat + 10 分钟窗 | 703 |
| H8–H10 | `tdrive_topk_wide` + metric | 5 有序 |
| H11 | 同上但澄清 metric | 同 H8–H10 |
| R1–R5 | 拒绝/澄清 | 无假列表 |

---

## 旁证（可选，非人工 chat）

引擎 ≡ Oracle 证据：`docs/hard_query_example/evidence.json`。

```bash
./scripts/kart.sh smoke
./scripts/kart.sh run query-ir \
  --ir docs/hard_query_example/topk_st_2.bound_ir.json \
  --manifest tdrive_v1_ready
```

另见：[`easy_query_example.md`](easy_query_example.md)、[`manual_verify_example.md`](manual_verify_example.md)。
