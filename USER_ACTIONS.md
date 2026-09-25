# USER_ACTIONS — operator-only steps

Automation covers unit tests, smoke vs Oracle, and live LLM acceptance.  
No Mock / Memory / fixture（实验路径：Live LLM → HBase）。

## 1. LLM 密钥

`.env`（gitignore）：`LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY`。勿提交、勿贴进文档。

```bash
./scripts/kart.sh probe
./scripts/kart.sh chat
```

## 2. 区域（可选）

`config/regions.yaml` — 见 `docs/regions.md`。

## 3. HBase

`./scripts/kart.sh up` → `doctor` OK；快照 `tdrive_v1_ready`（缺则 `build-snapshot`）。

## 4. 入口

手册：`docs/how-to-run.md` · 用例：`docs/easy_query_example.md`

```bash
cd /home/jaytang/projects/llm-kv
./scripts/kart.sh up
./scripts/kart.sh check
./scripts/kart.sh down
```
