# prepare_reuse_review

> comparative_refine_2.md §5 — reusable safe prepare path.

## Separation

| concept | implementation |
|---|---|
| compileAndValidate | `QueryEngine.runFixed(..., PrepareMode.SAFETY_ONLY)` |
| estimateForDecision | FastCost ranking (pre-LLM); Final cost card optional |
| executePrepared | `executeSelected` reuses `selected` SafePlanHandle |
| collectDiagnostics | Final extract deferred / Fast extract for trace estimates |

## Eliminated hybrid-only work

1. Speculative prepare no longer runs Final `extractFinal` + live per-scan Region
   locate before LLM returns (default `KART_CBO_LLM_PREPARE_MODE=safety_only`).
2. `executeSelected` reuses `selectedFeatures` when present; safety_only path uses
   `extractFast` for Coordinator estimate fields only (not a second Final locate pass).
3. `HBaseRegionMapping` caches locate results process-wide (public infra — CBO
   remeasured in the same after package).

## Measured effect

FULL breakdown shows features/region were already ≈0 relative to safety. Therefore
eliminating Final extract is correct engineering but **does not** unlock the AIS
DTW/Fréchet E2E budget; the remaining hybrid-only tax is still dominated by
`PlanValidator` over large `n_scan_tasks` (see `prepare_breakdown.md`).

## Fairness

- CBO search/select algorithm unchanged.
- Public locate cache benefits both arms; paired after CBO baseline is the one in
  `paired_before_after.jsonl` / after E2E files.
- Cross-query plan-decision cache remains **off** for main comparison
  (`cbo-llm-proposal` without cache).

## Version binding

Reuse binds to the same BoundIR / compiled SafePlanHandle within one arm run.
Cache arm still re-validates plan IDs; no answer cache.
