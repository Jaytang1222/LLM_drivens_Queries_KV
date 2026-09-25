# KART 操作手册（How to run）

命令**只在 WSL** 执行。**Canonical 工作目录：** `/home/jaytang/projects/llm-kv`（`KART_DST`）。  
Windows 改代码后必须同步再跑实验：

```bash
./scripts/kart.sh sync         # Windows → WSL
./scripts/kart.sh sync-check   # stamp + 关键文件 hash
```

路径：**Live LLM → BoundIR → BeamSearch → HBase（T-Drive / `tdrive_v1_ready`）** 为生产/验收主路径。  
单元/性质测试仍可使用 `MemoryBackend` + fixture（不替代 HBase smoke）。对比实验 `bench-*.sh` 见 `spec/comparative_experiment.md`；运行前必须先读 [`comparative-experiment-readiness-2026-09-25.md`](comparative-experiment-readiness-2026-09-25.md)，其中列出了公平性门禁和当前已知限制。

## 主流程

| 步骤 | 命令 |
|------|------|
| 开 ZK + HBase | `./scripts/kart.sh up` |
| 缺快照时建库 | `./scripts/kart.sh run build-snapshot --data datasets/tdrive` |
| 健康检查 | `./scripts/kart.sh doctor` |
| 一键验收 | `./scripts/kart.sh check` |
| 交互查询 | `./scripts/kart.sh chat`（需 `.env`） |
| Oracle smoke | `./scripts/kart.sh smoke` → **24/24**（含 DTW / FRECHET / HAUSDORFF Top-K） |
| 收工 | `./scripts/kart.sh down` |

验证用例与参考结果：[`easy_query_example.md`](easy_query_example.md)、[`hard_query_example.md`](hard_query_example.md)、[`manual_verify_example.md`](manual_verify_example.md)。语义：[`supported-semantics.md`](supported-semantics.md)。区域名：[`regions.md`](regions.md)。环境钉死：[`environment-lock.md`](environment-lock.md)。

## Live LLM（`.env`，勿提交）

```bash
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-...
LLM_JSON_MODE=true
```

```bash
./scripts/kart.sh probe
./scripts/kart.sh chat
```

启动应见 `backend=hbase manifest=tdrive_v1_ready`。确认输入 `y`（`n` 可补充修正后重解析）。产物：`runs/<query_id>/`。

## 常用命令

```bash
./scripts/kart.sh help
./scripts/kart.sh smoke
./scripts/kart.sh chat-easy       # 对照 easy_query_example
./scripts/kart.sh chat-handbook   # easy + hard 手册批跑
./scripts/kart.sh rebuild
./scripts/kart.sh run query-ir --ir docs/hard_query_example/topk_st_2.bound_ir.json --manifest tdrive_v1_ready
# optional: --policy rule|best_first|llm|llm_direct ; --plan-only (E2, no execute)
./scripts/kart.sh run query-ir --ir docs/hard_query_example/topk_st_2.bound_ir.json --manifest tdrive_v1_ready --policy best_first --plan-only
./scripts/kart.sh run explain --ir docs/hard_query_example/topk_st_2.bound_ir.json --manifest tdrive_v1_ready --no-llm
./scripts/kart.sh run explain --ir docs/hard_query_example/topk_st_2.bound_ir.json --manifest tdrive_v1_ready --policy best_first
```

## 数据与正确性

- Manifest：`catalog/tdrive_v1_ready`（表 `traj_*_v1` / `idx_*_v1`）
- Smoke 真值：`experiments/workloads/tdrive_smoke.oracle.json`
- 困难用例旁证：`docs/hard_query_example/`（`evidence.json` + 冻结 BoundIR）
- 门禁：smoke **24/24** +（可选）`chat-easy` / `chat-handbook`

## Cost calibration

代价模型 `cost_v2_rs_sched`（§13.4 + ScheduleEstimate）。**§16 离线 Feedback**（权威 Java fitter；非热更新）：

```bash
# 一键：采集 pairs → fit-cost → 合并 planner.yaml → 归档可重放包
./scripts/kart.sh fit-cost-pack

# 需要时先生成 BoundIR 网格：
./scripts/kart.sh gen-cost-ir
# 或：./scripts/publish-cost-calib-pack.sh --gen-ir experiments/workloads/cost_calib
```

`config/planner.yaml` 中 `cost.calibrated: true` 表示已发布权威拟合系数。  
权威来源：`kart fit-cost` / `FeedbackCalibrator`；归档：`experiments/results/cost_calib_pack_*` + `cost_calibration_meta.json`。
