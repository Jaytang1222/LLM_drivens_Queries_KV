#!/usr/bin/env python3
"""Build POI BoundIR slice + assemble nl_ir_v3 from nl_ir_v2 + oracle answers."""
from __future__ import annotations

import json
import re
from pathlib import Path

from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
WL_V2 = ROOT / "experiments/workloads/nl_ir_v2.json"
BOUND_OUT = ROOT / "experiments/workloads/bound_ir_poi_e1.json"
ORACLE_OUT = ROOT / "experiments/workloads/bound_ir_poi_e1.oracle.json"
NL_V3 = ROOT / "experiments/workloads/nl_ir_v3.json"
REGIONS_YAML = ROOT / "config/regions.yaml"

# Demo POIs only (exclude tdrive_* experiment boxes).
POIS = [
    ("zhongguancun", "中关村", ["中关村", "zhongguancun"]),
    ("wangjing", "望京", ["望京", "Wangjing", "wangjing"]),
    ("guomao", "国贸", ["国贸", "guomao"]),
    ("beijing_cbd", "CBD", ["CBD", "北京CBD", "beijing_cbd"]),
    ("tiananmen", "天安门", ["天安门", "tiananmen"]),
    ("capital_airport", "首都机场", ["首都机场", "capital_airport", "机场"]),
    ("haidian_central", "海淀", ["海淀", "haidian_central"]),
    ("chaoyang_central", "朝阳", ["朝阳", "chaoyang_central"]),
]

# Reuse E1 / complex temporal windows (epoch ms, Asia/Shanghai wall times in NL).
WINDOWS = [
    ("w_ss1", 1202044800000, 1202045400000,
     "2008-02-03T21:20:00+08:00", "2008-02-03T21:30:00+08:00"),
    ("w_hst", 1202043000000, 1202046600000,
     "2008-02-03T20:50:00+08:00", "2008-02-03T21:50:00+08:00"),
    ("w_day", 1202000445000, 1202089755000,
     "2008-02-03T09:00:45+08:00", "2008-02-04T09:49:15+08:00"),
]

TRANSFORM = Transformer.from_crs("EPSG:4326", "EPSG:32650", always_xy=True)


def parse_regions(path: Path) -> dict[str, dict]:
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


def meters_envelope(reg: dict) -> dict:
    corners = [
        TRANSFORM.transform(reg["min_lon"], reg["min_lat"]),
        TRANSFORM.transform(reg["min_lon"], reg["max_lat"]),
        TRANSFORM.transform(reg["max_lon"], reg["min_lat"]),
        TRANSFORM.transform(reg["max_lon"], reg["max_lat"]),
    ]
    xs = [c[0] for c in corners]
    ys = [c[1] for c in corners]
    return {
        "min_x": min(xs),
        "min_y": min(ys),
        "max_x": max(xs),
        "max_y": max(ys),
        "relation": "INTERSECTS",
        "boundary": "INCLUDED",
    }


def make_bound(qid: str, spatial: dict, start_ms: int, end_ms: int,
               predicates=None) -> dict:
    return {
        "query_id": qid,
        "source": {"dataset_id": "tdrive_v1", "entity": "trajectory"},
        "temporal": {"start_ms": start_ms, "end_ms": end_ms},
        "spatial": spatial,
        "predicates": predicates or [],
        "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
        "similarity": None,
        "result": {"mode": "TRAJECTORY_IDS", "k": None, "tie_breaker": "TID_ASC"},
        "snapshot": {
            "manifest_id": "tdrive_v1_ready",
            "semantics_version": "point_dtw_v1",
        },
    }


def build_bound_workload(regions: dict) -> list[dict]:
    queries = []
    for reg_name, _, _ in POIS:
        spat = meters_envelope(regions[reg_name])
        spat = dict(spat)
        spat["region_name"] = reg_name
        for wid, t0, t1, _, _ in WINDOWS:
            qid = f"poi_{reg_name}_{wid}"
            queries.append(make_bound(qid, spat, t0, t1))
        # vehicle + POI on day-scale window (short windows often miss taxi 8857 downtown)
        _, t0, t1, _, _ = WINDOWS[2]
        qid = f"poi_{reg_name}_veh_w_day"
        queries.append(make_bound(
            qid, spat, t0, t1,
            predicates=[{"field": "vehicle_id", "op": "EQ", "value": "8857"}],
        ))
    return queries


def load_oracle_ids(oracle_path: Path) -> dict[str, list]:
    if not oracle_path.is_file():
        return {}
    d = json.loads(oracle_path.read_text(encoding="utf-8"))
    out = {}
    for row in d.get("answers") or []:
        out[row["query_id"]] = row.get("trajectory_ids") or []
    return out


