#!/usr/bin/env python3
"""
Generate bound_ir_advantage_hard_v2 — 28 high-difficulty operating-region BoundIRs.

Layers (system-advantage focused):
  tzh_rbo_trap (8)      — T+Z+H; RBO maps to P_TZ
  tz_hard_intersect (8) — large TZ intersect → multi SafePlan
  th_zh_uncertain (4)   — dual-index + uncertain cost
  topk_st_metric (8)    — ST Top-K metric/k variants

Does NOT run FullScan. Oracle fill is gated separately.
Merge with advantage_v1 via --merge after hard oracle exists.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]
WL_DIR = ROOT / "experiments/workloads"

V1 = WL_DIR / "bound_ir_v1.json"
ADV_V1 = WL_DIR / "bound_ir_advantage_v1.json"
ADV_V1_ORA = WL_DIR / "bound_ir_advantage_v1.oracle.json"
HOLD_V2 = WL_DIR / "bound_ir_holdout_v2.json"
HARD_OUT = WL_DIR / "bound_ir_advantage_hard_v2.json"
HARD_ORA = WL_DIR / "bound_ir_advantage_hard_v2.oracle.json"
HARD_PROV = WL_DIR / "bound_ir_advantage_hard_v2.provenance.json"
V2_OUT = WL_DIR / "bound_ir_advantage_v2.json"
V2_ORA = WL_DIR / "bound_ir_advantage_v2.oracle.json"
V2_PROV = WL_DIR / "bound_ir_advantage_v2.provenance.json"

BUCKET = 600_000
HOUR = 3_600_000
DAY = 86_400_000
ROLE = "kart_operating_region"
SEED = "2026-09-27-advantage-hard-v2"

LAYERS_TARGET = {
    "tzh_rbo_trap": 8,
    "tz_hard_intersect": 8,
    "th_zh_uncertain": 4,
    "topk_st_metric": 8,
}


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def save(p: Path, obj: dict) -> None:
    p.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def ir_fingerprint(q: dict) -> str:
    """Stable hash of BoundIR body (without query_id)."""
    body = {k: v for k, v in q.items() if k != "query_id"}
    blob = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def clone_q(src: dict, new_id: str, **mut: Any) -> dict:
    q = copy.deepcopy(src)
    q["query_id"] = new_id
    for k, v in mut.items():
        if k == "temporal" and isinstance(v, dict) and q.get("temporal"):
            q["temporal"].update(v)
        elif k == "spatial" and isinstance(v, dict) and q.get("spatial"):
            q["spatial"].update(v)
        elif k == "similarity" and isinstance(v, dict):
            if q.get("similarity") is None:
                q["similarity"] = {}
            q["similarity"].update(v)
        elif k == "result" and isinstance(v, dict) and q.get("result"):
            q["result"].update(v)
        elif k == "predicates":
            q["predicates"] = v
        else:
            q[k] = v
    return q


def pad_spatial(q: dict, pad: float) -> dict:
    sp = copy.deepcopy(q.get("spatial") or {})
    if not sp:
        return {}
    return {
        "min_x": sp["min_x"] - pad,
        "min_y": sp["min_y"] - pad,
        "max_x": sp["max_x"] + pad,
        "max_y": sp["max_y"] + pad,
    }


def shift_temporal(q: dict, start_off: int, end_off: int) -> dict:
    t = q.get("temporal") or {}
    return {
        "start_ms": t["start_ms"] + start_off,
        "end_ms": t["end_ms"] + end_off,
    }


def collect_existing_ids_and_fps() -> Tuple[Set[str], Set[str]]:
    ids: Set[str] = set()
    fps: Set[str] = set()
    for p in (V1, ADV_V1, HOLD_V2):
        if not p.is_file():
            continue
        wl = load(p)
        for q in wl.get("queries") or []:
            qid = q.get("query_id")
            if qid:
                ids.add(str(qid))
            fps.add(ir_fingerprint(q))
    return ids, fps


class Builder:
    def __init__(self) -> None:
        self.v1 = load(V1)
        self.by = {q["query_id"]: q for q in self.v1["queries"]}
        self.queries: List[dict] = []
        self.catalog: Dict[str, dict] = {}
        self.used_ids, self.used_fps = collect_existing_ids_and_fps()
        self.layers: Counter = Counter()

    def need(self, qid: str) -> dict:
        if qid not in self.by:
            raise SystemExit(f"missing template {qid}")
        return self.by[qid]

    def add(
        self,
        q: dict,
        family: str,
        layer: str,
        hypothesis: str,
        **extra: Any,
    ) -> None:
        qid = q["query_id"]
        if family in {"T", "Z", "H"}:
            raise SystemExit(f"refuse single-index family for {qid}")
        if layer not in LAYERS_TARGET:
            raise SystemExit(f"unknown layer {layer}")
        if self.layers[layer] >= LAYERS_TARGET[layer]:
            raise SystemExit(f"layer {layer} already full")
        if qid in self.used_ids:
            raise SystemExit(f"duplicate query_id {qid}")
        fp = ir_fingerprint(q)
        if fp in self.used_fps:
            raise SystemExit(f"duplicate IR fingerprint for {qid}")
        # structural sanity
        if layer.startswith("tzh") and not (
            q.get("temporal") and q.get("spatial") and q.get("predicates")
        ):
            raise SystemExit(f"TZH requires T+Z+H predicates: {qid}")
        if layer.startswith("tz_") and not (q.get("temporal") and q.get("spatial")):
            raise SystemExit(f"TZ requires temporal+spatial: {qid}")
        if layer.startswith("topk") and (q.get("result") or {}).get("mode") != "TOP_K":
            raise SystemExit(f"topk layer needs TOP_K: {qid}")

        self.queries.append(q)
        self.catalog[qid] = {
            "family": family,
            "layer": layer,
            "advantage_hypothesis": hypothesis,
            "source_template": extra.pop("source_template", None),
            **{k: v for k, v in extra.items() if v is not None},
        }
        self.used_ids.add(qid)
        self.used_fps.add(fp)
        self.layers[layer] += 1

    def build(self) -> dict:
        # --- tzh_rbo_trap x8 ---
        h1 = self.need("h_st_1")
        h2 = self.need("h_st_2")
        h3 = self.need("h_st_3")
        h4 = self.need("h_st_4")
        hyp_tzh = "RBO maps T+Z+H to P_TZ; KART/conditional-LLM choose among more SafePlans"
        specs_tzh = [
            ("a2_tzh_a", h1, {"temporal": shift_temporal(h1, -2 * HOUR, 2 * HOUR)}, "h_st_1"),
            ("a2_tzh_b", h1, {"temporal": shift_temporal(h1, HOUR, 3 * HOUR)}, "h_st_1"),
            ("a2_tzh_c", h2, {"temporal": shift_temporal(h2, -BUCKET, BUCKET)}, "h_st_2"),
            ("a2_tzh_d", h2, {"spatial": pad_spatial(h2, 800.0)}, "h_st_2"),
            ("a2_tzh_e", h3, {"temporal": shift_temporal(h3, -HOUR, HOUR)}, "h_st_3"),
            ("a2_tzh_f", h3, {"spatial": pad_spatial(h3, 1200.0)}, "h_st_3"),
            ("a2_tzh_g", h4, {"temporal": shift_temporal(h4, -3 * HOUR, HOUR)}, "h_st_4"),
            (
                "a2_tzh_h",
                h4,
                {
                    "temporal": shift_temporal(h4, BUCKET, 2 * HOUR),
                    "spatial": pad_spatial(h4, 500.0),
                },
                "h_st_4",
            ),
        ]
        for qid, src, mut, tmpl in specs_tzh:
            self.add(
                clone_q(src, qid, **mut),
                "TZH",
                "tzh_rbo_trap",
                hyp_tzh,
                source_template=tmpl,
                rbo_trap=True,
            )

        # --- tz_hard_intersect x8 ---
        ll1 = self.need("st_ll_1")
        ll2 = self.need("st_ll_2")
        sl1 = self.need("st_sl_1")
        ls1 = self.need("st_ls_1")
        hyp_tz = "Large TZ intersect yields n_safe>=2; shared-pool / conditional select matters"
        specs_tz = [
            (
                "a2_tz_hard_a",
                ll1,
                {
                    "temporal": shift_temporal(ll1, -12 * HOUR, 0),
                    "spatial": pad_spatial(ll1, 900.0),
                },
                "st_ll_1",
            ),
            (
                "a2_tz_hard_b",
                ll1,
                {
                    "temporal": shift_temporal(ll1, -2 * DAY, HOUR),
                    "spatial": pad_spatial(ll1, 1500.0),
                },
                "st_ll_1",
            ),
            (
                "a2_tz_hard_c",
                ll2,
                {
                    "temporal": shift_temporal(ll2, -3 * HOUR, 2 * HOUR),
                    "spatial": pad_spatial(ll2, 700.0),
                },
                "st_ll_2",
            ),
            (
                "a2_tz_hard_d",
                ll2,
                {
                    "temporal": shift_temporal(ll2, BUCKET, -BUCKET),
                    "spatial": pad_spatial(ll2, 2200.0),
                },
                "st_ll_2",
            ),
            (
                "a2_tz_hard_e",
                sl1,
                {
                    "temporal": shift_temporal(sl1, -6 * HOUR, 6 * HOUR),
                    "spatial": pad_spatial(sl1, 1000.0),
                },
                "st_sl_1",
            ),
            (
                "a2_tz_hard_f",
                sl1,
                {
                    "temporal": shift_temporal(sl1, -18 * HOUR, 18 * HOUR),
                    "spatial": pad_spatial(sl1, 1800.0),
                },
                "st_sl_1",
            ),
            (
                "a2_tz_hard_g",
                ls1,
                {
                    "temporal": shift_temporal(ls1, -2 * HOUR, 2 * HOUR),
                    "spatial": pad_spatial(ls1, 2500.0),
                },
                "st_ls_1",
            ),
            (
                "a2_tz_hard_h",
                ls1,
                {
                    "temporal": shift_temporal(ls1, -5 * HOUR, 5 * HOUR),
                    "spatial": pad_spatial(ls1, 4000.0),
                },
                "st_ls_1",
            ),
        ]
        for qid, src, mut, tmpl in specs_tz:
            self.add(
                clone_q(src, qid, **mut),
                "TZ",
                "tz_hard_intersect",
                hyp_tz,
                source_template=tmpl,
            )

        # --- th_zh_uncertain x4 ---
        ht = self.need("h_t_mid")
        ht_l = self.need("h_t_large")
        hs = self.need("h_s_small")
        hs2 = self.need("h_s_2")
        hyp_dual = "Dual-index CostCards often close/uncertain; conditional LLM trigger region"
        self.add(
            clone_q(ht, "a2_th_unc_a", temporal=shift_temporal(ht, -HOUR, 2 * HOUR)),
            "TH",
            "th_zh_uncertain",
            hyp_dual,
            source_template="h_t_mid",
        )
        self.add(
            clone_q(ht_l, "a2_th_unc_b", temporal=shift_temporal(ht_l, -BUCKET, BUCKET)),
            "TH",
            "th_zh_uncertain",
            hyp_dual,
            source_template="h_t_large",
        )
        self.add(
            clone_q(hs, "a2_zh_unc_a", spatial=pad_spatial(hs, 900.0)),
            "ZH",
            "th_zh_uncertain",
            hyp_dual,
            source_template="h_s_small",
        )
        self.add(
            clone_q(hs2, "a2_zh_unc_b", spatial=pad_spatial(hs2, 1600.0)),
            "ZH",
            "th_zh_uncertain",
            hyp_dual,
            source_template="h_s_2",
        )

        # --- topk_st_metric x8 ---
        tk = self.need("topk_st_1")
        tk_f = self.need("topk_st_frechet_1")
        tk_h = self.need("topk_st_hausdorff_1")
        tk3 = self.need("topk_st_3")
        hyp_topk = "ST Top-K metric/k uncertainty; wrong plan selection is expensive"
        specs_topk = [
            (
                "a2_topk_dtw_k3",
                tk,
                {
                    "result": {"k": 3},
                    "temporal": shift_temporal(tk, -BUCKET, BUCKET),
                    "spatial": pad_spatial(tk, 400.0),
                },
                "topk_st_1",
                "DTW",
                3,
            ),
            (
                "a2_topk_dtw_k7",
                tk,
                {
                    "result": {"k": 7},
                    "temporal": shift_temporal(tk, -2 * HOUR, HOUR),
                    "spatial": pad_spatial(tk, 600.0),
                },
                "topk_st_1",
                "DTW",
                7,
            ),
            (
                "a2_topk_dtw_wide",
                tk3,
                {
                    "result": {"k": 5},
                    "temporal": shift_temporal(tk3, -HOUR, HOUR),
                    "spatial": pad_spatial(tk3, 800.0),
                },
                "topk_st_3",
                "DTW",
                5,
            ),
            (
                "a2_topk_frechet_k3",
                tk_f,
                {
                    "result": {"k": 3},
                    "temporal": shift_temporal(tk_f, -BUCKET, 2 * BUCKET),
                    "spatial": pad_spatial(tk_f, 500.0),
                },
                "topk_st_frechet_1",
                "FRECHET",
                3,
            ),
            (
                "a2_topk_frechet_k7",
                tk_f,
                {
                    "result": {"k": 7},
                    "temporal": shift_temporal(tk_f, HOUR, 2 * HOUR),
                    "spatial": pad_spatial(tk_f, 700.0),
                },
                "topk_st_frechet_1",
                "FRECHET",
                7,
            ),
            (
                "a2_topk_frechet_wide",
                tk_f,
                {
                    "result": {"k": 5},
                    "temporal": shift_temporal(tk_f, -2 * HOUR, 2 * HOUR),
                    "spatial": pad_spatial(tk_f, 1000.0),
                },
                "topk_st_frechet_1",
                "FRECHET",
                5,
            ),
            (
                "a2_topk_haus_k3",
                tk_h,
                {
                    "result": {"k": 3},
                    "temporal": shift_temporal(tk_h, -2 * BUCKET, BUCKET),
                    "spatial": pad_spatial(tk_h, 450.0),
                },
                "topk_st_hausdorff_1",
                "HAUSDORFF",
                3,
            ),
            (
                "a2_topk_haus_k5",
                tk_h,
                {
                    "result": {"k": 5},
                    "temporal": shift_temporal(tk_h, -HOUR, HOUR),
                    "spatial": pad_spatial(tk_h, 1100.0),
                },
                "topk_st_hausdorff_1",
                "HAUSDORFF",
                5,
            ),
        ]
        for qid, src, mut, tmpl, metric, k in specs_topk:
            q = clone_q(src, qid, **mut)
            # ensure metric matches intent
            if q.get("similarity"):
                q["similarity"]["metric"] = metric
            self.add(
                q,
                "TOPK",
                "topk_st_metric",
                hyp_topk,
                source_template=tmpl,
                metric=metric,
                k=k,
            )

        missing = [L for L, n in LAYERS_TARGET.items() if self.layers[L] != n]
        if missing:
            raise SystemExit(f"layer counts wrong: {dict(self.layers)} missing={missing}")
        if len(self.queries) != 28:
            raise SystemExit(f"expected 28 queries, got {len(self.queries)}")

        return {
            "manifest_id": self.v1.get("manifest_id") or "tdrive_v1_ready",
            "semantics_version": self.v1.get("semantics_version") or "point_dtw_v1",
            "workload_id": "bound_ir_advantage_hard_v2",
            "evaluation_role": ROLE,
            "seed": SEED,
            "anchor": self.v1.get("anchor"),
            "queries": self.queries,
            "catalog": self.catalog,
            "query_count": len(self.queries),
            "layers": dict(self.layers),
            "notes": [
                "Hard operating-region slice only; FullScan oracle filled separately.",
                "Not confirmatory holdout; do not retune conditional_llm_freeze on this set.",
            ],
        }


def cmd_generate() -> None:
    wl = Builder().build()
    save(HARD_OUT, wl)
    prov = {
        "path": "experiments/workloads/bound_ir_advantage_hard_v2.json",
        "evaluation_role": ROLE,
        "n_queries": wl["query_count"],
        "layers": wl["layers"],
        "sha256": sha256_file(HARD_OUT),
        "oracle_status": "pending_fullscan_fill",
        "generator": "experiments/adapters/generate_advantage_hard_v2.py",
        "seed": SEED,
        "exclude": [
            "single-predicate T/Z/H",
            "empty/boundary",
            "spatial-only Top-K",
            "holdout v2 locked test ids",
        ],
        "query_ids": [q["query_id"] for q in wl["queries"]],
    }
    save(HARD_PROV, prov)
    print(
        "OK hard n=%d layers=%s sha=%s"
        % (wl["query_count"], wl["layers"], prov["sha256"][:12])
    )


def oracle_rows(ora: dict) -> List[dict]:
    ans = ora.get("answers")
    if isinstance(ans, list):
        return [r for r in ans if isinstance(r, dict) and r.get("query_id")]
    if isinstance(ans, dict):
        out = []
        for qid, row in ans.items():
            if isinstance(row, dict):
                r = dict(row)
                r.setdefault("query_id", qid)
                out.append(r)
        return out
    return []


def index_oracle(path: Path) -> Dict[str, dict]:
    by: Dict[str, dict] = {}
    for row in oracle_rows(load(path)):
        by[str(row["query_id"])] = row
    return by


def cmd_merge() -> None:
    if not HARD_OUT.is_file():
        raise SystemExit(f"missing {HARD_OUT}")
    if not HARD_ORA.is_file():
        raise SystemExit(f"missing {HARD_ORA} — run FullScan fill first")
    if not ADV_V1.is_file() or not ADV_V1_ORA.is_file():
        raise SystemExit("missing advantage_v1 workload/oracle")

    v1 = load(ADV_V1)
    hard = load(HARD_OUT)
    ora_v1 = index_oracle(ADV_V1_ORA)
    ora_hard = index_oracle(HARD_ORA)

    raw_queries = list(v1["queries"]) + list(hard["queries"])
    queries: List[dict] = []
    seen_fps: Dict[str, str] = {}
    deduplicated: List[dict] = []
    for q in raw_queries:
        qid = str(q["query_id"])
        fp = ir_fingerprint(q)
        first = seen_fps.get(fp)
        if first is not None:
            # Keep the first (historical v1) occurrence so repeated equivalent
            # BoundIRs do not receive extra weight in E2/E3 summaries.
            deduplicated.append({"query_id": qid, "duplicate_of": first})
            continue
        seen_fps[fp] = qid
        queries.append(q)

    raw_catalog: Dict[str, dict] = {}
    for qid, meta in (v1.get("catalog") or {}).items():
        m = dict(meta)
        m.setdefault("source_workload", "bound_ir_advantage_v1")
        raw_catalog[qid] = m
    for qid, meta in (hard.get("catalog") or {}).items():
        if qid in raw_catalog:
            raise SystemExit(f"id collision on merge: {qid}")
        m = dict(meta)
        m["source_workload"] = "bound_ir_advantage_hard_v2"
        raw_catalog[qid] = m

    catalog: Dict[str, dict] = {
        q["query_id"]: raw_catalog[q["query_id"]] for q in queries
    }

    ids = [q["query_id"] for q in queries]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate query_id after merge")

    missing = [i for i in ids if i not in ora_v1 and i not in ora_hard]
    if missing:
        raise SystemExit("oracle missing after merge: " + ", ".join(missing))

    answers = []
    for qid in ids:
        answers.append(ora_hard.get(qid) or ora_v1[qid])

    empty = [
        a["query_id"]
        for a in answers
        if not (a.get("trajectory_ids") or a.get("ids") or [])
    ]
    # Top-K / hash may legitimately be tiny but non-empty list; empty list is fail for this set
    if empty:
        raise SystemExit("empty oracle answers (fail-closed): " + ", ".join(empty))

    bad_fam = [qid for qid, m in catalog.items() if m.get("family") in {"T", "Z", "H"}]
    if bad_fam:
        raise SystemExit("single-index leaked: " + ", ".join(bad_fam))

    wl = {
        "manifest_id": v1.get("manifest_id") or "tdrive_v1_ready",
        "semantics_version": v1.get("semantics_version") or "point_dtw_v1",
        "workload_id": "bound_ir_advantage_v2",
        "evaluation_role": ROLE,
        "seed": "2026-09-27-advantage-v2",
        "anchor": v1.get("anchor") or hard.get("anchor"),
        "queries": queries,
        "catalog": catalog,
        "query_count": len(queries),
        "layers": dict(Counter(m.get("layer") for m in catalog.values() if m.get("layer"))),
        "notes": [
            "advantage_v1 (40 raw) ∪ advantage_hard_v2 (28), with exact BoundIR fingerprints deduplicated.",
            "Operating-region only; BoundIR-65 remains regression; holdout v2 test remains locked confirmation.",
        ],
    }
    ora = {
        "manifest_id": wl["manifest_id"],
        "semantics_version": wl["semantics_version"],
        "answers": answers,
        "total": len(answers),
        "non_empty": len(answers),
        "source": "merged advantage_v1.oracle + advantage_hard_v2.oracle (FullScan)",
    }
    save(V2_OUT, wl)
    save(V2_ORA, ora)
    prov = {
        "path": "experiments/workloads/bound_ir_advantage_v2.json",
        "oracle_path": "experiments/workloads/bound_ir_advantage_v2.oracle.json",
        "evaluation_role": ROLE,
        "n_queries": len(queries),
        "n_raw_queries": len(raw_queries),
        "n_from_advantage_v1": sum(
            1 for q in queries if q["query_id"] in v1["catalog"]
        ),
        "n_from_hard_v2": sum(
            1 for q in queries if q["query_id"] in hard["catalog"]
        ),
        "n_deduplicated": len(deduplicated),
        "deduplicated_queries": deduplicated,
        "deduplication": "exact_bound_ir_fingerprint_keep_first",
        "layers": wl["layers"],
        "families": sorted({str(m.get("family")) for m in catalog.values()}),
        "sha256": sha256_file(V2_OUT),
        "oracle_sha256": sha256_file(V2_ORA),
        "oracle_status": "frozen",
        "generator": "experiments/adapters/generate_advantage_hard_v2.py --merge",
        "hard_generator": "experiments/adapters/generate_advantage_hard_v2.py",
        "query_ids": ids,
        "notes": [
            "Default E2/E3 operating-region set after suite switch.",
            "Do not retune conditional_llm_freeze on this set.",
            "holdout v2 test remains locked one-shot confirmation (not merged).",
        ],
    }
    save(V2_PROV, prov)
    # freeze hard provenance if present
    if HARD_PROV.is_file():
        hp = load(HARD_PROV)
        hp["oracle_status"] = "frozen"
        hp["oracle_path"] = "experiments/workloads/bound_ir_advantage_hard_v2.oracle.json"
        hp["oracle_sha256"] = sha256_file(HARD_ORA)
        save(HARD_PROV, hp)
    print(
        "OK advantage_v2 n=%d raw=%d deduplicated=%d oracle_non_empty=%d"
        % (len(queries), len(raw_queries), len(deduplicated), len(answers))
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--merge",
        action="store_true",
        help="Merge advantage_v1 + hard_v2(+oracle) into bound_ir_advantage_v2",
    )
    args = ap.parse_args()
    if args.merge:
        cmd_merge()
    else:
        cmd_generate()


if __name__ == "__main__":
    main()
