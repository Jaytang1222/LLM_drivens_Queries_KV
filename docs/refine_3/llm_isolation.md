# llm_isolation

> comparative_refine_3.md §7 — 同模型/同提示下的隔离测量（已测）。

配置：

1. **LLM alone**：`KART_CBO_LLM_SPECULATE=0`（无投机验证并行）
2. **LLM ∥ legacy validator**：speculate=1 + `KART_VALIDATOR_COVERAGE=legacy`
3. **LLM ∥ indexed validator**：speculate=1 + indexed

| query | llm_alone | llm_∥legacy | llm_∥indexed | tok_in | tok_out | spec_used_idx | safety_idx |
|---|---|---|---|---|---|---|---|
| ais_st_control_small | 4197 | 5096 | 4268 | 171 | 21 | True | 1 |
| ais_st_control_tiny | 4004 | 4182 | 3940 | 169 | 20 | True | 0 |
| ais_st_port_focus | 4982 | 5804 | 4939 | 172 | 20 | True | 4 |
| ais_topk_dtw_2week | 4572 | 9598 | 4070 | 181 | 20 | True | 56 |
| ais_topk_frechet_wide | 5075 | 11298 | 6828 | 181 | 20 | True | 23 |


## 判断（实测）

- **∥legacy ≫ alone**（DTW 9.6s vs 4.6s；Fréchet 11.3s vs 5.1s）→ 旧验证器并行争用属实。
- **∥indexed ≈ alone**（DTW 4.1 vs 4.6；Fréchet 6.8 vs 5.1）→ indexed 基本消除该争用。
- 单独也仍有 ~4–7s → 推理/提示本身仍是下界。
- 服务端排队/GPU 细分指标：**不可用**（仅客户端 `llm_latency_ms` + token 计数）。
