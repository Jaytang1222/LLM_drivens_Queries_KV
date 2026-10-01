# benefit_calibration

> comparative_refine.md §5 — relative benefit vs CBO; development only.

## Labels

`S(q,p) = E_cbo(q) - E_p(q)` from paired opportunity repeats / same-run E2E.

## Online features used today

| feature | kind | used in v6 |
|---|---|---|
| FastCost estimated_ms on novel envelopes | statistical estimate | yes (prefer/rank) |
| CBO selectedCost.estimated_ms | statistical estimate | yes (cost-beat trigger) |
| TOP_K / similarity / wide ST flags | query-visible | yes (uncertainty trigger) |
| Measured t_exec / scan rows | post-hoc label | **not** used online |

## Observed ranking error (v6 paired E2E)

| query | prefer | selected | S=E_cbo-E_hyb | regret_vs_P_T_census |
|---|---|---|---:|---|
| a2_topk_dtw_wide | P_Z | P_Z | -91 | prefer≠census_best |
| a2_tz_hard_a | P_Z | P_Z | -1039 | prefer≠census_best |
| a2_tz_hard_d | P_Z | P_Z | -620 | prefer≠census_best |
| topk_st_3 | P_T | P_T | 24 | aligned_or_unknown |

## Training / grouping status

- **Not yet trained**: no holdout-isolated tree/regression model in this package.
- Calibration conclusion from evidence: absolute FastCost ranks frequently prefer
  `P_Z` while measured opportunity winners are `P_T` → **high selection regret**.
- Recommended next fit: predict `S(q,p)` (or rank margin vs CBO) with features above,
  grouped by template / temporal span / spatial area; report MAE of S and top-1 regret
  on a held-out template group before deploying thresholds.

## Gate proposal (not yet enforced as formal metric)

Adopt / speculate only if `predicted_S > κ · predicted_critical_path_overhead`
(with κ≥1). Otherwise keep CBO execution; LLM may still be requested under LLM-on.

