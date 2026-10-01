#!/usr/bin/env python3
"""Synthetic LLM action-protocol eval (comparative_refine_6 §8, no DB)."""
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

PROMPT_VERSION = "cbo_llm_action_v10"
ALLOWED = {
    "NO_ACTION",
    "KEEP",
    "PROBE_JOINT_CANDIDATES",
    "CHECK_INTERSECT",
    "CHECK_FRAGMENTATION",
    "PROPOSE_P_T",
    "PROPOSE_P_Z",
    "PROPOSE_P_TZ",
    "PROPOSE_TIME",
    "PROPOSE_ZORDER",
    "PROPOSE_JOINT",
    "PROPOSE_PREFER",
}


def parse_action(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    text = raw.strip()
    if text.startswith("```"):
        parts = text.split("\n", 1)
        text = parts[1] if len(parts) > 1 else text
        text = text.replace("```", "").strip()
    up = text.upper()
    if up in ALLOWED:
        return "NO_ACTION" if up == "KEEP" else up
    try:
        brace = text.find("{")
        end = text.rfind("}")
        if brace >= 0 and end > brace:
            obj = json.loads(text[brace : end + 1])
            a = str(obj.get("action") or "").strip().upper()
            if a in ALLOWED:
                return "NO_ACTION" if a == "KEEP" else a
    except Exception:
        return None
    return None


def build_prompt(case: Dict[str, Any], order: List[str]) -> str:
    lines = [
        f"prompt_version={PROMPT_VERSION}",
        "task=propose_one_check_or_candidate",
        "You do NOT compare floats. Propose ONE action id from:",
        "NO_ACTION, PROBE_JOINT_CANDIDATES, CHECK_INTERSECT, "
        "PROPOSE_P_T, PROPOSE_P_Z, PROPOSE_PREFER",
        "schedule_mode=cbo_parallel",
        f"cbo={case['cbo']}",
        f"q={case['q']}",
        "whitelist=" + ",".join(order),
    ]
    hints = case.get("hints") or {}
    for pid in order:
        h = hints.get(pid)
        if h is None:
            lines.append(f"cand={pid}")
        else:
            lines.append(f"cand={pid} uncertain_saving_hint={h}")
    lines.append('Reply ONLY {"action":"PROPOSE_P_T"} style JSON.')
    return "\n".join(lines) + "\n"


def chat(base: str, model: str, prompt: str, timeout: int = 60) -> Tuple[str, int]:
    url = base.rstrip("/") + "/chat/completions"
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": 24,
        "messages": [
            {
                "role": "system",
                "content": (
                    "Propose one allowed action id. Examples: "
                    '{"action":"NO_ACTION"} or {"action":"PROPOSE_P_T"} '
                    'or {"action":"PROBE_JOINT_CANDIDATES"}. No other text.'
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
    return content, ms


CASES: List[Dict[str, Any]] = [
    {
        "id": "clear_time",
        "kind": "propose_time",
        "cbo": "P_TZ",
        "q": "topk dtw wide window",
        "whitelist": ["P_T", "P_Z"],
        "hints": {"P_T": 1800, "P_Z": 200},
        "expect_any": {"PROPOSE_P_T", "PROPOSE_PREFER", "PROBE_JOINT_CANDIDATES", "CHECK_INTERSECT"},
        "gold": "PROPOSE_P_T",
    },
    {
        "id": "no_opp",
        "kind": "no_action",
        "cbo": "P_TZ",
        "q": "tiny control st",
        "whitelist": ["P_T"],
        "hints": {"P_T": 50},
        "expect_any": {"NO_ACTION"},
        "gold": "NO_ACTION",
    },
    {
        "id": "uncertain_probe",
        "kind": "probe",
        "cbo": "P_TZ",
        "q": "port focus intersect uncertain",
        "whitelist": ["P_T", "P_Z"],
        "hints": {"P_T": 600, "P_Z": 550},
        "expect_any": {"PROBE_JOINT_CANDIDATES", "CHECK_INTERSECT", "PROPOSE_PREFER", "NO_ACTION"},
        "gold": "PROBE_JOINT_CANDIDATES",
    },
    {
        "id": "illegal_only_cbo",
        "kind": "keep",
        "cbo": "P_T",
        "q": "already time access",
        "whitelist": [],
        "hints": {},
        "expect_any": {"NO_ACTION"},
        "gold": "NO_ACTION",
    },
    {
        "id": "order_a",
        "kind": "order",
        "cbo": "P_TZ",
        "q": "order sensitivity a",
        "whitelist": ["P_T", "P_Z"],
        "hints": {"P_T": 1500, "P_Z": 100},
        "expect_any": {"PROPOSE_P_T", "PROPOSE_PREFER", "PROBE_JOINT_CANDIDATES"},
        "gold": "PROPOSE_P_T",
        "order": ["P_T", "P_Z"],
    },
    {
        "id": "order_b",
        "kind": "order",
        "cbo": "P_TZ",
        "q": "order sensitivity b",
        "whitelist": ["P_Z", "P_T"],
        "hints": {"P_T": 1500, "P_Z": 100},
        "expect_any": {"PROPOSE_P_T", "PROPOSE_PREFER", "PROBE_JOINT_CANDIDATES"},
        "gold": "PROPOSE_P_T",
        "order": ["P_Z", "P_T"],
    },
    {
        "id": "neg_z",
        "kind": "reject_bad",
        "cbo": "P_TZ",
        "q": "zorder harmful",
        "whitelist": ["P_Z", "P_T"],
        "hints": {"P_Z": 100, "P_T": 40},
        "expect_any": {"NO_ACTION", "PROBE_JOINT_CANDIDATES"},
        "gold": "NO_ACTION",
        "bad": {"PROPOSE_P_Z"},
    },
    {
        "id": "joint_hint",
        "kind": "probe",
        "cbo": "P_T",
        "q": "spatial temporal overlap",
        "whitelist": ["P_Z", "P_TZ"],
        "hints": {"P_TZ": 1200, "P_Z": 300},
        "expect_any": {
            "PROPOSE_P_TZ",
            "PROPOSE_JOINT",
            "PROPOSE_PREFER",
            "PROBE_JOINT_CANDIDATES",
            "CHECK_INTERSECT",
        },
        "gold": "PROBE_JOINT_CANDIDATES",
    },
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:11434/v1")
    ap.add_argument("--model", default="qwen2.5:1.5b-instruct")
    ap.add_argument("--out-jsonl", required=True)
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()

    results: List[dict] = []
    for case in CASES:
        order = list(case.get("order") or case["whitelist"] or [])
        prompt = build_prompt(case, order)
        err = None
        raw = ""
        ms = 0
        try:
            raw, ms = chat(args.base_url, args.model, prompt)
        except Exception as e:
            err = str(e)
        action = parse_action(raw) if not err else None
        format_ok = action is not None
        expect = case.get("expect_any") or set()
        soft_ok = bool(action and action in expect)
        gold_ok = action == case.get("gold")
        bad = case.get("bad") or set()
        avoided_bad = not (action in bad) if bad else None
        results.append({
            "id": case["id"],
            "kind": case["kind"],
            "gold": case.get("gold"),
            "action": action,
            "format_ok": format_ok,
            "soft_ok": soft_ok,
            "gold_ok": gold_ok,
            "avoided_bad": avoided_bad,
            "latency_ms": ms,
            "raw": (raw or "")[:300],
            "error": err,
            "order": order,
        })

    Path(args.out_jsonl).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out_jsonl).write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in results) + "\n",
        encoding="utf-8",
    )

    n = len(results)
    fmt = sum(1 for r in results if r["format_ok"])
    soft = sum(1 for r in results if r["soft_ok"])
    gold = sum(1 for r in results if r["gold_ok"])
    order_a = next((r for r in results if r["id"] == "order_a"), None)
    order_b = next((r for r in results if r["id"] == "order_b"), None)
    order_same = (
        order_a and order_b and order_a["action"] and order_a["action"] == order_b["action"]
    )
    neg = next((r for r in results if r["id"] == "neg_z"), None)

    md = f"""# llm_action_eval

> comparative_refine_6.md §8 — 受限动作提议合成测试（无 DB）。

## 汇总

| 指标 | 值 |
|---|---|
| format_ok | {fmt}/{n} |
| soft_ok（落在合理动作集） | {soft}/{n} |
| gold_ok（精确金标） | {gold}/{n} |
| 顺序一致性 (order_a vs order_b) | {order_same} |
| 负例避免 PROPOSE_P_Z | {None if neg is None else neg.get('avoided_bad')} |
| model | `{args.model}` |

## 逐例

| id | kind | gold | action | format | soft | gold_ok | ms |
|---|---|---|---|---|---|---|---|
"""
    for r in results:
        md += (
            f"| {r['id']} | {r['kind']} | {r['gold']} | {r['action']} | "
            f"{r['format_ok']} | {r['soft_ok']} | {r['gold_ok']} | {r['latency_ms']} |\n"
        )
    md += """
## 归因说明

- 合法动作不等于信息增益；若模型总提同一动作，增量应归统计/探测而非 LLM。
- 固定策略对照未新增正式比较臂；本文件仅作协议/语义合成验收。
"""
    Path(args.out_md).write_text(md, encoding="utf-8")
    print(f"ACTION_SYNTH format_ok={fmt}/{n} soft={soft}/{n} gold={gold}/{n}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
