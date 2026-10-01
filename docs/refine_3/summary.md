# summary — comparative_refine_3

## 核心结果（已测）

1. **验证器算法优化成功且公平（两臂共用）**  
   PlanValidator coverage：decode-once + prefix-max 区间索引。  
   DTW safety 30.5s→56ms；Fréchet 8.2s→23ms。Oracle 全通过。

2. **LLM 隔离**  
   与 legacy 验证并行会拉高 LLM 墙钟；indexed 后与 alone 接近。  
   稳态 LLM 仍约 4–7 s（1.5b + 当前提示）。

3. **新基线配对仍 0 胜**  
   公共加速同时缩短 CBO；LLM 成为不可覆盖下界。  
   DTW G：−34060 → −1439（巨大工程改善，仍未净胜）。

4. **hint 消融**  
   无稳定“更有价值”的独立决策证据；存在展示顺序敏感。

## 停止/转向

符合文档 §10：验证器已快，但 `L_new` 仍超过可用残余时间 → **先降推理成本，不扩全量**。

## 配置与表

- 配置：`RUN_CONFIG.md`
- plan/exec 对照：`timing_plan_exec.md`
- 六问：`answers_six.md`
