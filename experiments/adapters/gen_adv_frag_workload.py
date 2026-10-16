#!/usr/bin/env python3
"""Build frag AOI / multi-window BoundIR workload (data unchanged)."""
import json
from pathlib import Path

SRC = Path("experiments/workloads/bound_ir_complex_v1.json")
OUT = Path("experiments/workloads/bound_ir_adv_frag_v1.json")


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
            cells.append({
                "min_x": x0 + sx,
                "min_y": y0 + sy,
                "max_x": x1 - sx,
                "max_y": y1 - sy,
                "relation": "INTERSECTS",
                "boundary": "INCLUDED",
            })
    # Prefer corners + edges: drop center-ish cells first
    order = [0, 2, 6, 8, 1, 3, 5, 7, 4]  # 3x3 indices
    picked = []
    for i in order:
        if i < len(cells) and len(picked) < keep:
            picked.append(cells[i])
    return picked


def split_windows(temporal, n=6):
    s, e = temporal["start_ms"], temporal["end_ms"]
    span = e - s
    # short slices with gaps between (OR of short windows)
    slice_ms = max(span // (n * 2), 10 * 60 * 1000)  # at least 10min
    step = span // n
    parts = []
    for i in range(n):
        a = s + i * step
        b = min(e, a + slice_ms)
        if b > a:
            parts.append({"start_ms": int(a), "end_ms": int(b)})
    return parts


def base_q(q, qid):
    out = {
        "ir_version": "1.0",
        "query_id": qid,
        "source": q["source"],
        "predicates": [],
        "semantics": q.get("semantics", {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"}),
        "result": {"mode": "TRAJECTORY_IDS", "tie_breaker": "TID_ASC"},
        "snapshot": q["snapshot"],
    }
    # drop similarity/topk for clean ST filter proof
    return out


def main():
    data = json.loads(SRC.read_text(encoding="utf-8"))
    fat = []
    for q in data["queries"]:
        t, s = q.get("temporal"), q.get("spatial")
        if not t or not s:
            continue
        if t["end_ms"] - t["start_ms"] < 12 * 3600 * 1000:
            continue
        if q.get("similarity") or q.get("result", {}).get("mode") == "TOP_K":
            continue
        fat.append(q)
    # diversify: take up to 4 sources
    seen = []
    for q in fat:
        if len(seen) >= 4:
            break
        seen.append(q)

    queries = []
    for i, q in enumerate(seen, 1):
        # multi-AOI (6 tiles), single fat temporal
        qa = base_q(q, f"frag_aoi_{i}")
        qa["temporal"] = dict(q["temporal"])
        parts = tile_aois(q["spatial"], keep=6)
        qa["spatial_parts"] = parts
        queries.append(qa)

        # multi-window (6), single fat spatial bbox
        qt = base_q(q, f"frag_twin_{i}")
        qt["spatial"] = dict(q["spatial"])
        qt["temporal_parts"] = split_windows(q["temporal"], n=6)
        queries.append(qt)

        # combo: 4 AOIs + 4 windows
        qc = base_q(q, f"frag_combo_{i}")
        qc["spatial_parts"] = tile_aois(q["spatial"], keep=4)
        qc["temporal_parts"] = split_windows(q["temporal"], n=4)
        queries.append(qc)

    out = {
        "workload_id": "bound_ir_adv_frag_v1",
        "manifest_id": data.get("manifest_id", "tdrive_v1_ready"),
        "evaluation_role": "adv_frag_aoi_multiwindow",
        "queries": queries,
    }
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {OUT} n={len(queries)}")
    for q in queries:
        ns = len(q.get("spatial_parts") or ([1] if q.get("spatial") else []))
        nt = len(q.get("temporal_parts") or ([1] if q.get("temporal") else []))
        print(f"  {q['query_id']}: spatial_parts={ns if 'spatial_parts' in q else 0} "
              f"spatial={1 if q.get('spatial') else 0} "
              f"temporal_parts={nt if 'temporal_parts' in q else 0} "
              f"temporal={1 if q.get('temporal') else 0}")


if __name__ == "__main__":
    main()
