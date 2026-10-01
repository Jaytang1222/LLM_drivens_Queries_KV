# validator_equivalence

> comparative_refine_3.md §6.4 — 等价与边界（已实施单元测试 + E2E Oracle）。

## 单元测试（本地/CI）

`RangeCoverageIndexTest`：

- 嵌套区间：较早长区间覆盖 probe（禁止“只看 start 最大区间”的错误实现）。
- `[start,stop)` 排他边界。
- 固定网格 probe：indexed 与 legacy 点覆盖一致。

## E2E

AIS/T-Drive 开发套件 `require_oracle=true`；本包跑次 Oracle 失败数见各 `*.log` 的 `BENCH_SUMMARY`。

## 未解决差异

无已知语义差异登记。若后续发现旧检查遗漏完整区间义务，将单独开修复，不混入性能优化。
