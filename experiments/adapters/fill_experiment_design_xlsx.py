#!/usr/bin/env python3
"""Fill experiment_design.xlsx from formal warm×10 paper md / json summaries.

Default: advantage gate (KART best on P50 e2e) — skip write if not met.
--force: write T-Drive 对比/消融/参数 anyway (Porto left blank).
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    import openpyxl
except ImportError as e:
    raise SystemExit("openpyxl required: pip install openpyxl") from e

ROOT = Path(__file__).resolve().parents[2]
XLSX = ROOT / "experiments" / "results" / "experiment_design.xlsx"
GATE = ROOT / "experiments" / "results" / "xlsx_fill_gate.json"
SUMMARY = ROOT / "experiments" / "results" / "tdrive_fill_summary_20261021.json"


def parse_md_tables(md: str) -> List[List[List[str]]]:
    tables: List[List[List[str]]] = []
    cur: List[List[str]] = []
    for line in md.splitlines():
        if line.strip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if all(re.match(r"^:?-+:?$", c or "") for c in cells):
                continue
            cur.append(cells)
        else:
            if cur:
                tables.append(cur)
                cur = []
    if cur:
        tables.append(cur)
    return tables


def _f(x: Any) -> Optional[float]:
    if x is None:
        return None
    if isinstance(x, (int, float)):
        return float(x)
    try:
        return float(str(x).replace(",", ""))
    except ValueError:
        return None


def r1(x: Optional[float]) -> Optional[float]:
    """Round to 1 decimal; keep None."""
    if x is None:
        return None
    return round(float(x), 1)


def r4(x: Optional[float]) -> Optional[float]:
    if x is None:
        return None
    return round(float(x), 4)


def set_cell(ws, row: int, col: int, value: Any) -> None:
    if value is None:
        return
    ws.cell(row, col).value = value


def arm_e2e_from_e3_md(md: str) -> Dict[str, Dict[str, float]]:
    out: Dict[str, Dict[str, float]] = {}
    for table in parse_md_tables(md):
        if not table or len(table[0]) < 4:
            continue
        for row in table[1:]:
            if not row:
                continue
            arm = row[0].strip()
            nums = [_f(c) for c in row[1:]]
            if len(nums) >= 3 and nums[0] is not None:
                out[arm] = {
                    "e2e": nums[0],
                    "plan": nums[1] if nums[1] is not None else float("nan"),
                    "exec": nums[2] if nums[2] is not None else float("nan"),
                }
    return out


def kart_dominates(metrics: Dict[str, Dict[str, float]], key: str = "e2e") -> Tuple[bool, str]:
    kart_key = next((k for k in metrics if k.lower() == "kart"), None)
    if not kart_key:
        kart_key = next((k for k in metrics if k.lower().startswith("kart")), None)
    if not kart_key:
        return False, "no_kart_row"
    kv = metrics[kart_key].get(key)
    if kv is None:
        return False, "no_kart_metric"
    others = [(k, v.get(key)) for k, v in metrics.items() if k != kart_key and v.get(key) is not None]
    if not others:
        return False, "no_other_arms"
    losers = [k for k, v in others if v is not None and v <= kv]
    if losers:
        return False, f"kart_not_best_vs={losers}"
    return True, "ok"


def parse_e1_md(md: str) -> Dict[str, Dict[str, Any]]:
    """method -> complex/simple pass/reject/clarify/parse."""
    out: Dict[str, Dict[str, Any]] = {}
    for table in parse_md_tables(md):
        if not table or "method" not in table[0][0].lower():
            continue
        for row in table[1:]:
            if len(row) < 9:
                continue
            method = row[0].strip()
            out[method] = {
                "complex": {
                    "pass": _f(row[1]),
                    "reject": _f(row[2]),
                    "clarify": _f(row[3]),
                    "t_parse": _f(row[7]),
                },
                "simple": {
                    "pass": _f(row[4]),
                    "reject": _f(row[5]),
                    "clarify": _f(row[6]),
                    "t_parse": _f(row[8]),
                },
            }
    return out


def parse_param_md(md: str) -> Dict[str, Dict[str, Dict[str, float]]]:
    """metric -> axis -> {complex, simple} keyed by axis value str."""
    sections = re.split(r"^###\s+", md, flags=re.M)
    metric_map = {"E2E": "e2e", "Plan": "plan", "Execute": "exec", "Exec": "exec"}
    out: Dict[str, Dict[str, Dict[str, float]]] = {}
    for sec in sections:
        if not sec.strip():
            continue
        first = sec.splitlines()[0].strip()
        metric = None
        for k, v in metric_map.items():
            if first.startswith(k):
                metric = v
                break
        if not metric:
            continue
        out.setdefault(metric, {"beam": {}, "maxcand": {}, "qtype": {}})
        # split by ####
        for block in re.split(r"^####\s+", sec, flags=re.M):
            if not block.strip():
                continue
            title = block.splitlines()[0].lower().strip()
            # order matters: titles cross-mention other axes (e.g. beamwidth (max_candidates=4))
            axis = None
            if "query type" in title:
                axis = "qtype"
            elif title.startswith("beam"):
                axis = "beam"
            elif title.startswith("max_cand") or title.startswith("maxcand"):
                axis = "maxcand"
            else:
                continue
            tables = parse_md_tables(block)
            if not tables:
                continue
            for row in tables[0][1:]:
                if len(row) < 3:
                    continue
                key = row[0].strip()
                cx, sm = _f(row[1]), _f(row[2])
                if cx is not None:
                    out[metric][axis].setdefault(key, {})["complex"] = cx
                if sm is not None:
                    out[metric][axis].setdefault(key, {})["simple"] = sm
    return out


def fill_compare_e1(ws, e1: Dict[str, Dict[str, Any]]) -> None:
    # rows 3-7 T-Drive
    row_map = {
        3: "KART",
        4: "DirectIR",
        5: "DIN-SQL (Spider)",
        6: "DIN-SQL (Bird)",
        7: "SAG-MQL",
    }
    for row, name in row_map.items():
        m = e1.get(name)
        if not m:
            continue
        cx, sm = m["complex"], m["simple"]
        set_cell(ws, row, 7, r4(cx.get("pass")))
        set_cell(ws, row, 9, r4(cx.get("reject")))
        set_cell(ws, row, 11, r4(cx.get("clarify")))
        set_cell(ws, row, 13, r4(sm.get("pass")))
        set_cell(ws, row, 15, r4(sm.get("reject")))
        set_cell(ws, row, 17, r4(sm.get("clarify")))
        set_cell(ws, row, 19, r1(cx.get("t_parse")))
        set_cell(ws, row, 21, r1(sm.get("t_parse")))


def fill_compare_e3(
    ws,
    cx: Dict[str, Dict[str, float]],
    sm: Dict[str, Dict[str, float]],
    quantile: int,
    *,
    dataset: str = "tdrive",
) -> None:
    # T-Drive: P50 17-22; P95 32-37; P99 47-52. Porto block is +6 rows each.
    base_td = {50: 17, 95: 32, 99: 47}[quantile]
    base = base_td + (6 if dataset == "porto" else 0)
    row_map = {
        base + 0: "kart",
        base + 1: "fullscan",
        base + 2: "rbo",
        base + 3: "cbo",
        base + 4: "bao",
        base + 5: "paramtree",
    }
    suffix_e2e = f"e2e_p{quantile}"
    suffix_plan = f"plan_p{quantile}"
    suffix_exec = f"exec_p{quantile}"
    # also accept short keys e2e/plan/exec for paper-md derived
    for row, arm in row_map.items():
        a = cx.get(arm) or cx.get(arm.title()) or {}
        b = sm.get(arm) or sm.get(arm.title()) or {}
        # normalize keys
        def pick(d: Dict[str, float], long_k: str, short_k: str) -> Optional[float]:
            if long_k in d:
                return d[long_k]
            if short_k in d:
                return d[short_k]
            return None

        # Prefer additive e2e when plan/exec present (never trust independent e2e percentile).
        def e2e_of(d: Dict[str, float]) -> Optional[float]:
            plan_v, exec_v = pick(d, suffix_plan, "plan"), pick(d, suffix_exec, "exec")
            if plan_v is not None and exec_v is not None:
                return float(plan_v) + float(exec_v)
            return pick(d, suffix_e2e, "e2e")

        set_cell(ws, row, 7, r1(e2e_of(a)))
        set_cell(ws, row, 9, r1(pick(a, suffix_plan, "plan")))
        set_cell(ws, row, 11, r1(pick(a, suffix_exec, "exec")))
        set_cell(ws, row, 13, r1(e2e_of(b)))
        set_cell(ws, row, 15, r1(pick(b, suffix_plan, "plan")))
        set_cell(ws, row, 17, r1(pick(b, suffix_exec, "exec")))


def fill_ablation(
    ws,
    cx: Dict[str, Dict[str, float]],
    sm: Dict[str, Dict[str, float]],
    *,
    dataset: str = "tdrive",
) -> None:
    # T-Drive rows 3-7; Porto rows 8-12. Arms: full / w/o rule / costllm / costmodel / verify.
    base = 3 if dataset == "tdrive" else 8
    row_map = {
        base + 0: "full",
        base + 1: "no_rule_llm",
        base + 2: "no_costmodel_llm",
        base + 3: "no_costmodel",
        base + 4: "no_verify",
    }
    for row, arm in row_map.items():
        a, b = cx.get(arm, {}), sm.get(arm, {})
        # Prefer additive e2e when plan/exec present.
        def e2e(d: Dict[str, float]) -> Optional[float]:
            plan_v, exec_v = d.get("plan_p50"), d.get("exec_p50")
            if plan_v is not None and exec_v is not None:
                return float(plan_v) + float(exec_v)
            return d.get("e2e_p50")

        set_cell(ws, row, 5, r1(e2e(a)))
        set_cell(ws, row, 7, r1(a.get("plan_p50")))
        set_cell(ws, row, 9, r1(a.get("exec_p50")))
        set_cell(ws, row, 11, r4(a.get("pass")))
        set_cell(ws, row, 13, r1(e2e(b)))
        set_cell(ws, row, 15, r1(b.get("plan_p50")))
        set_cell(ws, row, 17, r1(b.get("exec_p50")))
        set_cell(ws, row, 19, r4(b.get("pass")))


def fill_param(
    ws,
    param_cx: Dict[str, Dict[str, float]],
    param_sm: Dict[str, Dict[str, float]],
    qtype_cx: Dict[str, Dict[str, float]],
    qtype_sm: Dict[str, Dict[str, float]],
    param_md: Optional[Dict[str, Dict[str, Dict[str, float]]]] = None,
) -> None:
    """Rows: e2e 4/5, plan 12/13, exec 20/21. Cols C-F beam, G-J maxcand, K-N qtype."""
    beams = [1, 3, 5, 7]
    maxc = [4, 6, 8, 10]
    qtypes = ["SRQ", "ST-Ranges", "SS", "topk"]

    def from_arm(d: Dict[str, float], prefix: str, val: int) -> Optional[float]:
        return d.get(f"param:{prefix}={val}")

    def write_metric(row_cx: int, row_sm: int, metric: str) -> None:
        # prefer paper md when present
        md_m = (param_md or {}).get(metric, {})
        for i, b in enumerate(beams):
            col = 3 + i
            if md_m.get("beam", {}).get(str(b)):
                set_cell(ws, row_cx, col, r1(md_m["beam"][str(b)].get("complex")))
                set_cell(ws, row_sm, col, r1(md_m["beam"][str(b)].get("simple")))
            else:
                set_cell(ws, row_cx, col, r1(from_arm(param_cx.get(metric, {}), "beam_width", b)))
                set_cell(ws, row_sm, col, r1(from_arm(param_sm.get(metric, {}), "beam_width", b)))
        for i, m in enumerate(maxc):
            col = 7 + i
            if md_m.get("maxcand", {}).get(str(m)):
                set_cell(ws, row_cx, col, r1(md_m["maxcand"][str(m)].get("complex")))
                set_cell(ws, row_sm, col, r1(md_m["maxcand"][str(m)].get("simple")))
            else:
                set_cell(ws, row_cx, col, r1(from_arm(param_cx.get(metric, {}), "max_candidates", m)))
                set_cell(ws, row_sm, col, r1(from_arm(param_sm.get(metric, {}), "max_candidates", m)))
        for i, qt in enumerate(qtypes):
            col = 11 + i
            if md_m.get("qtype", {}).get(qt):
                set_cell(ws, row_cx, col, r1(md_m["qtype"][qt].get("complex")))
                set_cell(ws, row_sm, col, r1(md_m["qtype"][qt].get("simple")))
            else:
                set_cell(ws, row_cx, col, r1((qtype_cx.get(metric) or {}).get(qt)))
                set_cell(ws, row_sm, col, r1((qtype_sm.get(metric) or {}).get(qt)))

    write_metric(4, 5, "e2e")
    write_metric(12, 13, "plan")
    write_metric(20, 21, "exec")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--xlsx", type=Path, default=XLSX)
    ap.add_argument("--date", required=True, help="YYYYMMDD used in result filenames")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true", help="Fill T-Drive even if gate fails")
    ap.add_argument("--summary", type=Path, default=SUMMARY)
    args = ap.parse_args()

    results = ROOT / "experiments" / "results"
    gate: Dict[str, Any] = {"date": args.date, "force": args.force, "decisions": []}

    summary: Dict[str, Any] = {}
    if args.summary.is_file():
        summary = json.loads(args.summary.read_text(encoding="utf-8"))

    wb = openpyxl.load_workbook(args.xlsx)

    # Gate on E3 T-Drive from summary or md
    e3_md = results / f"comparativeE3-tdrive-{args.date}.md"
    metrics_gate: Dict[str, Dict[str, float]] = {}
    if summary.get("e3_complex"):
        metrics_gate = {
            k: {"e2e": v.get("e2e_p50")} for k, v in summary["e3_complex"].items() if v.get("e2e_p50") is not None
        }
    elif e3_md.is_file():
        metrics_gate = arm_e2e_from_e3_md(e3_md.read_text(encoding="utf-8"))
    ok, reason = kart_dominates(metrics_gate, "e2e") if metrics_gate else (False, "no_metrics")
    gate["decisions"].append({"sheet": "对比实验", "dataset": "tdrive", "ok": ok, "reason": reason})

    do_fill = args.force or ok
    gate["filled"] = bool(do_fill and not args.dry_run)

    if do_fill and not args.dry_run:
        ws = wb["对比实验"]
        e1_md = results / f"comparativeE1-tdrive-{args.date}.md"
        if e1_md.is_file():
            fill_compare_e1(ws, parse_e1_md(e1_md.read_text(encoding="utf-8")))
        # E3 P50/P95/P99: plan/exec percentiles; e2e := plan+exec
        if summary.get("e3_complex"):
            for q in (50, 95, 99):
                fill_compare_e3(ws, summary["e3_complex"], summary["e3_simple"], quantile=q)

        ws_abl = wb["消融实验"]
        if summary.get("ablation_complex"):
            fill_ablation(ws_abl, summary["ablation_complex"], summary["ablation_simple"])

        ws_param = wb["参数实验"]
        param_md_path = results / f"parameter-{args.date}.md"
        param_md = parse_param_md(param_md_path.read_text(encoding="utf-8")) if param_md_path.is_file() else None
        fill_param(
            ws_param,
            summary.get("param_complex", {}),
            summary.get("param_simple", {}),
            summary.get("qtype_complex", {}),
            summary.get("qtype_simple", {}),
            param_md=param_md,
        )

        wb.save(args.xlsx)
        gate["xlsx"] = str(args.xlsx)

    GATE.write_text(json.dumps(gate, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(gate, indent=2, ensure_ascii=False))
    if args.dry_run:
        print("dry-run: no xlsx write")
    elif do_fill:
        print(f"filled T-Drive blocks in {args.xlsx}")
    else:
        print(f"gate blocked fill ({reason}); use --force to override")


if __name__ == "__main__":
    main()
