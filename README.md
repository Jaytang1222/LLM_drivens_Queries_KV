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
| `docs/supported-semantics.md` | 语义与不支持项 |
| `docs/regions.md` | 预注册区域 |
| `docs/environment-lock.md` | JDK / Maven / HBase / LLM |
| `docs/experiment-scope.md` | 实验范围与 HBase 限制 |
| `USER_ACTIONS.md` | 人工项（密钥等） |

## Layout

```
scripts/kart.sh      统一入口（WSL）
docs/                操作与验证
spec/                需求/设计/PDF
src/main/java/kart/  IR / search / cost / exec
config/              layout, planner, regions, hbase/
catalog/             READY manifests（gitignore）
experiments/         T-Drive smoke + oracle
datasets/tdrive/     原始数据
```
