"""Fit side-track online_benefit_model_adv_v1.json from ADV force-plan census."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _feat(r: dict, *keys: str) -> float | None:
    extras = r.get("extras") or r.get("features") or {}
    if not isinstance(extras, dict):
        extras = {}
    for k in keys:
        v = extras.get(k)
        if v is None:
            v = r.get(k)
        if isinstance(v, (int, float)) and math.isfinite(v) and v >= 0:
            return float(v)
    return None


def _rows(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out


def fit(census: Path, out: Path, min_n: int = 20) -> dict:
    rows = _rows(census)
    xs, ys = [], []
    qids = set()
    for r in rows:
        if r.get("census_tag", "").startswith("force_") or r.get("arm") == "fixed-plan":
            pass
        elif r.get("arm") not in ("fixed-plan", "cbo") and not r.get("force_plan_id"):
            continue
        y = r.get("t_exec_ms")
        if not isinstance(y, (int, float)) or not math.isfinite(y) or y <= 0:
            continue
        ranges = _feat(r, "n_ranges", "scan_ranges", "seek_ranges", "range_count", "z_ranges")
        fetch = _feat(r, "fetch_chunks", "fetch_gets", "estimated_candidate_chunks", "chunks_keep")
        index = _feat(r, "index_rows", "estimated_index_rows", "decode_rows")
        if ranges is None or fetch is None or index is None:
            continue
        xs.append([math.log1p(ranges), math.log1p(fetch), math.log1p(index)])
        ys.append(float(y))
        if r.get("query_id"):
            qids.add(r["query_id"])
    if len(xs) < min_n:
        raise SystemExit(f"need >= {min_n} rows with features, got {len(xs)}")
    # OLS via normal equations: [1,x] β = y
    n = len(xs)
    xtx = [[0.0] * 4 for _ in range(4)]
    xty = [0.0] * 4
    for x, y in zip(xs, ys):
        row = [1.0] + x
        for i in range(4):
            xty[i] += row[i] * y
            for j in range(4):
                xtx[i][j] += row[i] * row[j]
    # Gaussian elimination
    a = [xtx[i][:] + [xty[i]] for i in range(4)]
    for i in range(4):
        piv = max(range(i, 4), key=lambda r: abs(a[r][i]))
        a[i], a[piv] = a[piv], a[i]
        if abs(a[i][i]) < 1e-12:
            raise SystemExit("singular design matrix")
        div = a[i][i]
        for j in range(i, 5):
            a[i][j] /= div
        for r in range(4):
            if r == i:
                continue
            f = a[r][i]
            for j in range(i, 5):
                a[r][j] -= f * a[i][j]
    beta = [a[i][4] for i in range(4)]
    preds = [beta[0] + sum(beta[k + 1] * xs[i][k] for k in range(3)) for i in range(n)]
    resid = [preds[i] - ys[i] for i in range(n)]
    resid_sorted = sorted(resid)
    p80 = resid_sorted[int(0.8 * (n - 1))]
    margin = max(50.0, abs(p80))
    model = {
        "basis": "log",
        "intercept": beta[0],
        "coefficients": beta[1:],
        "margin_ms": margin,
        "training_queries": max(min_n, len(qids)),
        "training_rows": n,
        "version": "calib_loglin_adv_v1",
        "note": "Side-track ADV force-plan census fit; do not replace online_benefit_model_v5",
        "source": str(census.as_posix()),
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    return {
        "out": str(out),
        "n_rows": n,
        "n_queries": len(qids),
        "intercept": beta[0],
        "coefficients": beta[1:],
        "margin_ms": margin,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--census", required=True)
    ap.add_argument("--out", default=str(ROOT / "config/online_benefit_model_adv_v1.json"))
    ap.add_argument("--min-n", type=int, default=20)
    args = ap.parse_args()
    print(json.dumps(fit(Path(args.census), Path(args.out), args.min_n), indent=2))


if __name__ == "__main__":
    main()
