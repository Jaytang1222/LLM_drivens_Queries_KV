# 本轮六问（comparative_refine_3.md §12）

跑次：`refine3-20261001-180830`。开发集；Oracle fail=0。

1. **safety 中多少成本来自重复扫描、解码和证书构造？**  
   **已测。** Legacy 下 coverage/probe ≈ 全部 safety：  
   - DTW：safety 30,533 ms，`range_decode_count`≈65M，`range_compare_count`≈65M（M=N=8064）。  
   - Fréchet：safety 8,227 ms，decode/compare ≈11.9M。  
   Indexed 后 decode≈2×N（coverage+physical 各一次），compare≈M log N 量级（DTW 112k，Fréchet 44k）；safety 降到 **56 / 23 ms**。  
   → 主因是 **O(M×N) 覆盖查找 + 反复 hex 解码**，不是证书字符串本身。

2. **保持安全语义后，首次验证可降低到什么水平？**  
   **已测。** 同输入：  
   | query | safety legacy | safety indexed | 约降 |
   |---|---:|---:|---:|
   | ais_topk_dtw_2week | 30533 | 56 | ~500× |
   | ais_topk_frechet_wide | 8227 | 23 | ~350× |
   | ais_st_port_focus | 2285 | 4 | ~500× |

3. **LLM 慢是否与验证器并行争用有关？**  
   **已测：与 legacy 并行时有关；indexed 后基本缓解。**  
   - DTW：alone 4572 / ∥legacy 9598 / ∥indexed 4070 ms  
   - Fréchet：alone 5075 / ∥legacy 11298 / ∥indexed 6828 ms  
   GPU/服务端排队指标：兼容接口 **不可用**（未冒充精确排队）。

4. **公共验证器优化后，新的 CBO 还留有多少收益窗口？**  
   **已测（小样本）。** CBO 同步变快（公共验证器）：  
   - DTW：`T_cbo`≈2867 ms；hybrid e2e≈4306（≈CBO-search + max(L≈4.0s, V+E)）→ **窗口为负**（约 −1.4 s）。  
   - Fréchet：`T_cbo`≈4133 vs hybrid≈7049 → **约 −2.9 s**。  
   不能再用“旧 CBO 慢验证”当隐藏 LLM 的窗口。

5. **不依赖 rank_hint，LLM 能否作出更有价值的选择？**  
   **已测：未见稳定增量。**  
   - hint off：多数与 on 相同；`ais_st_port_focus` 改为 P_T。  
   - shuffle：多条改为另一 whitelist id（位置偏好），**未证明执行收益为正**。  
   仍不能宣称 LLM 相对 FastCost 有净价值。

6. **净收益是否真实、可复现，代价与有效场景是什么？**  
   开发配对：**0 胜 / 9 负**，mean G_after≈−2708。  
   - 工程进展真实：DTW hybrid e2e 67.8s→4.3s，但仍输给新 CBO。  
   - 剩余下界主要是 **LLM 调用（约 4–7 s）** 相对短执行节省。  
   - **暂停扩全量**；下一步应压 LLM 延迟或门控跳过短查询，而非再猜验证器。
