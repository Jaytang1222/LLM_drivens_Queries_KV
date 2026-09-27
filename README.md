# KART (LLM_KV)

LLM-guided trajectory queries over **HBase** (T-Drive): typed IR, BeamSearch + cost selection, schema/coverage-safe plans.

- Java 8 · HBase 2.2.3 · **WSL Ubuntu**
- Spec: `spec/requirement.md`, `spec/design.md`, `spec/副本1.pdf`
- **Run:** [`docs/how-to-run.md`](docs/how-to-run.md)
- **Verify:** [`docs/easy_query_example.md`](docs/easy_query_example.md)

## Quick start（WSL）

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh up
# first time if catalog missing:
#   ./scripts/kart.sh run build-snapshot --data datasets/tdrive
./scripts/kart.sh chat       # Live LLM → HBase（.env）
./scripts/kart.sh smoke      # 24/24 vs Oracle
./scripts/kart.sh down
```

## Docs

| 文件 | 用途 |
|------|------|
| `docs/how-to-run.md` | 启动 / 验收 / 聊天 |
| `docs/easy_query_example.md` | 主路径用例与参考结果 |
| `docs/hard_query_example.md` | 困难用例（+ `docs/hard_query_example/` 冻结 IR） |
| `docs/manual_verify_example.md` | 人工验证（简单→复杂 + 拒绝） |
| `docs/supported-semantics.md` | 语义与不支持项 |
| `docs/regions.md` | 预注册区域 |
| `docs/environment-lock.md` | JDK / Maven / HBase / LLM |
| `docs/comparative-experiment-readiness-2026-09-25.md` | 对比实验当前验收入口，历史细节见 `docs/comparative-refine.md` |
| `docs/ablation-experiment-readiness-2026-09-25.md` | 消融实验当前验收入口，历史细节见 `docs/ablation-refine.md` |
| `experiments/README.md` | 对比和消融实验入口、workload 与结果约定 |

## Layout

```
scripts/             环境、同步、校准、smoke 与 bench 入口
  kart.sh kart-env.sh sync-wsl-workspace.sh
  bench-parse.sh bench-plan.sh bench-e2e.sh bench-all.sh bench-ablation.sh
docs/                操作与验证
spec/                需求/设计/PDF
src/main/java/kart/  IR / search / cost / exec
config/              layout, planner, regions, hbase/
catalog/             READY manifests（gitignore）
experiments/         T-Drive smoke + oracle
datasets/tdrive/     原始数据
```

`testdata/fixture-v1/` 是 `build-fixture` 可重新生成的合成样例；单元测试在临时目录或内存中生成 fixture，对比和消融实验使用 `experiments/workloads/` 与 `tdrive_v1_ready`。
