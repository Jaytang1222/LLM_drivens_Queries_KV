# paired_before_after

> comparative_refine_2.md — before = docs/refine E2E (v6); after = refine_2 E2E (v7 + safety_only).

## T-Drive

n=4 wins=0 losses=4 ties=0; mean G_before=-1434 mean G_after=-4346

| query | cbo_b | hyb_b | G_b | cbo_a | hyb_a | G_a | sel | action | prep_mode | prep_ms | spec_val |
|---|---|---|---|---|---|---|---|---|---|---|---|
| a2_topk_dtw_wide | 263 | 1685 | -1422 | 463 | 4918 | -4455 | P_Z | propose | safety_only | 33 | 34 |
| a2_tz_hard_a | 2872 | 4746 | -1874 | 2713 | 7239 | -4526 | P_Z | propose | safety_only | 599 | 599 |
| a2_tz_hard_d | 3471 | 4694 | -1223 | 3445 | 6920 | -3475 | P_Z | propose | safety_only | 568 | 569 |
| topk_st_3 | 141 | 1357 | -1216 | 228 | 5158 | -4930 | P_T | propose | safety_only | 1 | 2 |


## AIS

n=5 wins=0 losses=5 ties=0; mean G_before=-5762 mean G_after=-10297

| query | cbo_b | hyb_b | G_b | cbo_a | hyb_a | G_a | sel | action | prep_mode | prep_ms | spec_val |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ais_st_control_small | 35 | 647 | -612 | 30 | 4043 | -4013 | P_TZ | propose | safety_only | 292 | 293 |
| ais_st_control_tiny | 148 | 398 | -250 | 227 | 4189 | -3962 | P_T | propose | safety_only | 1 | 2 |
| ais_st_port_focus | 2823 | 4542 | -1719 | 2792 | 5342 | -2550 | P_Z | propose | safety_only | 2233 | 2233 |
| ais_topk_dtw_2week | 34567 | 58113 | -23546 | 33798 | 67858 | -34060 | P_T | propose | safety_only | 35796 | 35796 |
| ais_topk_frechet_wide | 14804 | 17487 | -2683 | 14814 | 21715 | -6901 | P_T | propose | safety_only | 5664 | 5665 |


Raw lines: `paired_before_after.jsonl`.
