#!/usr/bin/env python3
"""
LLMOpt-style Generator → Selector bridge for KART plan family.

Upstream (experiments/third_party/llmopt):
  - README: "Utilizing An LLM As Generator" then Selector; Tree-CNN/Bao may pick among hints
  - Inference scripts under LLM_training/ (vLLM) — we do NOT run PG or their checkpoints

Protocol kept:
  G: propose candidate plan_ids from the constructor family
  S: select exactly one plan_id
  Java LlmOptPlanArm forcePlanId + validator/executor (stand-in for pg_hint_plan)

Intentional non-ports: PostgreSQL, pg_hint_plan_lucifer, JOB/IMDb weights, vLLM training stack.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from llm_client import chat, extract_json, merge_usage  # noqa: E402

PLAN_FAMILY = [
    "P_FULL",
    "P_T",
    "P_Z",
    "P_H",
    "P_TZ",
    "P_TH",
    "P_ZH",
    "P_TZH",
    "P_T_TIME_BIPART",
]

PROVENANCE = {
    "method": "llmopt",
    "upstream_readme": "experiments/third_party/llmopt/README.md",
    "upstream_api": ["Generator (pred_hints)", "Selector (pick one hint/plan)"],
    "intentional_changes": [
        "plan_id family = KART SafePlan constructors (not PG hints)",
        "no PostgreSQL / pg_hint_plan_lucifer",
        "no vLLM / fine-tuned LLMOpt checkpoints — same fair LLM endpoint",
        "Java validator+executor replaces PG execution",
    ],
}


def generator(bound_ir: dict, usage: dict):
    prompt = (
        "You are LLMOpt Generator for KART. "
        "Given BoundIR JSON, propose up to 4 plan_id candidates from this family only:\n"
        + ", ".join(PLAN_FAMILY)
        + '\nReply JSON {"candidates":["P_T","P_Z",...]}.\nBoundIR:\n'
        + json.dumps(bound_ir, ensure_ascii=False)[:4000]
    )
    content, u = chat([{"role": "user", "content": prompt}], temperature=0.0, json_mode=True)
    merge_usage(usage, u)
    obj = extract_json(content) or {}
    cands = obj.get("candidates") or []
    out = []
    for c in cands:
        s = str(c)
        if s in PLAN_FAMILY and s not in out:
            out.append(s)
    if not out:
        out = ["P_T", "P_Z", "P_FULL"]
    return out


def selector(bound_ir: dict, cands: list, usage: dict):
    prompt = (
        "You are LLMOpt Selector for KART. Pick exactly one plan_id from candidates.\n"
        'Reply JSON {"plan_id":"P_T"}.\n'
        f"Candidates: {cands}\nBoundIR summary query_id={bound_ir.get('query_id')}"
    )
    content, u = chat([{"role": "user", "content": prompt}], temperature=0.0, json_mode=True)
    merge_usage(usage, u)
    obj = extract_json(content) or {}
    pid = str(obj.get("plan_id", cands[0]))
    if pid not in cands:
        pid = cands[0]
    return pid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bound-ir", required=True, help="Path to BoundIR JSON")
    args = ap.parse_args()
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    try:
        with open(args.bound_ir, "r", encoding="utf-8") as f:
            bound = json.load(f)
        cands = generator(bound, usage)
        chosen = selector(bound, cands, usage)
        out = {
            "status": "OK",
            "candidates": cands,
            "plan_id": chosen,
            "usage": usage,
            "protocol": "llmopt_G_then_S",
            "provenance": PROVENANCE,
        }
    except Exception as e:
        out = {
            "status": "FAILED",
            "error": str(e),
            "usage": usage,
            "provenance": PROVENANCE,
        }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
