# KART 验证用例手册（实验路径 = Live LLM → HBase）

- 日期：2026-09-23（修订：全程 HBase；操作面以真实 LLM 为主）  
- 真值：Fixture Oracle / `[A,B]`；T-Drive `tdrive_smoke.oracle.json`（22/22）  
- 脚手架说明（**不可删**）：[`docs/scaffolding.md`](scaffolding.md)  
- 工作目录：`cd /home/jaytang/projects/llm-kv`

## 实验路径（强制）

**HBase 是唯一实验数据库。** `MemoryBackend` / `testdata` / Mock / 单测按 requirement **必须保留**，但不作为本手册手工步骤。

```bash
./scripts/kart.sh up              # ZK + HBase + load-fixture
./scripts/kart.sh doctor
./scripts/kart.sh chat            # 真实 LLM（需 .env）；回归可用 chat --mock
./scripts/kart.sh smoke           # T-Drive
./scripts/kart.sh down
```

| 数据 | Manifest | HBase 表 |
|------|----------|----------|
| §18 Fixture | `fixture_v1_ready` | `fixture_*`（与 T-Drive 隔离） |
| T-Drive | `tdrive_v1_ready` | `traj_*_v1` / `idx_*_v1` |

结果：`runs/<query_id>/result.json`。

---

## 格式 A：补全后执行 → 参考结果

### A1. Fixture · 时空 Top-K（完整句）· **HBase**

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | `Find the 2 trajectories most similar to R inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00` |
| **缺少的地方** | （无） |
| **修改补充后的文字** | （同输入） |
| **具体查询** | BoundIR `q_fixture_001`，manifest `fixture_v1_ready`，经 **HBase** `fixture_*` 表执行 |
| **参考结果** | **唯一**：`trajectory_ids=[A, B]`；DTW `A≈12.727922061357855`，`B≈56.568542494923804`；`status=OK`；banner 含 `backend=hbase` |
| **复跑** | `./scripts/kart.sh chat`（真实 LLM）→ 粘贴句 → `y`；无密钥时用 `chat --mock`（仍走 HBase） |

### A2. Fixture · 缺参考 ID · **HBase**

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | `Find top-2 most similar trajectories inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00` |
| **缺少的地方** | `reference_trajectory_id` |
| **修改补充后的文字** | 澄清答 `R` → 确认 `y` |
| **具体查询** | 同 A1（HBase） |
| **参考结果** | **`[A, B]`** |

### A3. Fixture · 缺区域 · **HBase**

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | `List trajectory ids that intersect an area between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00` |
| **缺少的地方** | 空间区域 |
| **修改补充后的文字** | 澄清答 `fixture_box` → `y` |
| **具体查询** | 同时间窗 + `fixture_box`，`TRAJECTORY_IDS`，HBase |
| **参考结果** | **`[A, B]`** |

### A4. Fixture · 中文 Top-K · **HBase**

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | `在 fixture_box 内，2008-02-02 08:00 到 08:10（+08:00），找出与 R 最相似的 2 条轨迹` |
| **缺少的地方** | （无） |
| **修改补充后的文字** | （同输入） |
| **具体查询** | 同 A1 |
| **参考结果** | **`[A, B]`** |

### A5. T-Drive · 小时空 IDS · **HBase**

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | （意图）`2008-02-03 21:20–21:30 +08` 锚点附近小矩形内的轨迹 |
| **缺少的地方** | 精确米制框 → 用冻结 IR |
| **修改补充后的文字** | BoundIR `st_ss_1` |
| **具体查询** | `query-ir --ir … --manifest tdrive_v1_ready`（**无** `--memory`） |
| **参考结果** | **唯一**：`["8857-8857_14"]` |

```bash
python3 - <<'PY'
import json
from pathlib import Path
items=json.load(open("experiments/results/verification_tdrive_pairs.json"))["items"]
ir=next(x["bound_ir"] for x in items if x["query_id"]=="st_ss_1")
Path("/tmp/st_ss_1.json").write_text(json.dumps(ir,indent=2))
PY
java -Dkart.root="$PWD" -jar target/kart.jar query-ir \
  --ir /tmp/st_ss_1.json --catalog catalog --manifest tdrive_v1_ready
```

### A6. T-Drive · 时间 + 车辆 · **HBase**

| 步骤 | 内容 |
|------|------|
| **具体查询** | BoundIR `h_t_small` |
| **参考结果** | **`["8857-8857_14"]`** |

### A7. T-Drive · 时空 + 车辆 · **HBase**

| 步骤 | 内容 |
|------|------|
| **具体查询** | BoundIR `h_st_1` |
| **参考结果** | **`["8857-8857_14"]`** |

### A8. T-Drive · 时空 Top-K k=3 · **HBase**

| 步骤 | 内容 |
|------|------|
| **具体查询** | BoundIR `topk_st_1`，`reference_tid=41360` |
| **参考结果** | 有序：`2406-2406_9` (280594.80484162876) → `4920-4920_24` (438242.773916195)（仅 2 条合格） |

### A9 / A10. T-Drive Top-K · **HBase**

见 `verification_tdrive_pairs.json` 中 `topk_st_2`、`topk_s_1`。  
或一次复验：`./scripts/kart.sh smoke` → **passed=22 failed=0**。

---

## 格式 B：不支持 → 拒绝（仍经 HBase 会话）

### B1. COUNT

| 步骤 | 内容 |
|------|------|
| **输入的自然语言** | `Please COUNT how many trajectories intersect fixture_box on 2008-02-02 between 08:00 and 08:10 +08` |
| **期望** | `UNSUPPORTED_QUERY`；`backend=hbase`；无伪造结果 |
| **复跑** | `./scripts/kart.sh chat` 或 `query-nl`（HBase）；回归可用 `--mock` |

### B2. 中文 COUNT

| **输入** | `统计 fixture_box 里有多少条轨迹` |
| **期望** | `UNSUPPORTED_QUERY` |

### B3–B5

非 DTW 度量、未注册地名等：澄清或拒绝；不得静默改语义后返回结果。均在 **已 `up` + `load-fixture`** 的 HBase 会话中验证。

---

## 场景矩阵（全部 HBase）

| ID | 类型 | Manifest | 后端 |
|----|------|----------|------|
| A1–A4, B* | Fixture NL | `fixture_v1_ready` | HBase `fixture_*` |
| A5–A10 | T-Drive IR/smoke | `tdrive_v1_ready` | HBase `traj_*` |

---

## 一键复验

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh up
./scripts/kart.sh check    # doctor + load-fixture + HBase chat + COUNT + smoke …
# 或手工：chat --mock / query-ir / smoke
./scripts/kart.sh down
```
