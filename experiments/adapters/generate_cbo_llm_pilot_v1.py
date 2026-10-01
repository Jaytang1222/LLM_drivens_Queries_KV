#!/usr/bin/env python3
"""
Generate bound_ir_cbo_llm_pilot_v1 candidate pool and (after screening) freeze workload.

Seed: 2026-09-28-cbo-llm-pilot-v1
Does NOT run the new LLM arm during selection.
Offline screening is scripts/screen-cbo-llm-pilot.sh (CBO/Bao/constructable/exec).
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]
WL = ROOT / "experiments/workloads"
SEED = "2026-09-28-cbo-llm-pilot-v1"
GEN_VERSION = "generate_cbo_llm_pilot_v1.py/v1"

ADV2 = WL / "bound_ir_advantage_v2.json"
V1 = WL / "bound_ir_v1.json"
HOLD_TEST = WL / "bound_ir_holdout_v2_test.json"

CAND_OUT = WL / "bound_ir_cbo_llm_pilot_v1.candidates.json"
SCREEN_LOG = WL / "bound_ir_cbo_llm_pilot_v1.screen_log.json"
PILOT_OUT = WL / "bound_ir_cbo_llm_pilot_v1.json"
PILOT_ORA = WL / "bound_ir_cbo_llm_pilot_v1.oracle.json"
PILOT_PROV = WL / "bound_ir_cbo_llm_pilot_v1.provenance.json"


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def save(p: Path, obj: Any) -> None:
    p.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def ir_fingerprint(q: dict) -> str:
    body = {k: v for k, v in q.items() if k != "query_id"}
    blob = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def collect_blocked_fps() -> Set[str]:
    blocked: Set[str] = set()
    for p in (ADV2, V1, HOLD_TEST):
        if not p.is_file():
            continue
        for q in load(p).get("queries", []):
            blocked.add(ir_fingerprint(q))
    return blocked


def clone_mut(src: dict, new_id: str, category: str, reason: str, **mut: Any) -> dict:
    q = copy.deepcopy(src)
    q["query_id"] = new_id
    for k, v in mut.items():
        if k == "temporal" and isinstance(v, dict) and isinstance(q.get("temporal"), dict):
            q["temporal"].update(v)
        elif k == "spatial" and isinstance(v, dict) and isinstance(q.get("spatial"), dict):
            q["spatial"].update(v)
        elif k == "predicates" and isinstance(v, list):
            q["predicates"] = copy.deepcopy(v)
        else:
            q[k] = copy.deepcopy(v)
    q["_pilot_meta"] = {
        "category": category,
        "reason": reason,
        "source_query_id": src.get("query_id"),
        "seed": SEED,
    }
    return q


def build_candidate_pool() -> List[dict]:
    """Deterministic pool ordered by (category priority, query_id)."""
    adv = load(ADV2)
    v1 = load(V1)
    by_id = {q["query_id"]: q for q in adv.get("queries", []) + v1.get("queries", [])}
    blocked = collect_blocked_fps()
    pool: List[dict] = []

    def add(q: dict) -> None:
        fp = ir_fingerprint({k: v for k, v in q.items() if k != "_pilot_meta"})
        if fp in blocked:
            return
        # also skip if duplicate within pool
        for existing in pool:
            body = {k: v for k, v in existing.items() if k != "_pilot_meta"}
            if ir_fingerprint(body) == fp:
                return
        pool.append(q)

    # --- CBO miss candidates: TZ/TZH templates shifted so SORT_MERGE stays constructable ---
    tz_sources = [
        "st_ss_1", "st_sl_1", "st_ls_1", "st_ll_1", "h_st_1",
        "a2_tz_hard_a", "a2_tz_hard_b", "a2_tz_hard_c", "a2_tz_hard_d",
        "a2_tz_hard_e", "a2_tz_hard_f",
    ]
    for i, sid in enumerate(tz_sources):
        src = by_id.get(sid)
        if not src or not src.get("temporal") or not src.get("spatial"):
            continue
        # Shift window by (i+1)*60s — new fingerprint, same family
        t0 = int(src["temporal"]["start_ms"]) + (i + 1) * 60_000
        t1 = int(src["temporal"]["end_ms"]) + (i + 1) * 60_000
        add(clone_mut(
            src, f"pilot_cbo_miss_{i+1:02d}", "cbo_candidate_miss",
            "TZ/TZH template; expect constructable SORT_MERGE not always in CBO safe set",
            temporal={"start_ms": t0, "end_ms": t1},
        ))

    # --- Bao P_FULL TZH candidates ---
    tzh_sources = [
        "h_st_1", "h_st_2", "h_st_3", "h_st_4",
        "a2_tzh_a", "a2_tzh_b", "a2_tzh_c", "a2_tzh_d",
        "a2_tzh_e", "a2_tzh_f", "a2_tzh_g", "a2_tzh_h",
    ]
    for i, sid in enumerate(tzh_sources):
        src = by_id.get(sid)
        if not src:
            continue
        # Need H predicate
        preds = src.get("predicates") or []
        has_h = any(
            isinstance(p, dict) and p.get("field") == "vehicle_id" and p.get("op") == "EQ"
            for p in preds
        )
        if not (src.get("temporal") and src.get("spatial") and has_h):
            continue
        t0 = int(src["temporal"]["start_ms"]) + (i + 3) * 90_000
        t1 = int(src["temporal"]["end_ms"]) + (i + 3) * 90_000
        add(clone_mut(
            src, f"pilot_bao_tzh_{i+1:02d}", "bao_tzh_full_risk",
            "TZH template where Bao surrogate often selects P_FULL; index plans exist",
            temporal={"start_ms": t0, "end_ms": t1},
        ))

    # --- Normal control: small TZ with clear CBO index plan ---
    ctrl_src = by_id.get("st_ss_1") or by_id.get("st_ss_2")
    if ctrl_src:
        t0 = int(ctrl_src["temporal"]["start_ms"]) + 15 * 60_000
        t1 = int(ctrl_src["temporal"]["end_ms"]) + 15 * 60_000
        add(clone_mut(
            ctrl_src, "pilot_control_01", "normal_control",
            "Small TZ control; CBO should already have a safe index plan; whitelist may be empty or redundant",
            temporal={"start_ms": t0, "end_ms": t1},
        ))

    # Stable order
    cat_rank = {"cbo_candidate_miss": 0, "bao_tzh_full_risk": 1, "normal_control": 2}
    pool.sort(key=lambda q: (cat_rank.get(q["_pilot_meta"]["category"], 9), q["query_id"]))
    return pool


def strip_meta(q: dict) -> dict:
    return {k: v for k, v in q.items() if k != "_pilot_meta"}


def write_candidates() -> dict:
    pool = build_candidate_pool()
    doc = {
        "seed": SEED,
        "generator": GEN_VERSION,
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "n_candidates": len(pool),
        "note": "Offline screen before freezing. Do not select by new LLM arm outcomes.",
        "candidates": [
            {
                "query": strip_meta(q),
                "category": q["_pilot_meta"]["category"],
                "reason": q["_pilot_meta"]["reason"],
                "source_query_id": q["_pilot_meta"]["source_query_id"],
                "fingerprint": ir_fingerprint(strip_meta(q)),
            }
            for q in pool
        ],
    }
    save(CAND_OUT, doc)
    return doc


def select_from_screen(screen: dict) -> Tuple[List[dict], dict]:
    """Apply §2.2 quotas without lowering standards."""
    rows = screen.get("rows") or []
    by_cat: Dict[str, List[dict]] = {
        "cbo_candidate_miss": [],
        "bao_tzh_full_risk": [],
        "normal_control": [],
    }
    for r in rows:
        cat = r.get("category")
        if cat in by_cat and r.get("keep"):
            by_cat[cat].append(r)

    selected: List[dict] = []
    disclosure = {
        "requested": {"cbo_candidate_miss": 2, "bao_tzh_full_risk": 2, "normal_control": 1},
        "actual": {},
        "shortfalls": [],
    }

    def take(cat: str, n: int) -> None:
        got = by_cat[cat][:n]
        disclosure["actual"][cat] = len(got)
        if len(got) < n:
            disclosure["shortfalls"].append({
                "category": cat,
                "wanted": n,
                "got": len(got),
                "reason": "insufficient offline-qualified candidates; not filling with lower-standard queries",
            })
        for r in got:
            selected.append(r["query"])

    take("cbo_candidate_miss", 2)
    take("bao_tzh_full_risk", 2)
    take("normal_control", 1)
    return selected, disclosure


def freeze_pilot(screen_path: Path, oracle_path: Optional[Path]) -> None:
    screen = load(screen_path)
    if screen.get("screen_mode") != "live_full_safe_set_validated":
        raise SystemExit("Refusing to freeze: live full-safe-set screening is required")
    queries, disclosure = select_from_screen(screen)
    if not queries:
        raise SystemExit("No queries selected from screen log — refusing empty pilot freeze")

    base = load(ADV2)
    out = {
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "workload_id": "bound_ir_cbo_llm_pilot_v1",
        "evaluation_role": "cbo_llm_mechanism_diagnostic",
        "seed": SEED,
        "anchor": base.get("anchor"),
        "queries": queries,
        "selection_disclosure": disclosure,
    }
    save(PILOT_OUT, out)

    ora = {"answers": []}
    if oracle_path and oracle_path.is_file():
        src = load(oracle_path)
        answers = src.get("answers") or []
        by_id = {a["query_id"]: a for a in answers if isinstance(a, dict) and a.get("query_id")}
        for q in queries:
            qid = q["query_id"]
            if qid in by_id:
                ora["answers"].append(by_id[qid])
    save(PILOT_ORA, ora)

    present_ora = {a["query_id"] for a in ora["answers"]}
    missing_ora = [q["query_id"] for q in queries if q["query_id"] not in present_ora]
    prov = {
        "path": str(PILOT_OUT.relative_to(ROOT)).replace("\\", "/"),
        "oracle_path": str(PILOT_ORA.relative_to(ROOT)).replace("\\", "/"),
        "evaluation_role": "cbo_llm_mechanism_diagnostic",
        "seed": SEED,
        "generator": GEN_VERSION,
        "n_queries": len(queries),
        "selection_disclosure": disclosure,
        "screen_log": str(SCREEN_LOG.relative_to(ROOT)).replace("\\", "/") if SCREEN_LOG.is_file() else None,
        "screen_sha256": sha256_file(screen_path),
        "candidate_pool_sha256": sha256_file(CAND_OUT),
        "sha256": sha256_file(PILOT_OUT),
        "oracle_sha256": sha256_file(PILOT_ORA),
        "oracle_status": "frozen" if not missing_ora else "pending_fullscan_fill",
        "oracle_missing_query_ids": missing_ora,
        "notes": [
            "Mechanism diagnostic set only; not a paper-level performance sample.",
            "Selected without running cbo-llm-proposal outcomes.",
        ],
    }
    save(PILOT_PROV, prov)
    print(json.dumps({"frozen": len(queries), "disclosure": disclosure, "oracle_missing": missing_ora}, indent=2))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-candidates", action="store_true")
    ap.add_argument("--freeze-from-screen", type=Path, default=None)
    ap.add_argument("--oracle", type=Path, default=None, help="Oracle JSON after FullScan fill")
    args = ap.parse_args()
    if args.write_candidates:
        doc = write_candidates()
        print(f"Wrote {CAND_OUT} n={doc['n_candidates']}")
        return
    if args.freeze_from_screen:
        freeze_pilot(args.freeze_from_screen, args.oracle)
        return
    ap.print_help()


if __name__ == "__main__":
    main()
