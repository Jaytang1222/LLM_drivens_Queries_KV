# KART (LLM_KV)

键值存储（**HBase**）上的轨迹查询整系统：NL → typed IR → 查询优化（CBO + dual rewrite / 学习臂）→ 覆盖校验 → 执行。

- **论文结果表：** `experiments/results/experiment_design.xlsx`
- **规格：** `docs/spec/{requirement,design}.md` 与 `docs/spec/*_experiment.md`
- **环境锁细节（实验室 IP/端口）：** `docs/environment-lock.md`

---

## 1. 仓库布局

```
config/                 成本补丁、planner、regions、HBase 客户端配置
datasets/
  tdrive/               T-Drive 原始分片（可重建 snapshot）
  tdrive-query/         正式冻结查询包（E1/complex/simple/workload/scalability）
  porto-query/          同上（Porto）；原始 CSV 默认不进 git（过大）
docs/                   环境锁、区域、语义、实验协议
experiments/
  adapters/             第三方桥（bao/paramtree/din/sag/…）+ clone 脚本
  suites/               dual_action_rules*.json（改写规则）
  workloads/scalability/  Scalability 可跑包 *_v2.json + formal_env_v2.sh
  results/              仅保留 experiment_design.xlsx（跑数产物本地生成）
scripts/                kart.sh + 正式实验入口 *.sh
src/                    Java 源码；正式 suite 目录在
                        src/main/resources/experiments/suites.yaml
```

正式 suite **只认** `src/main/resources/experiments/suites.yaml` 里的 id。

---

## 2. 软件与版本（锁定）

客户端与脚本按下列版本验证；换大版本需自行回归。

| 组件 | 版本 | 说明 |
|------|------|------|
| OS | Ubuntu 20.04/22.04（**原生 Linux** 或 **WSL2**） | 正式跑数用 bash；勿在纯 Windows CMD 跑 `*.sh` |
| JDK | **OpenJDK 8**（`1.8.0`） | `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64` |
| Maven | **3.6.3+**（锁 3.6.3） | 必须配合 Linux 本地仓 `$HOME/.m2/repository` |
| HBase **client**（pom） | **2.2.3** | `org.apache.hbase:hbase-client` |
| HBase **server** | **2.2.3** | 与 client 对齐；安装树见下 |
| ZooKeeper | **3.4.10** | 与 HBase 同装；客户端连 `2181` |
| Hadoop（HDFS） | **2.8.5** | HBase 依赖的 HDFS |
| Python | **3.8+** | 第三方 bridge / 汇总脚本（bao、paramtree、summarize…） |
| LLM | OpenAI 兼容 HTTP API | DeepSeek / Gemini 等，见 `.env` |

实验室正式集群安装树示例（见 `docs/environment-lock.md`）：

```text
/data1/hbase/tyq/envs/
  hadoop-2.8.5/
  hbase-2.2.3/
  zookeeper-3.4.10/
```

HDFS / HBase 根（实验室）：

- HDFS：`hdfs://<namenode>:9000`
- HBase root：`hdfs://<namenode>:9000/hbase-tyq`
- ZK znode：`/hbase-tyq`（**必须**与 `hbase-site.xml` 里 `zookeeper.znode.parent` 一致）

> 若你使用自己的单机/小集群：版本尽量保持 **HBase 2.2.3 + ZK 3.4.x + Hadoop 2.8.x**，并改客户端 `hbase-site.xml` 指向你的 ZK。

---

## 3. 操作系统：WSL2 或原生 Linux

### 3.1 推荐拓扑

| 角色 | 建议 |
|------|------|
| 编辑代码 | Windows（`F:/Projects/LLM_KV`）或直接在 Linux 编辑 |
| 编译 / 跑实验 | **WSL2 Ubuntu** 或校园 Linux 服务器 |
| HBase / ZK / HDFS | 独立集群节点（可本机 pseudo-distributed，正式表用 4RS） |

