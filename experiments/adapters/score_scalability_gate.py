#!/usr/bin/env python3
"""Score Scalability gate v2 from e2e.jsonl (P50 plan+exec, ADV rate, FS geo-mean)."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

ARMS = ("kart", "fullscan", "rbo", "cbo", "bao", "paramtree")
OTHERS = ("fullscan", "rbo", "cbo", "bao", "paramtree")
ADV_PREFIXES = ("P_XZ", "P_TS", "P_XZT")  # P_XZTXZ / P_XZ / P_TS / P_XZT…


def e2e_ms(row: dict):
    plan, exe = row.get("t_plan_ms"), row.get("t_exec_ms")
    if isinstance(plan, (int, float)) and isinstance(exe, (int, float)):
        return float(plan) + float(exe)
    e = row.get("t_e2e_ms")
    return float(e) if isinstance(e, (int, float)) else None


def ok(row: dict) -> bool:
    if row.get("ok_oracle") is False or row.get("ok") is False:
        return False
    return True


def is_adv(plan_id) -> bool:
    if not plan_id:
        return False
    return any(plan_id.startswith(p) for p in ADV_PREFIXES) and "BIPART" not in plan_id


def med(xs):
    return statistics.median(xs) if xs else None


def load_rows(path: Path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def score(rows, min_wins=4, max_lose_ratio=1.15, min_adv_share=0.80, fs_geo_min=1.5):
    by = defaultdict(lambda: defaultdict(list))
    plans = defaultdict(list)
    for r in rows:
        if str(r.get("stage", "e2e")).lower() != "e2e":
            continue
        if not ok(r):
            continue
        arm = (r.get("arm") or "").lower()
        if arm not in ARMS:
            continue
        qid = r.get("query_id")
        v = e2e_ms(r)
        if v is None or qid is None:
            continue
        by[qid][arm].append(
            {
                "e2e": v,
                "t_plan": r.get("t_plan_ms"),
                "t_exec": r.get("t_exec_ms"),
                "plan_id": r.get("plan_id") or r.get("final_plan_id"),
            }
        )
        if arm == "kart":
            plans[qid].append(r.get("plan_id") or r.get("final_plan_id"))

    slot_reports = {}
    n_pass = 0
    fs_ratios = []
    adv_trials = 0
    kart_trials = 0
    for qid, arms in sorted(by.items()):
        if "kart" not in arms:
            continue
        kart_e = [x["e2e"] for x in arms["kart"]]
        kart_p = med([x["t_plan"] for x in arms["kart"] if isinstance(x["t_plan"], (int, float))])
        kart_x = med([x["t_exec"] for x in arms["kart"] if isinstance(x["t_exec"], (int, float))])
        km = med(kart_e)
        wins = 0
        lose = []
        detail = {}
        for o in OTHERS:
            if o not in arms:
                continue
            om = med([x["e2e"] for x in arms[o]])
            op = med([x["t_plan"] for x in arms[o] if isinstance(x["t_plan"], (int, float))])
            ox = med([x["t_exec"] for x in arms[o] if isinstance(x["t_exec"], (int, float))])
            win = km is not None and om is not None and km <= om + 1e-9
            if win:
                wins += 1
            else:
                lose.append((o, om, (km / om) if (km and om) else None))
            detail[o] = {
                "p50_e2e": om,
                "p50_t_plan": op,
                "p50_t_exec": ox,
                "win": win,
            }
        lose_ok = True
        lose_notes = []
        if wins == min_wins and lose:
            for o, om, ratio in lose:
                ox = detail[o]["p50_t_exec"]
                if ratio is not None and ratio <= max_lose_ratio:
                    lose_notes.append(f"{o}:ratio={ratio:.3f}")
                elif kart_x is not None and ox is not None and kart_x <= ox + 1e-9:
                    lose_notes.append(f"{o}:plan_tax_exec_ok")
                else:
                    lose_ok = False
                    lose_notes.append(f"{o}:FAIL ratio={ratio}")
        slot_pass = wins > min_wins or (wins >= min_wins and lose_ok)
        if wins >= 5:
            slot_pass = True
        adv_n = sum(1 for p in plans[qid] if is_adv(p))
        slot_adv = adv_n / max(1, len(plans[qid]))
        adv_trials += adv_n
        kart_trials += len(plans[qid])
        if "fullscan" in arms and km:
            fm = med([x["e2e"] for x in arms["fullscan"]])
            if fm and fm > 0:
                fs_ratios.append(fm / km)
        slot_reports[qid] = {
            "kart_p50_e2e": km,
            "kart_p50_t_plan": kart_p,
            "kart_p50_t_exec": kart_x,
            "wins": wins,
            "slot_pass": slot_pass,
            "adv_share": slot_adv,
            "plans": sorted({p for p in plans[qid] if p}),
            "others": detail,
            "lose_notes": lose_notes,
        }
        if slot_pass:
            n_pass += 1

    geo = None
    if fs_ratios:
        geo = math.exp(sum(math.log(r) for r in fs_ratios) / len(fs_ratios))
    adv_share = adv_trials / max(1, kart_trials)
    gate = {
        "n_slots": len(slot_reports),
        "slots_pass": n_pass,
        "all_slots_pass": n_pass == len(slot_reports) and len(slot_reports) > 0,
        "fs_geo_mean_speedup": geo,
        "fs_geo_pass": geo is not None and geo >= fs_geo_min,
        "adv_share": adv_share,
        "adv_pass": adv_share >= min_adv_share,
        "slots": slot_reports,
    }
    gate["pass"] = gate["all_slots_pass"] and gate["fs_geo_pass"] and gate["adv_pass"]
    return gate


def pick_winners(rows, workload_queries, min_wins=4):
    """For mine workloads with *_mXX ids: pick best cand per slot prefix."""
    by = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    plans = defaultdict(lambda: defaultdict(list))
    for r in rows:
        if not ok(r):
            continue
        arm = (r.get("arm") or "").lower()
        qid = r.get("query_id") or ""
        if arm not in ARMS or "_" not in qid:
            continue
        # prefix = c_srq_m00 -> c_srq
        parts = qid.rsplit("_m", 1)
        if len(parts) != 2:
            continue
        slot, cand = parts[0], parts[1]
        v = e2e_ms(r)
        if v is None:
            continue
        by[slot][cand][arm].append(v)
        if arm == "kart":
            plans[slot][cand].append(r.get("plan_id") or r.get("final_plan_id"))

    q_by_id = {q["query_id"]: q for q in workload_queries}
    picks = []
    report = {}
    for slot in sorted(by.keys()):
        scored = []
        for cand, arms in by[slot].items():
            if "kart" not in arms:
                continue
            km = med(arms["kart"])
            wins = 0
            margins = []
            for o in OTHERS:
                if o not in arms:
                    continue
                om = med(arms[o])
                if km is not None and om is not None and km <= om + 1e-9:
                    wins += 1
                    margins.append(om - km)
            adv = sum(1 for p in plans[slot][cand] if is_adv(p)) / max(
                1, len(plans[slot][cand])
            )
            fs = med(arms["fullscan"]) if "fullscan" in arms else None
            fs_ratio = (fs / km) if (fs and km) else 0.0
            scored.append(
                {
                    "cand": cand,
                    "qid": f"{slot}_m{cand}",
                    "wins": wins,
                    "mean_margin": statistics.mean(margins) if margins else -1e9,
                    "adv_share": adv,
                    "fs_ratio": fs_ratio,
                    "kart_p50": km,
                }
            )
        scored.sort(
            key=lambda s: (s["wins"], s["adv_share"], s["fs_ratio"], s["mean_margin"]),
            reverse=True,
        )
        report[slot] = scored
        best = None
        for s in scored:
            if s["wins"] >= min_wins and s["adv_share"] >= 0.6:
                best = s
                break
        if best is None and scored:
            best = scored[0]
        if best:
            q = q_by_id.get(best["qid"])
            if q:
                picks.append(q)
    return picks, report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--e2e", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--fs-geo-min", type=float, default=1.5)
    ap.add_argument("--min-adv-share", type=float, default=0.80)
    ap.add_argument("--pick", action="store_true")
    ap.add_argument("--workload", type=Path, default=None)
    ap.add_argument("--picks-out", type=Path, default=None)
    args = ap.parse_args()
    rows = load_rows(args.e2e)
    gate = score(
        rows, fs_geo_min=args.fs_geo_min, min_adv_share=args.min_adv_share
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(gate, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "pass": gate["pass"],
                "slots_pass": gate["slots_pass"],
                "fs_geo": gate["fs_geo_mean_speedup"],
                "adv_share": gate["adv_share"],
                "out": str(args.out),
            }
        )
    )
    if args.pick:
        if not args.workload or not args.picks_out:
            raise SystemExit("--pick needs --workload and --picks-out")
        wl = json.loads(args.workload.read_text(encoding="utf-8"))
        picks, rep = pick_winners(rows, wl["queries"])
        args.picks_out.write_text(
            json.dumps({"queries": picks, "mine_rank": rep}, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps({"picked": [q["query_id"] for q in picks], "n": len(picks)}))


if __name__ == "__main__":
    main()
