# KART (LLM_KV)

键值存储（**HBase**）上的轨迹查询整系统：NL → typed IR → 查询优化（CBO + dual rewrite / 学习臂）→ 覆盖校验 → 执行。

- **语言 / 运行时：** Java 8 · Maven 3.6+ · HBase client 2.2.3  
- **推荐工作区：** Linux / WSL Ubuntu（脚本以 bash 为准；Windows 可编辑源码后 `sync` 到 Linux）  
- **论文结果表：** `experiments/results/experiment_design.xlsx`  
- **规格：** `docs/spec/{requirement,design}.md` 与 `docs/spec/*_experiment.md`

---

## 1. 仓库布局（精简后）

```
config/                 成本补丁、planner、regions、HBase 客户端示例路径
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

正式 suite **只认** `src/main/resources/experiments/suites.yaml` 里的 id（或显式 yaml 路径）。  
对比 / 消融 / 参数 / Workload / Scalability 的表内 id 已与 xlsx 对齐。

---

## 2. 拉取后必做：自行补充的本地文件

### 2.1 `.env`（LLM，**必填**才能 chat / E1 / rewrite）

```bash
cp .env.example .env
# 编辑 .env：填入真实 LLM_API_KEY（或 GOOGLE_API_KEY）
```

| 变量 | 含义 |
|------|------|
| `LLM_PROVIDER` | `deepseek`（默认）或 `google` |
| `LLM_BASE_URL` | OpenAI 兼容 Chat Completions 根，如 `https://api.deepseek.com/v1` |
| `LLM_MODEL` | 如 `deepseek-flash` |
| `LLM_API_KEY` | **密钥，勿提交** |
| `LLM_JSON_MODE` | DeepSeek 建议 `true` |

`.env` / `config/environment.yaml` 已在 `.gitignore`。仓库只提供 `.env.example` 与 `config/environment.example.yaml`。

### 2.2 `config/environment.yaml`（可选）

```bash
cp config/environment.example.yaml config/environment.yaml
```

默认即可；需改 HBase `site_xml` / catalog 目录时再改。客户端 ZK 配置在：

- `config/hbase/hbase-site.xml`
- `src/main/resources/config/hbase/hbase-site.xml`（bench 路径常用这份）

**校园 4RS 集群**地址与端口见 `docs/environment-lock.md`（须与你们实验室实际部署一致；别人的集群改 site 与 catalog）。

### 2.3 `catalog/`（**必填**才能真正跑查询）

目录默认 **gitignore**。需从实验服务器拷贝 READY manifest，例如：

- `tdrive_v1_ready` / `tdrive_x10_ready` / `tdrive_x50_ready` / `tdrive_x100_ready`
- `porto_v1_ready` / `porto_s50_ready` / `porto_s25_ready` / `porto_s10_ready`

或本机重建：

```bash
./scripts/kart.sh run build-snapshot --data datasets/tdrive
# Porto：先准备 CSV 到 datasets/porto/（未进 git），再按服务器文档 build
```

### 2.4 第三方 upstream（E1 外臂 / Bao / ParamTree）

```bash
bash experiments/adapters/clone-third-party.sh
bash experiments/adapters/clone-third-party.sh --verify
```

克隆落在 `experiments/adapters/upstream/`（gitignore）。缺 upstream 时，带 `din-*` / `sag-*` / `bao` / `paramtree` / `llmopt` 的 suite 会在审计处失败。

### 2.5 Bao 模型目录

- T-Drive 默认：`experiments/adapters/bao/kart_bao_model/`  
- Porto 脚本默认：`KART_BAO_MODEL_DIR=.../kart_bao_model_porto`  
  若本地没有 `kart_bao_model_porto`，跑 Porto 前请：

```bash
export KART_BAO_MODEL_DIR="$PWD/experiments/adapters/bao/kart_bao_model"
# 或从服务器拷贝训练好的 porto 模型目录
```

### 2.6 成本补丁（正式实验默认）

已进仓库，一般不用改：

- `config/cost_patch_offline_llm.json`（T-Drive）
- `config/cost_patch_offline_llm_porto.json`
- `config/cost_patch_offline_llm_porto_param.json`（参数实验 Porto）
- `config/cost_patch_scalability.json`

脚本会通过 `KART_COST_PATCH` 选用。

---

## 3. 构建与健康检查

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64   # 按本机调整
cd /path/to/LLM_KV

# 编译打包
./scripts/kart.sh rebuild          # 或: mvn -q -DskipTests package

# 依赖 / HBase / LLM 探测（不打印 API key）
./scripts/kart.sh doctor
./scripts/kart.sh probe            # 需要已配置 .env

