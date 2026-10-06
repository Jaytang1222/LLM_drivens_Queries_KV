# Comparative experiments

KART 是 HBase 上基于 LLM 的轨迹查询整系统（NL→IR、CBO+dual 优化、校验与执行输出）。下列 `--arm kart` 是该系统在 E1 / E2 / E3 的入口，不是单独的优化器产品。

```bash
./scripts/kart.sh up && ./scripts/kart.sh rebuild

# E1 — nl_ir_v2.json
./scripts/bench-parse.sh --run-id r1 \
  --arm kart,direct-draftir,din-sql-spider,din-sql-bird,sag-mql-nofeedback

# E2/E3 — bound_ir_cmp_v3.json
./scripts/bench-plan.sh --run-id r1 --arm bao,llmopt,kart,cbo
./scripts/bench-e2e.sh  --run-id r1 --arm fullscan,rbo,cbo,kart --cache cold --trials 1

./scripts/bench-ablation.sh --run-id abl1 --cache warm --trials 5
```

Workloads kept for formal runs:
- `nl_ir_v2.json` — E1
- `bound_ir_cmp_v3.json` (+ oracle) — E2/E3 与消融主表
- `bound_ir_v1.json` (+ oracle) — oracle-diff-65 / 回归
- `tdrive_smoke.json` (+ oracle) — smoke
- `bound_ir_holdout_v2_*` — freeze-measure / holdout stress

Adapters kept are runtime bridges only (`din`/`sag`/`direct`/`translate`/`bao`/`llmopt` + `llm_client.py`).

Gate: `bash scripts/check-comparative-framework.sh`.
Transplants: `adapters/TRANSPLANT.md`.
