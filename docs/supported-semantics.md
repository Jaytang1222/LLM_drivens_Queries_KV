# Supported semantics

Version tag: `point_similarity_v2`（BoundIR `snapshot.semantics_version`；兼容读取旧标签 `point_dtw_v1`）。

## Supported

1. **Entity**: trajectories  
2. **Temporal**: `[start_ms, end_ms)` Asia/Shanghai → epoch ms；DraftIR 可用相对时间 `now` / `last_Nd` / `last_Nh`（及 `-Nd`/`-Nh`），Binder 需固定 `nowMs`（墙钟锚点）解析  
3. **Spatial**: closed AABB in UTM meters; NL lon/lat or `config/regions.yaml` name  
4. **Coupling**: `SAME_POINT` / `OBSERVED_POINT`  
5. **Attributes**: `vehicle_id` EQ  
6. **Results**: `TRAJECTORY_IDS` | `TOP_K`  
7. **Metrics**: `DTW` | `FRECHET`（离散）| `HAUSDORFF`（对称点集）；`FULL_TRAJECTORY`；空 / 缺失 metric → **拒绝**（不默认 DTW）

## Plan path

BeamSearch（legal START/INTERSECT/REPLACE/`CHOOSE_MERGE`/`PARTITION_UNION`/FINISH）→ `plan.schema.json` + CoverageCertificate → Final cost（`cost_v2_rs_sched`）→ HBase。  
- `CHOOSE_MERGE`：多索引时选 `HASH_SET` | `SORT_MERGE`  
- `PARTITION_UNION`：仅系统可证明分区（时间二分 / Z 四叉），LLM 不可发明几何  
NL：默认 `LlmProposalPolicy`（frontier `proposals[]`）；`query-ir`/smoke 默认 `RulePolicy`。  
显式 `--policy=rule|best_first|llm|llm_direct`：`best_first` = §19.1 #4；`llm_direct` = 一次输出完整 `PlanEnvelope`（或族内 `plan_id` 捷径），同 `PlanValidator`（§19.1 #5）。  
`query-ir --plan-only`：只规划+选择（E2 `t_plan`），不执行；trace 含 `t_plan_ms` / `t_exec_ms` / `plan_regret_ms`。  
runs 产物（NFR-3）：`candidates/<plan_id>/plan.json`（+ `physical.json`）与 **SAFE/REJECT** `validation_reports.json`。  
`max_llm_calls>0` 耗尽停搜（`LLM_BUDGET`）。
执行：`soft_memory_bytes` 按保留载荷字节累计，超限 → `RESOURCE_EXHAUSTED`（不截断结果；**落盘 spill 明确不做**，属后续）；`max_exec_ms` 墙钟超时同上。无 SafePlan → `NO_SAFE_PLAN`。

## Unsupported

COUNT / 聚合、连续路径、连续 Fréchet、Quadtree、CD-Taxi、未注册地名臆造。

## Indexes

`idx_time` / `idx_zorder` / `idx_hash` + `P_FULL`。覆盖硬约束；代价只排 SafePlan。

验证用例：[`easy_query_example.md`](easy_query_example.md)、[`hard_query_example.md`](hard_query_example.md)。
