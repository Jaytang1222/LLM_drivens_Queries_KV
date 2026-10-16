#!/usr/bin/env python3
"""Frag suite + control queries (no dataset transform)."""
import json
from pathlib import Path
from gen_adv_frag_workload import tile_aois, split_windows, base_q

SRC = Path("experiments/workloads/bound_ir_complex_v1.json")
FRAG = Path("experiments/workloads/bound_ir_adv_frag_v1.json")
OUT = Path("experiments/workloads/bound_ir_adv_frag_ctrl_v1.json")


def main():
    frag = json.loads(FRAG.read_text(encoding="utf-8"))
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
        if len(fat) >= 2:
            break

    controls = []
    # Fat single-rect ST (same data window as source) — ADV may still compete.
    for i, q in enumerate(fat, 1):
        qc = base_q(q, f"ctrl_fat_{i}")
        qc["temporal"] = dict(q["temporal"])
        qc["spatial"] = dict(q["spatial"])
        controls.append(qc)

    # Short window + spatial — must force classic (no ADV inject).
    q0 = fat[0]
    qs = base_q(q0, "ctrl_short_1")
    qs["spatial"] = dict(q0["spatial"])
    mid = (q0["temporal"]["start_ms"] + q0["temporal"]["end_ms"]) // 2
    qs["temporal"] = {"start_ms": mid, "end_ms": mid + 2 * 3600 * 1000}
    controls.append(qs)

    # Vehicle EQ + fat ST — force classic.
    # Use a placeholder id; empty result is OK for plan-choice / timing.
    qv = base_q(q0, "ctrl_veh_1")
    qv["temporal"] = dict(q0["temporal"])
    qv["spatial"] = dict(q0["spatial"])
    qv["predicates"] = [{"field": "vehicle_id", "op": "EQ", "value": "1"}]
    controls.append(qv)

    out = {
        "workload_id": "bound_ir_adv_frag_ctrl_v1",
        "manifest_id": frag.get("manifest_id", "tdrive_v1_ready"),
        "evaluation_role": "adv_frag_plus_control",
        "queries": list(frag["queries"]) + controls,
    }
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {OUT} n={len(out['queries'])}")
    for q in out["queries"]:
        print(" ", q["query_id"])


if __name__ == "__main__":
    main()
