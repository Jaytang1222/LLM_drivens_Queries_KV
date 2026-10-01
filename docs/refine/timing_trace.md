# timing_trace

> comparative_refine.md §4 — speculative wall split; await overlaps side-task work.

## Contract

- Serial: `G = (P_cbo+E_cbo) - (P_hybrid+E_selected) = S - H`.
- Speculative: `t_e2e` is wall clock; do not sum full plan+exec intervals.
- `t_spec_validate_ms` / `t_spec_exec_ms` are side-thread phases; `t_spec_await_ms` is
  main-thread wait after LLM (overlaps remaining side work).

### T-Drive E2E (this package)

| query | cbo_e2e | hyb_e2e | search | llm | await | spec_validate | spec_exec | prefer | selected | G |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|
| a2_topk_dtw_wide | 263 | 1685 | 31 | 1651 | 0 | 23 | 296 | P_Z | P_Z | -1422 |
| a2_tz_hard_a | 2872 | 4746 | 1362 | 3382 | 0 | 594 | 2553 | P_Z | P_Z | -1874 |
| a2_tz_hard_d | 3471 | 4694 | 1797 | 2122 | 772 | 593 | 2301 | P_Z | P_Z | -1223 |
| topk_st_3 | 141 | 1357 | 38 | 1316 | 0 | 1 | 84 | P_T | P_T | -1216 |

### AIS E2E (this package)

| query | cbo_e2e | hyb_e2e | search | llm | await | spec_validate | spec_exec | prefer | selected | G |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|
| ais_st_control_small | 35 | 647 | 8 | 630 | 0 | 352 | 141 | P_TZ | P_TZ | -612 |
| ais_st_control_tiny | 148 | 398 | 82 | 314 | 0 | 1 | 17 | P_T | P_T | -250 |
| ais_st_port_focus | 2823 | 4542 | 2550 | 1437 | 551 | 1746 | 242 | P_Z | P_Z | -1719 |
| ais_topk_dtw_2week | 34567 | 58113 | 32620 | 857 | 24630 | 24453 | 1034 | P_T | P_T | -23546 |
| ais_topk_frechet_wide | 14804 | 17487 | 12360 | 1093 | 4029 | 4579 | 543 | P_T | P_T | -2683 |


## Attribution notes

- If `t_spec_validate_ms ≫ t_spec_exec_ms`, long await is dominated by **fixed-plan
  validate/compile/cost**, not HBase scan.
- If `t_spec_exec_ms` ≈ await and llm small, wait is mostly **execution**.
- If `llm_latency_ms` ≈ hybrid wall − search and await≈0, exec finished during LLM.
- Resource contention: when speculative validate+exec overlaps LLM only (not CBO exec),
  HBase contention with the main arm is limited to the hybrid query itself.

