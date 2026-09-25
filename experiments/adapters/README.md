# Adapters

```bash
bash experiments/adapters/clone-third-party.sh
```

| Bridge | Arm ids | Upstream touchpoint |
|--------|---------|---------------------|
| `din/din_bridge.py` | din-spider, din-bird | `DIN-SQL.py` / `DIN-SQL_BIRD.py` makers & templates |
| `sag/sag_bridge.py` | sag | `sag/runtime.py` repair loop |
| `llmopt/llmopt_bridge.py` | llmopt | G→S protocol |
| `bao/kart_plan_family_weights.json` | bao (Java `BaoPlanArm`) | `bao_server.select_plan` argmin |

Honesty / fairness disclosure: **[TRANSPLANT.md](TRANSPLANT.md)**.

Upstream checkouts live under `experiments/third_party/` (gitignored); SHAs in `commits.json`.

Shared LLM client: `llm_client.py` (`temperature=0`, same catalog). JSON-mode HTTP retries count as extra calls. E1 early-reject is shared in Java (`ParseFairness`), not re-implemented per bridge.

Verify frozen checkouts without pulling:

```bash
bash experiments/adapters/clone-third-party.sh --verify
```
