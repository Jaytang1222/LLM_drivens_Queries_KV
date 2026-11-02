#!/usr/bin/env python3
"""
Bao select_plan bridge — calls upstream BaoForPostgreSQL code unchanged.

Upstream: experiments/adapters/upstream/bao/bao_server/main.py BaoModel.select_plan
  *arms, buffers = messages
  if no model -> PG_OPTIMIZER_INDEX (0)
  else predict each arm and argmin

Intentional changes (I/O only):
  - Arms = KART SafePlan encoded as PG EXPLAIN JSON via kart_pg_plan_encode
  - Model path = KART-trained BaoRegression (upstream fit on Kart rewards)
  - No PostgreSQL / C extension
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BAO_SERVER = ROOT / "experiments" / "adapters" / "upstream" / "bao" / "bao_server"
DEFAULT_MODEL = ROOT / "experiments" / "adapters" / "bao" / "kart_bao_model"

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(BAO_SERVER))

from kart_pg_plan_encode import default_buffers, encode_plan  # noqa: E402


PROVENANCE = {
    "method": "bao",
    "upstream": "experiments/adapters/upstream/bao/bao_server/main.py",
    "upstream_api": ["BaoModel.select_plan", "BaoRegression.predict"],
    "intentional_changes": [
        "arms = KART SafePlan encoded as PG EXPLAIN JSON (kart_pg_plan_encode)",
        "no PostgreSQL / bao pg_extension",
        "select-time Buffers = fixed default_buffers (shared state), not per-arm proxies",
        "model trained offline on KART t_exec via BaoRegression.fit; exclude eval query_ids",
    ],
}


def _add_buffer_info_to_plans(buffer_info, plans):
  """Verbatim from bao_server/main.py add_buffer_info_to_plans."""
  for p in plans:
    p["Buffers"] = buffer_info
  return plans


def _select_plan(bao_reg, messages):
  """
  Verbatim decision body of bao_server/main.py BaoModel.select_plan.
  Imports model.BaoRegression only (main.py pulls psycopg2 via train/reg_blocker).
  """
  from constants import PG_OPTIMIZER_INDEX  # upstream

  *arms, buffers = messages
  if bao_reg is None:
    return PG_OPTIMIZER_INDEX
  arms = _add_buffer_info_to_plans(buffers, arms)
  res = bao_reg.predict(arms)
  return int(res.argmin())


def _load_regression(model_dir: Path):
  import model as bao_model  # upstream bao_server/model.py

  if not (model_dir.is_dir() and (model_dir / "nn_weights").exists()):
    return None
  reg = bao_model.BaoRegression(have_cache_data=True)
  reg.load(str(model_dir))
  return reg


def _score(arms_in: list[dict], reg) -> dict:
  """Upstream select_plan on an already-loaded BaoRegression."""
  if not arms_in:
    return {"status": "ERR", "error": "empty arms", "provenance": PROVENANCE}

  arms_in = sorted(arms_in, key=lambda a: str(a.get("plan_id") or ""))
  plan_ids = [str(a["plan_id"]) for a in arms_in]
  # Upstream select_plan stamps the *same* live buffer snapshot on every arm.
  buffers = default_buffers()
  encoded = []
  for a in arms_in:
    enc = encode_plan(str(a["plan_id"]), a.get("card") or {}, buffers=None)
    encoded.append({"Plan": enc["Plan"]})

  messages = list(encoded) + [buffers]
  idx = _select_plan(reg, messages)
  if idx < 0 or idx >= len(plan_ids):
    idx = 0
  return {
      "status": "OK",
      "plan_id": plan_ids[idx],
      "chosen_index": idx,
      "n_arms": len(plan_ids),
      "arms": plan_ids,
      "model_loaded": reg is not None,
      "provenance": PROVENANCE,
  }


def select(arms_in: list[dict], model_dir: Path) -> dict:
  """One-shot path (loads the model). Prefer --serve for timed runs."""
  model_dir = Path(model_dir).resolve()
  os.chdir(str(BAO_SERVER))
  reg = _load_regression(model_dir)
  out = _score(arms_in, reg)
  out["model_dir"] = str(model_dir)
  return out


def serve(model_dir: Path) -> int:
  """Resident Bao server: load once, then one JSON line per select_plan."""
  import time

  real_out = sys.stdout
  sys.stdout = sys.stderr
  try:
    t0 = time.perf_counter()
    model_dir = Path(model_dir).resolve()
    os.chdir(str(BAO_SERVER))
    reg = _load_regression(model_dir)
    load_ms = (time.perf_counter() - t0) * 1000.0
  except Exception as e:
    sys.stdout = real_out
    real_out.write(json.dumps({"status": "ERR", "error": str(e)}) + "\n")
    real_out.flush()
    return 1
  sys.stdout = real_out
  real_out.write(json.dumps({
      "status": "READY",
      "load_ms": load_ms,
      "model_loaded": reg is not None,
  }) + "\n")
  real_out.flush()
  for line in sys.stdin:
    line = line.strip()
    if not line:
      continue
    sys.stdout = sys.stderr
    try:
      payload = json.loads(line)
      arms = payload.get("arms") or []
      t1 = time.perf_counter()
      out = _score(arms, reg)
      out["infer_ms"] = (time.perf_counter() - t1) * 1000.0
      out["model_dir"] = str(model_dir)
    except Exception as e:
      out = {"status": "ERR", "error": str(e), "provenance": PROVENANCE}
    finally:
      sys.stdout = real_out
    real_out.write(json.dumps(out, ensure_ascii=False) + "\n")
    real_out.flush()
  return 0


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--arms-json", help="JSON file: {arms:[{plan_id,card},...]}")
  ap.add_argument("--model-dir", default=str(DEFAULT_MODEL))
  ap.add_argument("--serve", action="store_true",
                  help="Load once and answer stdin JSON lines (paper server)")
  args = ap.parse_args()
  if args.serve:
    return serve(Path(args.model_dir))
  if not args.arms_json:
    ap.error("--arms-json is required unless --serve")
  payload = json.loads(Path(args.arms_json).read_text(encoding="utf-8"))
  arms = payload.get("arms") or payload
  out = select(arms, Path(args.model_dir))
  print(json.dumps(out, ensure_ascii=False))
  return 0 if out.get("status") == "OK" else 1


if __name__ == "__main__":
  raise SystemExit(main())
