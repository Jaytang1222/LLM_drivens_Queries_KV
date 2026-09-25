# KART 验证用例手册（chat 主路径）

- Manifest：`tdrive_v1_ready` · 后端：HBase only  
- **怎么测：** `./scripts/kart.sh chat` → 粘贴「粘贴到 kart>」→ 若系统追问则按「请回复」填一行 → Confirm 输入 `y`  
- 真值：`experiments/workloads/tdrive_smoke.oracle.json`  
- 工作目录：`cd /home/jaytang/projects/llm-kv`（先 `./scripts/kart.sh up`）

## 人工交互约定

| 你输入什么 | 说明 |
|------------|------|
| **粘贴到 kart>** | 整句自然语言，直接回车 |
| **请回复（一行）** | 仅当出现 `I need a bit more information` / `Please answer ...` 时，按提示字段粘贴表格里的字面量 |
| **Confirm** | 出现 `Confirm and plan? [y/n]` 时输入 `y` |
| **不要** | 回复 `h_s_small` / `topk_st_1` 等 smoke **query_id**（那不是 region） |

## 注册区名（chat 可用）

| region | 用途 |
|--------|------|
| `tdrive_smoke_anchor` | V2 / V5 可验收 |
| `tdrive_topk_box` | V3 / V4 / V6 可验收 |
| `beijing_core` | 仅演示；**不要**用来验收唯一 `_14` |

---

## 格式 1：可跑通并对照结果

### V1. 时间 + 车辆（唯一 1 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句，推荐）** | `Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **若先试短句** | `Find trajectories of taxi 8857` → 可 Confirm 得到 **24 条**；要唯一 1 条请改贴上面完整句 |
| **请回复** | 无（完整句一般不问） |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_14"]`** |

Confirm 摘要应可见：`predicate=vehicle_id EQ 8857` 与 ISO 时间。

---

### V2. 时空 + 车辆（唯一 1 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>（完整句）** | `Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00` |
| **若先试短句** | `Show me taxi 8857 around tdrive_smoke_anchor` |
| **请回复（若问 temporal）** | `2008-02-03T20:50:00+08:00 to 2008-02-03T21:50:00+08:00` |
| **Confirm** | `y` |
| **参考结果** | **`["8857-8857_14"]`** |

**禁止**用 `beijing_core` 替代 `tdrive_smoke_anchor` 后仍期望本结果。

---

### V3. 小时空 IDS（含锚点 tid）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `List trajectory ids intersecting tdrive_topk_box between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果** | 结果中须含 **`8857-8857_14`**（常见为 2 条量级） |

---

### V4. Top-K DTW（有序）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 3 most similar to 8857-8857_14 using DTW` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["2406-2406_9", "4920-4920_24"]`** |

摘要中**不应**出现 `predicate=vehicle_id EQ 8857`（参考 tid 里的数字不是候选过滤）。

---

### V5. Top-K DTW（恰好 3 条）

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `Top-3 DTW neighbors of 8857-8857_14 intersecting tdrive_smoke_anchor between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00` |
| **请回复** | 无 |
| **Confirm** | `y` |
| **参考结果（有序）** | **`["5340-5340_15", "2376-2376_10", "289-289_12"]`** |

---

### V6. 故意省略 metric → 澄清后再跑

| 步骤 | 你做什么 |
|------|----------|
| **粘贴到 kart>** | `Find k=3 trajectories most similar to 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00` |
| **请回复（问 metric 时）** | `DTW` |
| **Confirm** | `y` |
| **参考结果** | 同 V4：`["2406-2406_9", "4920-4920_24"]` |

---

## 格式 2：应被拒绝 / 澄清（不要强行 Confirm 出假结果）

### R1. COUNT

| **粘贴到 kart>** | `COUNT how many trajectories intersect beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00` |
| **期望** | 直接 `UNSUPPORTED_QUERY`；无结果列表 |

### R2. 连续路径

| **粘贴到 kart>** | `Find trajectories whose continuous path segment crosses the ring road (not just GPS samples)` |
| **期望** | `UNSUPPORTED_QUERY: continuous path / LINEAR_SEGMENT...` |

### R3. 未知 metric

| **粘贴到 kart>** | `Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE` |
| **期望** | `UNSUPPORTED_QUERY: unsupported metric=EDIT_DISTANCE; ...` |

### R4. 连续 Fréchet

| **粘贴到 kart>** | `Use continuous Fréchet distance to find neighbors of 8857-8857_14` |
| **期望** | `UNSUPPORTED_QUERY: continuous Fréchet is not supported...` |

### R5. 未注册地名

| **粘贴到 kart>** | `Trajectories through 火星广场 yesterday` |
| **请回复（若问 spatial）** | 可填注册名如 `tdrive_topk_box`，或说明不知道：`I do not know a registered region` |
| **期望** | 澄清；**不得**臆造 `beijing_core` 后吐出万级轨迹 |

---

## 场景矩阵

| ID | 粘贴要点 | 参考结果 |
|----|----------|----------|
| V1 完整 | 时间+车辆 | 1 条 `_14` |
| V1 短句 | 仅车辆 | 24 条 |
| V2 | `tdrive_smoke_anchor`+时间+车辆 | 1 条 |
| V4/V6 | `tdrive_topk_box`+DTW | 有序 2 条 |
| R1–R5 | 拒绝/澄清 | 无假列表 |

```bash
./scripts/kart.sh up && ./scripts/kart.sh chat
# kart> （粘贴上表完整句）
# > y
# kart> /quit
```

另见：[`hard_query_example.md`](hard_query_example.md)。
