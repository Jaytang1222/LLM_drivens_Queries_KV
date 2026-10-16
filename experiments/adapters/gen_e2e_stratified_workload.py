#!/usr/bin/env python3
"""Build E2/E3 stratified BoundIR workloads (simple_v2 / complex_v2).

Design (from ADV force/e2e_mix8r pockets; data/snapshot unchanged):
  - Equal n=24 per difficulty; ~6 each of SRQ / ST-Ranges / SS / topk.
  - Simple: classic Z/H/TZ pockets (short/compact/veh/multi-AOI).
  - Complex: ADV XZ / TS / XZT族 + classic multi-AOI / fat-Z pockets.
  - Type role encoded in query_id prefix; catalog written beside workloads.
"""
from __future__ import annotations

import json
from pathlib import Path

SRC = Path("experiments/workloads/bound_ir_complex_v1.json")
FRAG = Path("experiments/workloads/bound_ir_adv_frag_v1.json")
MIX = Path("experiments/workloads/bound_ir_adv_mix_v1.json")
SIMPLE_OUT = Path("experiments/workloads/bound_ir_simple_v2.json")
COMPLEX_OUT = Path("experiments/workloads/bound_ir_complex_v2.json")
CATALOG_OUT = Path("experiments/workloads/bound_ir_e2e_v2.catalog.json")

REF_TIDS = [41360, 45720, 25697]
METRICS_SS = ["DTW", "FRECHET", "HAUSDORFF"]


