#!/usr/bin/env python3
"""
Build bound_ir_cbo_opportunity_verify_v1 from corrected census + advantage_v2 oracles.

Development cases (4 stable search-miss queries) are isolated — not in verify set.
Large e2e-opportunity quota may be vacant if no savings >= provisional hybrid overhead.
Holdout test set is never used.
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
SEED = "2026-09-28-cbo-opportunity-verify-v1"
GEN = "generate_cbo_opportunity_verify_v1.py/v1"
HYBRID_EXTRA_MS = 900.0

ADV2 = WL / "bound_ir_advantage_v2.json"
ADV2_ORA = WL / "bound_ir_advantage_v2.oracle.json"
HOLD = WL / "bound_ir_holdout_v2_test.json"
V1 = WL / "bound_ir_v1.json"
CENSUS_CORRECTED = ROOT / "experiments/opportunity/opportunity-dev-v1/corrected/summary.json"

OUT = WL / "bound_ir_cbo_opportunity_verify_v1.json"
OUT_ORA = WL / "bound_ir_cbo_opportunity_verify_v1.oracle.json"
OUT_PROV = WL / "bound_ir_cbo_opportunity_verify_v1.provenance.json"
SCREEN = WL / "bound_ir_cbo_opportunity_verify_v1.screen_log.json"

DEV_CASES = {
    "a2_tz_hard_d",
    "a2_topk_dtw_wide",
    "topk_st_3",
    "a2_tz_hard_a",
}


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


def template_group(qid: str) -> str:
    # coarse grouping by id prefix / family
    for pref in ("a2_tz_hard", "a2_topk", "topk_st", "st_ss", "st_sl", "st_ls", "st_ll", "tz_", "tzh_"):
        if qid.startswith(pref):
            return pref
    parts = qid.rsplit("_", 1)
    return parts[0] if parts else qid


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--census-summary", type=Path, default=CENSUS_CORRECTED)
    args = ap.parse_args()

    adv = load(ADV2)
    ora = load(ADV2_ORA)
    answers = {a["query_id"]: a for a in ora.get("answers", [])}
    by_id = {q["query_id"]: q for q in adv.get("queries", [])}

    hold_fps: Set[str] = set()
    if HOLD.is_file():
        for q in load(HOLD).get("queries", []):
            hold_fps.add(ir_fingerprint(q))

    # Development isolation: fingerprints + template groups of the 4 cases
    blocked_fps: Set[str] = set(hold_fps)
    blocked_groups: Set[str] = set()
    for qid in DEV_CASES:
        q = by_id.get(qid)
        if q:
            blocked_fps.add(ir_fingerprint(q))
            blocked_groups.add(template_group(qid))

    census = load(args.census_summary) if args.census_summary.is_file() else {}
    stable = {s["query_id"]: s for s in census.get("stable_opportunities") or []}
    # Also index per-query labels from corrected summary
    qinfo = {q["query_id"]: q for q in census.get("queries") or []}

    screen_rows: List[dict] = []
    selected: List[dict] = []
    vacancies: List[dict] = []

    def try_add(qid: str, category: str, reason: str) -> bool:
        q = by_id.get(qid)
        if not q:
            screen_rows.append({"query_id": qid, "keep": False, "reason": "not_in_advantage_v2"})
            return False
        if qid not in answers:
            screen_rows.append({"query_id": qid, "keep": False, "reason": "missing_oracle"})
            return False
        fp = ir_fingerprint(q)
        if fp in blocked_fps or fp in hold_fps:
            screen_rows.append({"query_id": qid, "keep": False, "reason": "blocked_fingerprint"})
            return False
        tg = template_group(qid)
        if tg in blocked_groups and category != "normal_control":
            # allow controls from other groups; still block exact FPS
            pass
        if any(ir_fingerprint(x["query"]) == fp for x in selected):
            screen_rows.append({"query_id": qid, "keep": False, "reason": "dup_in_selected"})
            return False
        # isolate near-neighbor of development template groups for opportunity cats
        if category in ("large_opportunity", "cost_misestimate") and tg in blocked_groups:
            screen_rows.append({"query_id": qid, "keep": False, "reason": f"dev_template_group:{tg}"})
            return False
        qq = copy.deepcopy(q)
        # verify IDs distinct from source
        new_id = f"cov_{category[:4]}_{qid}"
        qq["query_id"] = new_id
        selected.append(
            {
                "query": qq,
                "category": category,
                "reason": reason,
                "source_query_id": qid,
                "fingerprint": ir_fingerprint(qq),
                "template_group": tg,
                "oracle_source_query_id": qid,
            }
        )
        blocked_fps.add(fp)
        screen_rows.append({"query_id": qid, "keep": True, "category": category, "reason": reason})
        return True

    # --- Quota 1: large opportunity (savings >= hybrid extra) — expected vacant ---
    large = [
        s
        for s in (census.get("stable_opportunities") or [])
        if (s.get("saving_median_ms") or 0) >= HYBRID_EXTRA_MS and s["query_id"] not in DEV_CASES
    ]
    n_large = 0
    for s in large:
        if n_large >= 2:
            break
        if try_add(
            s["query_id"],
            "large_opportunity",
            f"stable median saving {s['saving_median_ms']} >= {HYBRID_EXTRA_MS}",
        ):
            n_large += 1
    if n_large < 2:
        vacancies.append(
            {
                "category": "large_opportunity",
                "requested": 2,
                "filled": n_large,
                "reason": f"no non-dev stable savings >= {HYBRID_EXTRA_MS} ms in census; "
                f"max stable was {max((s.get('saving_median_ms') or 0) for s in (census.get('stable_opportunities') or [0]))}",
            }
        )

    # --- Quota 2: cost misestimate (stable miss but savings < hybrid) outside dev groups ---
    n_cost = 0
    for s in census.get("stable_opportunities") or []:
        if n_cost >= 2:
            break
        if s["query_id"] in DEV_CASES:
            continue
        if (s.get("saving_median_ms") or 0) >= HYBRID_EXTRA_MS:
            continue
        if try_add(
            s["query_id"],
            "cost_misestimate",
            f"stable miss {s['plan_id']} median_saving={s['saving_median_ms']} (below hybrid overhead)",
        ):
            n_cost += 1
    if n_cost < 2:
        vacancies.append(
            {
                "category": "cost_misestimate",
                "requested": 2,
                "filled": n_cost,
                "reason": "insufficient non-dev stable cost-misestimate cases after template isolation",
            }
        )

    # --- Quota 3: semantic boundary (empty / topk / edge) with oracle ---
    semantic_prefer = [
        qid
        for qid, q in by_id.items()
        if qid not in DEV_CASES
        and (
            (q.get("result") or {}).get("mode") == "TOP_K"
            or "empty" in qid
            or "bound" in qid
            or qid.startswith("st_ss")
        )
    ]
    # Prefer queries labeled no_faster in census
    semantic_ordered = []
    for qid in semantic_prefer:
        info = qinfo.get(qid) or {}
        labs = info.get("labels") or []
        if "no_faster_safe_plan" in labs or not labs:
            semantic_ordered.append(qid)
    for qid in by_id:
        if qid not in semantic_ordered and qid not in DEV_CASES:
            semantic_ordered.append(qid)
    n_sem = 0
    for qid in semantic_ordered:
        if n_sem >= 2:
            break
        if try_add(qid, "semantic_boundary", "legal BoundIR boundary/control semantics with oracle"):
            n_sem += 1
    if n_sem < 2:
        vacancies.append(
            {"category": "semantic_boundary", "requested": 2, "filled": n_sem, "reason": "insufficient"}
        )

    # --- Quota 4: normal controls (no_faster_safe_plan) ---
    n_ctrl = 0
    for qid, info in qinfo.items():
        if n_ctrl >= 2:
            break
        if qid in DEV_CASES:
            continue
        labs = info.get("labels") or []
        if "no_faster_safe_plan" not in labs:
            continue
        if try_add(qid, "normal_control", "census: no_faster_safe_plan; CBO already adequate"):
            n_ctrl += 1
    if n_ctrl < 2:
        # fallback any remaining with oracle
        for qid in by_id:
            if n_ctrl >= 2:
                break
            if try_add(qid, "normal_control", "fallback control with oracle"):
                n_ctrl += 1
    if n_ctrl < 2:
        vacancies.append(
            {"category": "normal_control", "requested": 2, "filled": n_ctrl, "reason": "insufficient"}
        )

    queries = [s["query"] for s in selected]
    # Build oracle remapped to new ids
    ora_answers = []
    for s in selected:
        src = answers[s["oracle_source_query_id"]]
        a = copy.deepcopy(src)
        a["query_id"] = s["query"]["query_id"]
        ora_answers.append(a)

    workload = {
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "workload_id": "bound_ir_cbo_opportunity_verify_v1",
        "evaluation_role": "cbo_opportunity_mechanism_verify",
        "seed": SEED,
        "generator": GEN,
        "note": "Mechanism verify set; not frequency/overall win-rate sample. Development cases excluded.",
        "queries": queries,
    }
    oracle = {
        "oracle_status": "frozen",
        "source": "remapped_from_bound_ir_advantage_v2.oracle.json_via_FullScan_answers",
        "answers": ora_answers,
    }
    provenance = {
        "path": str(OUT.relative_to(ROOT)).replace("\\", "/"),
        "oracle_path": str(OUT_ORA.relative_to(ROOT)).replace("\\", "/"),
        "seed": SEED,
        "generator": GEN,
        "hybrid_extra_plan_ms_provisional": HYBRID_EXTRA_MS,
        "development_cases_isolated": sorted(DEV_CASES),
        "blocked_template_groups": sorted(blocked_groups),
        "vacancies": vacancies,
        "n_queries": len(queries),
        "categories": {
            c: sum(1 for s in selected if s["category"] == c)
            for c in ("large_opportunity", "cost_misestimate", "semantic_boundary", "normal_control")
        },
        "selected": [
            {
                "query_id": s["query"]["query_id"],
                "category": s["category"],
                "source_query_id": s["source_query_id"],
                "fingerprint": s["fingerprint"],
                "template_group": s["template_group"],
                "reason": s["reason"],
            }
            for s in selected
        ],
        "holdout_excluded": True,
        "sha256": {},
    }

    save(OUT, workload)
    save(OUT_ORA, oracle)
    save(SCREEN, {"seed": SEED, "rows": screen_rows, "vacancies": vacancies})
    provenance["sha256"] = {
        "workload": sha256_file(OUT),
        "oracle": sha256_file(OUT_ORA),
        "screen_log": sha256_file(SCREEN),
        "advantage_v2": sha256_file(ADV2),
        "advantage_v2_oracle": sha256_file(ADV2_ORA),
        "census_corrected": sha256_file(args.census_summary) if args.census_summary.is_file() else None,
    }
    save(OUT_PROV, provenance)
    print(json.dumps({"n_queries": len(queries), "categories": provenance["categories"], "vacancies": vacancies}, indent=2))
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
