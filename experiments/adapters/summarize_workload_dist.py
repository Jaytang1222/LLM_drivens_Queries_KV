#!/usr/bin/env python3
"""Summarize Workload dist×range e2e runs → md + optional xlsx fill.

Prefer merged suite runs (2026-11-01+):
  experiments/results/workload-dist-{tdrive,porto}-YYYYMMDD/
with query_id prefix srq_{skewed|gaussian|uniform}_*.

Legacy per-dist dirs still supported:
  experiments/results/workload-dist-{tdrive,porto}-{skewed,gaussian,uniform}-YYYYMMDD/

Cell value = P50 of (t_plan+t_exec) over queries×trials for that (D,r), successes only.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RANGES = (50, 100, 500, 1000, 1500)
DISTS = ("skewed", "gaussian", "uniform")
ARMS = ("kart", "fullscan", "rbo", "cbo", "bao", "paramtree")
ARM_LABEL = {
    "kart": "KART",
    "fullscan": "FullScan",
    "rbo": "RBO",
    "cbo": "CBO",
    "bao": "Bao",
    "paramtree": "Paramtree",
}
RANGE_RE = re.compile(r"_r(\d{4})$")
DIST_RE = re.compile(r"^srq_(skewed|gaussian|uniform)_")


def median(xs):
    return float(statistics.median(xs)) if xs else None


def load_trials(run_dir: Path):
    for name in ("e2e.jsonl", "trials.jsonl"):
        p = run_dir / name
        if p.is_file():
            return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    return []


def range_of(qid: str):
    m = RANGE_RE.search(qid or "")
    return int(m.group(1)) if m else None


def dist_of(qid: str):
    m = DIST_RE.match(qid or "")
    return m.group(1) if m else None


def e2e_ms(row: dict):
    plan = row.get("t_plan_ms")
    exe = row.get("t_exec_ms")
    if isinstance(plan, (int, float)) and isinstance(exe, (int, float)):
        return float(plan) + float(exe)
    e = row.get("t_e2e_ms")
    return float(e) if isinstance(e, (int, float)) else None


def ok(row: dict) -> bool:
    if row.get("ok_oracle") is False:
        return False
    if row.get("ok") is False:
        return False
    return True


def summarize_rows(rows, dist_filter: str | None = None):
    """If dist_filter set, keep only query_ids for that distribution."""
    e2e = defaultdict(lambda: defaultdict(list))
    plan = defaultdict(lambda: defaultdict(list))
    exe = defaultdict(lambda: defaultdict(list))
    n_ok = defaultdict(lambda: defaultdict(int))
    n_all = defaultdict(lambda: defaultdict(int))
    for r in rows:
        if str(r.get("stage", "e2e")).lower() != "e2e":
            continue
        arm = (r.get("arm") or r.get("planner_mode") or "").lower()
        if arm not in ARM_LABEL:
            continue
        qid = str(r.get("query_id") or "")
        if dist_filter is not None:
            d = dist_of(qid)
            if d != dist_filter:
                continue
        rq = range_of(qid)
        if rq is None:
            continue
        n_all[arm][rq] += 1
        if not ok(r):
            continue
        v = e2e_ms(r)
        if v is None:
            continue
        n_ok[arm][rq] += 1
        e2e[arm][rq].append(v)
        if isinstance(r.get("t_plan_ms"), (int, float)):
            plan[arm][rq].append(float(r["t_plan_ms"]))
        if isinstance(r.get("t_exec_ms"), (int, float)):
            exe[arm][rq].append(float(r["t_exec_ms"]))
    cells = {}
    for arm in ARMS:
        for rq in RANGES:
            cells[(arm, rq)] = {
                "e2e_p50": median(e2e[arm][rq]),
                "plan_p50": median(plan[arm][rq]),
                "exec_p50": median(exe[arm][rq]),
                "n_ok": n_ok[arm][rq],
                "n_all": n_all[arm][rq],
            }
    return cells, len(rows)


def summarize_run(run_dir: Path, dist_filter: str | None = None):
    return summarize_rows(load_trials(run_dir), dist_filter=dist_filter)


def fill_xlsx(path: Path, grid: dict):
    import openpyxl

    wb = openpyxl.load_workbook(path)
    ws = wb["Workload"]
    # rows: Tdrive 4-9, Porto 10-15; cols: skewed C-G, gaussian H-L, uniform M-Q
    row_of = {
        ("tdrive", "kart"): 4,
        ("tdrive", "fullscan"): 5,
        ("tdrive", "rbo"): 6,
        ("tdrive", "cbo"): 7,
        ("tdrive", "bao"): 8,
        ("tdrive", "paramtree"): 9,
        ("porto", "kart"): 10,
        ("porto", "fullscan"): 11,
        ("porto", "rbo"): 12,
        ("porto", "cbo"): 13,
        ("porto", "bao"): 14,
        ("porto", "paramtree"): 15,
    }
    col0 = {"skewed": 3, "gaussian": 8, "uniform": 13}  # 1-indexed C/H/M
    for (ds, dist), cells in grid.items():
        for arm in ARMS:
            r = row_of[(ds, arm)]
            for i, rq in enumerate(RANGES):
                c = col0[dist] + i
                v = cells[(arm, rq)]["e2e_p50"]
                ws.cell(r, c).value = None if v is None else round(v, 1)
    wb.save(path)


def resolve_dataset_runs(results_dir: Path, dataset: str, date: str):
    """Prefer merged suite run; fall back to legacy per-dist dirs."""
    merged = results_dir / f"workload-dist-{dataset}-{date}"
    if merged.is_dir() and (
        (merged / "e2e.jsonl").is_file() or (merged / "trials.jsonl").is_file()
    ):
        return ("merged", merged)
    legacy = {}
    for dist in DISTS:
        run = results_dir / f"workload-dist-{dataset}-{dist}-{date}"
        if run.is_dir():
            legacy[dist] = run
    return ("legacy", legacy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default="20261027")
    ap.add_argument("--results-dir", type=Path, default=ROOT / "experiments/results")
    ap.add_argument("--xlsx", type=Path, default=ROOT / "experiments/results/experiment_design.xlsx")
    ap.add_argument("--fill-xlsx", action="store_true")
    ap.add_argument("--md-out", type=Path, default=None)
    args = ap.parse_args()
    grid = {}
    lines = [f"# comparativeWorkload-{args.date}", "", "P50 e2e := plan+exec (successes only).", ""]

    def emit_dist_block(ds: str, dist: str, cells: dict, header: str):
        lines.append(f"### {dist} ({header})")
        lines.append("")
        hdr = "| arm | " + " | ".join(str(r) for r in RANGES) + " |"
        sep = "|---|" + "---:|" * len(RANGES)
        lines.append(hdr)
        lines.append(sep)
        for arm in ARMS:
            vals = []
            for rq in RANGES:
                e = cells[(arm, rq)]["e2e_p50"]
                p = cells[(arm, rq)]["plan_p50"]
                x = cells[(arm, rq)]["exec_p50"]
                if e is None:
                    vals.append("—")
                else:
                    vals.append(f"{e:.0f} (p{p:.0f}/x{x:.0f})" if p is not None else f"{e:.0f}")
            lines.append(f"| {ARM_LABEL[arm]} | " + " | ".join(vals) + " |")
        rates = []
        for arm in ARMS:
            ok_n = sum(cells[(arm, rq)]["n_ok"] for rq in RANGES)
            all_n = sum(cells[(arm, rq)]["n_all"] for rq in RANGES)
            rates.append(f"{ARM_LABEL[arm]}={ok_n}/{all_n}")
        lines.append("")
        lines.append("oracle_ok: " + ", ".join(rates))
        lines.append("")

    for ds in ("tdrive", "porto"):
        lines.append(f"## {ds}")
        mode, resolved = resolve_dataset_runs(args.results_dir, ds, args.date)
        if mode == "merged":
            rows = load_trials(resolved)
            lines.append(f"- merged run `{resolved.name}` rows={len(rows)}")
            for dist in DISTS:
                cells, _ = summarize_rows(rows, dist_filter=dist)
                grid[(ds, dist)] = cells
                emit_dist_block(ds, dist, cells, f"from merged, filter=srq_{dist}_*")
        else:
            for dist in DISTS:
                run = resolved.get(dist)
                if run is None:
                    lines.append(f"- missing `workload-dist-{ds}-{dist}-{args.date}`")
                    continue
                cells, n = summarize_run(run)
                grid[(ds, dist)] = cells
                emit_dist_block(ds, dist, cells, f"`{run.name}`, trials_rows={n}")

    md = "\n".join(lines) + "\n"
    md_out = args.md_out or (args.results_dir / f"comparativeWorkload-{args.date}.md")
    md_out.write_text(md, encoding="utf-8")
    print("wrote", md_out)
    if args.fill_xlsx:
        if len(grid) < 6:
            raise SystemExit(f"need 6 cells, have {len(grid)}: refuse fill")
        fill_xlsx(args.xlsx, grid)
        print("filled", args.xlsx)


if __name__ == "__main__":
    main()
