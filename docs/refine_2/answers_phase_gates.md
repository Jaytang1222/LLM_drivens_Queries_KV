# 阶段推进条件回答（comparative_refine_2.md §10.3）

1. **主要耗时已定位到可解释子阶段吗？**  
   **是。** FULL prepare 下 AIS DTW/Fréchet 的 prepare 几乎全部落在 `t_fixed_safety_ms`（PlanValidator），features/region 可忽略（见 `prepare_breakdown.md`）。这修正了“Region 定位吃掉 24s”的先验。

2. **准备路径是否真正减少工作，而非仅改计时边界？**  
   **部分是。** `safety_only` 确认去掉 Final extract（features=0）；`executeSelected` 不再二次 Final；locate 有进程缓存。但 **主成本 safety 仍在**，故 E2E 未因跳过 features 而净胜。

3. **计划安全和答案是否保持一致？**  
   PlanValidator 仍完整运行；本轮 E2E `ok_oracle=true`（见服务器 BENCH 行）。未删除安全检查。

4. **E2E 改善是否超过运行波动，并保留负结果？**  
   **本轮开发配对仍 0 胜。** T-Drive mean G_after≈−4346；AIS≈−10297。负结果全部保留。LLM 延迟上升使多数查询相对 v6 before **更差**——准备下降≠实验胜出。

5. **改善来自公共优化、候选扩展、排序校准还是 LLM？**  
   - 公共 locate 缓存：次要。  
   - Hybrid safety_only：去掉 features，未动 safety 主因。  
   - LLM v7：可独立归因（无强制 prefer / 无纠偏 / 无 rule-assist）；**9/9 选择=FastCost rank_hint**，未证明额外决策增量，且调用更慢。  
   - 校准：未上线（`calibration_validation.md` partial）。

## 开发焦点案例

- `ais_topk_frechet_wide`: G_before=-2683 → G_after=-6901；hyb 17487→21715；spec_val≈5.7s（safety）；llm≈9.8s；选 P_T（与 hint 一致）。
- `ais_topk_dtw_2week`: G_before=-23546 → G_after=-34060；hyb 58113→67858；spec_val≈35.8s（safety）；llm≈21.3s；选 P_T。

任意净胜？ **no**（开发配对，非全量）。