### 3.2 WSL2（Ubuntu）最小安装

在 **PowerShell（管理员）** 启用 WSL2 后，安装 Ubuntu，然后在 WSL 内：

```bash
sudo apt update
sudo apt install -y openjdk-8-jdk maven git curl python3 python3-pip python3-venv
java -version    # 应看到 1.8.x
mvn -version     # 3.6+
```

设置 Java（写入 `~/.bashrc`）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

克隆或同步仓库到 **Linux 文件系统**（推荐 `~/projects/llm-kv`，避免在 `/mnt/f/...` 上直接猛跑 Maven/HBase 客户端）：

```bash
# 方式 A：直接 clone
git clone git@github.com:Jaytang1222/LLM_drivens_Queries_KV.git ~/projects/llm-kv
cd ~/projects/llm-kv

# 方式 B：Windows 盘有一份源码时，用仓库脚本同步到 WSL
# 默认：KART_SRC=/mnt/f/Projects/LLM_KV  →  KART_DST=$HOME/projects/llm-kv
export KART_SRC=/mnt/f/Projects/LLM_KV
export KART_DST=$HOME/projects/llm-kv
cd "$KART_SRC"    # 或已有 checkout
./scripts/kart.sh sync && ./scripts/kart.sh sync-check
cd "$KART_DST"
```

**WSL 下 Maven 仓库坑：** Windows 的 `settings.xml` 若写了 `F:\maven-repository`，在 WSL 会变成坏路径。请始终用：

```bash
./scripts/kart.sh rebuild    # 内部带 -Dmaven.repo.local=$HOME/.m2/repository
# 或手动：
mvn -q -DskipTests -Dmaven.repo.local=$HOME/.m2/repository package
```

### 3.3 原生 Linux（校园服务器）

与 WSL 相同依赖即可。本仓库正式驱动机示例路径：`/home/tyq/projects/llm-kv`（大数据/结果可放 `/data1/hbase/tyq/...`）。需要能访问 HBase RS / ZK 网段（常需校园网或 VPN）。

---

## 4. HBase / ZooKeeper / HDFS（服务端）

客户端 **不能**替代服务端。跑查询前集群须已启动：NameNode/DataNode、ZooKeeper、HBase Master、RegionServer。

### 4.1 版本与进程（对照）

```text
Hadoop 2.8.5     → HDFS
ZooKeeper 3.4.10 → 法定人数 / 客户端 2181
HBase 2.2.3      → Master + RegionServer(s)
```

快速自检（在能 `jps` 的节点上）：

```bash
jps
# 期望大致包含：NameNode, DataNode, QuorumPeerMain, HMaster, HRegionServer
```

### 4.2 实验室 4RS 拓扑（正式实验）

| 角色 | 主机（示例） |
|------|----------------|
| Master + ZK + DN + RS | `10.242.104.108` |
| RS + DN | `10.242.1.172` / `10.242.2.130` / `10.242.4.168` |

端口习惯（避让共用集群默认口，以你们 `hbase-site` / `hdfs-site` 为准）：

- ZK client：`2181`
- HDFS RPC：`9000`；DN 数据口示例 `50018`
- HBase Master：`16100`；RS：`16120`

细节与披露说明见 `docs/environment-lock.md`。

### 4.3 单机伪分布式（自建最小环境）

1. 安装同版本 Hadoop 2.8.x、ZooKeeper 3.4.x、HBase 2.2.3。  
2. 配置 `hbase-site.xml`：`hbase.rootdir` 指向 HDFS 或 `file://`（仅开发）；`hbase.zookeeper.quorum=localhost`。  
3. 启动顺序：`HDFS → ZK → HBase`。  
4. 把本仓库客户端 `hbase-site.xml` 改成你的 ZK（见下一节）。

---

## 5. 客户端如何连上集群

仓库内有两份客户端配置（内容需与你的集群一致）：

