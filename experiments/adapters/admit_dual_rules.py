"""Admit dual rules from paired train measurements; rebuild disjoint long/wide eval."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIN_SUPPORT = 3
MIN_SAVING_MS = 8
TRAIN_KEY = "training_queries"

TINY_AREA = 2.0
TINY_SPAN = 900.0


def _feats(q):
    t = q.get("temporal") or {}
    s = q.get("spatial") or {}
    span = None
    if t.get("end_ms") is not None and t.get("start_ms") is not None:
        span = (t["end_ms"] - t["start_ms"]) / 1000.0
    area = None
    if s:
        area = (s["max_x"] - s["min_x"]) * (s["max_y"] - s["min_y"]) / 1e6
    return span, area


def is_tiny(q) -> bool:
    span, area = _feats(q)
    return (area is not None and area < TINY_AREA) or (
        span is not None and span < TINY_SPAN and (area is None or area < 10)
    )


def _slim_set(s):
    if not s:
        return None
    if isinstance(s, str):
        try:
            s = json.loads(s)
        except json.JSONDecodeError:
            return None
    if not isinstance(s, dict):
        return None
    out = {}
    a = s.get("access")
    if isinstance(a, str) and a.startswith("drop_"):
        out["access"] = a
    fb = s.get("fetch_batch")
    if isinstance(fb, str) and fb.lower().startswith("b") and fb.lower() not in ("b128", "b256", "b500"):
        out["fetch_batch"] = fb.lower()
    rb = s.get("range_budget")
    if isinstance(rb, str) and rb.lower() in ("r256", "r128"):
        out["range_budget"] = rb.lower()
    if s.get("meta_probe") == "on":
        out["meta_probe"] = "on"
    tk = s.get("topk")
    if isinstance(tk, str) and tk.startswith("lb"):
        out["topk"] = tk
    return out or None


def _canon_set(s):
    slim = _slim_set(s)
    if not slim:
        return None
    return json.dumps(slim, sort_keys=True, separators=(",", ":"))


def _load(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def split_eval(root: Path) -> dict:
    rules = _load(root / "experiments/suites/dual_action_rules.json")
    train = set(rules[TRAIN_KEY])
    cmp = _load(root / "experiments/workloads/bound_ir_cmp_v3.json")
    ora = _load(root / "experiments/workloads/bound_ir_cmp_v3.oracle.json")
    ora_by = {a["query_id"]: a for a in ora["answers"]}
    keep, overlap = [], []
    for q in cmp["queries"]:
        if q["query_id"] in train:
            overlap.append(q)
        else:
            keep.append(q)
    extra_src = [
        (root / "experiments/workloads/bound_ir_holdout_v2_test.json",
         root / "experiments/workloads/bound_ir_holdout_v2_test.oracle.json"),
        (root / "experiments/workloads/bound_ir_holdout_v2_val.json",
         root / "experiments/workloads/bound_ir_holdout_v2_val.oracle.json"),
        (root / "experiments/workloads/bound_ir_v1.json",
         root / "experiments/workloads/bound_ir_v1.oracle.json"),
        (root / "experiments/workloads/bound_ir_holdout_v2_train.json",
         root / "experiments/workloads/bound_ir_holdout_v2_train.oracle.json"),
    ]
    have = {q["query_id"] for q in keep}
    have |= train
    fills_q, fills_a, fill_ids = [], [], []
    need = 98 - len(keep)
    for wp, op in extra_src:
        if need <= 0:
            break
        w = _load(wp)
        o = {a["query_id"]: a for a in _load(op)["answers"]}
        for q in w["queries"]:
            if need <= 0:
                break
            qid = q["query_id"]
            if qid in have or qid not in o or is_tiny(q):
                continue
            fills_q.append(q)
            fills_a.append(o[qid])
            fill_ids.append(qid)
            have.add(qid)
            need -= 1
    if len(keep) + len(fills_q) != 98:
        raise SystemExit("eval fill %s keep %s need 98" % (len(fills_q), len(keep)))
    train_q = overlap
    train_a = [ora_by[q["query_id"]] for q in overlap]
    cmp["queries"] = keep + fills_q
    ora["answers"] = [ora_by[q["query_id"]] for q in keep] + fills_a
    ids = [q["query_id"] for q in cmp["queries"]]
    if set(ids) != {a["query_id"] for a in ora["answers"]} or len(ids) != 98:
        raise SystemExit("eval/oracle mismatch")
    if set(ids) & train:
        raise SystemExit("eval still overlaps train: %s" % sorted(set(ids) & train))

    tw = {
        "manifest_id": cmp.get("manifest_id", "tdrive_v1_ready"),
        "evaluation_role": "dual_rule_train_disjoint",
        "queries": train_q,
    }
    to = {"answers": train_a}
    twp = root / "experiments/workloads/bound_ir_dual_train.json"
    top = root / "experiments/workloads/bound_ir_dual_train.oracle.json"
    twp.write_text(json.dumps(tw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    top.write_text(json.dumps(to, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cmp_p = root / "experiments/workloads/bound_ir_cmp_v3.json"
    ora_p = root / "experiments/workloads/bound_ir_cmp_v3.oracle.json"
    cmp_p.write_text(json.dumps(cmp, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ora_p.write_text(json.dumps(ora, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    prov_p = root / "experiments/workloads/bound_ir_cmp_v3.provenance.json"
    prov = _load(prov_p)
    prov["n_queries"] = 98
    prov["n_oracle_copied"] = 98
    prov["sha256"] = hashlib.sha256(cmp_p.read_bytes()).hexdigest()
    prov["oracle_sha256"] = hashlib.sha256(ora_p.read_bytes()).hexdigest()
    prov["dual_train_disjoint"] = {
        "removed_from_eval": [q["query_id"] for q in overlap],
        "filled": fill_ids,
        "train_workload": "experiments/workloads/bound_ir_dual_train.json",
    }
    prov_p.write_text(json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {
        "eval_n": 98,
        "removed": [q["query_id"] for q in overlap],
        "filled": fill_ids,
        "train_n": len(train_q),
    }


def _features(row):
    ctx = row.get("llm_context") or {}
    if isinstance(ctx, str):
        try:
            ctx = json.loads(ctx)
        except json.JSONDecodeError:
            ctx = {}
    feats = ctx.get("features") if isinstance(ctx, dict) else None
    return feats if isinstance(feats, dict) else {}


def _program(row):
    for k in ("llm_program", "program", "gate_best_program"):
        p = row.get(k)
        if isinstance(p, dict) and p:
            return p
    return None


def admit_from_pairs(base: dict, rows: list) -> dict:
    by_arm = defaultdict(dict)
    for r in rows:
        if r.get("query_id") and r.get("arm"):
            by_arm[r["arm"]][r["query_id"]] = r
    kart = by_arm.get("kart") or by_arm.get("cbo-llm-rewrite") or {}
    cbo = by_arm.get("cbo") or {}
    buckets = defaultdict(list)
    for qid, kr in kart.items():
        cr = cbo.get(qid)
        if not cr or kr.get("t_e2e_ms") is None or cr.get("t_e2e_ms") is None:
            continue
        saving = cr["t_e2e_ms"] - kr["t_e2e_ms"]
        if saving < MIN_SAVING_MS:
            continue
        src = kr.get("final_selection_source") or ""
        prog = _program(kr)
        if kr.get("gate_decision") == "SKIP" or src in ("cbo", "", None):
            continue
        key = _canon_set(prog)
        if not key:
            continue
        buckets[key].append((qid, saving, _features(kr), json.loads(key)))
    existing = {_canon_set(r.get("set")) for r in base.get("rules") or []}
    scope = None
    sha = base.get("source_sha256")
    for r in base.get("rules") or []:
        if r.get("scope"):
            scope = r["scope"]
            sha = r.get("source_sha256") or sha
            break
    new_rules = list(base.get("rules") or [])
    added = []
    for key, hits in buckets.items():
        if key in existing or len(hits) < MIN_SUPPORT:
            continue
        plans = [h[2].get("cbo_plan") for h in hits if h[2].get("cbo_plan")]
        if len(set(plans)) != 1:
            continue
        chunks = [h[2].get("chunks_keep") for h in hits if isinstance(h[2].get("chunks_keep"), (int, float))]
        zr = [h[2].get("z_ranges") for h in hits if isinstance(h[2].get("z_ranges"), (int, float))]
        when = [["cbo_plan", "==", plans[0]]]
        if chunks:
            when.append(["chunks_keep", ">=", int(min(chunks))])
        if zr:
            when.append(["z_ranges", ">=", int(min(zr))])
        metrics = [h[2].get("metric") for h in hits if h[2].get("metric")]
        if len(set(metrics)) == 1:
            when.append(["metric", "==", metrics[0]])
        rid = "adm_%s_%s" % (plans[0].lower(), hashlib.sha256(key.encode()).hexdigest()[:8])
        rule = {
            "id": rid,
            "when": when,
            "set": hits[0][3],
            "status": "confirmed",
            "admission": "train_measured_v1",
            "source_sha256": sha,
            "support": len(hits),
            "scope": scope,
            "evidence": [{"query_id": h[0], "saving_ms": int(h[1]), "trials": 1} for h in hits],
            "training_min_saving_ms": MIN_SAVING_MS,
        }
        new_rules.append(rule)
        added.append(rid)
        existing.add(key)
    out = dict(base)
    out["rules"] = new_rules
    return {"doc": out, "added": added}


def _scale_spatial(q, factor, new_id):
    out = json.loads(json.dumps(q))
    out["query_id"] = new_id
    s = out.get("spatial")
    if not s:
        return None
    cx = (s["min_x"] + s["max_x"]) / 2.0
    cy = (s["min_y"] + s["max_y"]) / 2.0
    hx = (s["max_x"] - s["min_x"]) * factor / 2.0
    hy = (s["max_y"] - s["min_y"]) * factor / 2.0
    out["spatial"] = {"min_x": cx - hx, "max_x": cx + hx, "min_y": cy - hy, "max_y": cy + hy}
    return out


def _drop(q, field, new_id):
    out = json.loads(json.dumps(q))
    out["query_id"] = new_id
    out[field] = None
    return out


def expand_train(root: Path) -> dict:
    eval_ids = {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_cmp_v3.json")["queries"]}
    dt = _load(root / "experiments/workloads/bound_ir_dual_train.json")
    by = {q["query_id"]: q for q in dt["queries"]}
    extra = []
    for name in (
        "experiments/workloads/bound_ir_holdout_v2_train.json",
        "experiments/workloads/bound_ir_holdout_v2.json",
    ):
        for q in _load(root / name)["queries"]:
            if q["query_id"] not in eval_ids and not is_tiny(q):
                extra.append(q)
    skip_huge = {"a2_tz_hard_b", "a2_tz_hard_c", "a2_tz_hard_e", "a2_tz_hard_f"}
    seen = set()
    qs = []
    for q in list(dt["queries"]) + extra:
        if q["query_id"] in skip_huge:
            continue
        if q["query_id"] in seen or q["query_id"] in eval_ids:
            continue
        seen.add(q["query_id"])
        qs.append(q)
    h = by.get("a2_tz_hard_h")
    g = by.get("a2_tz_hard_g")
    synth = []
    if h:
        synth += [x for x in (
            _scale_spatial(h, 0.35, "adm_tz_h_mid"),
            _drop(h, "spatial", "adm_t_h"),
            _drop(h, "temporal", "adm_z_h"),
        ) if x]
    if g:
        synth += [x for x in (
            _scale_spatial(g, 0.35, "adm_tz_g_mid"),
            _drop(g, "spatial", "adm_t_g"),
        ) if x]
    for q in synth:
        if q["query_id"] not in seen and not is_tiny(q):
            qs.append(q)
            seen.add(q["query_id"])
    outp = root / "experiments/workloads/bound_ir_dual_train_expand.json"
    outp.write_text(json.dumps({"manifest_id": "tdrive_v1_ready", "queries": qs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"n": len(qs), "ids": [q["query_id"] for q in qs], "path": str(outp)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split-eval", action="store_true")
    ap.add_argument("--expand-train", action="store_true")
    ap.add_argument("--paired")
    ap.add_argument("--base", default="experiments/suites/dual_action_rules.json")
    ap.add_argument("--out")
    args = ap.parse_args()
    if args.split_eval:
        print(json.dumps(split_eval(ROOT), indent=2))
        return
    if args.expand_train:
        print(json.dumps(expand_train(ROOT), indent=2))
        return
    if args.paired:
        base = _load(Path(args.base))
        rows = [json.loads(l) for l in Path(args.paired).read_text(encoding="utf-8").splitlines() if l.strip()]
        res = admit_from_pairs(base, rows)
        dest = Path(args.out or args.base)
        dest.write_text(json.dumps(res["doc"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"added": res["added"], "n_rules": len(res["doc"]["rules"])}))
        return
    ap.error("use --split-eval, --expand-train or --paired")


if __name__ == "__main__":
    main()
