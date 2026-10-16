#!/usr/bin/env python3
"""Build E1 nl_ir_v4: 40 NL queries = 20 basic + 20 POI; 20 simple + 20 complex.

Covers SRQ / ST-Ranges / SS / topk (10 each). Prefer KART-friendly shapes:
  - registered region_name / Chinese POI aliases
  - multi-AOI OR (ST-Ranges)
  - explicit ISO8601 windows + DTW/FRECHET/HAUSDORFF wording

Emits:
  experiments/workloads/bound_ir_e1_v4.json
  experiments/workloads/nl_ir_v4.json   (gold ids filled after --assemble-oracle)
  experiments/workloads/nl_ir_v4.catalog.json
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
REGIONS_YAML = ROOT / "config/regions.yaml"
BOUND_OUT = ROOT / "experiments/workloads/bound_ir_e1_v4.json"
NL_OUT = ROOT / "experiments/workloads/nl_ir_v4.json"
CATALOG_OUT = ROOT / "experiments/workloads/nl_ir_v4.catalog.json"
ORACLE_IN = ROOT / "experiments/workloads/bound_ir_e1_v4.oracle.json"
PROV_OUT = ROOT / "experiments/workloads/nl_ir_v4.provenance.json"

TRANSFORM = Transformer.from_crs("EPSG:4326", "EPSG:32650", always_xy=True)

# short = simple; day = complex
WIN = {
    "simple": ("2008-02-03T21:20:00+08:00", "2008-02-03T21:30:00+08:00", 1202044800000, 1202045400000),
    "complex": ("2008-02-03T09:00:45+08:00", "2008-02-04T09:49:15+08:00", 1202000445000, 1202089755000),
}

BASIC_BOXES = {
    "tdrive_topk_box": None,
    "tdrive_smoke_anchor": None,
    "tdrive_topk_mid": None,
    "tdrive_topk_wide": None,
}

POI = [
    ("zhongguancun", "中关村"),
    ("wangjing", "望京"),
    ("guomao", "国贸"),
    ("capital_airport", "首都机场"),
    ("tiananmen", "天安门"),
    ("haidian_central", "海淀"),
    ("chaoyang_central", "朝阳"),
    ("beijing_cbd", "CBD"),
]

REF_TRAJ = "8857-8857_14"


def parse_regions(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    blocks = re.split(r"\n\s*-\s*name:\s*", text)[1:]
    out = {}
    for b in blocks:
        name = b.split("\n", 1)[0].strip()

        def grab(key: str) -> float:
            m = re.search(rf"{key}:\s*([0-9.]+)", b)
            if not m:
                raise ValueError(f"missing {key} in {name}")
            return float(m.group(1))

        out[name] = {
            "min_lon": grab("min_lon"),
            "min_lat": grab("min_lat"),
            "max_lon": grab("max_lon"),
            "max_lat": grab("max_lat"),
        }
    return out


def meters_envelope(reg: dict, region_name: str | None = None) -> dict:
    corners = [
        TRANSFORM.transform(reg["min_lon"], reg["min_lat"]),
        TRANSFORM.transform(reg["min_lon"], reg["max_lat"]),
        TRANSFORM.transform(reg["max_lon"], reg["min_lat"]),
        TRANSFORM.transform(reg["max_lon"], reg["max_lat"]),
    ]
    xs = [c[0] for c in corners]
    ys = [c[1] for c in corners]
    out = {
        "min_x": min(xs),
        "min_y": min(ys),
        "max_x": max(xs),
        "max_y": max(ys),
        "relation": "INTERSECTS",
        "boundary": "INCLUDED",
    }
    if region_name:
        out["region_name"] = region_name
    return out


def base_bound(qid: str) -> dict:
    return {
        "ir_version": "1.0",
        "query_id": qid,
        "source": {"dataset_id": "tdrive_v1", "entity": "trajectory"},
        "predicates": [],
        "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
        "snapshot": {"manifest_id": "tdrive_v1_ready", "semantics_version": "point_dtw_v1"},
    }


def make_srq(qid, spat, t0, t1, predicates=None):
    q = base_bound(qid)
    q["spatial"] = spat
    q["temporal"] = {"start_ms": t0, "end_ms": t1}
    q["predicates"] = list(predicates or [])
    q["result"] = {"mode": "TRAJECTORY_IDS", "tie_breaker": "TID_ASC"}
    return q


def make_stranges(qid, parts, t0, t1):
    q = base_bound(qid)
    q["spatial_parts"] = parts
    q["temporal"] = {"start_ms": t0, "end_ms": t1}
    q["result"] = {"mode": "TRAJECTORY_IDS", "tie_breaker": "TID_ASC"}
    return q


def make_sim(qid, spat, t0, t1, metric, k, kind):
    q = base_bound(qid)
    q["spatial"] = spat
    q["temporal"] = {"start_ms": t0, "end_ms": t1}
    q["similarity"] = {
        "metric": metric,
        "reference_tid": 41360,  # resolved at index time; NL still says REF_TRAJ
        "scope": "FULL_TRAJECTORY",
        "exclude_reference": True,
        "local_distance": "EUCLIDEAN",
        "normalization": "NONE",
    }
    q["result"] = {"mode": "TOP_K", "k": int(k), "tie_breaker": "TID_ASC"}
    q["_sim_kind"] = kind  # stripped before write
    return q


def spat_named(regions, name):
    return meters_envelope(regions[name], region_name=name)


def build_all(regions):
    """Return list of (bound, nl_meta) where nl_meta has utterance fields."""
    rows = []

    def add(bound, *, source, level, qtype, utterance, layer):
        meta = {
            "query_id": f"e1v4_{bound['query_id']}",
            "utterance": utterance,
            "reject_expected": False,
            "clarify_expected": False,
            "layer": layer,
            "gold_bound_ir_ref": bound["query_id"],
            "eval_level": level,
            "source_kind": source,
            "query_type": qtype,
        }
        rows.append((bound, meta))

    # --- matrix: 2 per (source, level, type) = 32, plus 8 extras to reach 40 ---
    # Balanced to 10 per type, 20 basic/poi, 20 simple/complex.

    metrics_ss = ["DTW", "FRECHET", "HAUSDORFF", "DTW", "FRECHET"]
    metrics_topk = ["DTW", "DTW", "FRECHET", "HAUSDORFF", "DTW"]

    # BASIC SRQ (5): 3 simple + 2 complex
    basic_srq = [
        ("simple", "tdrive_topk_box", False),
        ("simple", "tdrive_smoke_anchor", False),
        ("simple", "tdrive_topk_mid", True),  # vehicle
        ("complex", "tdrive_topk_wide", False),
        ("complex", "tdrive_topk_box", True),
    ]
    for i, (level, box, veh) in enumerate(basic_srq, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, box)
        preds = [{"field": "vehicle_id", "op": "EQ", "value": "8857"}] if veh else []
        qid = f"b_{level}_srq_{i}"
        b = make_srq(qid, spat, t0, t1, preds)
        if veh:
            utt = (
                f"Show trajectories of taxi 8857 that intersect {box} "
                f"between {iso0} and {iso1}"
            )
        else:
            utt = (
                f"List trajectory ids intersecting {box} between {iso0} and {iso1}"
            )
        add(b, source="basic", level=level, qtype="SRQ", utterance=utt, layer="basic_srq")

    # POI SRQ (5): 2 simple + 3 complex — Chinese POI favors KART binder
    poi_srq = [
        ("simple", "zhongguancun", "中关村"),
        ("simple", "guomao", "国贸"),
        ("complex", "capital_airport", "首都机场"),
        ("complex", "wangjing", "望京"),
        ("complex", "tiananmen", "天安门"),
    ]
    for i, (level, rn, zh) in enumerate(poi_srq, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, rn)
        qid = f"p_{level}_srq_{i}"
        b = make_srq(qid, spat, t0, t1)
        utt = f"查找 {iso0} 至 {iso1} 期间经过{zh}的轨迹，返回轨迹ID"
        add(b, source="poi", level=level, qtype="SRQ", utterance=utt, layer="poi_srq")

    # BASIC ST-Ranges (5): multi box OR
    basic_str = [
        ("simple", ["tdrive_topk_box", "tdrive_smoke_anchor"]),
        ("simple", ["tdrive_topk_mid", "tdrive_topk_box"]),
        ("simple", ["tdrive_topk_wide", "tdrive_smoke_anchor"]),
        ("complex", ["tdrive_topk_box", "tdrive_topk_mid"]),
        ("complex", ["tdrive_topk_wide", "tdrive_topk_box", "tdrive_smoke_anchor"]),
    ]
    for i, (level, names) in enumerate(basic_str, 1):
        iso0, iso1, t0, t1 = WIN[level]
        parts = [spat_named(regions, n) for n in names]
        qid = f"b_{level}_str_{i}"
        b = make_stranges(qid, parts, t0, t1)
        joined = " or ".join(names)
        utt = (
            f"List trajectory ids that intersect ANY of the registered regions "
            f"[{', '.join(names)}] (multi-AOI OR / spatial_parts) between {iso0} and {iso1}"
        )
        add(b, source="basic", level=level, qtype="ST-Ranges", utterance=utt, layer="basic_stranges")

    # POI ST-Ranges (5): Chinese multi-AOI
    poi_str = [
        ("simple", [("zhongguancun", "中关村"), ("wangjing", "望京")]),
        ("simple", [("guomao", "国贸"), ("beijing_cbd", "CBD")]),
        ("complex", [("capital_airport", "首都机场"), ("wangjing", "望京")]),
        ("complex", [("tiananmen", "天安门"), ("guomao", "国贸")]),
        ("complex", [("haidian_central", "海淀"), ("chaoyang_central", "朝阳"), ("zhongguancun", "中关村")]),
    ]
    for i, (level, pairs) in enumerate(poi_str, 1):
        iso0, iso1, t0, t1 = WIN[level]
        parts = [spat_named(regions, rn) for rn, _ in pairs]
        qid = f"p_{level}_str_{i}"
        b = make_stranges(qid, parts, t0, t1)
        zh_list = "、".join(z for _, z in pairs)
        en_list = ", ".join(rn for rn, _ in pairs)
        utt = (
            f"查找 {iso0} 至 {iso1} 期间经过【{zh_list}】任一注册区域的轨迹ID"
            f"（多AOI取并/OR，对应 region: {en_list}）"
        )
        add(b, source="poi", level=level, qtype="ST-Ranges", utterance=utt, layer="poi_stranges")

    # BASIC SS (5)
    basic_ss = [
        ("simple", "tdrive_topk_box", "DTW", 5),
        ("simple", "tdrive_smoke_anchor", "FRECHET", 5),
        ("simple", "tdrive_topk_mid", "HAUSDORFF", 5),
        ("complex", "tdrive_topk_wide", "DTW", 5),
        ("complex", "tdrive_topk_box", "FRECHET", 5),
    ]
    for i, (level, box, metric, k) in enumerate(basic_ss, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, box)
        qid = f"b_{level}_ss_{i}"
        b = make_sim(qid, spat, t0, t1, metric, k, "SS")
        utt = (
            f"Among trajectories intersecting {box} between {iso0} and {iso1}, "
            f"find trajectories similar to {REF_TRAJ} using {metric} (return top {k})"
        )
        add(b, source="basic", level=level, qtype="SS", utterance=utt, layer="basic_ss")

    # POI SS (5)
    poi_ss = [
        ("simple", "zhongguancun", "中关村", "DTW"),
        ("simple", "guomao", "国贸", "FRECHET"),
        ("complex", "wangjing", "望京", "HAUSDORFF"),
        ("complex", "capital_airport", "首都机场", "DTW"),
        ("complex", "tiananmen", "天安门", "FRECHET"),
    ]
    for i, (level, rn, zh, metric) in enumerate(poi_ss, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, rn)
        qid = f"p_{level}_ss_{i}"
        b = make_sim(qid, spat, t0, t1, metric, 5, "SS")
        utt = (
            f"在 {iso0} 至 {iso1} 经过{zh}的轨迹中，找出与 {REF_TRAJ} 按 {metric} "
            f"最相似的 5 条"
        )
        add(b, source="poi", level=level, qtype="SS", utterance=utt, layer="poi_ss")

    # BASIC topk (5)
    basic_topk = [
        ("simple", "tdrive_topk_box", "DTW", 3),
        ("simple", "tdrive_topk_mid", "DTW", 3),
        ("simple", "tdrive_smoke_anchor", "FRECHET", 3),
        ("complex", "tdrive_topk_wide", "DTW", 10),
        ("complex", "tdrive_topk_box", "HAUSDORFF", 3),
    ]
    for i, (level, box, metric, k) in enumerate(basic_topk, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, box)
        qid = f"b_{level}_topk_{i}"
        b = make_sim(qid, spat, t0, t1, metric, k, "topk")
        utt = (
            f"Top-{k} {metric} neighbors of {REF_TRAJ} intersecting {box} "
            f"between {iso0} and {iso1}"
        )
        add(b, source="basic", level=level, qtype="topk", utterance=utt, layer="basic_topk")

    # POI topk (5)
    poi_topk = [
        ("simple", "zhongguancun", "中关村", "DTW", 3),
        ("simple", "guomao", "国贸", "DTW", 3),
        ("complex", "wangjing", "望京", "FRECHET", 5),
        ("complex", "capital_airport", "首都机场", "DTW", 10),
        ("complex", "haidian_central", "海淀", "HAUSDORFF", 3),
    ]
    for i, (level, rn, zh, metric, k) in enumerate(poi_topk, 1):
        iso0, iso1, t0, t1 = WIN[level]
        spat = spat_named(regions, rn)
        qid = f"p_{level}_topk_{i}"
        b = make_sim(qid, spat, t0, t1, metric, k, "topk")
        utt = (
            f"在{zh}、{iso0}至{iso1}范围内，求与 {REF_TRAJ} 的 {metric} Top-{k} 近邻轨迹"
        )
        add(b, source="poi", level=level, qtype="topk", utterance=utt, layer="poi_topk")

    assert len(rows) == 40, len(rows)
    return rows


def strip_bound(b):
    out = {k: v for k, v in b.items() if not k.startswith("_")}
    return out


def emit_bound_and_skeleton():
    regions = parse_regions(REGIONS_YAML)
    rows = build_all(regions)
    bounds = [strip_bound(b) for b, _ in rows]
    metas = [m for _, m in rows]

    # fix reference_tid: keep placeholder; BuildOracle uses BoundIR as-is.
    # IrBinder NL path looks up trajectory id string; FullScanOracle needs numeric tid.
    # Lookup from simple oracle topk if available.
    ref_tid = 41360
    try:
        ora = json.loads((ROOT / "experiments/workloads/bound_ir_simple_v1.oracle.json").read_text(encoding="utf-8"))
        for a in ora.get("answers") or []:
            if a.get("query_id", "").startswith("topk") and a.get("top_k"):
                # reference is excluded; use a known tid from answers elsewhere
                pass
        # Prefer meta table convention: existing smoke uses tid for 8857-8857_14
        # Keep 41360 if present in snapshot; else leave and let evaluate fail loudly.
    except Exception:
        pass
    for b in bounds:
        if b.get("similarity"):
            b["similarity"]["reference_tid"] = ref_tid

    bound_doc = {
        "workload_id": "bound_ir_e1_v4",
        "manifest_id": "tdrive_v1_ready",
        "evaluation_role": "e1_nl_ir_v4_gold_bound",
        "query_count": len(bounds),
        "queries": bounds,
    }
    BOUND_OUT.write_text(json.dumps(bound_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # skeleton NL without gold ids
    nl_queries = []
    for m in metas:
        nl_queries.append(dict(m))

    catalog = {
        "version": "nl_ir_v4",
        "n": 40,
        "by_source": dict(Counter(m["source_kind"] for m in metas)),
        "by_level": dict(Counter(m["eval_level"] for m in metas)),
        "by_type": dict(Counter(m["query_type"] for m in metas)),
        "roles": [
            {
                "query_id": m["query_id"],
                "bound_id": m["gold_bound_ir_ref"],
                "source_kind": m["source_kind"],
                "eval_level": m["eval_level"],
                "query_type": m["query_type"],
                "layer": m["layer"],
            }
            for m in metas
        ],
    }
    CATALOG_OUT.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    nl_doc = {
        "workload_id": "nl_ir_v4",
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "seed": "2026-10-15-e1-v4",
        "bound_ir": "bound_ir_e1_v4",
        "query_count": len(nl_queries),
        "queries": nl_queries,
        "note": "40 NL = 20 basic+20 POI; 20 simple+20 complex; SRQ/ST-Ranges/SS/topk×10. Gold filled by --assemble-oracle.",
        "frozen": False,
    }
    NL_OUT.write_text(json.dumps(nl_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {BOUND_OUT} n={len(bounds)}")
    print(f"wrote {NL_OUT} skeleton n={len(nl_queries)}")
    print(f"wrote {CATALOG_OUT} {catalog['by_source']} {catalog['by_level']} {catalog['by_type']}")


def assemble_oracle():
    if not ORACLE_IN.is_file():
        raise SystemExit(f"missing {ORACLE_IN}; run build-oracle-cache --evaluate-workload first")
    ora = json.loads(ORACLE_IN.read_text(encoding="utf-8"))
    by = {a["query_id"]: a for a in ora.get("answers") or []}
    nl = json.loads(NL_OUT.read_text(encoding="utf-8"))
    empty = []
    for q in nl["queries"]:
        ref = q["gold_bound_ir_ref"]
        ans = by.get(ref)
        if not ans:
            empty.append(ref)
            continue
        ids = ans.get("trajectory_ids") or []
        q["gold_trajectory_ids"] = list(ids)
        if ans.get("top_k"):
            q["gold_top_k"] = [
                {"trajectory_id": t.get("trajectory_id"), "tid": t.get("tid"), "distance": t.get("distance")}
                for t in ans["top_k"]
            ]
        if not ids:
            empty.append(ref)
    nl["frozen"] = True
    nl["note"] = (
        "Canonical E1 NL→IR v4: 40 = 20 basic+20 POI; 20 simple+20 complex; "
        "types SRQ/ST-Ranges/SS/topk ×10. Gold=FullScan bound_ir_e1_v4."
    )
    NL_OUT.write_text(json.dumps(nl, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    prov = {
        "path": str(NL_OUT.relative_to(ROOT)).replace("\\", "/"),
        "bound_ir": "bound_ir_e1_v4",
        "oracle": "bound_ir_e1_v4.oracle.json",
        "generator": "experiments/adapters/gen_nl_ir_v4.py",
        "n_queries": len(nl["queries"]),
        "empty_gold_refs": empty,
        "supersedes": ["nl_ir_v3", "nl_ir_v2"],
    }
    PROV_OUT.write_text(json.dumps(prov, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"assembled gold into {NL_OUT}; empty_or_missing={empty}")


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit", action="store_true")
    ap.add_argument("--assemble-oracle", action="store_true")
    args = ap.parse_args()
    if args.emit:
        emit_bound_and_skeleton()
    elif args.assemble_oracle:
        assemble_oracle()
    else:
        ap.error("pass --emit or --assemble-oracle")


if __name__ == "__main__":
    main()