| 文件 | 用途 |
|------|------|
| `src/main/resources/config/hbase/hbase-site.xml` | **bench / doctor / suite 常用** |
| `config/hbase/hbase-site.xml` | `AppConfig` 默认 `site_xml`；示例可为 `localhost` |

关键属性：

```xml
<property>
  <name>hbase.zookeeper.quorum</name>
  <value>10.242.104.108</value>   <!-- 或 localhost / 你的 ZK 列表 -->
</property>
<property>
  <name>hbase.zookeeper.property.clientPort</name>
  <value>2181</value>
</property>
<property>
  <name>zookeeper.znode.parent</name>
  <value>/hbase-tyq</value>       <!-- 必须与服务端 hbase.zookeeper.znode.parent 一致 -->
</property>
```

可选应用配置：

```bash
cp config/environment.example.yaml config/environment.yaml
# 按需改 hbase.site_xml / catalog.dir
```

验证：

```bash
./scripts/kart.sh doctor
# 应能列出表 / 看到 RegionServer；失败则先查网络与 znode
```

---

## 6. 拉取后必做：自行补充的本地文件

### 6.1 `.env`（LLM，**必填**才能 chat / E1 / rewrite）

```bash
cp .env.example .env
# 编辑 .env：填入真实 LLM_API_KEY（或 GOOGLE_API_KEY）
```

| 变量 | 含义 |
|------|------|
| `LLM_PROVIDER` | `deepseek`（默认）或 `google` |
| `LLM_BASE_URL` | OpenAI 兼容根，如 `https://api.deepseek.com/v1` |
| `LLM_MODEL` | 如 `deepseek-flash` |
| `LLM_API_KEY` | **密钥，勿提交** |
| `LLM_JSON_MODE` | DeepSeek 建议 `true` |

`.env` / `config/environment.yaml` 已在 `.gitignore`。

### 6.2 `catalog/`（**必填**才能真正跑查询）

目录默认 **gitignore**。从实验服务器拷贝 READY manifest，例如：

- `tdrive_v1_ready` / `tdrive_x10_ready` / `tdrive_x50_ready` / `tdrive_x100_ready`
- `porto_v1_ready` / `porto_s50_ready` / `porto_s25_ready` / `porto_s10_ready`

或本机重建：

```bash
./scripts/kart.sh run build-snapshot --data datasets/tdrive
# Porto：先准备 CSV 到 datasets/porto/（未进 git），再 build
```

### 6.3 第三方 upstream（E1 外臂 / Bao / ParamTree）

```bash
bash experiments/adapters/clone-third-party.sh
bash experiments/adapters/clone-third-party.sh --verify
```

落在 `experiments/adapters/upstream/`（gitignore）。需要 git 网络；服务器无外网时在可联网机器 clone 后拷入。

### 6.4 Bao 模型目录

- T-Drive 默认：`experiments/adapters/bao/kart_bao_model/`
- Porto 脚本默认：`KART_BAO_MODEL_DIR=.../kart_bao_model_porto`

```bash
export KART_BAO_MODEL_DIR="$PWD/experiments/adapters/bao/kart_bao_model"
# 或从服务器拷贝 porto 专用模型目录
```

### 6.5 成本补丁（已进仓库）

- `config/cost_patch_offline_llm.json`（T-Drive）
- `config/cost_patch_offline_llm_porto.json`
- `config/cost_patch_offline_llm_porto_param.json`（参数实验 Porto）
- `config/cost_patch_scalability.json`

---

## 7. 构建与健康检查

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
cd /path/to/LLM_KV          # WSL/Linux 工作区

