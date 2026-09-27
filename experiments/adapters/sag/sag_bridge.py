#!/usr/bin/env python3
"""
SAG-inspired bridge aligned with Text-to-NoSQL sag/runtime.py control flow.

Upstream loop (runtime._run_attempt / sag_solve_nlq_db), transplanted:
  (1) grounding context into system prompt (catalog cards stand-in for GroundingIndex)
  (2) k attempts × max_repair_rounds: decode strict DraftIR JSON → local schema gate
      → feed violations back as repair messages
  (3) select_best by (violations, empty)
  (4) if k>1, cluster by DraftIR fingerprint and take majority

Intentional non-ports (spec §5.4):
  - MongoWorld / pymongo execution witness
  - A_path/A_value Mongo probes, empty-result bisection, synthetic-_id
  - TEND EXC / QueryCraft UI

Early-reject for COUNT / continuous / EDIT_DISTANCE is done in Java ParseFairness
(same gate as kart) — this bridge does not duplicate it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from llm_client import (  # noqa: E402
    DRAFT_IR_HINT,
    LlmHttpError,
    LOGICAL_CATALOG,
    chat,
    extract_json,
    merge_usage,
)

UPSTREAM = "experiments/third_party/text-to-nosql/src/tend/solver/sag/runtime.py"

# Mirror SAGPolicy defaults scaled for KART cost (v2-like k=1 by default; override via env)
K_CONSISTENCY = int(os.environ.get("KART_SAG_K", "1"))
MAX_REPAIR_ROUNDS = int(os.environ.get("KART_SAG_REPAIR_ROUNDS", "3"))


def _sys_prompt() -> str:
    return (
        "You are a SAG-style Schema-as-Data Grounding solver for trajectory DraftIR.\n"
        "Emit ONLY a DraftIR JSON object (strict). No MongoDB / MQL.\n"
        + DRAFT_IR_HINT
        + "\n"
        + LOGICAL_CATALOG
    )


def _user_prompt(utterance: str) -> str:
    return f"NLQ: {utterance}\nProduce DraftIR JSON."


def _gate(draft) -> list:
    """Local stand-in for A_path ∧ schema gates (no Mongo). Returns violation strings."""
    viol = []
    if not isinstance(draft, dict):
        return ["not a JSON object"]
    if draft.get("ir_version") is None and "temporal" not in draft and "spatial" not in draft:
        viol.append("missing core DraftIR fields")
    src = draft.get("source") or {}
    if src and src.get("dataset_id") and src.get("dataset_id") != "tdrive_v1":
        viol.append("source.dataset_id must be tdrive_v1")
    missing = draft.get("missing") or []
    if "unsupported" in missing:
        viol.append("model marked unsupported")
    # spatial region must be registered if named
    spatial = draft.get("spatial") or {}
    rn = spatial.get("region_name")
    registered = {
        "tdrive_smoke_anchor",
        "tdrive_topk_box",
        "tdrive_topk_wide",
        "tdrive_topk_s1",
        "beijing_core",
        "zhongguancun",
        "wangjing",
        "guomao",
        "beijing_cbd",
        "tiananmen",
        "capital_airport",
        "haidian_central",
        "chaoyang_central",
    }
    if rn and rn not in registered:
        viol.append(f"unknown region_name={rn}")
    sim = draft.get("similarity") or {}
    metric = (sim.get("metric") or "").upper()
    if metric and metric not in ("DTW", "FRECHET", "HAUSDORFF", ""):
        viol.append(f"unsupported metric={metric}")
    return viol


def _fingerprint(draft) -> str:
    try:
        blob = json.dumps(draft, sort_keys=True, ensure_ascii=False)
    except Exception:
        blob = str(draft)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def _decode(msgs, usage: dict):
    content, u = chat(msgs, temperature=0.0, json_mode=True)
    merge_usage(usage, u)
    return extract_json(content), content


def _run_attempt(utterance: str, usage: dict, attempt: int) -> dict:
    """One attempt ≈ runtime._run_attempt without Mongo execution."""
    msgs = [
        {"role": "system", "content": _sys_prompt()},
        {"role": "user", "content": _user_prompt(utterance)},
    ]
    best = None
    best_key = (10**9, 1, 0)  # violations, empty, -round  (lower better for first two)
    rounds_done = 0
    for rounds in range(1, MAX_REPAIR_ROUNDS + 1):
        rounds_done = rounds
        draft, raw = _decode(msgs, usage)
        viol = _gate(draft)
        empty = 1 if draft is None else 0
        key = (len(viol), empty, -rounds)
        cand = {"draft": draft, "violations": viol, "round": rounds, "raw": raw}
        if best is None or key < best_key:
            best = cand
            best_key = key
        if not viol and draft is not None:
            break
        # feed gate feedback (stand-in for execution-grounded repair)
        fb = "gate feedback:\n- " + "\n- ".join(viol or ["invalid or empty DraftIR"])
        msgs.append({"role": "assistant", "content": raw or "{}"})
        msgs.append(
            {
                "role": "user",
                "content": fb + "\nRepair and reply with ONLY corrected DraftIR JSON.",
            }
        )
    return {
        "attempt": attempt,
        "best": best,
        "rounds": rounds_done,
        "fp": _fingerprint(best["draft"]) if best and best.get("draft") else None,
    }


def select_best_attempt(outcomes: list) -> dict:
    """Mirror runtime.select_best / cluster_attempts (fingerprint majority if k>1)."""
    valid = [o for o in outcomes if o.get("best") and o["best"].get("draft") is not None]
    if not valid:
        return outcomes[0] if outcomes else {"best": None}
    if len(valid) == 1:
        return valid[0]
    # cluster by fingerprint
    buckets = {}
    for o in valid:
        fp = o.get("fp") or "?"
        buckets.setdefault(fp, []).append(o)
    largest = max(buckets.values(), key=len)

    def rank(o):
        b = o["best"]
        return (len(b.get("violations") or []), b.get("round") or 0)

    return min(largest, key=rank)


def run_sag(utterance: str) -> dict:
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    k = max(1, K_CONSISTENCY)
    outcomes = []
    for a in range(1, k + 1):
        outcomes.append(_run_attempt(utterance, usage, a))
    chosen = select_best_attempt(outcomes)
    best = (chosen or {}).get("best") or {}
    draft = best.get("draft")
    viol = best.get("violations") or []

    provenance = {
        "method": "sag",
        "upstream_file": UPSTREAM,
        "upstream_api": [
            "SAGPolicy / _run_attempt repair loop",
            "select_best + cluster_attempts (fingerprint)",
        ],
        "policy": {
            "k_consistency": k,
            "max_repair_rounds": MAX_REPAIR_ROUNDS,
            "arm": "v2" if k == 1 else "v3-like",
        },
        "intentional_changes": [
            "DraftIR instead of Mongo MQL pipeline",
            "local schema gate instead of A_path/A_value + pymongo",
            "no MongoWorld / empty bisection / synthetic-_id",
            "early-reject shared in Java ParseFairness (not duplicated here)",
        ],
        "rounds": chosen.get("rounds") if chosen else 0,
        "attempts": len(outcomes),
    }

    if draft is None:
        return {
            "status": "INVALID_IR",
            "error": "no DraftIR after SAG loop",
            "usage": usage,
            "provenance": provenance,
        }
    if "unsupported" in (draft.get("missing") or []):
        return {
            "status": "UNSUPPORTED_QUERY",
            "error": draft.get("error") or "unsupported",
            "draft": draft,
            "usage": usage,
            "provenance": provenance,
        }
    if viol and any("unsupported" in v for v in viol):
        return {
            "status": "UNSUPPORTED_QUERY",
            "error": "; ".join(viol),
            "draft": draft,
            "usage": usage,
            "provenance": provenance,
        }
    return {
        "status": "OK",
        "draft": draft,
        "usage": usage,
        "provenance": provenance,
        "gate_violations": viol,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--utterance", required=True)
    ap.add_argument("--feedback", default="", help="ignored; repair is internal (SAG loop)")
    args = ap.parse_args()
    try:
        out = run_sag(args.utterance)
    except LlmHttpError as e:
        out = {
            "status": "INFRASTRUCTURE_FAILURE",
            "error": str(e),
            "usage": e.usage,
            "provenance": {
                "method": "sag",
                "error": "http_failure",
                "http_status": e.status,
            },
        }
    except Exception as e:
        out = {
            "status": "FAILED",
            "error": str(e),
            "usage": {"calls": 0},
            "provenance": {"method": "sag", "error": "failed"},
        }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
