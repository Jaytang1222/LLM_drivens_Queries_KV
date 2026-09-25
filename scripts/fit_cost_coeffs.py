#!/usr/bin/env python3
"""
Legacy diagnostic NNLS on §13.4 structural drivers.

Authoritative calibration is Java FitCostCmd / FeedbackCalibrator, which fits named
coeffs by replaying CostModel.estimate (ScheduleEstimate). Prefer:

  mvn -q exec:java -Dexec.mainClass=kart.cli.KartMain -Dexec.args="fit-cost --pairs runs/cost_calib ..."

This script remains for offline inspection of driver·weight diagnostics only.
It does NOT publish gamma_byte via CostModel replay — do not merge into planner.yaml
for experiment freezes unless you also ran the Java fitter.
"""
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


# Diagnostic drivers (match FeedbackCalibrator.FEATURE_KEYS)
FEATURE_KEYS = [
    "rpc_est",
    "seek_ranges",
    "index_bytes",
    "decode_rows",
    "set_bytes",
    "fetch_gets",
    "exact_points",
    "recon_chunks",
    "dtw_cells",
]

# Incomplete vs Java NAMED_COEFFS (no gamma_byte / beta_hash / …) — diagnostic only.
NAMED_ORDER = [
    "alpha_rpc",
    "alpha_seek",
    "alpha_byte",
    "alpha_decode",
    "beta_emit",
    "gamma_rpc",
    "delta_point",
    "rho_linear",
    "eta_cell",
]


def _read_feature(src: Dict[str, Any], key: str) -> float:
    if key in src and src[key] is not None:
        try:
            return float(src[key])
        except (TypeError, ValueError):
            pass
    # Legacy coarse-feature aliases
    if key in ("rpc_est", "seek_ranges"):
        return float(src.get("scan_ranges") or 0.0)
    if key == "decode_rows":
        return float(src.get("estimated_index_rows") or 0.0)
    if key in ("index_bytes", "set_bytes"):
        return float(src.get("estimated_raw_bytes") or 0.0)
    if key == "fetch_gets":
        cand = float(src.get("estimated_candidate_chunks") or 0.0)
        return max(1.0, math.ceil(cand / 500.0))
    if key == "exact_points":
        return float(src.get("estimated_candidate_chunks") or 0.0) * 128.0
    if key == "recon_chunks":
        return float(src.get("estimated_eligible_trajectories") or 0.0)
    if key == "dtw_cells":
        return float(src.get("estimated_dtw_cells") or 0.0)
    return 0.0


def load_pairs(root: Path) -> List[Tuple[str, Dict[str, float], float, Dict[str, Any]]]:
    pairs = []
    if not root.is_dir():
        return pairs
    for qdir in sorted(root.iterdir()):
        if not qdir.is_dir():
            continue
        feat_p = qdir / "cost_features.json"
        trace_p = qdir / "trace.json"
        if not feat_p.is_file() or not trace_p.is_file():
            continue
        feat = json.loads(feat_p.read_text(encoding="utf-8"))
        trace = json.loads(trace_p.read_text(encoding="utf-8"))
        y = float(trace.get("elapsedMs") or trace.get("elapsed_ms") or 0.0)
        src = feat.get("features") if isinstance(feat.get("features"), dict) else feat
        x = {k: _read_feature(src, k) for k in FEATURE_KEYS}
        meta = {
            "matched_chunks": trace.get("matched_chunks"),
            "candidate_chunks": trace.get("candidate_chunks"),
            "filter_pass_rate": src.get("filter_pass_rate"),
        }
        pairs.append((qdir.name, x, y, meta))
    return pairs


def nnls_simple(X: List[List[float]], y: List[float], iters: int = 800) -> List[float]:
    n = len(X[0]) if X else 0
    w = [0.0] * n
    if not X:
        return w
    scales = [max(1.0, max(abs(row[j]) for row in X)) for j in range(n)]
    lr = 1e-8
    for _ in range(iters):
        grad = [0.0] * n
        for row, yi in zip(X, y):
            pred = sum(wi * xi for wi, xi in zip(w, row))
            err = pred - yi
            for j in range(n):
                grad[j] += err * row[j]
        for j in range(n):
            w[j] = max(0.0, w[j] - (lr / scales[j]) * grad[j])
    return w


def mae(X: List[List[float]], y: List[float], w: List[float]) -> float:
    if not y:
        return 0.0
    s = 0.0
    for row, yi in zip(X, y):
        pred = sum(wi * xi for wi, xi in zip(w, row))
        s += abs(pred - yi)
    return s / len(y)