def base_q(q, qid, result=None, predicates=None):
    return {
        "ir_version": "1.0",
        "query_id": qid,
        "source": q["source"],
        "predicates": list(predicates or []),
        "semantics": q.get("semantics", {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"}),
        "result": result
        or {"mode": "TRAJECTORY_IDS", "tie_breaker": "TID_ASC"},
        "snapshot": {
            "manifest_id": q["snapshot"]["manifest_id"],
            "semantics_version": q["snapshot"].get("semantics_version", "point_dtw_v1"),
        },
    }


def expand_spatial(s, factor):
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


def shrink_spatial(s, factor=0.35):
    return expand_spatial(s, factor)


def shrink_temporal(t, span_ms):
    mid = (t["start_ms"] + t["end_ms"]) // 2
    half = span_ms // 2
    return {"start_ms": int(mid - half), "end_ms": int(mid - half + span_ms)}


def tile_aois(spatial, nx=3, ny=3, keep=6, shrink=0.15):
    min_x, min_y = spatial["min_x"], spatial["min_y"]
    max_x, max_y = spatial["max_x"], spatial["max_y"]
    dx = (max_x - min_x) / nx
    dy = (max_y - min_y) / ny
    cells = []
    for iy in range(ny):
        for ix in range(nx):
            x0 = min_x + ix * dx
            y0 = min_y + iy * dy
            x1 = x0 + dx
            y1 = y0 + dy
            sx = (x1 - x0) * shrink
            sy = (y1 - y0) * shrink
            cells.append(
                {
                    "min_x": x0 + sx,
                    "min_y": y0 + sy,
                    "max_x": x1 - sx,
                    "max_y": y1 - sy,
                    "relation": "INTERSECTS",
                    "boundary": "INCLUDED",
                }
            )
    order = [0, 2, 6, 8, 1, 3, 5, 7, 4]
    picked = []
    for i in order:
        if i < len(cells) and len(picked) < keep:
            picked.append(cells[i])
    return picked


def split_windows(temporal, n=6):
    s, e = temporal["start_ms"], temporal["end_ms"]
    span = e - s
    slice_ms = max(span // (n * 2), 10 * 60 * 1000)
    step = span // n
    parts = []
    for i in range(n):
        a = s + i * step
        b = min(e, a + slice_ms)
        if b > a:
            parts.append({"start_ms": int(a), "end_ms": int(b)})
    return parts


def similarity_q(seed, qid, metric, k, spatial, temporal=None, ref_tid=None):
    q = base_q(
        seed,
        qid,
        result={"mode": "TOP_K", "k": int(k), "tie_breaker": "TID_ASC"},
    )
    q["spatial"] = dict(spatial)
    if temporal is not None:
        q["temporal"] = dict(temporal)
    else:
        q["temporal"] = None
    q["similarity"] = {
        "metric": metric,
        "reference_tid": int(ref_tid if ref_tid is not None else REF_TIDS[0]),
        "scope": "FULL_TRAJECTORY",
        "exclude_reference": True,
        "local_distance": "EUCLIDEAN",
        "normalization": "NONE",
    }
    return q


def fat_seeds(data, n=12):
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
    return [q for _, q in fat[:n]]


def classify(q):
    qid = str(q["query_id"])
    if "_ss_" in qid or qid.startswith(("s_ss_", "c_ss_")):
        return "SS"
    if "_topk_" in qid or qid.startswith(("s_topk_", "c_topk_")):
        return "topk"
    if q.get("spatial_parts") or q.get("temporal_parts"):
        return "ST-Ranges"
    if q.get("similarity") or (q.get("result") or {}).get("mode") == "TOP_K":
        return "topk" if (q.get("result") or {}).get("mode") == "TOP_K" else "SS"
    return "SRQ"


def build_simple(seeds):
    """Classic-optimal compact scenes → Z / H / TZ."""
    queries = []
    roles = []

    # SRQ x6
    for i, q in enumerate(seeds[:2], 1):
        qq = base_q(q, f"s_srq_short_{i}")
        qq["spatial"] = shrink_spatial(q["spatial"], 0.55)
        qq["temporal"] = shrink_temporal(q["temporal"], 2 * 3600 * 1000)
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "SRQ", "target_index": "TZ", "scene": "short_st"})

    q = seeds[2]
    qq = base_q(q, "s_srq_fat_1")
    qq["spatial"] = dict(q["spatial"])
    qq["temporal"] = dict(q["temporal"])
    queries.append(qq)
    roles.append({"query_id": qq["query_id"], "type": "SRQ", "target_index": "Z", "scene": "selective_fat"})

    # T-Drive vehicle_ids are taxi ids (e.g. 8857). Keep ST loose so H hits non-empty.
    qv1 = base_q(
        seeds[0],
        "s_srq_veh_1",
        predicates=[{"field": "vehicle_id", "op": "EQ", "value": "8857"}],
    )
    qv1["spatial"] = expand_spatial(seeds[0]["spatial"], 2.0)
    qv1["temporal"] = dict(seeds[0]["temporal"])
    queries.append(qv1)
    roles.append({"query_id": qv1["query_id"], "type": "SRQ", "target_index": "H", "scene": "vehicle_eq"})

    qv2 = base_q(
        seeds[0],
        "s_srq_veh_2",
        predicates=[{"field": "vehicle_id", "op": "EQ", "value": "8857"}],
    )
    qv2["spatial"] = None
    qv2["temporal"] = dict(seeds[0]["temporal"])
    queries.append(qv2)
    roles.append({"query_id": qv2["query_id"], "type": "SRQ", "target_index": "H", "scene": "vehicle_eq_t"})

    qs = base_q(seeds[4], "s_srq_s_1")
    qs["spatial"] = shrink_spatial(seeds[4]["spatial"], 0.4)
    qs["temporal"] = None
    queries.append(qs)
    roles.append({"query_id": qs["query_id"], "type": "SRQ", "target_index": "Z", "scene": "spatial_only"})

    # ST-Ranges x6
    for i, q in enumerate(seeds[:3], 1):
        qq = base_q(q, f"s_str_aoi_{i}")
        qq["temporal"] = shrink_temporal(q["temporal"], 4 * 3600 * 1000)
        qq["spatial_parts"] = tile_aois(shrink_spatial(q["spatial"], 0.7), keep=4)
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "ST-Ranges", "target_index": "Z", "scene": "multi_aoi_compact"})

    for i, q in enumerate(seeds[3:6], 1):
        qq = base_q(q, f"s_str_twin_{i}")
        qq["spatial"] = shrink_spatial(q["spatial"], 0.6)
        qq["temporal_parts"] = split_windows(shrink_temporal(q["temporal"], 6 * 3600 * 1000), n=4)
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "ST-Ranges", "target_index": "Z", "scene": "multi_window_compact"})

    # SS x6 (metric diversity, k=5)
    for i in range(6):
        seed = seeds[i % len(seeds)]
        metric = METRICS_SS[i % 3]
        qq = similarity_q(
            seed,
            f"s_ss_{metric.lower()}_{i + 1}",
            metric=metric,
            k=5,
            spatial=shrink_spatial(seed["spatial"], 0.45),
            temporal=None,
            ref_tid=REF_TIDS[i % len(REF_TIDS)],
        )
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "SS", "target_index": "Z", "scene": f"sim_{metric}"})

    # topk x6 (k diversity)
    ks = [3, 3, 3, 10, 10, 10]
    for i, k in enumerate(ks):
        seed = seeds[(i + 2) % len(seeds)]
        qq = similarity_q(
            seed,
            f"s_topk_k{k}_{i + 1}",
            metric="DTW",
            k=k,
            spatial=shrink_spatial(seed["spatial"], 0.5),
            temporal=shrink_temporal(seed["temporal"], 3 * 3600 * 1000),
            ref_tid=REF_TIDS[i % len(REF_TIDS)],
        )
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "topk", "target_index": "TZ", "scene": f"topk_k{k}"})

    return queries, roles


