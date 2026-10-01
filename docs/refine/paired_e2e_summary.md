# paired_e2e_summary

> comparative_refine.md §8–§9 — frozen **development** pairs (not unknown test).

## Cache / resource

- Arm: `cbo` vs `cbo-llm-proposal` (uncached).
- LLM: local Ollama `qwen2.5:1.5b-instruct`, `max_tokens=32`, speculate k=1.
- Warm cache protocol for HBase; plan-decision cache off.

### T-Drive overhead 4q

| query | T_cbo | T_hyb | G | win | oracle_hyb |
|---|---:|---:|---:|:---:|:---:|
| a2_topk_dtw_wide | 263 | 1685 | -1422 | L | True |
| a2_tz_hard_a | 2872 | 4746 | -1874 | L | True |
| a2_tz_hard_d | 3471 | 4694 | -1223 | L | True |
| topk_st_3 | 141 | 1357 | -1216 | L | True |

- n=4 win/tie/loss=0/0/4
- mean G=-1433.8 ms; median G=-1322.5 ms
- total T_cbo=6747; total T_hyb=12482

### AIS opportunity set

| query | T_cbo | T_hyb | G | win | oracle_hyb |
|---|---:|---:|---:|:---:|:---:|
| ais_st_control_small | 35 | 647 | -612 | L | True |
| ais_st_control_tiny | 148 | 398 | -250 | L | True |
| ais_st_port_focus | 2823 | 4542 | -1719 | L | True |
| ais_topk_dtw_2week | 34567 | 58113 | -23546 | L | True |
| ais_topk_frechet_wide | 14804 | 17487 | -2683 | L | True |

- n=5 win/tie/loss=0/0/5
- mean G=-5762.0 ms; median G=-1719.0 ms
- total T_cbo=52377; total T_hyb=81187


## Acceptance vs §10.3

- These queries were repeatedly used for development → **not** a frozen unknown
  validation set. Results guide next optimization only.
- No claim of full-workload win over CBO.

