#!/usr/bin/env python3
"""
ParamTree cost-model bridge — calls upstream QueryInference.predict unchanged.

Paper: Yang et al., Rethinking Learned Cost Models: Why Start from Scratch?
       SIGMOD 2023. Code: https://github.com/yplusone/ParamTree
"""
from __future__ import annotations

import argparse
import json
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PT = ROOT / "experiments" / "adapters" / "upstream" / "paramtree"
DEFAULT_MODEL = "kart_e2e"
ADAPTER = Path(__file__).resolve().parent

sys.path.insert(0, str(ADAPTER))
sys.path.insert(0, str(PT))

from kart_plan_to_paramtree import encode_planinfo  # noqa: E402
from kart_scheme_build import main as build_scheme  # noqa: E402
from _upstream_bootstrap import chdir_and_prepare  # noqa: E402

PROVENANCE = {
    "method": "paramtree",
    "paper": "Yang et al., Rethinking Learned Cost Models, SIGMOD 2023",
    "upstream": "experiments/adapters/upstream/paramtree/model/query_inference.py",
    "upstream_api": ["QueryInference.predict", "Models.predict_data", "MobTree"],
    "intentional_changes": [
        "KART SafePlan -> PG EXPLAIN Plan-only JSON at select (no Actual*)",
        "predict_op: get_op_info(execute=learn); infer Plan Rows/Cost + Loops=1",
        "scheme_kart_histogram_info.pickle synthetic catalog (no live PG)",
        "Database_info loads pickle only (skip Postgres_Connector)",
        "candidate selection = argmin predicted runtime over SafePlan menu",
        "offline fit uses ANALYZE Actual*=t_exec; train must exclude eval query_ids",
    ],
}


def _args(leaf_num=10, batch_size=10000, train_samples=2000):
  return types.SimpleNamespace(
      leaf_num=leaf_num,
      batch_size=batch_size,
      train_samples=train_samples,
      trim=0.1,
      alpha=0.05,
  )


def _tool(model_name: str, load: bool):
  build_scheme()
  chdir_and_prepare()
  from feature.infos import coefs, scale, query_cparams  # noqa: E402
  from model.query_inference import QueryInference  # noqa: E402

  tool = QueryInference(
      "kart",
      coefs=coefs,
      scale=scale,
      args=_args(),
      load=load,
      load_model_name=model_name,
      node_features=query_cparams,
  )
  for op in tool.mobtrees.feature_tool.features.keys():
    for key in tool.mobtrees.feature_tool.features[op].keys():
      tool.mobtrees.feature_tool.features[op][key]["node_features"] = query_cparams
  return tool


def _missing(model_name: str) -> dict | None:
  model_path = PT / "saved_models" / f"{model_name}.pickle"
  if model_path.is_file():
    return None
  return {
      "status": "ERR",
      "error": (
          f"ParamTree model missing: {model_path}. Train with "
          "python experiments/adapters/paramtree/paramtree_train_from_kart.py "
          "--e2e <e2e.jsonl...>"
      ),
      "provenance": PROVENANCE,
  }


def _score(arms_in: list[dict], tool, model_name: str) -> dict:
  """Upstream predict on an already-loaded QueryInference."""
  if not arms_in:
    return {"status": "ERR", "error": "empty arms", "provenance": PROVENANCE}
  arms_in = sorted(arms_in, key=lambda a: str(a.get("plan_id") or ""))
  plan_ids = [str(a["plan_id"]) for a in arms_in]
  plans = [
      encode_planinfo(str(a["plan_id"]), a.get("card") or {}, mode="infer")
      for a in arms_in
  ]
  from util.util import add_parent_info, add_initplan_info  # noqa: E402

  for item in plans:
    add_parent_info(item["planinfo"])
    add_initplan_info(item["planinfo"])
  scores = tool.predict(plans, learn=False)
  if not isinstance(scores, list):
    scores = [scores]
  if len(scores) != len(plan_ids):
    return {"status": "ERR", "error": "score length mismatch", "provenance": PROVENANCE}
  best_i = min(range(len(scores)), key=lambda i: float(scores[i]))
  return {
      "status": "OK",
      "plan_id": plan_ids[best_i],
      "chosen_index": best_i,
      "n_arms": len(plan_ids),
      "arms": plan_ids,
      "scores": [float(s) for s in scores],
      "model": str(PT / "saved_models" / f"{model_name}.pickle"),
      "provenance": PROVENANCE,
  }


def select(arms_in: list[dict], model_name: str) -> dict:
  """One-shot path (loads the model). Prefer --serve for timed runs."""
  missing = _missing(model_name)
  if missing:
    return missing
  tool = _tool(model_name, load=True)
  return _score(arms_in, tool, model_name)


def serve(model_name: str) -> int:
  """Resident cost model: load once, then one JSON line per predict."""
  import time

  real_out = sys.stdout
  sys.stdout = sys.stderr
  try:
    missing = _missing(model_name)
    if missing:
      raise FileNotFoundError(missing["error"])
    t0 = time.perf_counter()
    tool = _tool(model_name, load=True)
    load_ms = (time.perf_counter() - t0) * 1000.0
  except Exception as e:
    sys.stdout = real_out
    real_out.write(json.dumps({"status": "ERR", "error": str(e)}) + "\n")
    real_out.flush()
    return 1
  sys.stdout = real_out
  real_out.write(json.dumps({"status": "READY", "load_ms": load_ms}) + "\n")
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
      out = _score(arms, tool, model_name)
      out["infer_ms"] = (time.perf_counter() - t1) * 1000.0
    except Exception as e:
      out = {"status": "ERR", "error": str(e), "provenance": PROVENANCE}
    finally:
      sys.stdout = real_out
    real_out.write(json.dumps(out, ensure_ascii=False) + "\n")
    real_out.flush()
  return 0


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--arms-json")
  ap.add_argument("--model-name", default=DEFAULT_MODEL)
  ap.add_argument("--serve", action="store_true",
                  help="Load once and answer stdin JSON lines (in-optimizer cost model)")
  args = ap.parse_args()
  if args.serve:
    return serve(args.model_name)
  if not args.arms_json:
    ap.error("--arms-json is required unless --serve")
  payload = json.loads(Path(args.arms_json).read_text(encoding="utf-8"))
  arms = payload.get("arms") or payload
  out = select(arms, args.model_name)
  print(json.dumps(out, ensure_ascii=False))
  return 0 if out.get("status") == "OK" else 1


if __name__ == "__main__":
  raise SystemExit(main())
