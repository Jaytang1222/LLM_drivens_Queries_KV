#!/usr/bin/env python3
"""
Deep SAG transplant: decode logical MQL → gate → optional WorldAccess → DraftIR.

Arm ids:
  sag-mql           — with KartWorldAccess feedback when KART_JAR / world available
  sag-mql-nofeedback — translation + structural gate only (ablation)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters"))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters", "translate"))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters", "sag"))

from llm_client import (  # noqa: E402
    LlmHttpError,
    LOGICAL_CATALOG,
    chat,
    extract_json,
    merge_usage,
)
from mql_to_draft_ir import (  # noqa: E402
    LOGICAL_MQL_HINT,
    TranslateError,
    extract_mql,
    mql_to_draft_ir,
)

UPSTREAM = "experiments/third_party/text-to-nosql/src/tend/solver/sag/runtime.py"
K_CONSISTENCY = int(os.environ.get("KART_SAG_K", "1"))
MAX_REPAIR_ROUNDS = int(os.environ.get("KART_SAG_REPAIR_ROUNDS", "3"))


def _sys_prompt() -> str:
    return (
        "You are a SAG-style solver. Emit ONLY a logical MQL JSON array "
        "(dialect kart_logical_mql/1.0). Do NOT emit DraftIR.\n"
        + LOGICAL_MQL_HINT
        + "\n"
        + LOGICAL_CATALOG
    )


def _gate_mql(pipeline) -> list:
    viol = []
    if not isinstance(pipeline, list):
        return ["not a JSON array"]
    try:
        mql_to_draft_ir(pipeline)
    except TranslateError as e:
        viol.append(e.code + ": " + e.message)
    return viol


def _fingerprint(pipeline) -> str:
    try:
        blob = json.dumps(pipeline, sort_keys=True, ensure_ascii=False)
    except Exception:
        blob = str(pipeline)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


def _decode(msgs, usage):
    content, u = chat(msgs, temperature=0.0, json_mode=True)
    merge_usage(usage, u)
    # Prefer MQL array; fall back to extract_json object wrapping
    pipe = extract_mql(content)
    if pipe is None:
        obj = extract_json(content)
        if isinstance(obj, list):
            pipe = obj
        elif isinstance(obj, dict) and "pipeline" in obj:
            pipe = obj["pipeline"]
    return pipe, content


def _world_probe(draft: dict, feedback_on: bool) -> dict:
    """Bounded WorldAccess probe. Returns {ok, empty, message, t_world_ms, unavailable}."""
    if not feedback_on:
        return {
            "ok": True,
            "empty": False,
            "message": "feedback_disabled",
            "t_world_ms": 0,
            "unavailable": False,
            "world_backend": None,
        }
    try:
        from kart_world import KartWorldAccess

        world = KartWorldAccess()
        if not world.is_available():
            return {
                "ok": False,
                "empty": True,
                "message": "world_unavailable",
                "t_world_ms": 0,
                "unavailable": True,
                "world_backend": None,
            }
        t0 = time.perf_counter()
        res = world.execute_draft(draft)
        t_ms = int((time.perf_counter() - t0) * 1000)
        if res.get("error") == "world_unavailable" or not res.get("backend"):
            return {
                "ok": False,
                "empty": True,
                "message": "world_unavailable",
                "t_world_ms": t_ms,
                "unavailable": True,
                "world_backend": None,
            }
        empty = not res.get("trajectory_ids") and not res.get("top_k")
        backend = res.get("backend")
        # sample_json is plumbing-only — never counts as real SAG feedback backend.
        if not backend or str(backend).startswith("sample_json:"):
            return {
                "ok": False,
                "empty": empty,
                "message": "world_unavailable:sample_json_not_formal",
                "t_world_ms": t_ms,
                "unavailable": True,
                "world_backend": backend,
                "note": res.get("note"),
            }
        return {
            "ok": bool(res.get("ok", False)),
            "empty": empty,
            "message": res.get("error") or ("empty" if empty else "ok"),
            "t_world_ms": t_ms,
            "unavailable": False,
            "world_backend": backend,
            "note": res.get("note"),
        }
    except Exception as e:
        return {
            "ok": False,
            "empty": True,
            "message": "world_unavailable:" + str(e),
            "t_world_ms": 0,
            "unavailable": True,
            "world_backend": None,
        }


def _run_attempt(utterance: str, usage: dict, attempt: int, feedback_on: bool) -> dict:
    msgs = [
        {"role": "system", "content": _sys_prompt()},
        {"role": "user", "content": "NLQ: " + utterance + "\nProduce MQL pipeline JSON array."},
    ]
    best = None
    best_key = (10**9, 1, 0)
    world_log = []
    for rnd in range(MAX_REPAIR_ROUNDS + 1):
        pipe, raw = _decode(msgs, usage)
        viol = _gate_mql(pipe) if pipe is not None else ["no MQL pipeline"]
        draft = None
        prov = None
        if pipe is not None and not viol:
            try:
                draft, prov = mql_to_draft_ir(pipe)
            except TranslateError as e:
                viol = [e.code + ": " + e.message]
        probe = {
            "ok": True,
            "empty": False,
            "message": "skipped",
            "t_world_ms": 0,
            "unavailable": False,
        }
        if draft is not None and not viol:
            probe = _world_probe(draft, feedback_on)
            world_log.append(probe)
            # Fail-closed: missing/unusable world must not be repaired into OK.
            if feedback_on and probe.get("unavailable"):
                return {
                    "best": {
                        "pipeline": pipe,
                        "draft": draft,
                        "prov": prov,
                        "violations": ["world_unavailable"],
                        "raw": (raw or "")[:1500],
                        "round": rnd,
                        "probe": probe,
                        "infrastructure_failure": True,
                    },
                    "world_log": world_log,
                    "attempt": attempt,
                }
            if feedback_on and not probe.get("ok"):
                viol = list(viol) + ["world:" + str(probe.get("message"))]
        empty = 1 if (draft is None or probe.get("empty")) else 0
        key = (len(viol), empty, -rnd)
        cand = {
            "pipeline": pipe,
            "draft": draft,
            "prov": prov,
            "violations": viol,
            "raw": (raw or "")[:1500],
            "round": rnd,
            "probe": probe,
        }
        if key < best_key:
            best_key = key
            best = cand
        if not viol and empty == 0:
            break
        msgs.append({"role": "assistant", "content": raw or ""})
        msgs.append(
            {
                "role": "user",
                "content": "Repair MQL. Violations/feedback: "
                + json.dumps(viol + ([probe.get("message")] if feedback_on else [])),
            }
        )
    return {"best": best, "world_log": world_log, "attempt": attempt}


def run_sag_mql(utterance: str, feedback_on: bool = True) -> dict:
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    attempts = []
    for a in range(max(1, K_CONSISTENCY)):
        attempts.append(_run_attempt(utterance, usage, a, feedback_on))

    # majority by pipeline fingerprint when k>1
    if K_CONSISTENCY > 1:
        votes = {}
        for at in attempts:
            b = at.get("best") or {}
            fp = _fingerprint(b.get("pipeline"))
            votes.setdefault(fp, []).append(b)
        best = max(votes.values(), key=len)[0]
    else:
        best = (attempts[0].get("best") if attempts else None) or {}

    provenance = {
        "method": "sag-mql-deep",
        "upstream_file": UPSTREAM,
        "intermediate": "logical_mql",
        "translator": "mql_to_draft_ir",
        "dialect": "kart_logical_mql/1.0",
        "feedback": bool(feedback_on),
        "k": K_CONSISTENCY,
        "repair_rounds": MAX_REPAIR_ROUNDS,
        "world_log": [w for at in attempts for w in (at.get("world_log") or [])],
        "intentional_changes": [
            "decode target = logical MQL (not DraftIR direct)",
            "deterministic MQL → DraftIR",
            "WorldAccess = KartWorldAccess (HBase query-ir or unavailable)",
            "no MongoWorld / pymongo",
        ],
    }
    if best.get("prov"):
        provenance["pipeline"] = best["prov"].get("pipeline")
        provenance["kart_topk_extension"] = best["prov"].get("kart_topk_extension")

    viol = best.get("violations") or ["no candidate"]
    draft = best.get("draft")
    if best.get("infrastructure_failure") or any("world_unavailable" in str(v) for v in viol):
        return {
            "status": "INFRASTRUCTURE_FAILURE",
            "error": "world_unavailable: sag-mql requires a real WorldAccess backend "
            "(set KART_WORLD_SAMPLE only for plumbing smoke; prefer HBase/Mongo "
            "snapshot-aligned backend). Use sag-mql-nofeedback without feedback.",
            "usage": usage,
            "provenance": provenance,
            "fail_class": "world_unavailable",
        }
    if draft is None:
        return {
            "status": "UNSUPPORTED_QUERY" if any("untranslatable" in v for v in viol) else "INVALID_IR",
            "error": "; ".join(viol),
            "usage": usage,
            "provenance": provenance,
            "untranslatable_mql": any("untranslatable" in v for v in viol),
        }
    if viol:
        return {
            "status": "INVALID_IR",
            "error": "; ".join(viol),
            "draft": draft,
            "usage": usage,
            "provenance": provenance,
        }
    if feedback_on:
        # Only OK with feedback when a real probe backend was used.
        backends = [
            (p or {}).get("world_backend")
            for at in attempts
            for p in (at.get("world_log") or [])
        ]
        if not any(backends):
            return {
                "status": "INFRASTRUCTURE_FAILURE",
                "error": "world_unavailable",
                "usage": usage,
                "provenance": provenance,
                "fail_class": "world_unavailable",
            }
    return {
        "status": "OK",
        "draft": draft,
        "usage": usage,
        "provenance": provenance,
        "gate_violations": [],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--utterance", required=True)
    ap.add_argument(
        "--feedback",
        choices=["on", "off"],
        default="on",
        help="WorldAccess execution feedback",
    )
    args = ap.parse_args()
    try:
        out = run_sag_mql(args.utterance, feedback_on=(args.feedback == "on"))
    except LlmHttpError as e:
        out = {
            "status": "INFRASTRUCTURE_FAILURE",
            "error": str(e),
            "usage": e.usage,
            "provenance": {"method": "sag-mql-deep", "http_status": e.status},
        }
    except Exception as e:
        out = {
            "status": "FAILED",
            "error": str(e),
            "usage": {"calls": 0},
            "provenance": {"method": "sag-mql-deep", "error": "failed"},
        }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
