# KART 操作手册（How to run）

## 0. 环境（WSL + HBase 实验路径）

命令**只在 WSL** 执行。推荐：

```bash
cd /home/jaytang/projects/llm-kv
bash scripts/sync-wsl-workspace.sh   # 若刚在 Windows 改过代码
```

**实验设计要求：查询执行必须走 HBase**（`spec/requirement.md`）。  
`MemoryBackend` / `--memory` 仅用于离线单测，不作为验收路径。

Live LLM 可选（项目根 `.env`）：

```bash
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-...
LLM_JSON_MODE=true
```

---

## 1. 实验主路径

| 步骤 | 命令 |
|------|------|
| ① 开 ZK + HBase（成功后自动 `load-fixture`） | `./scripts/kart.sh up` |
| ② 确认 / 补载 fixture | `./scripts/kart.sh doctor`；`./scripts/kart.sh load-fixture` |
| ③ 自动验收 | `./scripts/kart.sh check` |
| ④ 交互（**HBase + 真实 LLM**） | `./scripts/kart.sh chat`（回归：`chat --mock`） |
| ⑤ T-Drive 回归 | `./scripts/kart.sh smoke` |
| 收工 | `./scripts/kart.sh down` |

验证用例（NL ↔ 参考结果）：**[`docs/verify_example.md`](verify_example.md)**。

Fixture 写入 **隔离表** `fixture_*`，不会覆盖 T-Drive 的 `traj_*_v1`。

---

## 2. 交互聊天

```bash
./scripts/kart.sh up
./scripts/kart.sh chat           # 真实 LLM + HBase fixture（需 .env）
./scripts/kart.sh chat --mock    # 仅回归：Mock LLM，仍走 HBase
```

启动应看到 `backend=hbase`。确认时输入 `y`。结果在 `runs/<query_id>/result.json`。

`--memory` / MemoryBackend **不是**实验路径；见 `docs/scaffolding.md`。

---

## 3. `check` 覆盖

1. jar  
2. `doctor`（失败则后续 HBase 用例 FAIL）  
3. `load-fixture-hbase`  
4. HBase Mock chat → `[A, B]`  
5. COUNT → UNSUPPORTED（HBase 路径）  
6. demo-failures（单元）  
7. T-Drive smoke 22/22  
8. 可选 live LLM  

---

## 4. 其它命令

```bash
./scripts/kart.sh help
./scripts/kart.sh load-fixture
./scripts/kart.sh doctor
./scripts/kart.sh smoke
./scripts/kart.sh probe
./scripts/kart.sh rebuild
```

---

## 5. 人工演示话术

见 `docs/verify_example.md` A1 / B1 / A5。

---

## 6. 数据说明

| 模式 | 数据 | 后端 |
|------|------|------|
| `chat` / `query-nl`（默认） | Fixture `fixture_v1_ready` | **HBase** `fixture_*` |
| `query-ir` + `tdrive_v1_ready` / `smoke` | T-Drive | **HBase** `traj_*` |
| `--memory` | Fixture | MemoryBackend（仅开发） |

区域名：`docs/regions.md`。

---

## 7. 测试用例与脚手架

- [`docs/verify_example.md`](verify_example.md) — 手工用例（HBase）  
- [`docs/scaffolding.md`](scaffolding.md) — fixture / Mock / 单测 **为何不可删**  
- T-Drive Oracle 摘录：`experiments/results/verification_tdrive_pairs.json`

## 8. 相关文档

- `docs/environment-lock.md` — JDK / HBase / LLM  
- `docs/wsl-projects-layout.md` — WSL 目录  
- `USER_ACTIONS.md`  
- `spec/requirement.md` §8  
