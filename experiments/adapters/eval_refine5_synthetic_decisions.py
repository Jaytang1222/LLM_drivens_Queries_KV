#!/usr/bin/env python3
"""Offline synthetic decision-protocol eval for comparative_refine_5 §4 (no DB)."""
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


PROMPT_VERSION = "cbo_llm_short_v9"


def build_prompt(
    cases: List[Dict[str, Any]],
    schedule_mode: str,
    extra_ms: int,
    uncertainty: Optional[int],
    order: List[int],
) -> Tuple[str, List[str]]:
    """Build v9-style prompt; order is permutation of candidate indices in cases."""
    lines = [
        f"prompt_version={PROMPT_VERSION}",
        "task=choose_faster_safe_e2e_plan",
        "exec_saving_ms: ms; positive => candidate exec faster than CBO; "
        "does NOT subtract LLM/prep critical-path cost.",
        "All values are predictions. Uncertainty unknown != 0.",
        "If evidence insufficient or predicted net gain non-positive: KEEP.",
        "Else pick a legal candidate index.",
        f"schedule_mode={schedule_mode}",
        f"estimated_extra_critical_path_ms={extra_ms}",
        f"saving_uncertainty_ms={'unknown' if uncertainty is None else uncertainty}",
        "cbo=P_TZ cbo_exec_ms=3000",
        "q=synthetic",
    ]
    id_map: List[str] = []
    for out_i, src_i in enumerate(order):
        c = cases[src_i]
        pid = c["plan_id"]
        id_map.append(pid)
        lines.append(
            f"{out_i}={pid} access={c.get('access','OTHER')} "
            f"exec_saving_ms={c['exec_saving_ms']} "
            f"candidate_exec_ms={c.get('candidate_exec_ms', 3000 - c['exec_saving_ms'])}"
        )
    lines.append('Examples: {"choice":"KEEP"}  OR  {"choice":0}')
    lines.append("Use integer index only (0,1,...). Never output the letter N.")
    return "\n".join(lines) + "\n", id_map


def parse_choice(raw: str, n: int) -> Tuple[Optional[str], Optional[int]]:
    if raw is None:
        return None, None
    text = raw.strip()
    if text.startswith("```"):
        parts = text.split("\n", 1)
        text = parts[1] if len(parts) > 1 else text
        text = text.replace("```", "").strip()
    if text.upper() in ("KEEP", "KEEP_CBO"):
        return "KEEP", None
    try:
        bare = int(text)
        if 0 <= bare < n:
            return "INDEX", bare
    except ValueError:
        pass
    try:
        brace = text.find("{")
        end = text.rfind("}")
        if brace >= 0 and end > brace:
            obj = json.loads(text[brace : end + 1])
            ch = obj.get("choice")
            if isinstance(ch, str) and ch.upper() in ("KEEP", "KEEP_CBO", "NULL"):
                return "KEEP", None
            if isinstance(ch, str) and ch.upper() == "N":
                return None, None
            if isinstance(ch, str) and ch.isdigit():
                ch = int(ch)
            if isinstance(ch, int) and 0 <= ch < n:
                return "INDEX", ch
    except Exception:
        return None, None
    return None, None


def expected_label(case_meta: Dict[str, Any], uncertainty: Optional[int], extra: int) -> str:
    """Gold label under conservative net-gain rule used in the contract."""
    kind = case_meta["kind"]
    if kind in ("neg", "e2e_neg", "uncertain"):
        return "KEEP"
    if kind == "pos":
        # clear positive after extra + uncertainty
        return "INDEX"
    if kind == "unit_equiv":
        return "INDEX"
    return "KEEP"


CASES: List[Dict[str, Any]] = [
    # 1 negative
    {"kind": "neg", "plan_id": "P_Z", "access": "ZORDER_ONLY", "exec_saving_ms": 100,
     "candidate_exec_ms": 2900, "extra": 800, "u": 200, "seed": 1},
    # 2 clear positive (1800 - 400 - 200 > 0)
    {"kind": "pos", "plan_id": "P_T", "access": "TIME_ONLY", "exec_saving_ms": 1800,
     "candidate_exec_ms": 1200, "extra": 400, "u": 200, "seed": 2},
    # 3 exec positive but e2e negative
    {"kind": "e2e_neg", "plan_id": "P_T", "access": "TIME_ONLY", "exec_saving_ms": 300,
     "candidate_exec_ms": 2700, "extra": 800, "u": 200, "seed": 3},
    # 4 uncertainty crosses zero
    {"kind": "uncertain", "plan_id": "P_T", "access": "TIME_ONLY", "exec_saving_ms": 500,
     "candidate_exec_ms": 2500, "extra": 200, "u": 600, "seed": 4},
    # 5 unit/sign consistent positive (same as pos, different wording via fields)
    {"kind": "unit_equiv", "plan_id": "P_T", "access": "TIME_ONLY", "exec_saving_ms": 1800,
     "candidate_exec_ms": 1200, "extra": 400, "u": 200, "seed": 5},
]


