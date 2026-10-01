# docs/refine — comparative_refine 交付包

依据 [`docs/comparative_refine.md`](../comparative_refine.md) 建议产物与 §11 六问。

| 文件 | 对应阶段 |
|---|---|
| `opportunity_upper_bound.md` | §3 阶段一 |
| `timing_trace.md` | §4 阶段二 |
| `benefit_calibration.md` | §5 阶段三 |
| `llm_decision_eval.md` | §6 |
| `paired_e2e_summary.md` | §8–§9 / 阶段四配对 E2E |
| `answers_six_questions.md` | §11 |
| `NEXT_OPTIMIZATION.md` | 依据结果的下一步 |
| `identity.txt` / `paths.txt` | 构建与路径 |
| `td_e2e.jsonl` / `ais_e2e.jsonl` | 原始配对行 |
| `td_ub_corrected.md` / `ais_ub_corrected.md` | 本轮上界 corrected 摘要 |

## 本包跑次

- E2E：`cbo-llm-overhead-e2e-local-ollama-20261001-155001`，`ais-llm-overhead-e2e-local-ollama-20261001-155129`
- 上界：`opportunity-refine-tdrive4q-20261001-160034`，`opportunity-refine-ais-20261001-160034`
- 臂：`cbo` vs `cbo-llm-proposal`（无计划决策缓存）；Ollama `qwen2.5:1.5b-instruct`；speculate k=1

## 边界

开发诊断，非全量正式主表；不弱化 CBO；不写入 query→赢家映射；失败/负收益保留在分母。

重新生成：

```bash
python experiments/adapters/build_refine_deliverables.py \
  --out-dir docs/refine \
  --identity experiments/refine_pull/refine-20261001-155001-identity.txt \
  --td-e2e experiments/refine_pull/td_e2e.jsonl \
  --ais-e2e experiments/refine_pull/ais_e2e.jsonl \
  --td-ub-summary experiments/refine_pull/td_ub_corrected.md \
  --ais-ub-summary experiments/refine_pull/ais_ub_corrected.md
```