def build_complex(seeds, frag_queries, mix_by_id):
    """ADV + classic diversity; reuse proven mix/frag shapes where possible."""
    queries = []
    roles = []

    # SRQ x6 from mix templates / reshaped fat
    def take_mix(qid, new_id, typ, idx, scene):
        src = mix_by_id[qid]
        qq = json.loads(json.dumps(src))
        qq["query_id"] = new_id
        queries.append(qq)
        roles.append({"query_id": new_id, "type": typ, "target_index": idx, "scene": scene})

    take_mix("adv_wide_1", "c_srq_xz_1", "SRQ", "XZ", "fat_wide_xz")
    # Second XZ pocket: same mega-ish seed expanded to xzWide band (~1.5e8+).
    seed_xz = seeds[1]
    qx = base_q(seed_xz, "c_srq_xz_2")
    qx["temporal"] = dict(seed_xz["temporal"])
    qx["spatial"] = expand_spatial(seed_xz["spatial"], 3.6)
    queries.append(qx)
    roles.append({"query_id": qx["query_id"], "type": "SRQ", "target_index": "XZ", "scene": "fat_wide_xz"})
    take_mix("adv_wide_2", "c_srq_ts_1", "SRQ", "TS", "fat_wide_ts")
    take_mix("adv_mega_1", "c_srq_xzt_1", "SRQ", "XZTXZ", "fat_mega_xzt")
    take_mix("adv_hyb_1", "c_srq_xzt_2", "SRQ", "XZTXZ", "hybrid_xzt")
    take_mix("ctrl_fat_1", "c_srq_z_1", "SRQ", "Z", "selective_fat_z")

    # ST-Ranges x6
    for i, qid in enumerate(["frag_aoi_1", "frag_aoi_2", "frag_aoi_3"], 1):
        take_mix(qid, f"c_str_aoi_{i}", "ST-Ranges", "Z", "multi_aoi_fat")

    # twin / combo from frag file (not all in mix)
    frag_by = {q["query_id"]: q for q in frag_queries}
    for i, qid in enumerate(["frag_twin_1", "frag_twin_2"], 1):
        src = frag_by[qid]
        qq = json.loads(json.dumps(src))
        qq["query_id"] = f"c_str_twin_{i}"
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "ST-Ranges", "target_index": "Z", "scene": "multi_window_fat"})

    src = frag_by["frag_combo_1"]
    qq = json.loads(json.dumps(src))
    qq["query_id"] = "c_str_combo_1"
    queries.append(qq)
    roles.append({"query_id": qq["query_id"], "type": "ST-Ranges", "target_index": "Z", "scene": "aoi_plus_window"})

    # SS x6 — wider candidate region + metric diversity
    for i in range(6):
        seed = seeds[i % len(seeds)]
        metric = METRICS_SS[i % 3]
        factor = 2.2 if i % 2 == 0 else 3.0
        qq = similarity_q(
            seed,
            f"c_ss_{metric.lower()}_{i + 1}",
            metric=metric,
            k=5,
            spatial=expand_spatial(seed["spatial"], factor),
            temporal=shrink_temporal(seed["temporal"], (8 + i) * 3600 * 1000),
            ref_tid=REF_TIDS[i % len(REF_TIDS)],
        )
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "SS", "target_index": "XZ/Z", "scene": f"sim_wide_{metric}"})

    # topk x6 — fat filter + k diversity
    ks = [3, 3, 3, 10, 10, 10]
    for i, k in enumerate(ks):
        seed = seeds[(i + 3) % len(seeds)]
        qq = similarity_q(
            seed,
            f"c_topk_k{k}_{i + 1}",
            metric="DTW" if i % 2 == 0 else "FRECHET",
            k=k,
            spatial=expand_spatial(seed["spatial"], 2.5),
            temporal=dict(seed["temporal"]),
            ref_tid=REF_TIDS[i % len(REF_TIDS)],
        )
        queries.append(qq)
        roles.append({"query_id": qq["query_id"], "type": "topk", "target_index": "XZ/TS", "scene": f"topk_fat_k{k}"})

    return queries, roles


