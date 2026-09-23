# Supported semantics (MVP)

Version tag: `point_dtw_v1` (also recorded on BoundIR `snapshot.semantics_version`).

## What is supported

1. **Entity**: trajectories (not raw GPS streams as query results).
2. **Temporal**: half-open interval `[start_ms, end_ms)` in epoch milliseconds (Asia/Shanghai wall clock when bound from NL).
3. **Spatial**: axis-aligned rectangle in projected meters (UTM after binder), relation `INTERSECTS`, boundary `INCLUDED`.
4. **Coupling**: `SAME_POINT` — a trajectory matches ST predicates if **some observed sample point** falls in both the time interval and the rectangle.
5. **Attributes**: optional `vehicle_id` equality predicate.
6. **Result modes**:
   - `TRAJECTORY_IDS` — distinct external trajectory ids after exact ST filter.
   - `TOP_K` — DTW distance to a reference trajectory (`similarity.reference_tid`), optional `exclude_reference`, tie-break `TID_ASC`.

## Exact filter

A chunk contributes a match when a decoded point `p` satisfies:

- `start_ms ≤ p.t < end_ms`
- `min_x ≤ p.x ≤ max_x` and `min_y ≤ p.y ≤ max_y`
- vehicle predicate if present (trajectory-level)

## DTW

Classic DTW with Euclidean local distance on `(x,y)`, full trajectory scope for MVP. Cell count is traced as `dtw_cells`; exceeding `max_dtw_cells` → `RESOURCE_EXHAUSTED`.

## Explicitly unsupported (MVP)

- `COUNT` / aggregations → `UNSUPPORTED_QUERY`
- Continuous path / segment geometry semantics
- Geocoding of free-text place names (only `config/regions.yaml` names or lon/lat boxes)
- Mutating indexes / online recalibration of cost coefficients (`calibrated: false` until offline fit)

## Indexes used for access

- Time buckets (`idx_time`)
- Z-order cells (`idx_zorder`)
- Vehicle hash (`idx_hash`)
- Full chunk scan fallback (`P_FULL`)

Coverage is a hard constraint; cost only ranks **safe** plans.
