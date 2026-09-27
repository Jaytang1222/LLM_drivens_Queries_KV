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


def run_direct(utterance: str, repair: bool = False) -> dict:
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
    if draft is None and repair:
        msgs.append({"role": "assistant", "content": content or ""})
        msgs.append({"role": "user", "content": "Reply with ONLY valid DraftIR JSON."})
        content, u = chat(msgs, temperature=0.0, json_mode=True)
        merge_usage(usage, u)
        draft = extract_json(content)
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
        out = run_direct(args.utterance, repair=args.repair)
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
