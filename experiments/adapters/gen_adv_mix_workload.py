#!/usr/bin/env python3
"""Balanced ADV mix: classic-optimal + per-family ADV-optimal pockets. Data unchanged.

Roles (force_mix evidence on tdrive_v1_ready):
  frag_aoi / ctrl_fat / ctrl_short → classic P_Z (or TZ)
  ctrl_veh → classic P_H
  adv_wide / adv_mega (fat ≥12h) → P_TS / P_XZ wall-clock winners
  adv_hyb (6–12h) → HYBRID inject P_XZ + P_XZTXZ
"""
import json
from pathlib import Path

SRC = Path("experiments/workloads/bound_ir_complex_v1.json")
FRAG = Path("experiments/workloads/bound_ir_adv_frag_v1.json")
OUT = Path("experiments/workloads/bound_ir_adv_mix_v1.json")


def base_q(q, qid):
    return {
        "ir_version": "1.0",
        "query_id": qid,
        "source": q["source"],
        "predicates": [],
        "semantics": q.get("semantics", {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"}),
        "result": {"mode": "TRAJECTORY_IDS", "tie_breaker": "TID_ASC"},
        "snapshot": q["snapshot"],
    }


def expand_spatial(s, factor=3.0):
    cx = 0.5 * (s["min_x"] + s["max_x"])
    cy = 0.5 * (s["min_y"] + s["max_y"])
    hx = 0.5 * (s["max_x"] - s["min_x"]) * factor
    hy = 0.5 * (s["max_y"] - s["min_y"]) * factor
    return {
        "min_x": cx - hx,
        "min_y": cy - hy,
        "max_x": cx + hx,
        "max_y": cy + hy,
        "relation": "INTERSECTS",
        "boundary": "INCLUDED",
    }


def shrink_temporal(t, span_ms):
    mid = (t["start_ms"] + t["end_ms"]) // 2
    half = span_ms // 2
    return {"start_ms": mid - half, "end_ms": mid - half + span_ms}


def main():
    frag = json.loads(FRAG.read_text(encoding="utf-8"))
    data = json.loads(SRC.read_text(encoding="utf-8"))

    queries = [q for q in frag["queries"] if str(q["query_id"]).startswith("frag_aoi_")][:4]

    fat = []
    for q in data["queries"]:
        t, s = q.get("temporal"), q.get("spatial")
        if not t or not s:
            continue
        if q.get("similarity") or (q.get("result") or {}).get("mode") == "TOP_K":
            continue
        span = t["end_ms"] - t["start_ms"]
        if span < 12 * 3600 * 1000:
            continue
        area = (s["max_x"] - s["min_x"]) * (s["max_y"] - s["min_y"])
        fat.append((area, q))
    fat.sort(key=lambda x: -x[0])

    # Fat wide/mega: force_mix winners were P_TS (wide) and P_XZ/P_TS (mega).
    for i, (_, q) in enumerate(fat[:4], 1):
        qw = base_q(q, f"adv_wide_{i}")
        qw["temporal"] = dict(q["temporal"])
        qw["spatial"] = expand_spatial(q["spatial"], 3.0)
        queries.append(qw)
    for i, (_, q) in enumerate(fat[:2], 1):
        qw = base_q(q, f"adv_mega_{i}")
        qw["temporal"] = dict(q["temporal"])
        qw["spatial"] = expand_spatial(q["spatial"], 5.0)
        queries.append(qw)

    # Hybrid 8–10h + large bbox: Z index blows up; XZT∩XZ should win wall-clock.
    for i, (_, q) in enumerate(fat[:3], 1):
        qh = base_q(q, f"adv_hyb_{i}")
        qh["temporal"] = shrink_temporal(q["temporal"], (7 + i) * 3600 * 1000)
        qh["spatial"] = expand_spatial(q["spatial"], 4.5)
        queries.append(qh)
    # XZT-family pocket: same idea, slightly fatter time window.
    for i, (_, q) in enumerate(fat[:2], 1):
        qx = base_q(q, f"adv_xzt_{i}")
        qx["temporal"] = shrink_temporal(q["temporal"], 10 * 3600 * 1000)
        qx["spatial"] = expand_spatial(q["spatial"], 5.0)
        queries.append(qx)

    # Classic-optimal controls.
    for i, (_, q) in enumerate(fat[:2], 1):
        qc = base_q(q, f"ctrl_fat_{i}")
        qc["temporal"] = dict(q["temporal"])
        qc["spatial"] = dict(q["spatial"])
        queries.append(qc)

    q0 = fat[0][1]
    qs = base_q(q0, "ctrl_short_1")
    qs["spatial"] = dict(q0["spatial"])
    qs["temporal"] = shrink_temporal(q0["temporal"], 2 * 3600 * 1000)
    queries.append(qs)

    qv = base_q(q0, "ctrl_veh_1")
    qv["temporal"] = dict(q0["temporal"])
    qv["spatial"] = dict(q0["spatial"])
    qv["predicates"] = [{"field": "vehicle_id", "op": "EQ", "value": "1"}]
    queries.append(qv)

    out = {
        "workload_id": "bound_ir_adv_mix_v1",
        "manifest_id": frag.get("manifest_id", "tdrive_v1_ready"),
        "evaluation_role": "adv_mix_classic_and_all_adv_families",
        "queries": queries,
    }
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {OUT} n={len(queries)}")
    for q in queries:
        t = q.get("temporal") or {}
        span_h = (t["end_ms"] - t["start_ms"]) / 3.6e6 if t else None
        print(" ", q["query_id"], "span_h=%.2f" % span_h if span_h is not None else "")


if __name__ == "__main__":
    main()
