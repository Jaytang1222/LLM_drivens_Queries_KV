#!/usr/bin/env python3
"""
Same-model direct DraftIR baseline (single shot + optional one repair on schema error).
Arm id: direct-draftir
"""
from __future__ import annotations

import argparse
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


def _coerce_draft(draft):
    if not isinstance(draft, dict):
        return draft
    if draft.get("ir_version") in (None, "1", 1):
        draft["ir_version"] = "1.0"
    src = draft.get("source")
    if not isinstance(src, dict):
        draft["source"] = {"dataset_id": "tdrive_v1", "entity": "trajectory"}
    else:
        src.setdefault("dataset_id", "tdrive_v1")
        src.setdefault("entity", "trajectory")
    sem = draft.get("semantics")
    if not isinstance(sem, dict):
        draft["semantics"] = {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"}
    else:
        sem.setdefault("mode", "OBSERVED_POINT")
        sem.setdefault("coupling", "SAME_POINT")
    res = draft.get("result")
    if not isinstance(res, dict):
        draft["result"] = {"mode": "TRAJECTORY_IDS"}
    else:
        res.setdefault("mode", "TRAJECTORY_IDS")
        res.pop("tie_breaker", None)
        if res.get("k") is None:
            res.pop("k", None)
    if isinstance(draft.get("temporal"), dict):
        draft["temporal"].setdefault("boundary", "[start,end)")
    if isinstance(draft.get("spatial"), dict):
        draft["spatial"].setdefault("relation", "INTERSECTS")
        draft["spatial"].setdefault("boundary", "INCLUDED")
    return draft


def run_direct(utterance: str, repair: bool = True) -> dict:
    usage = {"calls": 0}
    msgs = [
        {
            "role": "system",
            "content": "Emit ONLY DraftIR JSON.\n" + DRAFT_IR_HINT + "\n" + LOGICAL_CATALOG,
        },
        {"role": "user", "content": utterance},
    ]
    content, u = chat(msgs, temperature=0.0, json_mode=True)
    merge_usage(usage, u)
    draft = extract_json(content)
    if isinstance(draft, dict):
        draft = _coerce_draft(draft)
    if (draft is None or not isinstance(draft, dict)) and repair:
        msgs.append({"role": "assistant", "content": content or ""})
        msgs.append({"role": "user", "content": "Reply with ONLY valid DraftIR JSON."})
        content, u = chat(msgs, temperature=0.0, json_mode=True)
        merge_usage(usage, u)
        draft = extract_json(content)
        if isinstance(draft, dict):
            draft = _coerce_draft(draft)
    provenance = {
        "method": "direct-draftir",
        "protocol": "single_shot" + ("_plus_one_repair" if repair else ""),
        "intentional_changes": ["fairness baseline; not DIN/SAG"],
    }
    if draft is None:
        return {
            "status": "INVALID_IR",
            "error": "no DraftIR",
            "usage": usage,
            "provenance": provenance,
        }
    if "unsupported" in (draft.get("missing") or []):
        return {
            "status": "UNSUPPORTED_QUERY",
            "draft": draft,
            "usage": usage,
            "provenance": provenance,
        }
    return {"status": "OK", "draft": draft, "usage": usage, "provenance": provenance}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--utterance", required=True)
    ap.add_argument("--repair", action="store_true")
    args = ap.parse_args()
    try:
        out = run_direct(args.utterance, repair=True)
    except LlmHttpError as e:
        out = {
            "status": "INFRASTRUCTURE_FAILURE",
            "error": str(e),
            "usage": e.usage,
            "provenance": {"method": "direct-draftir", "http_status": e.status},
        }
    except Exception as e:
        out = {"status": "FAILED", "error": str(e), "usage": {"calls": 0}}
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
