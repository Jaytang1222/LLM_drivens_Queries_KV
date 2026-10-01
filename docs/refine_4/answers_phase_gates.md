# 本轮问题（comparative_refine_4.md §11）

## execute 变慢来自计划本身、并行推理还是测量波动？

两者都有，见 `execution_isolation.md`：

- **计划本身**：`ais_st_control_small` 上 `P_TZ` alone≈70ms 已慢于 `P_T`≈22ms；refine_3 误采纳慢计划属于选择问题。
- **并行竞争显著**：AIS Top-K / port_focus 上 alone→par 常见 +15%–100%（如 Frechet `P_T` 734→1517，DTW `P_TZ` 2692→3396）。短查询波动较小。
- 本轮 hybrid 全部 `keep_cbo`，E2E 变慢主因是 **支付 LLM 墙钟**，不是误采纳慢计划。

## 哪些特征能预测范围减少与回表增加之间的净代价？

`calib_loglin_v1` 用执行前 FastCost 特征：`log1p(scan_ranges)`、`log1p(fetch_gets)`、`log1p(index_rows)`，目标 `S=E_cbo-E_p`。开发拟合 n=958、MAE≈1043ms，**非 holdout**。线上用于投机/采纳门槛（500/200ms）。

## 校准后是否减少慢计划采纳并保留 AIS 优势？

- 负收益误采纳：**0**（9/9 KEEP 或门控回退 CBO）。
- 正收益采纳：**0**；AIS Top-K 未再切到 `P_T`，第三轮可见的执行优势本轮未兑现。
- 结论：校准+短协议消除了慢计划伤害，但也 **系统性丢掉了 AIS 切换收益**（模型保守 KEEP）。

## 短决策是否在降低延迟时保留有用选择能力？

相对 v7（tokens_out≈20、latency 4–7s）：本轮 med tokens_out=**6**、latency med=**1217ms**（688–2955）。合法率高（全为 KEEP）。**有用选择能力不足**：9/9 KEEP，未提出异于 CBO 的编号；延迟仍常高于短查询可覆盖窗口，AIS 长查询也未进入稳定 &lt;1s 目标。

## 净收益主要由校准、LLM、调度还是公共执行优化贡献？

本轮未改公共执行器；indexed 验证器两臂共用。相对 CBO：E2E **0 胜 / 9 负**。损失几乎全部来自 **短协议 LLM 等待**；校准贡献表现为阻止误采纳，而非净胜。

## 新配对是否支持继续做未知查询验证？

**否。** 开发净胜未出现，且 LLM 仍为主要下界；先不扩未知集。下一步见 `NEXT_OPTIMIZATION.md`。
