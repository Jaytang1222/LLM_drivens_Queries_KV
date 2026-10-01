# summary — comparative_refine_2

## 三项预期收获

1. **AIS 执行优势能否靠减少额外准备转为 E2E 净收益？**  
   **本轮否。** 分解表明额外准备主因是 **PlanValidator/safety**（随 `n_scan_tasks`），不是 Final features。跳过 features 后 Fréchet/DTW 仍负 G；且 v7 LLM 更慢，墙钟更差。

2. **取消强制 prefer 后 LLM 是否有真实决策增量？**  
   **未证明。** 9/9 合法 propose 且等于 FastCost `rank_hint`；0 次 keep_cbo；0 次与 hint 分歧。协议已可归因，但小模型在本提示下表现为复述 rank_hint，并付出更高延迟。

3. **可获益场景与成本边界？**  
   - 短查询：LLM 3–5s 级调用已远超可节约执行。  
   - 长相似度：需 `safety≪(T_cbo−C−E)` 且 LLM 重叠后仍有余量；当前 DTW safety 单独已达数十秒。  
   - 不宜在未压 safety / 未压 LLM 前扩全量。

## 正确性

E2E oracle 通过；安全路径未削弱。

## 归因一句话

观测进展来自 **计时真相（safety 主导）** 与 **LLM 协议可归因**；**不是** E2E 胜出，也不是 LLM 增量决策。

## 配置

见 `RUN_CONFIG.md`。下一步见 `NEXT_OPTIMIZATION.md`。