def merge_planner_yaml(planner: Path, named: Dict[str, Any], filter_rate: Optional[float]) -> None:
    text = planner.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^(\s*calibrated:\s*)\w+", r"\g<1>true", text, count=1)
    for k, v in named.items():
        if k in ("calibrated", "model_version"):
            continue
        pat = re.compile(rf"(?m)^(\s*{re.escape(k)}:\s*)[^\n]+")
        if pat.search(text):
            text = pat.sub(rf"\g<1>{v}", text, count=1)
        else:
            # Insert after calibrated line if missing
            text = re.sub(
                r"(?m)^(\s*calibrated:\s*true\s*)$",
                rf"\1\n  {k}: {v}",
                text,
                count=1,
            )
    if "model_version:" in text:
        text = re.sub(
            r"(?m)^(\s*model_version:\s*)[^\n]+",
            r"\g<1>cost_v2_rs_sched",
            text,
            count=1,
        )
    # Always clear DTW override so calibrated eta_cell is used
    if re.search(r"(?m)^\s*eta_cell_dtw:", text):
        text = re.sub(r"(?m)^(\s*eta_cell_dtw:\s*)[^\n]+", r"\g<1>0.0", text, count=1)
    else:
        text = re.sub(
            r"(?m)^(\s*eta_cell:\s*[^\n]+)$",
            r"\1\n  eta_cell_dtw: 0.0",
            text,
            count=1,
        )
    planner.write_text(text, encoding="utf-8")
    if filter_rate is not None:
        side = planner.parent.parent / "experiments" / "results" / "filter_pass_update.json"
        side.parent.mkdir(parents=True, exist_ok=True)
        side.write_text(
            json.dumps({"filter_pass_rate": filter_rate}, indent=2) + "\n",
            encoding="utf-8",
        )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", type=Path, default=Path("runs/cost_calib"))
    ap.add_argument("--report", type=Path, default=Path("experiments/results/cost_calibration_report.json"))
    ap.add_argument("--out-yaml", type=Path, default=Path("experiments/results/cost_coeffs_calibrated.yaml"))
    ap.add_argument("--merge-planner", type=Path, default=None,
                    help="If set, merge fitted coeffs into this planner.yaml")
    args = ap.parse_args()

    pairs = load_pairs(args.pairs)
    pairs.sort(key=lambda p: p[0])
    n = len(pairs)
    if n == 0:
        raise SystemExit(f"no feature/trace pairs under {args.pairs}")

    n_train = max(1, int(n * 0.6))
    n_val = max(0, int(n * 0.2))
    if n_train + n_val >= n:
        n_val = max(0, n - n_train - 1)
    train = pairs[:n_train]
    val = pairs[n_train : n_train + n_val]
    test = pairs[n_train + n_val :]

    def mat(ps):
        X = [[p[1][k] for k in FEATURE_KEYS] for p in ps]
        y = [p[2] for p in ps]
        return X, y

    Xtr, ytr = mat(train)
    w = nnls_simple(Xtr, ytr)
    Xv, yv = mat(val)
    Xt, yt = mat(test)

    named = {NAMED_ORDER[i]: (w[i] if i < len(w) else 0.0) for i in range(len(NAMED_ORDER))}
    named["eta_cell_dtw"] = 0.0
    named["calibrated"] = True
    named["model_version"] = "cost_v2_rs_sched"

    hits = 0
    samples = 0
    for p in pairs:
        mc = p[3].get("matched_chunks")
        cc = p[3].get("candidate_chunks")
        if mc is not None and cc is not None and float(cc) > 0:
            hits += int(mc)
            samples += int(cc)
    filter_rate = ((hits + 1.0) / (samples + 2.0)) if samples > 0 else None

    report: Dict[str, Any] = {
        "train_size": len(train),
        "val_size": len(val),
        "test_size": len(test),
        "train_mae": mae(Xtr, ytr, w),
        "val_mae": mae(Xv, yv, w),
        "test_mae": mae(Xt, yt, w),
        "weights": {FEATURE_KEYS[i]: w[i] for i in range(len(w))},
        "named_coeffs": named,
        "filter_pass_rate": filter_rate,
        "filter_pass_hits": hits if samples > 0 else None,
        "filter_pass_samples": samples if samples > 0 else None,
        "note": "DIAGNOSTIC only — prefer Java FitCostCmd / FeedbackCalibrator CostModel MAE fit.",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")

    lines = ["cost:", "  calibrated: true", "  model_version: cost_v2_rs_sched"]
    for k in NAMED_ORDER:
        lines.append(f"  {k}: {named[k]}")
    lines.append("  eta_cell_dtw: 0.0")
    args.out_yaml.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if args.merge_planner is not None:
        merge_planner_yaml(args.merge_planner, named, filter_rate)

    print(json.dumps({
        "pairs": n,
        "report": str(args.report),
        "yaml": str(args.out_yaml),
        "merged_planner": str(args.merge_planner) if args.merge_planner else None,
        "filter_pass_rate": filter_rate,
    }, indent=2))


if __name__ == "__main__":
    main()
