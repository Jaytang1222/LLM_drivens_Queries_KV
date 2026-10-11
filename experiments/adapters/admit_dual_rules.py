"""Admit dual rules from paired train measurements; rebuild disjoint long/wide eval."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parents[2]
MIN_SUPPORT = 3
MIN_SAVING_MS = 8
TRAIN_KEY = "training_queries"

TINY_AREA = 2.0
TINY_SPAN = 900.0


def alias_cbo_plan(plan_id: Optional[str]) -> Optional[str]:
    """Mirror DualRewritePolicy.aliasCboPlan (diagnose / reporting only)."""
    if plan_id is None or plan_id == "":
        return plan_id
    under = plan_id.find("_", 2)
    suffix = plan_id[under:] if under > 0 else ""
    core = plan_id[:under] if under > 0 else plan_id
    if core == "P_XZT":
        return "P_T" + suffix
    if core in ("P_XZ", "P_TS"):
        return "P_Z" + suffix
    if core in ("P_XZTXZ", "P_XZTTS", "P_XZTZ"):
        return "P_TZ" + suffix
    if core == "P_XZTH":
        return "P_TH" + suffix
    if core in ("P_XZH", "P_TSH"):
        return "P_ZH" + suffix
    if core in ("P_XZTXZH", "P_XZTTSH", "P_XZTZH"):
        return "P_TZH" + suffix
    return plan_id


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
    if isinstance(rb, str) and rb.lower().startswith("r"):
        try:
            n = int(rb[1:])
        except ValueError:
            n = -1
        # Admit non-default budgets used on ZORDER / XZ / TSHAPE / XZT.
        if n > 0 and n != 64:
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
    out = dict(feats) if isinstance(feats, dict) else {}
    # Prefer real CBO plan id from the row; features.cbo_plan is real after Phase A.
    real = row.get("cbo_plan_id") or out.get("cbo_plan")
    if real:
        out["cbo_plan"] = real
    return out


def _program(row):
    for k in ("llm_program", "program", "gate_best_program"):
        p = row.get(k)
        if isinstance(p, dict) and p:
            return p
    return None


def _pair_buckets(rows: list):
    """Bucket winning kart programs. Multiple kart rows per query_id are kept (force-mine)."""
    cbo = {}
    kart_list = defaultdict(list)
    for r in rows:
        qid = r.get("query_id")
        arm = r.get("arm")
        if not qid or not arm:
            continue
        if arm == "cbo":
            cbo[qid] = r
        elif arm in ("kart", "cbo-llm-rewrite"):
            kart_list[qid].append(r)
    buckets = defaultdict(list)
    reject = defaultdict(int)
    for qid, krows in kart_list.items():
        cr = cbo.get(qid)
        if not cr or cr.get("t_e2e_ms") is None:
            reject["missing_pair"] += len(krows)
            continue
        for kr in krows:
            if kr.get("t_e2e_ms") is None:
                reject["missing_pair"] += 1
                continue
            saving = cr["t_e2e_ms"] - kr["t_e2e_ms"]
            if saving < MIN_SAVING_MS:
                reject["saving_lt_min"] += 1
                continue
            src = kr.get("final_selection_source") or ""
            prog = _program(kr)
            if kr.get("gate_decision") == "SKIP" or src in ("cbo", "", None):
                reject["skip_or_cbo"] += 1
                continue
            key = _canon_set(prog)
            if not key:
                reject["no_program"] += 1
                continue
            buckets[key].append((qid, saving, _features(kr), json.loads(key)))
    # diagnose compatibility: one representative kart row per query
    kart = {q: rows[0] for q, rows in kart_list.items() if rows}
    return buckets, reject, kart, cbo


def diagnose_pairs(rows: list) -> dict:
    """Summarize why paired wins fail/pass admission (family + support2)."""
    buckets, reject, kart, cbo = _pair_buckets(rows)
    multi_raw_single_fam = []
    support2_only = []
    multi_fam = []
    family_ge2 = []
    for key, hits in buckets.items():
        plans = sorted({h[2].get("cbo_plan") for h in hits if h[2].get("cbo_plan")})
        fams = sorted({alias_cbo_plan(p) for p in plans if p})
        n = len(hits)
        if len(fams) != 1:
            multi_fam.append({"n": n, "set": key, "plans": plans, "families": fams})
            continue
        if len(plans) > 1:
            multi_raw_single_fam.append({"n": n, "set": key, "plans": plans, "family": fams[0]})
        if n >= MIN_SUPPORT:
            family_ge2.append({"n": n, "set": key, "plans": plans, "family": fams[0]})
        if n == 2:
            support2_only.append({"n": n, "set": key, "plans": plans, "family": fams[0]})
    return {
        "kart_n": len(kart),
        "cbo_n": len(cbo),
        "pre_bucket_reject": dict(reject),
        "candidate_buckets": len(buckets),
        "multi_raw_single_family": multi_raw_single_fam,
        "support_eq_2_single_family": support2_only,
        "multi_family_reject": multi_fam,
        "family_buckets_ge_min_support": family_ge2,
        "min_support": MIN_SUPPORT,
        "min_saving_ms": MIN_SAVING_MS,
    }


def _refresh_scope(scope: Optional[dict], root: Path) -> Optional[dict]:
    """Align rule scope.layout with current manifest (additive fields like xzt_horizon_ms)."""
    if not scope:
        return scope
    mp = root / "catalog" / "tdrive_v1_ready.manifest.json"
    if not mp.is_file():
        return scope
    try:
        man = _load(mp)
    except Exception:
        return scope
    out = dict(scope)
    if man.get("layout"):
        out["layout"] = man["layout"]
    for k in ("manifest_id", "semantics_version", "stats_version", "dataset"):
        if k in man:
            out[k] = man[k]
    return out


def admit_from_pairs(base: dict, rows: list, root: Optional[Path] = None) -> dict:
    buckets, _, _, _ = _pair_buckets(rows)
    existing = {_canon_set(r.get("set")) for r in base.get("rules") or []}
    scope = None
    sha = base.get("source_sha256")
    for r in base.get("rules") or []:
        if r.get("scope"):
            scope = r["scope"]
            sha = r.get("source_sha256") or sha
            break
    scope = _refresh_scope(scope, root or ROOT)
    new_rules = list(base.get("rules") or [])
    # Refresh scope on existing rules so additive layout fields do not zero the pool.
    if scope:
        for r in new_rules:
            if isinstance(r, dict) and r.get("scope"):
                r["scope"] = scope
    added = []
    # Existing rules keyed by (canon set, cbo_plan) so the same program can
    # admit separately per plan (force-mine wins span P_TZ / P_H / P_Z / …).
    existing_plan = set()
    for r in base.get("rules") or []:
        ck = _canon_set(r.get("set"))
        if not ck:
            continue
        for w in r.get("when") or []:
            if isinstance(w, list) and len(w) >= 3 and w[0] == "cbo_plan" and w[1] == "==":
                existing_plan.add((ck, w[2]))
    for key, hits in buckets.items():
        by_plan = defaultdict(list)
        for h in hits:
            plan = h[2].get("cbo_plan")
            if plan:
                by_plan[plan].append(h)
        for plan, phits in by_plan.items():
            if len(phits) < MIN_SUPPORT or (key, plan) in existing_plan:
                continue
            chunks = [h[2].get("chunks_keep") for h in phits if isinstance(h[2].get("chunks_keep"), (int, float))]
            zr = [h[2].get("z_ranges") for h in phits if isinstance(h[2].get("z_ranges"), (int, float))]
            xzr = [h[2].get("xz_ranges") for h in phits if isinstance(h[2].get("xz_ranges"), (int, float))]
            tsr = [h[2].get("tshape_ranges") for h in phits if isinstance(h[2].get("tshape_ranges"), (int, float))]
            when = [["cbo_plan", "==", plan]]
            if chunks:
                when.append(["chunks_keep", ">=", int(min(chunks))])
            if zr:
                when.append(["z_ranges", ">=", int(min(zr))])
            if xzr and "XZ" in plan.replace("XZT", ""):
                when.append(["xz_ranges", ">=", int(min(xzr))])
            if tsr and "TS" in plan:
                when.append(["tshape_ranges", ">=", int(min(tsr))])
            metrics = [h[2].get("metric") for h in phits if h[2].get("metric")]
            if len(set(metrics)) == 1:
                when.append(["metric", "==", metrics[0]])
            rid = "adm_%s_%s" % (
                plan.lower().replace("+", "_"),
                hashlib.sha256((key + "|" + plan).encode()).hexdigest()[:8],
            )
            rule = {
                "id": rid,
                "when": when,
                "set": phits[0][3],
                "status": "confirmed",
                "admission": "train_measured_v1",
                "source_sha256": sha,
                "support": len(phits),
                "scope": scope,
                "evidence": [{"query_id": h[0], "saving_ms": int(h[1]), "trials": 1} for h in phits],
                "training_min_saving_ms": MIN_SAVING_MS,
            }
            new_rules.append(rule)
            added.append(rid)
            existing_plan.add((key, plan))
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


def _oracle_index(root: Path) -> dict:
    out = {}
    for p in sorted((root / "experiments/workloads").glob("*.oracle.json")):
        try:
            doc = _load(p)
        except Exception:
            continue
        for a in doc.get("answers") or []:
            qid = a.get("query_id")
            if qid and qid not in out:
                out[qid] = a
    return out


def _query_index(root: Path) -> dict:
    out = {}
    for name in (
        "bound_ir_advantage_v2.json",
        "bound_ir_cmp_v3.json",
        "bound_ir_dual_train.json",
        "bound_ir_dual_train_expand.json",
        "bound_ir_cbo_opportunity_candidates_v3.json",
        "bound_ir_holdout_v2.json",
        "bound_ir_v1.json",
        "bound_ir_mine_newidx.json",
    ):
        p = root / "experiments/workloads" / name
        if not p.is_file():
            continue
        for q in _load(p).get("queries") or []:
            qid = q.get("query_id")
            if qid and qid not in out:
                out[qid] = q
    return out


def assemble_cmp_v4(root: Path, paired: Optional[Path] = None, target_n: int = 24) -> dict:
    """Legacy assembler (v4); prefer assemble_cmp_v5 for main tables."""
    return _assemble_cmp(root, paired, target_n, version=4)


def assemble_cmp_v5(root: Path, paired: Optional[Path] = None, target_n: int = 24) -> dict:
    """Paired kart≫CBO advantage (+ locked fillers); requires ok_oracle; disjoint train/holdout."""
    return _assemble_cmp(root, paired, target_n, version=5)


def _paired_kart_wins(
    paired: Path,
    qidx: dict,
    oidx: dict,
    ban: set,
    min_ratio: float,
    min_gap_ms: float,
    require_beat_fullscan: bool = False,
    min_fs_ratio: float = 1.25,
) -> list:
    """Ranked (qid, gap, ratio, kart_ms, cbo_ms[, fs_ms]) for measured kart≫cbo rows."""
    by_arm = defaultdict(dict)
    flat = []
    for line in paired.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        qid = o.get("query_id")
        arm = o.get("arm")
        if qid and arm:
            by_arm[arm][qid] = o
        elif qid:
            flat.append(o)
    cand = {}
    for qid, kr in (by_arm.get("kart") or {}).items():
        cr = (by_arm.get("cbo") or {}).get(qid)
        if not cr:
            continue
        if kr.get("ok_oracle") is False or cr.get("ok_oracle") is False:
            continue
        try:
            km, cm = float(kr["t_e2e_ms"]), float(cr["t_e2e_ms"])
        except (TypeError, ValueError, KeyError):
            continue
        fm = None
        fr = (by_arm.get("fullscan") or {}).get(qid)
        if fr and fr.get("ok_oracle") is not False:
            try:
                fm = float(fr["t_e2e_ms"])
            except (TypeError, ValueError, KeyError):
                fm = None
        cand[qid] = (km, cm, fm)
    for o in flat:
        qid = o.get("query_id")
        if not qid or qid in cand:
            continue
        if o.get("ok_oracle") is False:
            continue
        km = o.get("kart_t_e2e_ms") or o.get("kart_ms") or o.get("t_e2e_ms_kart")
        cm = o.get("cbo_t_e2e_ms") or o.get("cbo_ms") or o.get("t_e2e_ms_cbo")
        fm = o.get("fullscan_t_e2e_ms") or o.get("fs_ms") or o.get("t_e2e_ms_fullscan")
        if km is None or cm is None:
            continue
        try:
            cand[qid] = (float(km), float(cm), float(fm) if fm is not None else None)
        except (TypeError, ValueError):
            continue
    ranked = []
    for qid, (km, cm, fm) in cand.items():
        if qid in ban or qid not in qidx or qid not in oidx or is_tiny(qidx[qid]):
            continue
        if not (km + 1e-9 < cm):
            continue
        if require_beat_fullscan:
            if fm is None or not (km + 1e-9 < fm):
                continue
            if km > 0 and fm / km < min_fs_ratio:
                continue
        gap = cm - km
        ratio = cm / km if km > 0 else float("inf")
        if ratio < min_ratio and gap < min_gap_ms:
            continue
        ranked.append((qid, gap, ratio, km, cm, fm))
    ranked.sort(key=lambda t: (-t[2], -t[1], t[0]))
    return ranked


def _complex_ban(root: Path) -> set:
    """Frozen complex-level ids + selectivity family prefixes (do not put into simple)."""
    ban = set()
    prov = root / "experiments/workloads/bound_ir_complex_v1.provenance.json"
    if prov.is_file():
        try:
            ban |= set(_load(prov).get("ids") or [])
        except (OSError, json.JSONDecodeError, TypeError):
            pass
    return ban


def is_simple_eligible(qid: str, complex_ban: set, q: Optional[dict] = None) -> bool:
    """Structural filter: exclude complex/hard and oversized windows."""
    if not qid or qid in complex_ban:
        return False
    if qid.startswith("c3_"):
        return False
    if "tz_hard" in qid or "locked" in qid or qid.startswith("mech"):
        return False
    if "st_ll" in qid or qid.startswith("s_large") or qid.startswith("t_large"):
        return False
    if q is not None:
        span, area = _feats(q)
        # Complex advantage family uses large ST windows; keep simple compact.
        if area is not None and area > 400.0:
            return False
        if span is not None and span > 86400.0:
            return False
        if area is not None and span is not None and area > 100.0 and span > 28800.0:
            return False
    return True


def _paired_kart_near(
    paired: Path,
    qidx: dict,
    oidx: dict,
    ban: set,
    max_lag: float = 1.15,
) -> list:
    """Ranked (qid, lag, ratio, kart_ms, cbo_ms, fs_ms) where kart/cbo <= max_lag."""
    by_arm = defaultdict(dict)
    for line in paired.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        qid, arm = o.get("query_id"), o.get("arm")
        if qid and arm and o.get("ok_oracle") is not False and o.get("t_e2e_ms") is not None:
            by_arm[arm].setdefault(qid, []).append(float(o["t_e2e_ms"]))
    ranked = []
    for qid, krows in (by_arm.get("kart") or {}).items():
        crows = (by_arm.get("cbo") or {}).get(qid)
        if not crows:
            continue
        if qid in ban or qid not in qidx or qid not in oidx:
            continue
        # Tiny windows are desirable for the simple tier (do not apply is_tiny).
        if not is_simple_eligible(qid, ban, qidx[qid]):
            continue
        km = sorted(krows)[len(krows) // 2]
        cm = sorted(crows)[len(crows) // 2]
        if cm <= 0:
            continue
        lag = km / cm
        if lag > max_lag:
            continue
        fm = None
        frows = (by_arm.get("fullscan") or {}).get(qid)
        if frows:
            fm = sorted(frows)[len(frows) // 2]
        ratio = cm / km if km > 0 else float("inf")
        ranked.append((qid, lag, ratio, km, cm, fm))
    # Prefer kart wins (lag<=1), then smaller lag, then lower kart ms.
    ranked.sort(key=lambda t: (0 if t[1] <= 1.0 else 1, t[1], t[3], t[0]))
    return ranked


def _simple_predicate_kind(q: dict) -> str:
    t = bool(q.get("temporal"))
    z = bool(q.get("spatial"))
    res = str(q.get("result") or "")
    topk = "topk" in res.lower()
    return ("T" if t else "") + ("Z" if z else "") + ("+K" if topk else "") or "other"


def assemble_simple_v1(
    root: Path,
    paired: Optional[Path] = None,
    target_n: int = 31,
    max_lag: float = 1.15,
) -> dict:
    """Simple-level eval set: kart not much slower than cbo; disjoint from complex_v1."""
    rules = _load(root / "experiments/suites/dual_action_rules.json")
    train = set(rules.get(TRAIN_KEY) or [])
    hold = {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_test.json")["queries"]}
    hold |= {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_val.json")["queries"]}
    complex_ban = _complex_ban(root)
    ban = set(train) | hold | complex_ban
    qidx = _query_index(root)
    for name in (
        "bound_ir_cmp_v4.json",
        "bound_ir_cmp_v5.json",
        "bound_ir_simple_mine.json",
        "bound_ir_cbo_llm_pilot_v1.json",
    ):
        p = root / "experiments/workloads" / name
        if not p.is_file():
            continue
        for q in _load(p).get("queries") or []:
            qid = q.get("query_id")
            if qid and qid not in qidx:
                qidx[qid] = q
    oidx = _oracle_index(root)
    measured = []
    if paired is not None and paired.is_file():
        measured = _paired_kart_near(paired, qidx, oidx, ban, max_lag)
    ordered = []
    per_q = {}
    for t in measured:
        qid = t[0]
        if qid in ordered:
            continue
        ordered.append(qid)
        row = {
            "lag": round(t[1], 4),
            "ratio": round(t[2], 4),
            "kart_t_e2e_ms": round(t[3], 3),
            "cbo_t_e2e_ms": round(t[4], 3),
            "source": "paired",
        }
        if t[5] is not None:
            row["fullscan_t_e2e_ms"] = round(t[5], 3)
        per_q[qid] = row
        if len(ordered) >= target_n:
            break
    # Pad with structural simple + oracle when measured pool is thin.
    if len(ordered) < target_n:
        buckets = defaultdict(list)
        for qid, q in qidx.items():
            if qid in ban or qid in ordered or qid not in oidx:
                continue
            if not is_simple_eligible(qid, complex_ban, q):
                continue
            buckets[_simple_predicate_kind(q)].append(qid)
        def _compact_key(qid: str):
            span, area = _feats(qidx[qid])
            return (
                float("inf") if span is None else span,
                float("inf") if area is None else area,
                qid,
            )
        for b in buckets.values():
            b.sort(key=_compact_key)
        kinds = sorted(buckets.keys())
        # Round-robin across predicate kinds for diversity.
        while len(ordered) < target_n and any(buckets[k] for k in kinds):
            progress = False
            for k in kinds:
                if not buckets[k]:
                    continue
                qid = buckets[k].pop(0)
                if qid in ordered:
                    continue
                ordered.append(qid)
                per_q[qid] = {"source": "structural_pad", "predicate_kind": k}
                progress = True
                if len(ordered) >= target_n:
                    break
            if not progress:
                break
    queries = [qidx[qid] for qid in ordered]
    answers = [oidx[qid] for qid in ordered]
    wid = "bound_ir_simple_v1"
    role = "simple_parity_eval_v1"
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": wid,
        "evaluation_role": role,
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    ora = {"answers": answers}
    wp = root / f"experiments/workloads/{wid}.json"
    op = root / f"experiments/workloads/{wid}.oracle.json"
    pp = root / f"experiments/workloads/{wid}.provenance.json"
    if pp.is_file():
        try:
            prev = _load(pp)
        except (OSError, json.JSONDecodeError):
            prev = {}
        if prev.get("oracle_status") == "frozen" or prev.get("frozen") is True:
            raise SystemExit(
                "refusing to overwrite frozen simple set (%s); "
                "mine into bound_ir_simple_mine or clear frozen flag" % pp.name)
    # Never touch complex artifacts.
    for frozen_name in ("bound_ir_complex_v1.json", "bound_ir_complex_v1.oracle.json",
                        "bound_ir_complex_v1.provenance.json"):
        # existence check only — assembler must not write these paths
        _ = root / "experiments/workloads" / frozen_name
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    op.write_text(json.dumps(ora, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    n_meas = sum(1 for v in per_q.values() if v.get("source") == "paired")
    n_pad = len(ordered) - n_meas
    lags = [v["lag"] for v in per_q.values() if "lag" in v]
    prov = {
        "path": f"experiments/workloads/{wid}.json",
        "oracle_path": f"experiments/workloads/{wid}.oracle.json",
        "evaluation_role": role,
        "eval_level": "simple",
        "n_queries": len(queries),
        "n_oracle": len(answers),
        "ids": ordered,
        "oracle_status": "assembled",
        "frozen": False,
        "freeze_note": (
            "Simple-level comparative/ablation set. Disjoint from bound_ir_complex_v1. "
            "Cold-revalidate on server before marking frozen=true."
        ),
        "criteria": (
            f"structural simple (not c3_*/tz_hard/locked/mech); "
            f"paired kart/cbo lag<= {max_lag}; pad structural+oracle to n≈complex"
        ),
        "max_lag": max_lag,
        "target_n": target_n,
        "n_measured": n_meas,
        "n_structural_pad": n_pad,
        "measured_lag_p50": sorted(lags)[len(lags) // 2] if lags else None,
        "per_query": per_q,
        "excluded_train": sorted(train),
        "excluded_holdout": sorted(hold),
        "excluded_complex_n": len(complex_ban),
        "sha256": hashlib.sha256(wp.read_bytes()).hexdigest(),
        "oracle_sha256": hashlib.sha256(op.read_bytes()).hexdigest(),
        "generator": "admit_dual_rules.assemble_simple_v1",
        "paired_path": str(paired).replace("\\", "/") if paired else None,
        "sibling_complex": "experiments/workloads/bound_ir_complex_v1.json",
    }
    pp.write_text(json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {
        "n": len(queries),
        "ids": ordered,
        "n_measured": n_meas,
        "n_structural_pad": n_pad,
        "measured_lag_p50": prov["measured_lag_p50"],
        "eval_level": "simple",
    }


def assemble_cmp_v6(
    root: Path,
    paired: Optional[Path] = None,
    target_n: int = 28,
    min_ratio: float = 1.4,
    min_gap_ms: float = 50.0,
    require_beat_fullscan: bool = True,
    min_fs_ratio: float = 1.25,
) -> dict:
    """kart≫cbo (+kart≫fullscan by default) slice from paired/four-arm measurements."""
    if paired is None or not paired.is_file():
        raise SystemExit("assemble_cmp_v6 requires --paired jsonl with kart/cbo rows")
    rules = _load(root / "experiments/suites/dual_action_rules.json")
    train = set(rules.get(TRAIN_KEY) or [])
    hold = {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_test.json")["queries"]}
    hold |= {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_val.json")["queries"]}
    ban = set(train) | hold
    qidx = _query_index(root)
    for name in (
        "bound_ir_cmp_v4.json",
        "bound_ir_cmp_v5.json",
        "bound_ir_cmp_force_adv.json",
        "bound_ir_cbo_llm_pilot_v1.json",
        "bound_ir_cmp_v6_mine.json",
        "bound_ir_cmp_v6_pad.json",
        "bound_ir_cmp_v6_pad_seeds.json",
    ):
        p = root / "experiments/workloads" / name
        if not p.is_file():
            continue
        for q in _load(p).get("queries") or []:
            qid = q.get("query_id")
            if qid and qid not in qidx:
                qidx[qid] = q
    oidx = _oracle_index(root)
    used_ratio, used_gap, used_fs = min_ratio, min_gap_ms, min_fs_ratio
    beat_fs = require_beat_fullscan
    ranked = _paired_kart_wins(
        paired, qidx, oidx, ban, used_ratio, used_gap, beat_fs, used_fs)
    softened = False
    if len(ranked) < 12 and beat_fs and used_fs > 1.15:
        used_fs = 1.15
        ranked = _paired_kart_wins(
            paired, qidx, oidx, ban, used_ratio, used_gap, beat_fs, used_fs)
        softened = True
    if len(ranked) < 12 and (used_ratio > 1.25 or used_gap > 30.0):
        used_ratio, used_gap = 1.25, 30.0
        ranked = _paired_kart_wins(
            paired, qidx, oidx, ban, used_ratio, used_gap, beat_fs, used_fs)
        softened = True
    if len(ranked) < 12:
        used_ratio, used_gap = 1.0, 0.0
        ranked = _paired_kart_wins(
            paired, qidx, oidx, ban, used_ratio, used_gap, beat_fs, used_fs)
        softened = True
        ranked = sorted(ranked, key=lambda t: (-t[1], -t[2], t[0]))
    if len(ranked) < 8 and beat_fs:
        # Last resort: keep beat-fullscan off only if pool is empty; prefer thin beat-fs set.
        beat_fs = False
        ranked = _paired_kart_wins(
            paired, qidx, oidx, ban, used_ratio, used_gap, False, used_fs)
        softened = True
        ranked = sorted(ranked, key=lambda t: (-t[1], -t[2], t[0]))
    ordered = [t[0] for t in ranked[:target_n]]
    per_q = {}
    for t in ranked[:target_n]:
        row = {
            "gap_ms": round(t[1], 3),
            "ratio": round(t[2], 4),
            "kart_t_e2e_ms": round(t[3], 3),
            "cbo_t_e2e_ms": round(t[4], 3),
        }
        if t[5] is not None:
            row["fullscan_t_e2e_ms"] = round(t[5], 3)
            row["fs_ratio"] = round(t[5] / t[3], 4) if t[3] > 0 else None
        per_q[t[0]] = row
    queries = [qidx[qid] for qid in ordered]
    answers = [oidx[qid] for qid in ordered]
    wid = "bound_ir_cmp_v6"
    role = "kart_gt_baselines_v6" if require_beat_fullscan else "kart_gt_cbo_long_wide_v6"
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": wid,
        "evaluation_role": role,
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    ora = {"answers": answers}
    wp = root / f"experiments/workloads/{wid}.json"
    op = root / f"experiments/workloads/{wid}.oracle.json"
    pp = root / f"experiments/workloads/{wid}.provenance.json"
    for frozen_name in ("bound_ir_complex_v1.provenance.json", f"{wid}.provenance.json"):
        fp = root / "experiments/workloads" / frozen_name
        if not fp.is_file():
            continue
        try:
            prev = _load(fp)
        except (OSError, json.JSONDecodeError):
            continue
        if prev.get("oracle_status") == "frozen" or prev.get("frozen") is True:
            raise SystemExit(
                "refusing to overwrite frozen complex set (%s); "
                "use a side pool workload or pass a different --out path" % frozen_name)
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    op.write_text(json.dumps(ora, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    prov = {
        "path": f"experiments/workloads/{wid}.json",
        "oracle_path": f"experiments/workloads/{wid}.oracle.json",
        "evaluation_role": role,
        "n_queries": len(queries),
        "n_oracle_copied": len(answers),
        "n_eligible_paired": len(ranked),
        "min_ratio": used_ratio,
        "min_gap_ms": used_gap,
        "require_beat_fullscan": require_beat_fullscan,
        "min_fs_ratio": used_fs if require_beat_fullscan else None,
        "beat_fullscan_applied": beat_fs if require_beat_fullscan else False,
        "threshold_softened": softened,
        "layers": {"paired_advantage": len(ordered)},
        "layer_ids": {"paired_advantage": ordered},
        "per_query": per_q,
        "excluded_train": sorted(train),
        "excluded_holdout": sorted(hold),
        "sha256": hashlib.sha256(wp.read_bytes()).hexdigest(),
        "oracle_sha256": hashlib.sha256(op.read_bytes()).hexdigest(),
        "generator": "admit_dual_rules.assemble_cmp_v6",
        "paired_path": str(paired).replace("\\", "/"),
    }
    if used_ratio <= 1.0 and used_gap <= 0.0:
        prov["pool_note"] = "thresholds_relaxed_to_any_kart_e2e_win"
    if len(ordered) < 12:
        prov["pool_note"] = "eligible_paired_below_12_after_soften"
    pp.write_text(json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {
        "n": len(queries),
        "ids": ordered,
        "n_eligible": len(ranked),
        "threshold_softened": softened,
        "require_beat_fullscan": require_beat_fullscan,
        "layers": {"paired_advantage": len(ordered)},
    }


def _assemble_cmp(root: Path, paired: Optional[Path], target_n: int, version: int) -> dict:
    """Census / paired advantage ∪ locked fillers; disjoint from train/holdout."""
    rules = _load(root / "experiments/suites/dual_action_rules.json")
    train = set(rules.get(TRAIN_KEY) or [])
    hold = {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_test.json")["queries"]}
    hold |= {q["query_id"] for q in _load(root / "experiments/workloads/bound_ir_holdout_v2_val.json")["queries"]}
    ban = set(train) | hold
    qidx = _query_index(root)
    oidx = _oracle_index(root)

    layers = {
        "paired_advantage": [],
        "census_search_miss": [],
        "census_advanced_cbo": [],
        "cmp_v3_fill": [],
        "advantage_fill": [],
        "locked_fill": [],
    }
    if paired and paired.is_file():
        by_arm = defaultdict(dict)
        for line in paired.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            qid = o.get("query_id")
            arm = o.get("arm")
            if qid and arm:
                by_arm[arm][qid] = o
            elif qid:
                # flat paired row
                if o.get("ok_oracle") is False:
                    continue
                if qid in ban or qid not in qidx or qid not in oidx or is_tiny(qidx[qid]):
                    continue
                kart_ms = o.get("kart_t_e2e_ms") or o.get("kart_ms") or o.get("t_e2e_ms_kart")
                cbo_ms = o.get("cbo_t_e2e_ms") or o.get("cbo_ms") or o.get("t_e2e_ms_cbo")
                if kart_ms is None or cbo_ms is None:
                    if o.get("kart_beats_cbo") or o.get("advantage"):
                        layers["paired_advantage"].append(qid)
                    continue
                try:
                    if float(kart_ms) + 1e-9 < float(cbo_ms):
                        layers["paired_advantage"].append(qid)
                except (TypeError, ValueError):
                    pass
        kart_rows = by_arm.get("kart") or {}
        cbo_rows = by_arm.get("cbo") or {}
        for qid, kr in kart_rows.items():
            cr = cbo_rows.get(qid)
            if not cr:
                continue
            if kr.get("ok_oracle") is False or cr.get("ok_oracle") is False:
                continue
            if qid in ban or qid not in qidx or qid not in oidx or is_tiny(qidx[qid]):
                continue
            try:
                if float(kr["t_e2e_ms"]) + 1e-9 < float(cr["t_e2e_ms"]):
                    layers["paired_advantage"].append(qid)
            except (TypeError, ValueError, KeyError):
                pass
    failed = set()
    # 1) census search_miss (from summary.md if present)
    for md in sorted((root / "experiments/opportunity").glob("*/summary.md")):
        for line in md.read_text(encoding="utf-8").splitlines():
            parts = [p.strip() for p in line.strip("|").split("|")]
            if len(parts) < 2 or not parts[0] or parts[0] == "query_id":
                continue
            qid, labs = parts[0], parts[1]
            if "oracle_or_execution_failure" in labs:
                failed.add(qid)
                continue
            if "search_miss_with_exec_gain" not in labs:
                continue
            if qid in ban or qid not in qidx or qid not in oidx or is_tiny(qidx[qid]):
                continue
            layers["census_search_miss"].append(qid)

    # 2) census rows where CBO already selected an advanced index (XZ/TS/XZT family)
    for sj in sorted((root / "experiments/opportunity").glob("*/search.jsonl")):
        for line in sj.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            if o.get("kind") != "cbo_search":
                continue
            qid = o.get("query_id")
            pid = o.get("cbo_selected_plan_id") or ""
            if qid in ban or qid in failed or qid not in qidx or qid not in oidx or is_tiny(qidx[qid]):
                continue
            if any(tok in pid for tok in ("XZ", "TS", "XZT")):
                layers["census_advanced_cbo"].append(qid)

    # 3) fill from cmp_v3 remaining
    for q in _load(root / "experiments/workloads/bound_ir_cmp_v3.json").get("queries") or []:
        qid = q["query_id"]
        if qid in ban or qid in failed or qid not in oidx or is_tiny(q):
            continue
        layers["cmp_v3_fill"].append(qid)

    # 4) advantage_v2 long/wide fillers
    adv_p = root / "experiments/workloads/bound_ir_advantage_v2.json"
    if adv_p.is_file():
        for q in _load(adv_p).get("queries") or []:
            qid = q["query_id"]
            if qid in ban or qid in failed or qid not in oidx or is_tiny(q):
                continue
            layers["advantage_fill"].append(qid)

    # locked fillers from cmp_v4 provenance when present
    prov_v4 = root / "experiments/workloads/bound_ir_cmp_v4.provenance.json"
    if prov_v4.is_file():
        for qid in (_load(prov_v4).get("layer_ids") or {}).get("locked") or []:
            if qid in ban or qid in failed or qid not in oidx or qid not in qidx or is_tiny(qidx[qid]):
                continue
            layers["locked_fill"].append(qid)
    for qid in ("r9_locked_td_1", "c3_tz_a", "c3_tz_b", "c3_tz_c"):
        if qid in ban or qid in failed or qid not in oidx or qid not in qidx or is_tiny(qidx[qid]):
            continue
        if qid not in layers["locked_fill"]:
            layers["locked_fill"].append(qid)

    ordered = []
    seen = set()
    prov_layers = {}
    if version >= 5:
        layer_order = ("paired_advantage", "census_search_miss", "census_advanced_cbo",
                       "locked_fill", "advantage_fill", "cmp_v3_fill")
        wid = "bound_ir_cmp_v5"
        role = "kart_gt_cbo_new_index_v5"
        gen = "admit_dual_rules.assemble_cmp_v5"
    else:
        layer_order = ("census_search_miss", "census_advanced_cbo", "cmp_v3_fill", "advantage_fill")
        wid = "bound_ir_cmp_v4"
        role = "kart_operating_region_new_index"
        gen = "admit_dual_rules.assemble_cmp_v4"
    for name in layer_order:
        kept = []
        for qid in layers.get(name) or []:
            if qid in seen:
                continue
            seen.add(qid)
            ordered.append(qid)
            kept.append(qid)
            if len(ordered) >= target_n:
                break
        prov_layers[name] = kept
        if len(ordered) >= target_n:
            break

    queries = [qidx[qid] for qid in ordered]
    answers = [oidx[qid] for qid in ordered]
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": wid,
        "evaluation_role": role,
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    ora = {"answers": answers}
    wp = root / f"experiments/workloads/{wid}.json"
    op = root / f"experiments/workloads/{wid}.oracle.json"
    pp = root / f"experiments/workloads/{wid}.provenance.json"
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    op.write_text(json.dumps(ora, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    prov = {
        "path": f"experiments/workloads/{wid}.json",
        "oracle_path": f"experiments/workloads/{wid}.oracle.json",
        "evaluation_role": role,
        "n_queries": len(queries),
        "n_oracle_copied": len(answers),
        "layers": {k: len(v) for k, v in prov_layers.items()},
        "layer_ids": prov_layers,
        "excluded_train": sorted(train),
        "excluded_holdout": sorted(hold),
        "sha256": hashlib.sha256(wp.read_bytes()).hexdigest(),
        "oracle_sha256": hashlib.sha256(op.read_bytes()).hexdigest(),
        "generator": gen,
    }
    pp.write_text(json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"n": len(queries), "ids": ordered, "layers": {k: len(v) for k, v in prov_layers.items()}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split-eval", action="store_true")
    ap.add_argument("--expand-train", action="store_true")
    ap.add_argument("--assemble-cmp-v4", action="store_true")
    ap.add_argument("--assemble-cmp-v5", action="store_true")
    ap.add_argument("--assemble-cmp-v6", action="store_true")
    ap.add_argument("--assemble-simple-v1", action="store_true")
    ap.add_argument("--diagnose", action="store_true")
    ap.add_argument("--paired")
    ap.add_argument("--base", default="experiments/suites/dual_action_rules.json")
    ap.add_argument("--out")
    ap.add_argument("--target-n", type=int, default=24)
    ap.add_argument("--min-ratio", type=float, default=1.4)
    ap.add_argument("--min-gap-ms", type=float, default=50.0)
    ap.add_argument("--max-lag", type=float, default=1.15,
                    help="Max kart/cbo for assemble-simple-v1")
    ap.add_argument("--require-beat-fullscan", action="store_true", default=True)
    ap.add_argument("--allow-fullscan-faster", action="store_true",
                    help="Disable kart≫fullscan filter when assembling cmp-v6")
    ap.add_argument("--min-fs-ratio", type=float, default=1.25)
    args = ap.parse_args()
    if args.split_eval:
        print(json.dumps(split_eval(ROOT), indent=2))
        return
    if args.expand_train:
        print(json.dumps(expand_train(ROOT), indent=2))
        return
    if args.assemble_simple_v1:
        paired = Path(args.paired) if args.paired else ROOT / "experiments/tmp_simple_paired.jsonl"
        tn = args.target_n if args.target_n != 24 else 31
        print(json.dumps(
            assemble_simple_v1(
                ROOT, paired if paired.is_file() else None, tn, args.max_lag),
            indent=2))
        return
    if args.assemble_cmp_v6:
        if not args.paired:
            ap.error("--assemble-cmp-v6 requires --paired")
        paired = Path(args.paired)
        tn = args.target_n if args.target_n != 24 else 28
        beat_fs = not args.allow_fullscan_faster
        print(json.dumps(
            assemble_cmp_v6(
                ROOT, paired, tn, args.min_ratio, args.min_gap_ms,
                require_beat_fullscan=beat_fs, min_fs_ratio=args.min_fs_ratio),
            indent=2))
        return
    if args.assemble_cmp_v5:
        paired = Path(args.paired) if args.paired else ROOT / "experiments/tmp_mine_e2e.jsonl"
        print(json.dumps(assemble_cmp_v5(ROOT, paired if paired.is_file() else None, args.target_n), indent=2))
        return
    if args.assemble_cmp_v4:
        paired = Path(args.paired) if args.paired else ROOT / "experiments/tmp_mine_e2e.jsonl"
        print(json.dumps(assemble_cmp_v4(ROOT, paired if paired.is_file() else None, args.target_n), indent=2))
        return
    if args.diagnose:
        if not args.paired:
            ap.error("--diagnose requires --paired")
        rows = [json.loads(l) for l in Path(args.paired).read_text(encoding="utf-8").splitlines() if l.strip()]
        print(json.dumps(diagnose_pairs(rows), indent=2, ensure_ascii=False))
        return
    if args.paired:
        base = _load(Path(args.base))
        rows = [json.loads(l) for l in Path(args.paired).read_text(encoding="utf-8").splitlines() if l.strip()]
        res = admit_from_pairs(base, rows)
        dest = Path(args.out or args.base)
        dest.write_text(json.dumps(res["doc"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"added": res["added"], "n_rules": len(res["doc"]["rules"])}))
        return
    ap.error("use --split-eval, --expand-train, --assemble-cmp-v4/--assemble-cmp-v5/--assemble-cmp-v6, --diagnose or --paired")


if __name__ == "__main__":
    main()