# 可选：Oracle 对齐 smoke（需 catalog + smoke workload）
./scripts/kart.sh smoke
```

Windows 编辑 → WSL 跑数时：

```bash
./scripts/kart.sh sync && ./scripts/kart.sh sync-check
```

`KART_SRC` / `KART_DST` 默认见 `scripts/kart.sh` 顶部；可用环境变量覆盖。

---

## 4. 交互式试用（非论文表）

```bash
./scripts/kart.sh chat             # NL → HBase
./scripts/kart.sh run query-ir --help
```

---

## 5. 正式实验（对齐 `experiment_design.xlsx`）

公共约定（脚本已设默认，可覆盖）：

| 项 | 默认 |
|----|------|
| cache | `warm`（`KART_CACHE`） |
| trials | `5`（`KART_TRIALS`；表内常按 warm×5/×10 报 P50） |
| ADV 索引 | `KART_ADVANCED_INDEXES=1` |
| 数据集 | `KART_DATASET=tdrive\|porto`（workload/scalability 还支持 `both`） |
| 日期戳 | `KART_EXP_DATE=YYYYMMDD` |

结果写到 `experiments/results/<run_id>/`，并生成 paper md（对比/消融/参数）。**跑数目录默认不提交 git。**

### 5.1 对比实验（xlsx「对比实验」+ case study）

E1 NL→IR + E3 e2e complex/simple：

```bash
./scripts/comparative.sh --dataset tdrive --date 20261102
./scripts/comparative.sh --dataset porto  --date 20261102
```

- Suites：`parse` / `e2e-learned` / `e2e-learned-simple`（Porto：`parse-porto` / `e2e-porto` / `e2e-porto-simple`）  
- 数据：`datasets/{tdrive,porto}-query/{E1,complex,simple}_query.json`

### 5.2 消融实验（xlsx「消融实验」）

```bash
./scripts/ablation.sh --dataset tdrive --date 20261102
./scripts/ablation.sh --dataset porto  --date 20261102
```

Factors：`no_rule_llm` / `no_costmodel_llm` / `no_costmodel` / `no_verify`（后者需 `--allow-unsafe`）。

### 5.3 参数实验（xlsx「参数实验」）

OAT：`beam_width ∈ {1,3,5,7}`，`max_candidates ∈ {4,6,8,10}`。

```bash
./scripts/parameter.sh --dataset tdrive --date 20261102
./scripts/parameter.sh --dataset porto  --date 20261102
```

Porto 会强制 param 专用 CostPatch + 空改写规则（见脚本注释）。

### 5.4 Workload（xlsx「Workload」）

SRQ × {skewed,gaussian,uniform} × range{50,100,500,1000,1500}：

```bash
./scripts/workload.sh --dataset both --date 20261102
# 可选填表：
# ./scripts/workload.sh --dataset both --date 20261102 --fill-xlsx
```

数据：`datasets/{tdrive,porto}-query/workload_query.json`。

### 5.5 Scalability（xlsx「Scalability」）

```bash
# T-Drive ×1/×10/×50/×100 ；Porto ×1/×0.5/×0.25/×0.1
./scripts/scalability.sh --dataset both --date 20261102
# 单尺度示例：
./scripts/scalability.sh --dataset tdrive --scales x1,x10 --date 20261102
```

- Suite id：`scalability-tdrive-x*_v2` / `scalability-porto-s*_v2`  
- 数据：`experiments/workloads/scalability/*_v2.json`（answers 已嵌入）  
- 环境：`experiments/workloads/scalability/formal_env_v2.sh`  
- 对应 manifest 必须在 `catalog/` 就绪（x100 / s10 等）

### 5.6 只跑某一个 suite

```bash
./scripts/kart.sh suite parse --run-id my-e1 --cache warm --trials 1
./scripts/kart.sh suite e2e-learned --run-id my-e3 --cache warm --trials 5
```

（具体子命令以 `./scripts/kart.sh --help` / `kart_run_suite` 为准。）

---

## 6. 方法名 ↔ arm id（实验设置表）

| 表中方法 | arm / factor |
|----------|----------------|
| KART | `kart`（rewrite 臂） |
| FullScan | `fullscan` |
| CBO | `cbo` |
| RBO | `rbo` |
| Bao | `bao` |
| ParamTree | `paramtree` |
| DirectIR | `direct-draftir` |
| DIN-SQL (Spider/Bird) | `din-sql-spider` / `din-sql-bird` |
| SAG-MQL | `sag-mql-nofeedback`（正式默认，无 WorldAccess 反馈） |

---

## 7. 常见问题

1. **`doctor` 连不上 HBase** — 检查 VPN/校园网、`hbase-site.xml` 的 ZK 与 `zookeeper.znode.parent`，以及本机能否访问 RegionServer。  
2. **`Need LLM_* in .env`** — 未复制 `.env.example` 或 key 为空。  
3. **第三方 arm 报 missing upstream** — 先跑 `clone-third-party.sh`。  
4. **Porto Bao 找不到模型** — 设置 `KART_BAO_MODEL_DIR` 或拷贝 `kart_bao_model_porto`。  
5. **Oracle / require_oracle 失败** — 确认 workload 内嵌 `answers` 与当前 manifest 数据一致；Scalability 大尺度须用对应 `*_ready` 表。  
6. **Maven 在 WSL 拉空依赖** — 使用 `kart.sh rebuild`（会设 `-Dmaven.repo.local=$HOME/.m2/repository`），避免 Windows `settings.xml` 的盘符路径。

---

## 8. 安全

- **禁止**把 `.env`、`config/environment.yaml`、真实 API key、私人 catalog 统计里的敏感路径提交到 git。  
- 推送前可用：`git status` / `git diff --cached` 确认无密钥；`.gitignore` 已忽略 `.env` 与 `experiments/adapters/upstream/`。
