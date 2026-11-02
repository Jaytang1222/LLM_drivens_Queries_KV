#!/usr/bin/env python3
"""
Train upstream ParamTree (QueryInference.fit) on KART e2e rewards.

Uses third_party/paramtree NORMAL_TRAIN path: fit(plan_trees) then save_model.

Honesty:
  - Training trees are EXPLAIN ANALYZE with Actual*=measured t_exec (labels).
  - Must exclude eval workload query_ids (--exclude-workload) to avoid leakage.
"""
from __future__ import annotations

import argparse
import json
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ADAPTER = Path(__file__).resolve().parent
PT = ROOT / "experiments" / "third_party" / "paramtree"
DEFAULT_MODEL = "kart_e2e"

sys.path.insert(0, str(ADAPTER))
sys.path.insert(0, str(ADAPTER.parent))
sys.path.insert(0, str(PT))

from kart_plan_to_paramtree import encode_planinfo  # noqa: E402
from kart_scheme_build import main as build_scheme  # noqa: E402
from _upstream_bootstrap import chdir_and_prepare  # noqa: E402
from learned_train_util import load_workload_query_ids, should_keep_row  # noqa: E402


def collect(jsonl_paths: list[Path], exclude_qids: set[str]):
  rows = []
  skipped = 0
  for path in jsonl_paths:
    for line in open(path, encoding="utf-8"):
      if not line.strip():
        continue
      r = json.loads(line)
      if not should_keep_row(r, exclude_qids):
        skipped += 1
        continue
      pid, tex = r.get("plan_id"), r.get("t_exec_ms")
      if not pid or tex is None:
        continue
      card = {
          "estimated_ms": r.get("best_safe_estimated_ms") or r.get("estimated_ms") or tex,
          "features": (r.get("features") or {}) if isinstance(r.get("features"), dict) else {},
      }
      rows.append(encode_planinfo(str(pid), card, actual_ms=float(tex), mode="train"))
  return rows, skipped


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--e2e", nargs="+", required=True)
  ap.add_argument("--model-name", default=DEFAULT_MODEL)
  ap.add_argument("--min-samples", type=int, default=20)
  ap.add_argument("--train-samples", type=int, default=5000)
  ap.add_argument("--leaf-num", type=int, default=10)
  ap.add_argument(
      "--exclude-workload",
      nargs="*",
      default=[],
      help="BoundIR workload JSON(s) whose query_ids must not appear in training",
  )
  args = ap.parse_args()

  exclude = load_workload_query_ids([Path(p) for p in args.exclude_workload])
  plans, skipped = collect([Path(p) for p in args.e2e], exclude)
  print(f"paramtree experience n={len(plans)} skipped_eval_overlap={skipped} "
        f"exclude_qids={len(exclude)}")
  if args.exclude_workload and not exclude:
    print("WARN: --exclude-workload paths yielded 0 query_ids", file=sys.stderr)
  if len(plans) < args.min_samples:
    print(f"ERR need >= {args.min_samples}", file=sys.stderr)
    return 1

  build_scheme()
  chdir_and_prepare()

  from feature.infos import coefs, scale, query_cparams  # noqa: E402
  from model.query_inference import QueryInference  # noqa: E402
  from util.util import add_parent_info, add_initplan_info, actual_rows_modify  # noqa: E402

  ns = types.SimpleNamespace(
      leaf_num=args.leaf_num,
      batch_size=10000,
      train_samples=args.train_samples,
      trim=0.1,
      alpha=0.05,
  )
  tool = QueryInference(
      "kart",
      coefs=coefs,
      scale=scale,
      args=ns,
      load=False,
      load_model_name=args.model_name,
      node_features=query_cparams,
  )
  for op in tool.mobtrees.feature_tool.features.keys():
    for key in tool.mobtrees.feature_tool.features[op].keys():
      tool.mobtrees.feature_tool.features[op][key]["node_features"] = query_cparams

  for item in plans:
    add_parent_info(item["planinfo"])
    add_initplan_info(item["planinfo"])
    actual_rows_modify(item["planinfo"]["Plan"])

  tool.fit(plans, input="plans", clear_buffer=True, train_samples=args.train_samples)
  tool.save_model(args.model_name)
  meta = {
      "n": len(plans),
      "skipped_eval_overlap": skipped,
      "exclude_query_ids_n": len(exclude),
      "exclude_workloads": list(args.exclude_workload),
      "model": args.model_name,
      "upstream": "QueryInference.fit + save_model",
      "reward": "t_exec_ms as Actual Total Time (train mode only)",
      "infer_contract": "Plan-only + get_op_info(execute=False) + Loops=1",
  }
  (PT / "saved_models" / f"{args.model_name}_kart_meta.json").write_text(
      json.dumps(meta, indent=2) + "\n", encoding="utf-8"
  )
  print("saved", PT / "saved_models" / f"{args.model_name}.pickle")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
