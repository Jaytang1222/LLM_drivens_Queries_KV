# llm_independent_decision

> comparative_refine_2.md §7 — no forced prefer / no prefer-corrective retry.

## Protocol (v7)

- Prompt: `cbo_llm_independent_v7`
- Max HTTP calls: 1
- Actions: `keep_cbo` | `propose`
- On illegal/timeout: fallback CBO (no rule-assist)
- Speculative ranker (pre-LLM): FastCost — tagged `speculative_ranker=fastcost`

## Counts (after E2E hybrid rows)

- same as rank_hint: 9
- different legal propose: 0
- keep_cbo: 0
- fail/fallback: 0
- speculative_waste rows: 0

| query | action | reason | rank_hint | selected | cbo | calls | llm_ms | spec_used | waste | fallback | prompt |
|---|---|---|---|---|---|---|---|---|---|---|---|
| a2_tz_hard_d | propose |  | P_Z | P_Z | P_TZ | 1 | 5230 | True | False |  | cbo_llm_independent_v7 |
| a2_topk_dtw_wide | propose |  | P_Z | P_Z | P_TZ | 1 | 4842 | True | False |  | cbo_llm_independent_v7 |
| topk_st_3 | propose | lower_scan_cost | P_T | P_T | P_TZ | 1 | 5092 | True | False |  | cbo_llm_independent_v7 |
| a2_tz_hard_a | propose |  | P_Z | P_Z | P_TZ | 1 | 5749 | True | False |  | cbo_llm_independent_v7 |
| ais_topk_frechet_wide | propose |  | P_T | P_T | P_TZ | 1 | 9826 | True | False |  | cbo_llm_independent_v7 |
| ais_topk_dtw_2week | propose |  | P_T | P_T | P_TZ | 1 | 21346 | True | False |  | cbo_llm_independent_v7 |
| ais_st_port_focus | propose |  | P_Z | P_Z | P_TZ | 1 | 2912 | True | False |  | cbo_llm_independent_v7 |
| ais_st_control_tiny | propose | lower_scan_cost | P_T | P_T | P_TZ | 1 | 4108 | True | False |  | cbo_llm_independent_v7 |
| ais_st_control_small | propose | lower_scan_cost | P_TZ | P_TZ | P_T | 1 | 4029 | True | False |  | cbo_llm_independent_v7 |


## Incremental value

Compare LLM choice vs FastCost rank_hint on the same whitelist. Agreement does not
prove LLM value; disagreement needs positive paired G to count as incremental.
Rule-assist is disabled in v7, so assists cannot inflate LLM credit.

**This package:** 9/9 agree with rank_hint, 0 disagreements, 0 keep_cbo. Combined with
higher `llm_latency_ms` (often 3–21 s vs ~1 s in v6), there is **no evidence of
positive LLM incremental value** under the current local 1.5b + v7 prompt.
