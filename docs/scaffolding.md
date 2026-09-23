# 正确性脚手架（不可因「已跑通 HBase」而删除）

实验主路径是 **Live LLM → BoundIR → HBase**。下列组件**不是**过时模拟，而是 `spec/requirement.md` 明文要求的正确性基础设施；删除会导致验收失败或无法回归。

| 保留项 | 依据 | 作用 |
|--------|------|------|
| `Fixture` / `load-fixture-hbase` / `fixture_*` 表 | FR-1.6；§8.3 | §18 确定性金标准 `[A,B]`；与 T-Drive 并列验收 |
| `testdata/fixture-v1/` | T0.9 | `build-fixture` 确定性导出与 checksum |
| `testdata/llm-mock/` + `MockLlmClient` | FR-7.4；§8.4 | 无外网/无密钥时的 NL 回归；CI |
| `MemoryBackend` + `src/test/**` | FR-7.1/7.3；§8.8 | 无 HBase 单元/差分测试；Oracle 对齐 |
| `FullScanOracle` + smoke oracle 缓存 | FR-7.2；T2.9 | T-Drive / fixture 参考答案 |

## 实验操作面（已精简）

- 默认后端：**HBase**（`chat` / `query-nl` 不加 `--memory`）
- 默认 NLP：**真实 LLM**（`.env`）；`--mock` 仅作回归备选
- 已删除：仅 Memory 的过时 spotcheck 脚本（`spotcheck-fixture-verif.sh` 等）

## 明确不要删

- `src/test/`、`testdata/`、`MockLlmClient`、`MemoryBackend`、`FixtureBuilder`
- `spec/*.md`（实验设计本身）
