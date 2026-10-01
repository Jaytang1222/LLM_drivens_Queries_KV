# docs/refine_2 — comparative_refine_2 交付包

依据 [`docs/comparative_refine_2.md`](../comparative_refine_2.md)。

| 文件 | 对应 |
|---|---|
| `prepare_breakdown.md/jsonl` | §4 阶段 A |
| `prepare_reuse_review.md` | §5 阶段 B |
| `paired_before_after.md/jsonl` | 优化前后配对 |
| `llm_independent_decision.md` | §7 阶段 C |
| `calibration_validation.md` | §8 阶段 D（partial） |
| `answers_phase_gates.md` | §10.3 五问 |
| `summary.md` | §12 总结 |
| `NEXT_OPTIMIZATION.md` | 下一步 |
| `RUN_CONFIG.md` | 运行配置核查 |
| `td_e2e.jsonl` / `ais_e2e.jsonl` | after（safety_only） |
| `prepare_full_ais.jsonl` | FULL prepare 分解 |

## 本包跑次

- Stamp：`refine2-20261001-171119`
- Before：`docs/refine`（v6，20261001-155001）
- Arms：`cbo` vs `cbo-llm-proposal`；Ollama `qwen2.5:1.5b-instruct`；speculate k=1

## 核心结论（一览）

- Prepare 主因 = **PlanValidator safety**（非 features/region）。
- v7 LLM 可归因，但选择≡FastCost hint，且更慢 → **无决策增量、无 E2E 净胜**。
- 开发集 0 胜；负结果保留。

重新生成：

```bash
python experiments/adapters/build_refine2_deliverables.py \
  --out-dir docs/refine_2 \
  --identity experiments/refine_2_pull/identity.txt \
  --paths experiments/refine_2_pull/paths.txt \
  --td-after experiments/refine_2_pull/td_e2e.jsonl \
  --ais-after experiments/refine_2_pull/ais_e2e.jsonl \
  --prepare-full experiments/refine_2_pull/prepare_full_ais.jsonl
```
