#!/usr/bin/env python3
"""Summarize parameter OAT jsonl: P50 t_plan/t_exec/t_e2e by axis × setting × query_type."""
from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


AXES = ("beam_width", "max_candidates", "max_llm_calls")
QTYPES = ("SRQ", "SS", "topk", "overall")
DEFAULTS = {"beam_width": 3, "max_candidates": 4, "max_llm_calls": 4}


def _num(v: Any) -> float | None:
    if v is None:
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def _p50(xs: list[float]) -> float | None:
    if not xs:
        return None
    ys = sorted(xs)
    return ys[(len(ys) - 1) // 2]


def _load(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def _ok(r: dict) -> bool:
    return r.get("ok_oracle") is True and r.get("error") in (None, "", "null")


def _bucket_key(r: dict) -> tuple[str, str, Any, str]:
    level = str(r.get("eval_level") or "?")
    axis = str(r.get("param_axis") or "?")
    val = r.get("param_value")
    if val is None and axis in r:
        val = r.get(axis)
    qt = str(r.get("query_type") or "SRQ")
    return level, axis, val, qt


def summarize(rows: list[dict]) -> None:
    # (level, axis, val, qt) -> metrics
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        groups[_bucket_key(r)].append(r)

    levels = sorted({k[0] for k in groups})
    for level in levels:
        print(f"\n=== eval_level={level} ===")
        for axis in AXES:
            print(f"\n-- axis={axis} --")
            vals = sorted(
                {k[2] for k in groups if k[0] == level and k[1] == axis},
                key=lambda x: (isinstance(x, str), x),
            )
            hdr = f"{'setting':>10} {'qtype':>8} {'n_ok':>5} {'n_fail':>6} {'fail%':>6} {'t_plan':>8} {'t_exec':>8} {'t_e2e':>8} {'default':>7}"
            print(hdr)
            for val in vals:
                # overall + per type
                for qt in QTYPES:
                    if qt == "overall":
                        subset = []
                        for k, rs in groups.items():
                            if k[0] == level and k[1] == axis and k[2] == val:
                                subset.extend(rs)
                    else:
                        subset = groups.get((level, axis, val, qt), [])
                    if not subset and qt != "overall":
                        continue
                    oks = [r for r in subset if _ok(r)]
                    fails = len(subset) - len(oks)
                    plans = [_num(r.get("t_plan_ms")) for r in oks]
                    execs = [_num(r.get("t_exec_ms")) for r in oks]
                    e2es = [_num(r.get("t_e2e_ms")) for r in oks]
                    plans = [x for x in plans if x is not None]
                    execs = [x for x in execs if x is not None]
                    e2es = [x for x in e2es if x is not None]
                    is_def = val == DEFAULTS.get(axis)
                    def_mark = "*" if is_def else ""
                    n = len(subset)
                    fail_pct = (100.0 * fails / n) if n else 0.0
                    print(
                        f"{str(val):>10} {qt:>8} {len(oks):5d} {fails:6d} {fail_pct:5.1f}% "
                        f"{_fmt(_p50(plans)):>8} {_fmt(_p50(execs)):>8} {_fmt(_p50(e2es)):>8} {def_mark:>7}"
                    )


def _fmt(v: float | None) -> str:
    if v is None:
        return "-"
    return f"{v:.1f}"


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: sum_param.py <param.jsonl|e2e.jsonl> [...]", file=sys.stderr)
        return 2
    all_rows: list[dict] = []
    for a in argv[1:]:
        p = Path(a)
        if not p.is_file():
            print(f"missing: {p}", file=sys.stderr)
            return 1
        all_rows.extend(_load(p))
    print(f"rows={len(all_rows)} files={len(argv) - 1}")
    summarize(all_rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
