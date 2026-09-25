# Shared OpenAI-compatible chat — fair experiment defaults (spec §5.6 / §6.6).
# temperature forced to 0 unless KART_LLM_TEMPERATURE is set for a paper-required setting.
from __future__ import annotations

import json
import os
import time
import urllib.request

# Fairness contract written into every bridge response.usage
FAIRNESS = {
    "temperature": float(os.environ.get("KART_LLM_TEMPERATURE", "0")),
    "same_endpoint": True,
    "json_mode_preferred": True,
}


def merge_usage(acc: dict, u: dict) -> dict:
    if acc is None:
        acc = {}
    if not u:
        return acc
    acc["calls"] = (acc.get("calls") or 0) + (u.get("calls") or 1)
    acc["prompt_tokens"] = (acc.get("prompt_tokens") or 0) + (u.get("prompt_tokens") or 0)
    acc["completion_tokens"] = (acc.get("completion_tokens") or 0) + (
        u.get("completion_tokens") or 0
    )
    acc["t_model_ms"] = (acc.get("t_model_ms") or 0) + (u.get("t_model_ms") or 0)
    acc["json_mode_fallbacks"] = (acc.get("json_mode_fallbacks") or 0) + (
        1 if u.get("json_mode_fallback") else 0
    )
    acc["model"] = u.get("model") or acc.get("model")
    acc["temperature"] = u.get("temperature") if u.get("temperature") is not None else acc.get(
        "temperature"
    )
    acc["fairness"] = u.get("fairness") or acc.get("fairness") or FAIRNESS
    return acc


def chat(messages, temperature=None, max_tokens=2048, json_mode=None):
    """
    Shared LLM call for all Python bridges.
    json_mode: True force JSON object; False free text; None = FAIRNESS default (True).
    DIN intermediate stages must pass json_mode=False (upstream free-text completions).
    A JSON-mode HTTP failure retries without response_format and counts as 2 calls.
    """
    base = os.environ.get("LLM_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    key = os.environ.get("LLM_API_KEY") or os.environ.get("OPENAI_API_KEY")
    model = os.environ.get("LLM_MODEL", "gpt-4o-mini")
    if not key:
        raise RuntimeError("LLM_API_KEY not set")
    if temperature is None:
        temperature = FAIRNESS["temperature"]
    if json_mode is None:
        json_mode = FAIRNESS["json_mode_preferred"]
    url = base + "/chat/completions"
    body = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    if json_mode:
        body["response_format"] = {"type": "json_object"}

    def _post(payload):
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + key,
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))

    t0 = time.perf_counter()
    attempts = 1
    json_fallback = False
    try:
        data = _post(body)
    except Exception:
        if not json_mode:
            raise
        body.pop("response_format", None)
        json_fallback = True
        attempts = 2
        data = _post(body)
    t_ms = int((time.perf_counter() - t0) * 1000)
    choice = data["choices"][0]["message"]["content"]
    usage = data.get("usage") or {}
    return choice, {
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "calls": attempts,
        "model": model,
        "temperature": temperature,
        "json_mode": bool(json_mode) and not json_fallback,
        "json_mode_fallback": json_fallback,
        "t_model_ms": max(0, t_ms),
        "fairness": FAIRNESS,
    }


def extract_json(text: str):
    if not text:
        return None
    text = text.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = [ln for ln in lines if not ln.strip().startswith("```")]
        text = "\n".join(lines)
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start : end + 1])
    except Exception:
        return None


LOGICAL_CATALOG = """
Logical catalog (DraftIR, NOT SQL) — same catalog for all E1 arms:
- temporal: {start, end} ISO-8601 Asia/Shanghai
- spatial: region_name (registered only) OR RECTANGLE min_lon/min_lat/max_lon/max_lat
- predicates: vehicle_id EQ <string>
- similarity: metric DTW|FRECHET|HAUSDORFF, reference_trajectory_id
- result: mode TRAJECTORY_IDS|TOP_K, k
- semantics: always OBSERVED_POINT / SAME_POINT
UNSUPPORTED: COUNT/aggregation; continuous path; continuous Fréchet; EDIT_DISTANCE;
  derived speed/dwell; pair co-location; inventing unknown places as beijing_core.
Registered regions: tdrive_smoke_anchor, tdrive_topk_box, tdrive_topk_wide, tdrive_topk_s1,
  beijing_core, zhongguancun, wangjing, guomao, beijing_cbd, tiananmen, capital_airport,
  haidian_central, chaoyang_central.
"""

# Presented to DIN prompt_makers as a fake "database schema" string
DIN_SCHEMA_FIELDS = (
    "Table trajectory, columns = [*,trajectory_id,vehicle_id,t_start,t_end,min_lon,min_lat,max_lon,max_lat]\n"
    "Table region, columns = [*,region_name,min_lon,min_lat,max_lon,max_lat]\n"
)
DIN_FOREIGN_KEYS = "[]"

# Minimal DraftIR skeleton shown to generators (shared surface)
DRAFT_IR_HINT = (
    'DraftIR JSON shape: {"ir_version":"v1","source":{"dataset_id":"tdrive_v1"},'
    '"temporal":{"start":"...","end":"..."},"spatial":{...},"result":{"mode":"TRAJECTORY_IDS"}}'
)
