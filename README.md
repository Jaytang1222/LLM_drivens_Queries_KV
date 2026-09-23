# KART (LLM_KV)

LLM-guided trajectory queries over HBase with typed IR, safe plan search, and a calibrated-ready cost model.

- Java 8, HBase 2.2.3, **WSL Ubuntu**（命令均在 WSL 中运行）
- Spec: `spec/requirement.md`, `spec/design.md`, `spec/task.md`
- **操作手册：[`docs/how-to-run.md`](docs/how-to-run.md)**

## Quick start（WSL · HBase 实验路径）

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh up
./scripts/kart.sh chat       # Live LLM → HBase（需 .env）
./scripts/kart.sh smoke      # T-Drive 22/22
./scripts/kart.sh down
```

用例：[`docs/verify_example.md`](docs/verify_example.md)。  
**勿删** fixture / `testdata` / Mock / 单测 —— 见 [`docs/scaffolding.md`](docs/scaffolding.md)。


## Plan selection (P5)

`query-ir` / `query-nl` / `chat` use cost-based `PlanSelector`. Coefficients in `config/planner.yaml` (`calibrated: false`).

## Docs

- `docs/how-to-run.md` — **日常启动 / 验收 / 聊天**
- `docs/verify_example.md` — **NL↔参考结果用例（HBase）**
- `docs/scaffolding.md` — **不可删除的正确性脚手架**
- `docs/environment-lock.md` — JDK / Maven / HBase / LLM
- `docs/regions.md` — 预注册区域
- `docs/supported-semantics.md` — 语义
- `USER_ACTIONS.md` — 标定等人工项

## Layout

```
scripts/kart.sh           统一入口（日常只用这个）
docs/how-to-run.md        操作手册
spec/                     requirement / design / task / plan
src/main/java/kart/       CLI / IR / plan / cost / exec
config/                   layout, planner, regions, hbase/
catalog/                  READY manifests（本地，gitignore）
experiments/              T-Drive smoke + oracle
```
