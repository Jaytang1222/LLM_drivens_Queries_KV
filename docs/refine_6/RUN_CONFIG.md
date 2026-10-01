# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build | 见 `identity.txt` |
| Prompt | `cbo_llm_action_v10` |
| Calibrator | `calib_loglin_v1` + overestimate p95（见 `feedback_calibration.md`） |
| Probe | `JointCandidateProbe` budget=50ms max_rows=200 seed=42 |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 24 |
| budget | 120000 ms |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal` |
| Protocol | v10 / always-call |
| CBO parallel KEEP | on |
| Speculative pre-LLM | **off**（action 模式） |
| Adopt | min_adopt=200；uncertainty=1128（全体残差 p95） |
| Validator | indexed；prepare=safety_only |
| Trials | 1；cache=warm |

## 数据集

| 包 | Suite | Manifest |
|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `ais_v1_ready` |

Before 对照：`docs/refine_5`（优化前）。
