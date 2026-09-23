# USER_ACTIONS — operator-only steps for MVP

Automation covers MemoryBackend tests, Mock LLM, cost selection, explain, failure demos,
READY snapshot + T-Drive smoke, and **live LLM acceptance** (see below).

## 1. Real LLM API

**Status (2026-09-23):** **DeepSeek** via gitignored `.env`
(`LLM_BASE_URL=https://api.deepseek.com/v1`, `LLM_MODEL=deepseek-chat`).
OI-1 JSON mode OK; `./scripts/kart.sh live-accept` **PASS**.

```bash
./scripts/kart.sh probe
./scripts/kart.sh live-accept
./scripts/kart.sh chat
# example utterance, then confirm with: y
```

- Do **not** commit `.env` or paste keys into chat/docs.
- Do **not** treat Mock/`query-nl --mock` as live OI-1.

## 2. Regions (OI-10)

**Status (2026-09-23):** default table filled from public map centers → approximate AABBs
(`config/regions.yaml`, `docs/regions.md`). No geocoding API.

Optional: edit boxes if your experiment needs stricter study areas.
Do not repurpose `fixture_box` for T-Drive (it is meter-space for the synthetic fixture).

## 3. Cost calibration (post-MVP)

- Coefficients remain `calibrated: false` until you fit on a train split of traces.
- Do not feed test-set optimal plans back into the same evaluation (see `spec/IMPLEMENTATION_PLAN.md` §13.6).

## 4. HBase classpath hygiene (optional)

- `doctor` OK on mixed **2.1.2** server classpath (TMan-spatial jar). Isolate/remove it before production stress if versions must match client **2.2.3**.

## 运行入口（2026-09-23）

**全部在 WSL 中运行。** 手册：`docs/how-to-run.md`。

```bash
cd /mnt/f/Projects/LLM_KV
./scripts/kart.sh up
./scripts/kart.sh check      # 多情形自动验收
./scripts/kart.sh chat       # 交互；确认时输入 y
./scripts/kart.sh down
```

`[STATUS]` 默认开启；关闭：`export KART_STATUS=false` 或 chat 内 `/status off`。

- `catalog/tdrive_v1_ready.manifest.json` status **READY** + stats present.
- Strict re-gate (2026-09-23): `mvn test` 100/0; full verify-snapshot **missing=0 extra=0**; T-Drive smoke **22/22**.
- Live LLM (2026-09-23): DeepSeek `deepseek-chat`, JSON mode OK, `kart.sh live-accept` **PASS**.
- `./scripts/kart.sh doctor` connects ZK/HBase when HBase is up.
