# 本轮问题（comparative_refine_5.md §11）

## 1. 9/9 KEEP 是否由任务语义不完整造成，还是模型/信息限制？

**两者都有，且曾被提示字面量放大。**

- 初版 v9 用 `{"choice":N}` 作示例，1.5B 模型字面输出 `{"choice":"N"}` → 合成 format_ok=0、E2E 大量 `non_json`（协议歧义/示例错误）。
- 修正为显式 `{"choice":0}` 后：合成 **format_ok 8/8**，清晰正例召回 **2/4**；但清晰反例/不确定 KEEP 仅 **0/4**，顺序一致性 **0/3**。
- 库内九条最终均为 CBO（`llm_action=keep_cbo` 或门控回退）；AIS 上常见 `gate_conservative_s`（模型曾提议，保守 `S_hat - u` 拒绝）。  
结论：语义修正恢复了**合法格式与部分正例理解**；稳定拒绝反例与可靠采纳正收益仍受 **小模型能力/偏好 + 高不确定性门控** 限制，不能单归因于“未解释字段”。

## 2. 校准对收益差值的误差多大，门槛依据是什么？

见 `saving_residual_validation.md`：405 对残差 **MAE≈1331 ms**（med≈-988）。  
门槛维持 `min_spec=500` / `min_adopt=200`，并设 `saving_uncertainty_ms=1000`（≈MAE 量级），采纳要求 `S_hat - u >= min_adopt`。特征缺失计数为 0（普查实测字段）。九条不得当 holdout。

## 3. 并行实验中 LLM 实际运行多久、与执行重叠多少？

见 `isolation_timing_audit.md`（已修正）：

- `llm_call_ms`：worker 内完整请求墙钟（par 下常见约 0.7–5 s）。
- `llm_await_after_exec_ms`：执行结束后的剩余等待。
- 长查询（DTW/Fréchet）常见 `await_after_exec_med=0` 且 `llm_call≈exec` 量级 → **请求与执行大部分重叠**；短查询则 await≈call，重叠少。  
第四轮 `llm_latency_ms=0` 不能解释为“无调用成本”。

## 4. 是否恢复有价值的候选采纳，同时保持反例拒绝能力？

- 库内：**正收益采纳 0 / 负收益误采纳 0**；机会未恢复为净胜切换。
- 合成：正例部分恢复（2/4），**反例拒绝失败（0/4）** → 不能声称已具备可靠反例拒绝。
- AIS Fréchet/DTW 的 `S_hat` 仍约 1.3–1.9 s，但在 u=1000 的保守门控下难以稳妥采纳。

## 5. KEEP 并行能否减少串行损失，是否引入竞争或浪费？

- `cbo_parallel_used=true`：`ais_st_control_small`、`a2_topk_dtw_wide`（无可信投机机会时）。短查询 E2E 仍被 LLM 下界压住（如 control_small CBO e2e 31 vs hybrid 281）。
- 隔离：多数 AIS 查询 alone→par **exec 变慢**（竞争）；投机路径在 KEEP/门控回退时记浪费。
- KEEP∥CBO **可隐藏部分串行等待**，但不能保证相对纯 CBO 净胜。

## 6. 真实 E2E 是否净胜，收益究竟来自哪里？

**否：0 胜 / 9 负**（同跑 CBO，Oracle 全过）。  
本轮未改公共执行器。可归因进展：决策契约澄清、`choice:N` 缺陷修复、残差/不确定性口径、隔离计时修正、KEEP∥CBO 调度。  
**未获得 E2E 净收益**；不能把“发生调用/门控回退”算作 LLM 增量价值。
