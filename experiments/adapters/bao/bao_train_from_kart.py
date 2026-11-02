#!/usr/bin/env python3
"""
Train upstream BaoRegression on KART e2e execution rewards.

Uses experiments/third_party/bao/bao_server/model.py BaoRegression.fit unchanged.
Experience: (PG-encoded plan, reward=t_exec_ms) from prior e2e.jsonl runs.

Honesty: pass --exclude-workload for the formal eval BoundIR so train query_ids
do not overlap the comparison suite (e.g. exclude complex_v2 when evaluating it).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
# Prefer adapters/upstream (current tree); fall back to legacy third_party path.
_UP = ROOT / "experiments" / "adapters" / "upstream" / "bao" / "bao_server"
_LEGACY = ROOT / "experiments" / "third_party" / "bao" / "bao_server"
BAO_SERVER = _UP if _UP.is_dir() else _LEGACY
DEFAULT_OUT = ROOT / "experiments" / "adapters" / "bao" / "kart_bao_model"
ADAPTER = Path(__file__).resolve().parent

sys.path.insert(0, str(ADAPTER))
sys.path.insert(0, str(ADAPTER.parent))
sys.path.insert(0, str(BAO_SERVER))

from kart_pg_plan_encode import encode_plan  # noqa: E402
from learned_train_util import load_workload_query_ids, should_keep_row  # noqa: E402


def load_experience(jsonl_paths: list[Path], exclude_qids: set[str]):
  xs, ys = [], []
  skipped = 0
  for path in jsonl_paths:
    for line in open(path, encoding="utf-8"):
      if not line.strip():
        continue
      r = json.loads(line)
      if not should_keep_row(r, exclude_qids):
        skipped += 1
        continue
      pid = r.get("plan_id")
      tex = r.get("t_exec_ms")
      if not pid or tex is None:
        continue
      card = {
          "estimated_ms": r.get("best_safe_estimated_ms") or r.get("estimated_ms") or tex,
          "features": {},
      }
      feats = r.get("features") or {}
      if isinstance(feats, dict) and "estimated_index_rows" in feats:
        card["features"]["estimated_index_rows"] = feats["estimated_index_rows"]
      # Training: plan + Buffers (TreeFeaturizer have_cache_data=True).
      # Per-plan buffer proxy from rows is OK here — it is part of the labeled
      # experience encoding, not a select-time state leak across candidates.
      enc = encode_plan(str(pid), card, None)
      xs.append(enc)
      ys.append(float(tex))
  return xs, ys, skipped


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--e2e", nargs="+", required=True, help="e2e.jsonl path(s)")
  ap.add_argument("--out", default=str(DEFAULT_OUT))
  ap.add_argument("--min-samples", type=int, default=20)
  ap.add_argument(
      "--exclude-workload",
      nargs="*",
      default=[],
      help="BoundIR workload JSON(s) whose query_ids must not appear in training",
  )
  args = ap.parse_args()

  paths = [Path(p) for p in args.e2e]
  exclude = load_workload_query_ids([Path(p) for p in args.exclude_workload])
  xs, ys, skipped = load_experience(paths, exclude)
  print(f"experience n={len(xs)} skipped_eval_overlap={skipped} "
        f"exclude_qids={len(exclude)} from {len(paths)} files")
  if args.exclude_workload and not exclude:
    print("WARN: --exclude-workload paths yielded 0 query_ids", file=sys.stderr)
  if len(xs) < args.min_samples:
    print(f"ERR need >= {args.min_samples} samples", file=sys.stderr)
    return 1

  out = Path(args.out).resolve()
  if out.exists():
    shutil.rmtree(out)
  out.mkdir(parents=True, exist_ok=True)

  os.chdir(str(BAO_SERVER))
  import model as bao_model  # upstream

  reg = bao_model.BaoRegression(have_cache_data=True, verbose=True)
  reg.fit(xs, ys)
  reg.save(str(out))
  meta = {
      "n": len(xs),
      "skipped_eval_overlap": skipped,
      "exclude_query_ids_n": len(exclude),
      "exclude_workloads": list(args.exclude_workload),
      "sources": [str(p) for p in paths],
      "upstream": "bao_server/model.py BaoRegression.fit",
      "reward": "t_exec_ms",
      "select_buffers": "default_buffers shared at infer (not per-arm)",
  }
  (out / "kart_train_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
  print("saved", out)
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
