#!/usr/bin/env python3
"""Expand bound_ir_v1 / nl_ir_v1 to formal comparative scale (seeded, layered)."""
from __future__ import annotations

import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BOUND = ROOT / "experiments/workloads/bound_ir_v1.json"
NL = ROOT / "experiments/workloads/nl_ir_v1.json"

HOUR = 3_600_000
DAY = 86_400_000


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def save(p: Path, obj: dict) -> None:
    p.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def by_id(queries):
    return {q["query_id"]: q for q in queries}


def clone_q(src: dict, new_id: str, **mut) -> dict:
    q = copy.deepcopy(src)
    q["query_id"] = new_id
    for k, v in mut.items():
        if k == "temporal" and isinstance(v, dict) and q.get("temporal"):
            q["temporal"].update(v)
        elif k == "similarity" and isinstance(v, dict):
            if q.get("similarity") is None:
                q["similarity"] = {}
            q["similarity"].update(v)
        elif k == "result" and isinstance(v, dict):
            q["result"].update(v)
        elif k == "predicates":
            q["predicates"] = v
        else:
            q[k] = v
    return q


def expand_bound(wl: dict) -> dict:
    qs = wl["queries"]
    idx = by_id(qs)
    catalog = wl.setdefault("catalog", {})
    added = []

    def add(q, fam, sel, **extra):
        if q["query_id"] in idx:
            return
        qs.append(q)
        idx[q["query_id"]] = q
        catalog[q["query_id"]] = {"family": fam, "selectivity": sel, **extra}
        added.append(q["query_id"])

    # Mid-selectivity T windows (±2h around small)
    t1 = idx["t_small_1"]
    add(
        clone_q(t1, "t_mid_1", temporal={"start_ms": t1["temporal"]["start_ms"] - 2 * HOUR,
                                         "end_ms": t1["temporal"]["end_ms"] + 2 * HOUR}),
        "T", "medium",
    )
    add(
        clone_q(t1, "t_mid_2", temporal={"start_ms": t1["temporal"]["start_ms"] - HOUR,
                                         "end_ms": t1["temporal"]["end_ms"] + 3 * HOUR}),
        "T", "medium",
    )
    # Half-open boundary: same start, end = start of small (often empty or tiny)
    add(
        clone_q(t1, "t_boundary_end_1", temporal={"start_ms": t1["temporal"]["start_ms"],
                                                  "end_ms": t1["temporal"]["start_ms"]}),
        "T", "boundary", notes="zero-width half-open interval",
    )
    add(
        clone_q(t1, "t_shift_1h", temporal={"start_ms": t1["temporal"]["start_ms"] + HOUR,
                                           "end_ms": t1["temporal"]["end_ms"] + HOUR}),
        "T", "small",
    )
    add(
        clone_q(idx["t_large_1"], "t_large_3", temporal={
            "start_ms": idx["t_large_1"]["temporal"]["start_ms"] - DAY,
            "end_ms": idx["t_large_1"]["temporal"]["end_ms"] - DAY,
        }),
        "T", "large",
    )

    # Spatial mid / boundary
    s1 = idx["s_small_1"]
    add(clone_q(s1, "s_mid_1"), "Z", "medium", notes="clone small spatial template")
    # Slightly expanded box if spatial present
    if s1.get("spatial"):
        sp = copy.deepcopy(s1["spatial"])
        pad = 500.0
        sp["min_x"] = sp["min_x"] - pad
        sp["min_y"] = sp["min_y"] - pad
        sp["max_x"] = sp["max_x"] + pad
        sp["max_y"] = sp["max_y"] + pad
        add(clone_q(s1, "s_mid_2", spatial=sp), "Z", "medium")

    # TZ mid + RBO traps (T+Z+H)
    st = idx["st_ss_1"]
    hst = idx["h_st_1"]
    add(clone_q(st, "st_mid_1", temporal={
        "start_ms": st["temporal"]["start_ms"] - HOUR,
        "end_ms": st["temporal"]["end_ms"] + HOUR,
    }), "TZ", "medium")
    add(clone_q(hst, "h_st_2", temporal={
        "start_ms": hst["temporal"]["start_ms"] - HOUR,
        "end_ms": hst["temporal"]["end_ms"] + HOUR,
    }), "TZH", "small", rbo_trap=True, notes="RBO maps T+Z+H to P_TZ")
    add(clone_q(hst, "h_st_3", temporal={
        "start_ms": hst["temporal"]["start_ms"] + 30 * 60 * 1000,
        "end_ms": hst["temporal"]["end_ms"] + 30 * 60 * 1000,
    }), "TZH", "small", rbo_trap=True)

    # TH / ZH extras
    add(clone_q(idx["h_t_small"], "h_t_mid", temporal={
        "start_ms": idx["h_t_small"]["temporal"]["start_ms"] - 2 * HOUR,
        "end_ms": idx["h_t_small"]["temporal"]["end_ms"] + 2 * HOUR,
    }), "TH", "medium")
    add(clone_q(idx["h_s_small"], "h_s_2"), "ZH", "small")

    # Top-K k variants + metric variants
    tk = idx["topk_st_1"]
    add(clone_q(tk, "topk_st_k1", result={"k": 1}), "TZ", "small", notes="DTW k=1")
    add(clone_q(tk, "topk_st_k5", result={"k": 5}), "TZ", "small", notes="DTW k=5")
    add(clone_q(idx["topk_st_frechet_1"], "topk_st_frechet_k5", result={"k": 5}),
        "TZ", "small", notes="FRECHET k=5")
    add(clone_q(idx["topk_st_hausdorff_1"], "topk_st_hausdorff_k1", result={"k": 1}),
        "TZ", "small", notes="HAUSDORFF k=1")
    add(clone_q(idx["topk_s_1"], "topk_s_k5", result={"k": 5}), "Z", "small", notes="DTW spatial k=5")

    # More empties
    add(clone_q(idx["empty_far_1"], "empty_far_2", temporal={
        "start_ms": 1262304000000,
        "end_ms": 1262307600000,
    }), "T", "empty", notes="2010 window outside snapshot")
    add(clone_q(idx["empty_far_1"], "empty_far_3", temporal={
        "start_ms": 946684800000,
        "end_ms": 946688400000,
    }), "T", "empty", notes="2000 window outside snapshot")

    # Extra large TZ
    add(clone_q(idx["st_ll_1"], "st_ll_2", temporal={
        "start_ms": idx["st_ll_1"]["temporal"]["start_ms"] - DAY,
        "end_ms": idx["st_ll_1"]["temporal"]["end_ms"],
    }), "TZ", "large")

    # H-only extras
    add(clone_q(idx["h_eq_1"], "h_eq_2"), "H", "small")

    # Second wave to reach formal 60+ scale
    for i, off in enumerate([2 * HOUR, 4 * HOUR, 6 * HOUR, 8 * HOUR], start=1):
        add(clone_q(t1, f"t_grid_{i}", temporal={
            "start_ms": t1["temporal"]["start_ms"] + off,
            "end_ms": t1["temporal"]["end_ms"] + off,
        }), "T", "small")
    for i, off in enumerate([HOUR, 3 * HOUR, 5 * HOUR], start=1):
        add(clone_q(idx["t_large_2"], f"t_large_grid_{i}", temporal={
            "start_ms": idx["t_large_2"]["temporal"]["start_ms"] + off,
            "end_ms": idx["t_large_2"]["temporal"]["end_ms"] + off,
        }), "T", "large")
    for i, pad in enumerate([200.0, 800.0, 1500.0], start=1):
        sp = copy.deepcopy(s1["spatial"])
        sp["min_x"] -= pad
        sp["min_y"] -= pad
        sp["max_x"] += pad
        sp["max_y"] += pad
        add(clone_q(s1, f"s_pad_{i}", spatial=sp), "Z",
            "small" if pad < 500 else ("medium" if pad < 1200 else "large"))
    for i, off in enumerate([30 * 60 * 1000, HOUR, 2 * HOUR], start=1):
        add(clone_q(st, f"st_grid_{i}", temporal={
            "start_ms": st["temporal"]["start_ms"] + off,
            "end_ms": st["temporal"]["end_ms"] + off,
        }), "TZ", "small")
    add(clone_q(hst, "h_st_4", temporal={
        "start_ms": hst["temporal"]["start_ms"] - 2 * HOUR,
        "end_ms": hst["temporal"]["end_ms"] + HOUR,
    }), "TZH", "medium", rbo_trap=True)
    add(clone_q(tk, "topk_st_k10", result={"k": 10}), "TZ", "small", notes="DTW k=10")
    add(clone_q(idx["topk_st_2"], "topk_st_2_k1", result={"k": 1}), "TZ", "small")
    add(clone_q(idx["st_sl_1"], "st_sl_2"), "TZ", "mixed", notes="small-T large-Z clone")
    add(clone_q(idx["st_ls_1"], "st_ls_2"), "TZ", "mixed", notes="large-T small-Z clone")
    add(clone_q(idx["empty_far_1"], "empty_far_4", temporal={
        "start_ms": 1893456000000,
        "end_ms": 1893459600000,
    }), "T", "empty", notes="2030 window")

    wl["workload_id"] = "bound_ir_v1"
    wl["seed"] = "2026-09-25-bound-ir-v1-formal"
    wl["query_count"] = len(qs)
    print(f"bound_ir queries={len(qs)} newly_added={len(added)}")
    print("added:", ", ".join(added))
    return wl


