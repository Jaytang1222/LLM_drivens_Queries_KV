# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build id | `refine2-20261001-171119` |
| Jar | `target/kart.jar`（hot-patch refine2，约 48MB，mtime 2026-10-01 16:42） |
| Prompt | `cbo_llm_independent_v7` |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama（OpenAI-compatible） |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| `max_tokens` | 96 |
| LLM wall budget | 120000 ms |
| 预热 | 跑前 `ollama /api/generate` 一次（冷启动与稳态区分） |

## 臂与协议

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal`（**无**计划决策缓存） |
| Speculative | on，`KART_CBO_LLM_SPECULATE_K=1`，ranker=`fastcost` |
| Prepare（E2E） | `safety_only` |
| Prepare（breakdown） | `full`（仅 AIS 分解跑次） |
| Trials | 1；cache=`warm` |
| Oracle | require_oracle=true |

## 数据集 / Suite

| 包 | Suite | Workload | Manifest |
|---|---|---|---|
| T-Drive 开发 4q | `experiments/suites/e2e-cbo-llm-overhead-v1.yaml` | `bound_ir_cbo_llm_overhead_v1.json` | `tdrive_v1_ready` |
| AIS 开发 5q | `experiments/suites/e2e-ais-llm-overhead-v1.yaml` | `bound_ir_ais_opportunity_v1.json` | `ais_v1_ready` |

开发九条（T-Drive 4 + AIS 5）**不是**未知测试集；仅作机制验证。

## 路径

```
PREPARE_FULL=refine2-prepare-full-20261001-171119
TD_E2E=refine2-td-e2e-20261001-171119
AIS_E2E=refine2-ais-e2e-20261001-171119
OUT_ROOT=experiments/refine_2/refine2-20261001-171119
```

Before 对照：`docs/refine` / `experiments/refine_pull/*`（v6 compact，20261001-155001）。