def chat(base: str, model: str, prompt: str, timeout: int = 60) -> Tuple[str, int, Optional[int], Optional[int]]:
    url = base.rstrip("/") + "/chat/completions"
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": 16,
        "messages": [
            {
                "role": "system",
                "content": (
                    "Choose the safer plan expected to finish end-to-end sooner. "
                    'Reply one JSON only. Examples: {"choice":"KEEP"} or {"choice":0}. '
                    "Use an integer index from the whitelist, never the letter N. No other text."
                ),
            },
            {"role": "user", "content": prompt},
        ],
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    ms = int((time.time() - t0) * 1000)
    content = data["choices"][0]["message"]["content"]
    usage = data.get("usage") or {}
    return content, ms, usage.get("prompt_tokens"), usage.get("completion_tokens")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:11434/v1")
    ap.add_argument("--model", default="qwen2.5:1.5b-instruct")
    ap.add_argument("--out-jsonl", required=True)
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()

    rows: List[dict] = []
    # per-case + order-swap for pos/unit_equiv
    jobs: List[Dict[str, Any]] = []
    for c in CASES:
        jobs.append({"case": c, "order": [0], "candidates": [c], "tag": c["kind"]})
        if c["kind"] in ("pos", "unit_equiv", "neg"):
            decoy = {
                "kind": "decoy",
                "plan_id": "P_H",
                "access": "HASH_ONLY",
                "exec_saving_ms": -200,
                "candidate_exec_ms": 3200,
            }
            jobs.append(
                {
                    "case": c,
                    "order": [1, 0],
                    "candidates": [decoy, c],
                    "tag": c["kind"] + "_swap",
                }
            )

    pos_ok = pos_n = neg_ok = neg_n = fmt_ok = 0
    for job in jobs:
        c = job["case"]
        cands = job["candidates"]
        order = job["order"]
        # map order positions to candidate list indices
        prompt, id_map = build_prompt(
            cands, "speculate_candidate", c["extra"], c["u"], list(range(len(cands)))
        )
        # rebuild with swap: put candidates in display order
        display = [cands[i] for i in order] if job["tag"].endswith("_swap") else cands
        if job["tag"].endswith("_swap"):
            prompt, id_map = build_prompt(
                display, "speculate_candidate", c["extra"], c["u"], list(range(len(display)))
            )
        gold = expected_label(c, c["u"], c["extra"])
        # gold INDEX means pick the plan_id of the true case in display
        gold_idx = None
        if gold == "INDEX":
            for i, d in enumerate(display if job["tag"].endswith("_swap") else cands):
                if d["plan_id"] == c["plan_id"] and d.get("exec_saving_ms") == c["exec_saving_ms"]:
                    gold_idx = i
                    break
        try:
            raw, latency, tin, tout = chat(args.base_url, args.model, prompt)
            kind, idx = parse_choice(raw, len(id_map))
            formal = kind is not None
            if formal:
                fmt_ok += 1
            pred = "KEEP" if kind == "KEEP" else ("INDEX" if kind == "INDEX" else "INVALID")
            match = False
            if gold == "KEEP" and pred == "KEEP":
                match = True
            if gold == "INDEX" and pred == "INDEX" and idx == gold_idx:
                match = True
            if c["kind"] in ("pos", "unit_equiv"):
                pos_n += 1
                if match:
                    pos_ok += 1
            if c["kind"] in ("neg", "e2e_neg", "uncertain"):
                neg_n += 1
                if match:
                    neg_ok += 1
            rows.append(
                {
                    "tag": job["tag"],
                    "kind": c["kind"],
                    "prompt_version": PROMPT_VERSION,
                    "gold": gold,
                    "gold_idx": gold_idx,
                    "pred": pred,
                    "pred_idx": idx,
                    "match": match,
                    "format_ok": formal,
                    "raw": raw,
                    "latency_ms": latency,
                    "tokens_in": tin,
                    "tokens_out": tout,
                    "id_map": id_map,
                    "prompt": prompt,
                }
            )
        except Exception as e:
            rows.append(
                {
                    "tag": job["tag"],
                    "kind": c["kind"],
                    "error": str(e),
                    "match": False,
                    "format_ok": False,
                }
            )

    out_j = Path(args.out_jsonl)
    out_j.parent.mkdir(parents=True, exist_ok=True)
    out_j.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")

    # order consistency for swap pairs
    by_base = {}
    for r in rows:
        if r.get("error"):
            continue
        by_base.setdefault(r["kind"], []).append(r)
    order_ok = 0
    order_n = 0
    for kind in ("pos", "unit_equiv", "neg"):
        base = [r for r in rows if r.get("tag") == kind]
        swap = [r for r in rows if r.get("tag") == kind + "_swap"]
        if base and swap and base[0].get("format_ok") and swap[0].get("format_ok"):
            order_n += 1
            # semantic: both KEEP or both select same plan_id
            b, s = base[0], swap[0]
            if b["pred"] == "KEEP" and s["pred"] == "KEEP":
                order_ok += 1
            elif b["pred"] == "INDEX" and s["pred"] == "INDEX":
                bp = b["id_map"][b["pred_idx"]] if b.get("pred_idx") is not None else None
                sp = s["id_map"][s["pred_idx"]] if s.get("pred_idx") is not None else None
                if bp == sp:
                    order_ok += 1

    md = f"""# decision_synthetic_eval

> comparative_refine_5.md §4 — 不执行数据库的合成协议诊断。

| 指标 | 值 |
|---|---|
| prompt | `{PROMPT_VERSION}` |
| model | `{args.model}` |
| n | {len(rows)} |
| format_ok | {fmt_ok}/{len(rows)} |
| clear-pos recall | {pos_ok}/{pos_n} |
| clear-neg / e2e-neg / uncertain keep | {neg_ok}/{neg_n} |
| order-consistency | {order_ok}/{order_n} |

合成通过只证明契约理解，不证明数据库收益。
"""
    Path(args.out_md).write_text(md, encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