./scripts/kart.sh rebuild   # 编译 shade jar
./scripts/kart.sh doctor    # JDK/HBase/配置
./scripts/kart.sh probe     # LLM（需 .env）
./scripts/kart.sh smoke     # 可选：Oracle 对齐（需 catalog + smoke workload）
```

Windows 改代码 → WSL 跑数：

```bash
./scripts/kart.sh sync && ./scripts/kart.sh sync-check
```

---

## 8. 交互式试用（非论文表）

```bash
./scripts/kart.sh chat
./scripts/kart.sh run query-ir --help
```

---

## 9. 正式实验（对齐 `experiment_design.xlsx`）

公共默认（可覆盖）：

| 项 | 默认 |
|----|------|
| cache | `warm`（`KART_CACHE`） |
| trials | `5`（`KART_TRIALS`） |
| ADV 索引 | `KART_ADVANCED_INDEXES=1` |
| 数据集 | `KART_DATASET=tdrive\|porto`（workload/scalability 支持 `both`） |
| 日期戳 | `KART_EXP_DATE=YYYYMMDD` |

结果：`experiments/results/<run_id>/`（**默认不提交 git**）。

### 9.1 对比（E1 + E3 + case study）

```bash
./scripts/comparative.sh --dataset tdrive --date 20261102
./scripts/comparative.sh --dataset porto  --date 20261102
```

### 9.2 消融

```bash
./scripts/ablation.sh --dataset tdrive --date 20261102
./scripts/ablation.sh --dataset porto  --date 20261102
```

Factors：`no_rule_llm` / `no_costmodel_llm` / `no_costmodel` / `no_verify`（后者需 `--allow-unsafe`）。

### 9.3 参数（OAT）

`beam_width ∈ {1,3,5,7}`，`max_candidates ∈ {4,6,8,10}`：

```bash
./scripts/parameter.sh --dataset tdrive --date 20261102
./scripts/parameter.sh --dataset porto  --date 20261102
```

### 9.4 Workload

```bash
./scripts/workload.sh --dataset both --date 20261102
```

### 9.5 Scalability

```bash
./scripts/scalability.sh --dataset both --date 20261102
./scripts/scalability.sh --dataset tdrive --scales x1,x10 --date 20261102
```

数据：`experiments/workloads/scalability/*_v2.json`；对应 `catalog/*_ready` 必须存在。

### 9.6 单 suite

```bash
./scripts/kart.sh suite parse --run-id my-e1 --cache warm --trials 1
./scripts/kart.sh suite e2e-learned --run-id my-e3 --cache warm --trials 5
```

以 `./scripts/kart.sh --help` 为准。

---

## 10. 方法名 ↔ arm id

| 表中方法 | arm / factor |
|----------|----------------|
| KART | `kart` |
| FullScan | `fullscan` |
| CBO | `cbo` |
| RBO | `rbo` |
| Bao | `bao` |
| ParamTree | `paramtree` |
| DirectIR | `direct-draftir` |
| DIN-SQL (Spider/Bird) | `din-sql-spider` / `din-sql-bird` |
| SAG-MQL | `sag-mql-nofeedback` |

---

## 11. 常见问题

1. **`doctor` 连不上 HBase** — 校园网/VPN、`hbase.zookeeper.quorum`、`zookeeper.znode.parent`、防火墙是否放行 RS。  
2. **`Need LLM_* in .env`** — 未 `cp .env.example .env` 或 key 为空。  
3. **missing upstream** — 跑 `clone-third-party.sh`。  
4. **Porto Bao 无模型** — 设 `KART_BAO_MODEL_DIR` 或拷贝 `kart_bao_model_porto`。  
5. **Oracle 失败** — workload 内嵌 `answers` 与当前 manifest 是否一致。  
6. **WSL Maven 空 jar** — 用 `kart.sh rebuild`，不要用 Windows 盘符 `localRepository`。  
7. **`jps` 没有 HMaster** — 先起 HDFS/ZK 再起 HBase；版本是否为 2.2.3。

---

## 12. 安全

- **禁止**提交 `.env`、`config/environment.yaml`、真实 API key。  
- `.gitignore` 已忽略 `.env`、`catalog/`、`experiments/adapters/upstream/`、`paper/`、跑数结果（保留 `experiment_design.xlsx`）。
