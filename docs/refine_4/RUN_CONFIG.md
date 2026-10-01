# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build | 见 `identity.txt` |
| Prompt | `cbo_llm_short_v8` |
| Calibrator | `calib_loglin_v1` |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 16 |
| budget | 120000 ms |
| 预热 | 跑前 `/api/generate` 一次 |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal`（无计划决策缓存） |
| Protocol | short / always-call |
| Prepare | `safety_only` |
| Speculative | k=1；`min_spec_s=500` |
| Adopt gate | `min_adopt_s=200` |
| Validator | `indexed` |
| Trials | 1；cache=`warm`；Oracle 必过 |

## 数据集

| 包 | Suite | Workload | Manifest |
|---|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `bound_ir_cbo_llm_overhead_v1.json` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `bound_ir_ais_opportunity_v1.json` | `ais_v1_ready` |

Before 对照：`docs/refine_3`（第三轮同包）。
