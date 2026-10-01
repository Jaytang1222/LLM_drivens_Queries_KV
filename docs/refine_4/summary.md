# summary — comparative_refine_4

## 已完成

| 阶段 | 状态 | 产物 |
|---|---|---|
| A 同计划独立/并行 | 已测 | `execution_isolation.md/jsonl` |
| B 相对收益校准 | 已接入 | `calibration_*.md`，`calib_loglin_v1` |
| C 短决策协议 | 已测 | `llm_short_decision.md/jsonl`（`cbo_llm_short_v8`） |
| 门控+配对 E2E | 已测 | `paired_e2e.jsonl`，`timing_plan_exec.md` |

## 主结果（相对同跑 CBO）

- E2E：**0 胜 / 9 负**；Oracle 全过。
- 九条 hybrid 全部 `keep_cbo`（短协议）；负收益误采纳 0，AIS 正收益采纳亦 0。
- LLM：tokens_out med=6，latency med≈1.2s（相对 refine_3 的 4–7s 明显下降，仍覆盖不了支付成本）。
- Isolation：多条 AIS 查询 alone→par 执行变慢，存在并行竞争；部分慢执行可由计划本身解释。

## 构建

`refine4-20261001-190054` @ `startserver02` / `/home/tyq`；Ollama `qwen2.5:1.5b-instruct`。详见 `RUN_CONFIG.md`、`identity.txt`。
