# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build id | `refine3-20261001-180830` |
| Jar | hot-patch refine3（indexed PlanValidator + hint ablation） |
| Prompt | `cbo_llm_independent_v7` |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 96 |
| budget | 120000 ms |
| 预热 | 跑前 `/api/generate` 一次 |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal`（无计划决策缓存） |
| Prepare | `safety_only` |
| Speculative | k=1（isolation 单独跑 `SPECULATE=0`） |
| Validator | `indexed`（主）/ `legacy`（对照） |
| Hint | `on` / `off` / `shuffle` |
| Trials | 1；cache=`warm`；Oracle 必过 |

## 数据集

| 包 | Suite | Workload | Manifest |
|---|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `bound_ir_cbo_llm_overhead_v1.json` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `bound_ir_ais_opportunity_v1.json` | `ais_v1_ready` |

开发九条，非未知测试集。Before 对照：`experiments/refine_2_pull`。
