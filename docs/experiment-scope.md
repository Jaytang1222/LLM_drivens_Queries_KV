# Experiment scope lock

- Date: 2026-09-25
- Canonical WSL workspace: **`/home/jaytang/projects/llm-kv`**
- Windows source: `/mnt/f/Projects/LLM_KV` (`F:\Projects\LLM_KV`)
- Compat symlink only: `/home/jaytang/build/LLM_KV` → canonical DST（禁止当作第二份树）

## Manifest / tables

正式与半正式实验（smoke、chat 验收、代价校准、未来 E1–E3）**唯一**数据范围：

| 项 | 值 |
|---|---|
| Manifest | `tdrive_v1_ready`（环境变量 `KART_EXPERIMENT_MANIFEST`） |
| Catalog | `catalog/tdrive_v1_ready.manifest.json` + 对应 stats |
| 表前缀 | manifest 内声明的 `traj_*_v1` / `idx_*_v1` 等 |

集群上其它历史/项目表（`doctor` 可能列出数十张）**不在实验范围**，不得用于正确性或性能结论。

## HBase 版本限制（冻结披露）

- 客户端：`hbase-client:2.2.3`（pom）
- `Admin.getClusterStatus().getHBaseVersion()` 可能报告 **2.1.2**（`HBASE_HOME` classpath 含 TMan-spatial 捆绑旧类）
- 证据文件：`experiments/results/hbase_version_evidence.txt`（`scripts/capture-hbase-evidence.sh`）
- **正式性能数字**须在报告中写明：单节点、混合 classpath；未统一服务端 classpath 前不宣称与纯净 HBase 2.2.3 集群可比。

## 同步门禁

```bash
./scripts/kart.sh sync          # Windows → DST + stamp
./scripts/kart.sh sync-check    # stamp + 关键文件 hash
```

实验 run 前两者必须 OK，并在 meta 中记录 `git_commit` 与 `KART_SYNC_*` stamp。

## 明确不做（本阶段）

- 全量 `verify-snapshot`（耗时长；既有 READY + smoke/chat 证据另述）
- Quadtree / CD-Taxi / 多 RegionServer（研究范围另声明）
