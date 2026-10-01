# llm_decision_eval

> comparative_refine.md §6 — attribution of LLM vs FastCost prefer.

## Protocol

- Prompt: `cbo_llm_compact_v6`, whitelist FastCost-ranked, `prefer=<cheapest novel>`.
- Max 2 HTTP calls; soft retry toward prefer; fail assist `rule_prefer_fastcost_after_llm_fail`.
- Cache: main paired rows use uncached `cbo-llm-proposal`.

## Decision changes vs FastCost prefer

| query | prefer | proposal | selected | assist | llm_calls | adopted_novel |
|---|---|---|---|---|---:|:---:|
| a2_topk_dtw_wide | P_Z | P_Z | P_Z | None | 1 | True |
| a2_tz_hard_a | P_Z | P_Z | P_Z | None | 1 | True |
| a2_tz_hard_d | P_Z | P_Z | P_Z | None | 1 | True |
| topk_st_3 | P_T | P_T | P_T | None | 1 | True |

FastCost prefer == selected: **4/4** on T-Drive package rows.

### AIS rows

| query | prefer | selected | llm_lat | speculative_used |
|---|---|---|---:|:---:|
| ais_st_control_small | P_TZ | P_TZ | 630 | True |
| ais_st_control_tiny | P_T | P_T | 314 | True |
| ais_st_port_focus | P_Z | P_Z | 1437 | True |
| ais_topk_dtw_2week | P_T | P_T | 857 | True |
| ais_topk_frechet_wide | P_T | P_T | 1093 | True |

## Attribution

- When selected == prefer, LLM did **not** demonstrate value beyond the ranker
  (may still be required by LLM-on protocol).
- When selected ≠ prefer and improves S, credit LLM; when worsens S, debit LLM.
- Current package: LLM largely follows FastCost prefer / soft-retry → **no clear
  extra decision value** beyond the (misfit) ranker on these dev queries.