def summarize(queries, roles):
    from collections import Counter

    types = Counter(r["type"] for r in roles)
    idx = Counter(r["target_index"] for r in roles)
    return {"n": len(queries), "by_type": dict(types), "by_target_index": dict(idx)}


def main():
    data = json.loads(SRC.read_text(encoding="utf-8"))
    frag = json.loads(FRAG.read_text(encoding="utf-8"))
    mix = json.loads(MIX.read_text(encoding="utf-8"))
    mix_by = {q["query_id"]: q for q in mix["queries"]}
    seeds = fat_seeds(data, n=12)

    simple_q, simple_r = build_simple(seeds)
    complex_q, complex_r = build_complex(seeds, frag["queries"], mix_by)

    assert len(simple_q) == len(complex_q) == 24
    for q in simple_q + complex_q:
        assert classify(q) in {"SRQ", "ST-Ranges", "SS", "topk"}

    simple_wl = {
        "workload_id": "bound_ir_simple_v2",
        "manifest_id": "tdrive_v1_ready",
        "evaluation_role": "e2e_simple_classic_pockets_balanced_types",
        "eval_level": "simple",
        "queries": simple_q,
    }
    complex_wl = {
        "workload_id": "bound_ir_complex_v2",
        "manifest_id": "tdrive_v1_ready",
        "evaluation_role": "e2e_complex_adv_and_classic_pockets_balanced_types",
        "eval_level": "complex",
        "queries": complex_q,
    }
    catalog = {
        "version": "e2e_v2",
        "design": {
            "n_per_level": 24,
            "types": ["SRQ", "ST-Ranges", "SS", "topk"],
            "index_families": ["Z/H/TZ", "XZ", "TS", "XZT"],
            "simple_intent": "classic Z/H/TZ win; compact ST / multi-AOI / veh / similarity",
            "complex_intent": "ADV XZ/TS/XZTXZ + classic multi-AOI/fat-Z; diverse win modes",
            "evidence": "force_mix8 / e2e_mix8r pockets",
        },
        "simple": {"summary": summarize(simple_q, simple_r), "roles": simple_r},
        "complex": {"summary": summarize(complex_q, complex_r), "roles": complex_r},
    }

    SIMPLE_OUT.write_text(json.dumps(simple_wl, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    COMPLEX_OUT.write_text(json.dumps(complex_wl, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    CATALOG_OUT.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"wrote {SIMPLE_OUT} n={len(simple_q)} {catalog['simple']['summary']}")
    print(f"wrote {COMPLEX_OUT} n={len(complex_q)} {catalog['complex']['summary']}")
    print(f"wrote {CATALOG_OUT}")
    for level, qs in (("simple", simple_q), ("complex", complex_q)):
        print(f"== {level} ==")
        for q in qs:
            t = q.get("temporal") or {}
            span = (t["end_ms"] - t["start_ms"]) / 3.6e6 if t else None
            span_s = f"{span:.2f}" if span is not None else "-"
            print(f"  {q['query_id']:22} type={classify(q):10} span_h={span_s}")


if __name__ == "__main__":
    main()
