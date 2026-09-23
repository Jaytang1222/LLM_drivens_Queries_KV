# /home/jaytang/projects — WSL 工程入口

两套项目刻意分开：共享运行时在 `~/envs`，工程代码在这里。

| 目录 | 类型 | Windows 对应 | 用途 |
|------|------|--------------|------|
| `tman-spatial/` | drvfs 挂载（NTFS） | `F:\Projects\TMan-spatial` | TMan-spatial 源码；与 Windows 同一棵树 |
| `llm-kv/` | **ext4 本地副本** | 同步自 `F:\Projects\LLM_KV` | KART / LLM_KV 的 WSL 工作区（Maven 必须跑在 Linux 文件系统） |

## 为什么 LLM_KV 不做成和 TMan 一样的挂载？

Maven + Windows `localRepository` 路径在 NTFS/`/mnt/f` 上会坏掉。因此：

- **编辑 / Git**：继续用 `F:\Projects\LLM_KV`（或 `/mnt/f/Projects/LLM_KV`）
- **WSL 编译与跑脚本**：用 `/home/jaytang/projects/llm-kv`
- 兼容路径：`/home/jaytang/build/LLM_KV` → 本目录的符号链接（旧脚本无需改）

## 共享环境（不要放进项目目录）

| 路径 | 说明 |
|------|------|
| `~/envs` | HBase / ZK / Spark / Redis（fstab → `F:\envs`） |
| `~/hbase` `~/zookeeper` `~/spark` `~/redis` | 指向 `~/envs/...` 的符号链接 |
| `~/data/...` | TDrive 等数据（fstab → `F:\Data\...`） |
| `~/.m2/repository` | WSL Maven 本地仓（`-Dmaven.repo.local`） |

## 日常同步（Windows → WSL 工作区）

```bash
bash /mnt/f/Projects/LLM_KV/scripts/sync-wsl-workspace.sh
```

`rebuild-jar.sh` / `kart.sh rebuild` 也会自动 rsync 后再编译。

## VS Code 打开哪个？

- 看/改 KART：打开 `/home/jaytang/projects/llm-kv`（或 Windows 侧 `F:\Projects\LLM_KV`）
- 看/改 TMan：打开 `/home/jaytang/projects/tman-spatial`
- 不要混开；两个窗口分别对应两个环境