def nl_supported_items(oracle_ids: dict[str, list]) -> list[dict]:
    items = []
    for reg_name, zh, aliases in POIS:
        for wid, _, _, iso0, iso1 in WINDOWS:
            qid_bound = f"poi_{reg_name}_{wid}"
            gold = oracle_ids.get(qid_bound)
            if not gold:
                continue
            for i, alias in enumerate(aliases[:2]):
                if i == 0 and any("\u4e00" <= c <= "\u9fff" for c in alias):
                    utt = f"查找 {iso0} 至 {iso1} 期间经过{alias}的轨迹，返回轨迹ID"
                elif any("\u4e00" <= c <= "\u9fff" for c in alias):
                    utt = f"列出{iso0}到{iso1}进入{alias}一带的轨迹ID"
                else:
                    utt = (
                        f"List trajectory ids intersecting {alias} between "
                        f"{iso0} and {iso1}"
                    )
                items.append({
                    "query_id": f"e1_{qid_bound}_{i}",
                    "utterance": utt,
                    "reject_expected": False,
                    "layer": "poi_spatial_temporal",
                    "gold_bound_ir_ref": qid_bound,
                    "gold_trajectory_ids": gold,
                    "region_name": reg_name,
                })
        # vehicle + POI (only if FullScan returned hits)
        qid_bound = f"poi_{reg_name}_veh_w_day"
        gold = oracle_ids.get(qid_bound) or []
        if gold:
            _, _, _, iso0, iso1 = WINDOWS[2]
            items.append({
                "query_id": f"e1_{qid_bound}_0",
                "utterance": (
                    f"查询出租车8857在{iso0}至{iso1}期间经过{zh}的轨迹"
                ),
                "reject_expected": False,
                "layer": "poi_st_vehicle",
                "gold_bound_ir_ref": qid_bound,
                "gold_trajectory_ids": gold,
                "region_name": reg_name,
            })
            items.append({
                "query_id": f"e1_{qid_bound}_1",
                "utterance": (
                    f"Show trajectories of taxi 8857 that intersect {reg_name} "
                    f"between {iso0} and {iso1}"
                ),
                "reject_expected": False,
                "layer": "poi_st_vehicle",
                "gold_bound_ir_ref": qid_bound,
                "gold_trajectory_ids": gold,
                "region_name": reg_name,
            })
    return items


def nl_clarify_items() -> list[dict]:
    items = []
    templates = [
        ("e1_c_poi_{rn}_notime", "查找经过{zh}的轨迹", "clarify_missing_time"),
        ("e1_c_poi_{rn}_vague", "昨天{zh}附近有哪些车", "clarify_vague"),
        ("e1_c_poi_{rn}_rel", "最近谁经过了{zh}", "clarify_relative_time"),
    ]
    for reg_name, zh, _ in POIS:
        for tid, utt, layer in templates:
            items.append({
                "query_id": tid.format(rn=reg_name),
                "utterance": utt.format(zh=zh),
                "reject_expected": False,
                "clarify_expected": True,
                "layer": layer,
                "region_name": reg_name,
            })
    return items


def main() -> None:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit-bound", action="store_true")
    ap.add_argument("--assemble", action="store_true")
    args = ap.parse_args()

    regions = parse_regions(REGIONS_YAML)

    if args.emit_bound:
        queries = build_bound_workload(regions)
        doc = {
            "workload_id": "bound_ir_poi_e1",
            "manifest_id": "tdrive_v1_ready",
            "semantics_version": "point_dtw_v1",
            "note": "POI region BoundIRs for E1 nl_ir_v3; spatial from regions.yaml via EPSG:32650 envelope",
            "query_count": len(queries),
            "queries": queries,
        }
        BOUND_OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {BOUND_OUT} n={len(queries)}")
        return

    if args.assemble:
        v2 = json.loads(WL_V2.read_text(encoding="utf-8"))
        oracle_ids = load_oracle_ids(ORACLE_OUT)
        if not oracle_ids:
            raise SystemExit(f"missing oracle answers: {ORACLE_OUT}")
        added = nl_supported_items(oracle_ids) + nl_clarify_items()
        seen = {q["query_id"] for q in v2["queries"]}
        new_q = []
        for q in added:
            if q["query_id"] in seen:
                continue
            seen.add(q["query_id"])
            new_q.append(q)
        queries = list(v2["queries"]) + new_q
        non_empty = sum(1 for q in new_q if q.get("gold_trajectory_ids"))
        doc = {
            "workload_id": "nl_ir_v3",
            "manifest_id": "tdrive_v1_ready",
            "semantics_version": "point_dtw_v1",
            "seed": "2026-10-11-e1-poi-v3",
            "parent_nl_ir": "nl_ir_v2",
            "poi_bound_ir": "bound_ir_poi_e1",
            "query_count": len(queries),
            "queries": queries,
            "note": (
                "nl_ir_v2 plus POI region NL (国贸/首都机场/中关村/…). "
                f"added={len(new_q)} supported_nonempty_gold={non_empty}"
            ),
        }
        NL_V3.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {NL_V3} total={len(queries)} added={len(new_q)} nonempty_gold={non_empty}")
        from collections import Counter
        print("added layers", dict(Counter(q["layer"] for q in new_q)))
        return

    ap.error("pass --emit-bound or --assemble")


if __name__ == "__main__":
    main()
