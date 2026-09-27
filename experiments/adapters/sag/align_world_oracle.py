#!/usr/bin/env python3
"""
Align HBase FullScan (query-ir --force via P_FULL policy) vs Oracle answers.

Uses BoundIR workload + oracle JSON (not DraftIR). Opt-in:
  KART_WORLD_BACKEND is not required; this script calls java query-ir directly.

Usage (WSL, READY HBase):
  python experiments/adapters/sag/align_world_oracle.py \\
    --workload experiments/workloads/bound_ir_v1.json \\
    --oracle experiments/workloads/bound_ir_v1.oracle.json \\
    --limit 5 --out experiments/results/world-align-smoke.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

_SHANGHAI = timezone(timedelta(hours=8))


def _resolve_jar(root: Path) -> Optional[str]:
    jar = os.environ.get("KART_JAR", "").strip()
    if jar and os.path.isfile(jar):
        return jar
    for rel in ("target/kart-0.1.0-SNAPSHOT.jar", "target/kart.jar"):
        p = root / rel
        if p.is_file():
            return str(p)
    return None


def _oracle_ids(ans: Any) -> Set[str]:
    if ans is None:
        return set()
    if isinstance(ans, dict):
        if "trajectory_ids" in ans:
            return set(str(x) for x in (ans.get("trajectory_ids") or []))
        if "ids" in ans:
            return set(str(x) for x in (ans.get("ids") or []))
        # nested answer
        inner = ans.get("answer") or ans.get("oracle")
        if isinstance(inner, dict):
            return _oracle_ids(inner)
    return set()


def _load_answers(ora: Any) -> Dict[str, Any]:
    if isinstance(ora, dict) and isinstance(ora.get("answers"), dict):
        return ora["answers"]
    out: Dict[str, Any] = {}
    # BuildOracleCacheCmd writes answers as a list of {query_id, trajectory_ids, ...}
    if isinstance(ora, dict) and isinstance(ora.get("answers"), list):
        for row in ora["answers"]:
            if isinstance(row, dict) and row.get("query_id"):
                out[str(row["query_id"])] = row
        return out
    if isinstance(ora, dict) and isinstance(ora.get("queries"), list):
        for row in ora["queries"]:
            if isinstance(row, dict) and row.get("query_id"):
                out[str(row["query_id"])] = row
        return out
    if isinstance(ora, list):
        for row in ora:
            if isinstance(row, dict) and row.get("query_id"):
                out[str(row["query_id"])] = row
    return out


def _ms_to_shanghai(ms: Any) -> Optional[str]:
    """Round-trip only whole seconds; Java parseShanghai has no millisecond pattern."""
    try:
        n = int(ms)
    except (TypeError, ValueError):
        return None
    if n % 1000 != 0:
        return None
    return datetime.fromtimestamp(n / 1000.0, tz=_SHANGHAI).strftime("%Y-%m-%dT%H:%M:%S")


def _looks_lonlat(spatial: dict) -> bool:
    try:
        xs = [float(spatial[k]) for k in ("min_x", "max_x")]
        ys = [float(spatial[k]) for k in ("min_y", "max_y")]
    except (KeyError, TypeError, ValueError):
        return False
    return all(-180.0 <= x <= 180.0 for x in xs) and all(-90.0 <= y <= 90.0 for y in ys)


def bound_query_to_draft(ir: dict) -> Optional[dict]:
    """DraftIR that Binder should turn back into the same BoundIR facts.

    Skips UTM rectangles and sub-second times, which cannot be expressed in DraftIR
    without changing the predicate the Oracle was built from.
    """
    if not isinstance(ir, dict):
        return None
    result = ir.get("result") if isinstance(ir.get("result"), dict) else None
    source = ir.get("source") if isinstance(ir.get("source"), dict) else None
    if not result or not source or not source.get("dataset_id"):
        return None
    mode = result.get("mode")
    if mode not in ("TRAJECTORY_IDS", "TOP_K"):
        return None
    if mode == "TOP_K":
        # reference_tid is an internal id; DraftIR needs the public trajectory id.
        return None
    draft: Dict[str, Any] = {
        "ir_version": "1.0",
        "query_id": ir.get("query_id"),
        "source": {
            "dataset_id": source.get("dataset_id"),
            "entity": source.get("entity") or "trajectory",
        },
        "semantics": {
            "mode": (ir.get("semantics") or {}).get("mode") or "OBSERVED_POINT",
            "coupling": (ir.get("semantics") or {}).get("coupling") or "SAME_POINT",
        },
        "result": {"mode": mode},
    }
    temporal = ir.get("temporal")
    if isinstance(temporal, dict) and temporal.get("start_ms") is not None:
        start = _ms_to_shanghai(temporal.get("start_ms"))
        end = _ms_to_shanghai(temporal.get("end_ms"))
        if not start or not end:
            return None
        draft["temporal"] = {"start": start, "end": end, "boundary": "[start,end)"}
    spatial = ir.get("spatial")
    if isinstance(spatial, dict):
        region = spatial.get("region_name")
        if isinstance(region, str) and region.strip():
            draft["spatial"] = {
                "region_name": region.strip(),
                "relation": "INTERSECTS",
                "boundary": "INCLUDED",
            }
        elif _looks_lonlat(spatial):
            draft["spatial"] = {
                "geometry": {
                    "type": "RECTANGLE",
                    "min_lon": spatial["min_x"],
                    "min_lat": spatial["min_y"],
                    "max_lon": spatial["max_x"],
                    "max_lat": spatial["max_y"],
                },
                "relation": "INTERSECTS",
                "boundary": "INCLUDED",
            }
        else:
            return None
    preds = []
    for p in ir.get("predicates") or []:
        if not isinstance(p, dict):
            continue
        if p.get("field") == "vehicle_id" and p.get("op") == "EQ" and p.get("value") is not None:
            preds.append({"field": "vehicle_id", "op": "EQ", "value": str(p["value"])})
        else:
            return None
    if preds:
        draft["predicates"] = preds
    return draft


def _run_kart_world(draft: dict) -> Dict[str, Any]:
    os.environ["KART_WORLD_BACKEND"] = "hbase"
    sag_dir = str(Path(__file__).resolve().parent)
    if sag_dir not in sys.path:
        sys.path.insert(0, sag_dir)
    from kart_world import KartWorldAccess

    world = KartWorldAccess()
    if not world.is_formal():
        return {
            "ok": False,
            "trajectory_ids": [],
            "status": "not_formal",
            "backend": world.backend,
            "stderr": "KART_WORLD_BACKEND=hbase did not select hbase_query_draft",
        }
    res = world.execute_draft(draft)
    tids = res.get("trajectory_ids") or []
    ok = bool(res.get("ok")) and str(res.get("backend") or "").startswith("hbase")
    return {
        "ok": ok,
        "trajectory_ids": [str(x) for x in tids],
        "status": res.get("status") or res.get("error"),
        "backend": res.get("backend"),
        "stderr": (res.get("stderr") or res.get("message") or "")[:500],
        "returncode": res.get("returncode"),
    }


def _run_query_ir(root: Path, jar: str, ir_obj: dict, manifest: str) -> Dict[str, Any]:
    fd, path = tempfile.mkstemp(prefix="align-ir-", suffix=".json")
    os.close(fd)
    try:
        Path(path).write_text(json.dumps(ir_obj), encoding="utf-8")
        cmd = [
            "java",
            "-Dkart.root=" + str(root),
            "-jar",
            jar,
            "query-ir",
            "--ir",
            path,
            "--manifest",
            manifest,
            "--config-root",
            str(root),
            "--policy",
            "rule",
            "--force-plan",
            os.environ.get("KART_WORLD_FORCE_PLAN", "P_FULL"),
        ]
        proc = subprocess.run(
            cmd,
            cwd=str(root),
            capture_output=True,
            text=True,
            timeout=300,
        )
        tids: List[str] = []
        status = None
        for line in (proc.stdout or "").splitlines():
            if line.startswith("trajectory_ids="):
                raw = line[len("trajectory_ids=") :].strip()
                if raw.startswith("["):
                    try:
                        tids = [str(x) for x in json.loads(raw.replace("'", '"'))]
                    except Exception:
                        # Java List toString: [a, b]
                        inner = raw.strip()[1:-1].strip()
                        tids = [x.strip() for x in inner.split(",") if x.strip()] if inner else []
            if line.startswith("status="):
                status = line.split("=", 1)[1].strip()
        return {
            "ok": proc.returncode == 0 and status == "OK",
            "trajectory_ids": tids,
            "status": status,
            "returncode": proc.returncode,
            "stderr": (proc.stderr or "")[:500],
        }
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--workload", required=True)
    ap.add_argument("--oracle", required=True)
    ap.add_argument("--limit", type=int, default=5)
    ap.add_argument("--manifest", default="tdrive_v1_ready")
    ap.add_argument(
        "--via",
        choices=["query-ir", "kart-world"],
        default="query-ir",
        help="kart-world: DraftIR → KartWorldAccess query-draft (KART_WORLD_BACKEND=hbase)",
    )
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    root = Path(__file__).resolve().parents[3]
    os.environ.setdefault("KART_ROOT", str(root))
    jar = _resolve_jar(root)
    report: Dict[str, Any] = {
        "mode": "kart_world_query_draft_vs_oracle" if args.via == "kart-world"
        else "bound_ir_query_ir_vs_oracle",
        "via": args.via,
        "jar": jar,
        "compared": [],
        "skipped": [],
        "n_ok": 0,
        "n_fail": 0,
        "aligned": False,
    }
    if not jar:
        report["error"] = "KART_JAR / target jar missing"
        print(json.dumps(report, indent=2))
        return 2

    wl = json.loads(Path(args.workload).read_text(encoding="utf-8"))
    ora = json.loads(Path(args.oracle).read_text(encoding="utf-8"))
    answers = _load_answers(ora)
    queries = wl.get("queries") if isinstance(wl, dict) else wl

    n = 0
    for q in queries:
        if n >= args.limit:
            break
        if not isinstance(q, dict):
            continue
        qid = q.get("query_id")
        ir = q.get("ir") if isinstance(q.get("ir"), dict) else q
        if not qid or not isinstance(ir, dict):
            continue
        draft = None
        if args.via == "kart-world":
            draft = bound_query_to_draft(ir)
            if draft is None:
                report["skipped"].append({"query_id": qid, "reason": "not_draft_roundtrip"})
                continue
        gold = _oracle_ids(answers.get(qid))
        if args.via == "kart-world":
            res = _run_kart_world(draft)
        else:
            res = _run_query_ir(root, jar, ir, args.manifest)
        got = set(str(x) for x in (res.get("trajectory_ids") or []))
        match = got == gold
        entry = {
            "query_id": qid,
            "match": match,
            "ok": res.get("ok"),
            "n_gold": len(gold),
            "n_got": len(got),
            "status": res.get("status"),
            "backend": res.get("backend"),
        }
        report["compared"].append(entry)
        if match and res.get("ok"):
            report["n_ok"] += 1
        else:
            report["n_fail"] += 1
        n += 1

    report["aligned"] = report["n_fail"] == 0 and report["n_ok"] > 0
    report["force_plan"] = os.environ.get("KART_WORLD_FORCE_PLAN", "P_FULL")
    if args.via == "kart-world":
        report["note"] = (
            "Pass means KartWorldAccess (KART_WORLD_BACKEND=hbase, java query-draft, "
            "force P_FULL) matches FullScan Oracle on DraftIR round-trips. "
            "sag-mql feedback may be enabled only after this gate."
        )
    else:
        report["note"] = (
            "Pass means query-ir force P_FULL matches FullScan Oracle on sample. "
            "Enable sag-mql only after the kart-world gate with KART_WORLD_BACKEND=hbase."
        )
    text = json.dumps(report, indent=2, ensure_ascii=False)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    return 0 if report["aligned"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