def expand_nl(wl: dict) -> dict:
    qs = wl["queries"]
    ids = {q["query_id"] for q in qs}

    def add(row):
        if row["query_id"] in ids:
            return
        qs.append(row)
        ids.add(row["query_id"])

    # More supported (reuse gold from existing where same semantics)
    gold_v1 = next(q for q in qs if q["query_id"] == "nl_v1")
    add({
        "query_id": "nl_v6",
        "utterance": "Find trajectories of taxi 8857 between 2008-02-03T21:00:00+08:00 and 2008-02-03T21:40:00+08:00",
        "reject_expected": False,
        "layer": "vehicle_temporal",
        "gold_trajectory_ids": list(gold_v1.get("gold_trajectory_ids") or []),
    })
    add({
        "query_id": "nl_v7",
        "utterance": "Show taxi 8857 trajectories intersecting tdrive_smoke_anchor from 2008-02-03T21:20:00+08:00 to 2008-02-03T21:30:00+08:00",
        "reject_expected": False,
        "layer": "st_vehicle",
        "gold_trajectory_ids": ["8857-8857_14"],
    })
    add({
        "query_id": "nl_z2",
        "utterance": "List trajectory ids intersecting beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00",
        "reject_expected": False,
        "layer": "spatial_temporal",
        "oracle_mode": "contains",
        "gold_must_contain": ["8857-8857_14"],
    })
    # DTW k=1 style
    add({
        "query_id": "nl_v8",
        "utterance": "Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 1 most similar to 8857-8857_14 using DTW",
        "reject_expected": False,
        "layer": "topk_dtw",
        "gold_trajectory_ids": ["2406-2406_9"],
        "gold_top_k": [{"trajectory_id": "2406-2406_9"}],
    })

    # Rejects
    for i, (qid, utt, layer) in enumerate([
        ("nl_r8", "GROUP BY taxi and return average duration in beijing_core", "reject_agg"),
        ("nl_r9", "Delete trajectories of taxi 8857 older than 2008-02-03", "reject_write"),
        ("nl_r10", "Find trajectories using continuous Hausdorff over polyline edges", "reject_continuous"),
        ("nl_r11", "Show taxis currently online near Wangjing right now", "reject_live"),
        ("nl_r12", "Rank trajectories by length in kilometers between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00", "reject_length"),
        ("nl_r13", "Find trajectories that stopped for more than 10 minutes in tdrive_smoke_anchor", "reject_stop"),
        ("nl_r14", "Join taxi trajectories with weather rainfall for 2008-02-03", "reject_join"),
        ("nl_r15", "Update vehicle_id of 8857-8857_14 to 9999", "reject_write"),
    ], start=8):
        add({"query_id": qid, "utterance": utt, "reject_expected": True, "layer": layer})

    # Clarify
    for qid, utt, layer in [
        ("nl_c3", "Find similar taxis near there", "clarify_vague"),
        ("nl_c4", "Show me the busy ones this morning", "clarify_relative_time"),
        ("nl_c5", "Top neighbors of that trajectory", "clarify_missing_ref"),
        ("nl_c6", "Trajectories in the box", "clarify_missing_time"),
        ("nl_c7", "8857 around noon", "clarify_ambiguous_window"),
        ("nl_c8", "Closest paths to the airport", "clarify_place_metric"),
    ]:
        add({
            "query_id": qid,
            "utterance": utt,
            "reject_expected": False,
            "clarify_expected": True,
            "layer": layer,
        })

    # More supported spatial-temporal
    add({
        "query_id": "nl_v9",
        "utterance": "List trajectory ids intersecting tdrive_topk_wide between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00",
        "reject_expected": False,
        "layer": "spatial_temporal",
        "oracle_mode": "contains",
        "gold_must_contain": ["8857-8857_14"],
    })
    add({
        "query_id": "nl_v10",
        "utterance": "Find trajectories of taxi 8857 intersecting zhongguancun between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00",
        "reject_expected": False,
        "layer": "st_vehicle",
        "oracle_mode": "contains",
        "gold_must_contain": ["8857-8857_14"],
    })
    add({
        "query_id": "nl_v11",
        "utterance": "Top-5 DTW neighbors of 8857-8857_14 intersecting tdrive_smoke_anchor between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00",
        "reject_expected": False,
        "layer": "topk_dtw",
        "gold_trajectory_ids": ["5340-5340_15", "2376-2376_10", "289-289_12"],
    })
    add({
        "query_id": "nl_v12",
        "utterance": "Find trajectories of taxi 8857 between 2008-02-03T22:00:00+08:00 and 2008-02-03T22:30:00+08:00",
        "reject_expected": False,
        "layer": "vehicle_temporal",
        "gold_trajectory_ids": [],
    })
    for qid, utt, layer in [
        ("nl_r16", "Compute the median speed of all taxis in guomao", "reject_agg"),
        ("nl_r17", "Export trajectories as GeoJSON for QGIS", "reject_export"),
        ("nl_c9", "Something near CBD yesterday evening", "clarify_vague"),
        ("nl_c10", "Find the usual routes", "clarify_vague"),
    ]:
        row = {"query_id": qid, "utterance": utt, "layer": layer}
        if qid.startswith("nl_r"):
            row["reject_expected"] = True
        else:
            row["reject_expected"] = False
            row["clarify_expected"] = True
        add(row)

    wl["workload_id"] = "nl_ir_v1"
    wl["seed"] = "2026-09-25-nl-ir-v1-formal"
    wl["query_count"] = len(qs)
    print(f"nl_ir queries={len(qs)}")
    return wl


def main():
    bound = expand_bound(load(BOUND))
    save(BOUND, bound)
    nl = expand_nl(load(NL))
    save(NL, nl)


if __name__ == "__main__":
    main()
