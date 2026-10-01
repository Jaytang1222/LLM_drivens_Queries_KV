# llm_short_decision

> comparative_refine_4.md §6 — `cbo_llm_short_v8`

协议：`{"choice":"KEEP"}` 或 `{ "choice": N }`（编号绑定请求内 whitelist）。

| 指标 | 值 |
|---|---|
| n | 9 |
| llm_latency med/min/max | 1217/688/2955 |
| tokens_in med | 126 |
| tokens_out med | 6 |
| max_tokens | 16 |

目标稳态 &lt;1s 仍为待验证；若 med 仍数秒，则当前硬件+模型下短协议未进入 AIS 预算。
