#!/usr/bin/env python3
"""
DIN-SQL upstream-backed bridge for KART.

Authenticity (spec §5.2 / §5.4):
  - Loads experiments/third_party/din-sql/DIN-SQL.py (spider) or DIN-SQL_BIRD.py (bird)
  - Calls upstream module order:
      schema_linking → classification → easy|medium|hard generation → self-correction
  - Spider: real prompt_maker() functions from DIN-SQL.py (sanitized load)
  - Bird: real SYSTEM_*/HUMAN_* templates + extract_* helpers from DIN-SQL_BIRD.py
  - Monkeypatches schema lookup → shared KART logical catalog
  - Final SQL surface → DraftIR JSON (intentional; spec §5.4)

Does NOT run Spider/BIRD SQL executors or LangChain/ChatOpenAI from the upstream file.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import types

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters"))
from llm_client import (  # noqa: E402
    DIN_FOREIGN_KEYS,
    DIN_SCHEMA_FIELDS,
    DRAFT_IR_HINT,
    LOGICAL_CATALOG,
    chat,
    extract_json,
    merge_usage,
)


def _kart_schema_block(knowledge: str) -> str:
    fields = DIN_SCHEMA_FIELDS + "\n# KART logical notes:\n" + LOGICAL_CATALOG
    if knowledge:
        fields += "\n# Fixed external knowledge (bird):\n" + knowledge + "\n"
    return fields


def _sanitize_spider_source(src: str) -> str:
    """Make DIN-SQL.py loadable: keep few-shots + makers; drop argv/API/GPT4/main."""
    cut = src.find("\nif sys.argv[1] ==")
    if cut < 0:
        cut = src.find("\nif __name__")
    head = src[:cut] if cut > 0 else src
    # Makers + find_* + debuger live AFTER the argv gate in upstream
    m_start = re.search(r"\ndef hard_prompt_maker\(", src)
    m_end = re.search(r"\ndef GPT4_generation\(", src)
    if not m_start:
        raise RuntimeError("DIN-SQL.py missing hard_prompt_maker")
    end = m_end.start() if m_end else len(src)
    body = src[m_start.start() : end]
    # Drop creatiing_schema (needs Spider tables.json); keep debuger if present
    # body already includes find_* and debuger before GPT4_generation
    stubs = """

def load_data(DATASET):
    return None

def creatiing_schema(DATASET_JSON):
    return None, None, None
"""
    return head + stubs + body


def _load_spider_module(path: str):
    src = open(path, "r", encoding="utf-8", errors="replace").read()
    src = _sanitize_spider_source(src)
    # Stub heavy deps so makers load without network / API keys
    if "pandas" not in sys.modules:
        try:
            import pandas  # noqa: F401
        except ImportError:
            pd_mod = types.ModuleType("pandas")
            pd_mod.read_csv = lambda *a, **k: None
            pd_mod.read_json = lambda *a, **k: None
            sys.modules["pandas"] = pd_mod
    if "openai" not in sys.modules:
        try:
            import openai  # noqa: F401
        except ImportError:
            sys.modules["openai"] = types.ModuleType("openai")
    g: dict = {
        "__name__": "din_spider_upstream",
        "__file__": path,
        "os": os,
        "sys": sys,
        "time": __import__("time"),
        "re": re,
    }
    exec(compile(src, path, "exec"), g)
    # Functions close over g as __globals__; keep g for monkeypatch.
    ns = types.SimpleNamespace(**{k: v for k, v in g.items() if not k.startswith("__")})
    ns._exec_globals = g
    return ns


def _extract_bird_templates(path: str) -> dict:
    """Parse SYSTEM_*/HUMAN_* string constants without importing langchain / reading BIRD DBs."""
    src = open(path, "r", encoding="utf-8", errors="replace").read()
    names = [
        "SYSTEM_SCHEMA_LINKING_TEMPLATE",
        "HUMAN_SCHEMA_LINKING_TEMPLATE",
        "SYSTEM_CLASSIFICATION_TEMPLATE",
        "HUMAN_CLASSIFICATION_TEMPLATE",
        "SYSTEM_EASY_CLASS_TEMPLATE",
        "HUMAN_EASY_CLASS_TEMPLATE",
        "SYSTEM_NON_NESTED_CLASS_TEMPLATE",
        "HUMAN_NON_NESTED_CLASS_TEMPLATE",
        "SYSTEM_NESTED_CLASS_TEMPLATE",
        "HUMAN_NESTED_CLASS_TEMPLATE",
        "SYSTEM_SELF_CORRECTION_PROMPT",
        "HUMAN_SELF_CORRECTION_PROMPT",
    ]
    out = {}
    for name in names:
        # NAME = """ ... """  or NAME = ''' ... '''
        m = re.search(
            rf"{name}\s*=\s*(\"\"\"|''')(.*?)(\1)",
            src,
            re.S,
        )
        if not m:
            raise RuntimeError(f"missing template {name} in {path}")
        out[name] = m.group(2)
    # extract helpers via small exec of function bodies only
    helper_src = ""
    for fn in (
        "extract_schema_links",
        "extract_label_and_sub_questions",
        "extract_sql_query",
        "extract_revised_sql_query",
    ):
        m = re.search(rf"\ndef {fn}\(.*?\n(?=\ndef |\nif __name__)", src, re.S)
        if m:
            helper_src += m.group(0)
    g = {"re": re, "List": list, "Tuple": tuple}
    if helper_src:
        # typing imports used in annotations — strip or ignore
        helper_src = helper_src.replace("List[str]", "list").replace(
            "Tuple[str, List[str]]", "tuple"
        )
        exec(compile(helper_src, path + ":helpers", "exec"), g)
    out["_helpers"] = g
    return out


def _patch_schema(mod, knowledge: str):
    fields = _kart_schema_block(knowledge)

    def find_fields_MYSQL_like(db_name):
        return fields

    def find_foreign_keys_MYSQL_like(db_name):
        return DIN_FOREIGN_KEYS

    def find_primary_keys_MYSQL_like(db_name):
        return "[]"

    # Patch both attribute view and exec globals (makers resolve names via __globals__)
    g = getattr(mod, "_exec_globals", None)
    for name, fn in (
        ("find_fields_MYSQL_like", find_fields_MYSQL_like),
        ("find_foreign_keys_MYSQL_like", find_foreign_keys_MYSQL_like),
        ("find_primary_keys_MYSQL_like", find_primary_keys_MYSQL_like),
    ):
        setattr(mod, name, fn)
        if isinstance(g, dict):
            g[name] = fn


def _llm(prompt: str, usage: dict, *, as_json: bool) -> str:
    msgs = [{"role": "user", "content": prompt}]
    if as_json:
        msgs[0]["content"] = (
            prompt
            + "\n\nIMPORTANT: Reply with ONLY a DraftIR JSON object (not SQL). "
            + DRAFT_IR_HINT
            + " Use OBSERVED_POINT semantics; source.dataset_id=tdrive_v1."
        )
    content, u = chat(msgs, temperature=0.0, json_mode=as_json)
    merge_usage(usage, u)
    return content or ""


def _extract_schema_links(text: str) -> str:
    m = re.search(r"Schema_links\s*:\s*(\[[^\]]*\])", text, re.I | re.S)
    if m:
        return m.group(1)
    return "[]"


def _extract_label(text: str) -> str:
    m = re.search(r'Label:\s*"?([A-Za-z\-]+)"?', text)
    if m:
        return m.group(1).strip().upper()
    t = text.upper()
    if "NON-NESTED" in t or "NON_NESTED" in t:
        return "NON-NESTED"
    if "NESTED" in t:
        return "NESTED"
    if "EASY" in t:
        return "EASY"
    return "NON-NESTED"


def _retarget_sql_surface(prompt: str) -> str:
    return (
        prompt.replace("\nSQL:", "\nDraftIR JSON:")
        .replace("generate the SQL", "generate the DraftIR JSON")
        .replace("SQL queries", "DraftIR JSON objects")
        .replace("sqlite SQL query", "DraftIR JSON")
        .replace("SQLite SQL QUERY", "DraftIR JSON")
        .replace("FIXED SQL QUERY", "FIXED DraftIR JSON")
    )


def run_din_spider(utterance: str, knowledge: str) -> dict:
    path = os.path.join(ROOT, "experiments", "third_party", "din-sql", "DIN-SQL.py")
    if not os.path.isfile(path):
        raise FileNotFoundError(
            f"missing upstream {path}; run experiments/adapters/clone-third-party.sh"
        )
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    mod = _load_spider_module(path)
    _patch_schema(mod, knowledge)
    db = "kart_tdrive"

    sl_prompt = mod.schema_linking_prompt_maker(utterance, db)
    sl_out = _llm(sl_prompt, usage, as_json=False)
    schema_links = _extract_schema_links(sl_out)

    cls_prompt = mod.classification_prompt_maker(utterance, db, schema_links)
    cls_out = _llm(cls_prompt, usage, as_json=False)
    label = _extract_label(cls_out)

    if label == "EASY":
        gen_prompt = mod.easy_prompt_maker(utterance, db, schema_links)
    elif label == "NON-NESTED":
        gen_prompt = mod.medium_prompt_maker(utterance, db, schema_links)
    else:
        sub_q = utterance
        m = re.search(r'questions\s*=\s*\["([^"]*)"\]', cls_out)
        if m:
            sub_q = m.group(1)
        gen_prompt = mod.hard_prompt_maker(utterance, db, schema_links, sub_q)

    gen_out = _llm(_retarget_sql_surface(gen_prompt), usage, as_json=True)
    draft = extract_json(gen_out)

    if draft is None or "unsupported" in (draft.get("missing") or []):
        if draft and "unsupported" in (draft.get("missing") or []):
            return _pack("UNSUPPORTED_QUERY", usage, path, "spider", label, draft=draft,
                         error=draft.get("error") or "unsupported")
        dbg = mod.debuger(utterance, db, gen_out or "")
        fixed = extract_json(_llm(_retarget_sql_surface(dbg), usage, as_json=True))
        draft = fixed

    return _finish(draft, usage, path, "spider", label)


def run_din_bird(utterance: str, knowledge: str) -> dict:
    path = os.path.join(ROOT, "experiments", "third_party", "din-sql", "DIN-SQL_BIRD.py")
    if not os.path.isfile(path):
        raise FileNotFoundError(
            f"missing upstream {path}; run experiments/adapters/clone-third-party.sh"
        )
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    tpl = _extract_bird_templates(path)
    helpers = tpl["_helpers"]
    schema = _kart_schema_block(knowledge)
    cols = "Column descriptions: see KART logical catalog above."
    hint = knowledge.strip() or "Use OBSERVED_POINT semantics; registered regions only."

    # Upstream BIRD loop: schema_linking → classification → easy|medium|nested → self_correction
    sl = tpl["SYSTEM_SCHEMA_LINKING_TEMPLATE"] + "\n" + tpl["HUMAN_SCHEMA_LINKING_TEMPLATE"].format(
        schema=schema, columns_descriptions=cols, question=utterance, hint=hint
    )
    sl_out = _llm(sl, usage, as_json=False)
    extract_sl = helpers.get("extract_schema_links")
    if extract_sl:
        links_list = extract_sl(sl_out) or []
        schema_links = str(links_list)
    else:
        schema_links = _extract_schema_links(sl_out)

    cls = tpl["SYSTEM_CLASSIFICATION_TEMPLATE"] + "\n" + tpl["HUMAN_CLASSIFICATION_TEMPLATE"].format(
        schema=schema,
        columns_descriptions=cols,
        question=utterance,
        hint=hint,
        schema_links=schema_links,
    )
    cls_out = _llm(cls, usage, as_json=False)
    extract_lab = helpers.get("extract_label_and_sub_questions")
    if extract_lab:
        label, sub_qs = extract_lab(cls_out)
        label = (label or _extract_label(cls_out)).upper()
        sub_q = sub_qs[0] if sub_qs else utterance
    else:
        label = _extract_label(cls_out)
        sub_q = utterance

    if label == "EASY":
        gen = tpl["SYSTEM_EASY_CLASS_TEMPLATE"] + "\n" + tpl["HUMAN_EASY_CLASS_TEMPLATE"].format(
            schema=schema,
            columns_descriptions=cols,
            question=utterance,
            hint=hint,
            schema_links=schema_links,
        )
    elif label == "NON-NESTED":
        gen = (
            tpl["SYSTEM_NON_NESTED_CLASS_TEMPLATE"]
            + "\n"
            + tpl["HUMAN_NON_NESTED_CLASS_TEMPLATE"].format(
                schema=schema,
                columns_descriptions=cols,
                question=utterance,
                hint=hint,
                schema_links=schema_links,
            )
        )
    else:
        label = "NESTED"
        human_n = tpl["HUMAN_NESTED_CLASS_TEMPLATE"]
        gen = tpl["SYSTEM_NESTED_CLASS_TEMPLATE"] + "\n" + human_n.format(
            schema=schema,
            columns_descriptions=cols,
            question=utterance,
            hint=hint,
            schema_links=schema_links,
            sub_questions=json.dumps([sub_q]),
        )

    gen_out = _llm(_retarget_sql_surface(gen), usage, as_json=True)
    draft = extract_json(gen_out)

    if draft is None or "unsupported" in (draft.get("missing") or []):
        if draft and "unsupported" in (draft.get("missing") or []):
            return _pack(
                "UNSUPPORTED_QUERY",
                usage,
                path,
                "bird",
                label,
                draft=draft,
                error=draft.get("error") or "unsupported",
            )
        corr = (
            tpl["SYSTEM_SELF_CORRECTION_PROMPT"]
            + "\n"
            + tpl["HUMAN_SELF_CORRECTION_PROMPT"].format(
                schema=schema,
                columns_descriptions=cols,
                question=utterance,
                hint=hint,
                sql_query=gen_out or "",
            )
        )
        draft = extract_json(_llm(_retarget_sql_surface(corr), usage, as_json=True))

    return _finish(draft, usage, path, "bird", label)


def _finish(draft, usage, path, mode, label):
    if draft is None:
        return _pack("INVALID_IR", usage, path, mode, label, error="no DraftIR after DIN pipeline")
    if "unsupported" in (draft.get("missing") or []):
        return _pack(
            "UNSUPPORTED_QUERY",
            usage,
            path,
            mode,
            label,
            draft=draft,
            error=draft.get("error") or "unsupported",
        )
    return _pack("OK", usage, path, mode, label, draft=draft)


def _pack(status, usage, path, mode, label, draft=None, error=None):
    out = {
        "status": status,
        "usage": usage,
        "complexity": label,
        "provenance": _prov(path, mode, label),
    }
    if draft is not None:
        out["draft"] = draft
    if error is not None:
        out["error"] = error
    return out


def _prov(path: str, mode: str, label: str) -> dict:
    if mode == "bird":
        api = [
            "SYSTEM_/HUMAN_* templates from DIN-SQL_BIRD.py",
            "extract_schema_links / extract_label_and_sub_questions",
            "schema_linking→classification→easy|non-nested|nested→self_correction",
        ]
    else:
        api = [
            "schema_linking_prompt_maker",
            "classification_prompt_maker",
            "easy|medium|hard_prompt_maker",
            "debuger→DraftIR_repair",
        ]
    return {
        "method": "din-sql",
        "mode": mode,
        "upstream_file": os.path.relpath(path, ROOT).replace("\\", "/"),
        "upstream_api": api,
        "intentional_changes": [
            "schema fields → KART logical catalog (not Spider/BIRD tables)",
            "SQL surface → DraftIR JSON",
            "no Spider/BIRD SQL execution; no LangChain ChatOpenAI from upstream",
            "fair LLM: temperature=0 via experiments/adapters/llm_client.py",
        ],
        "classification_label": label,
    }


def run_din(utterance: str, mode: str, knowledge: str) -> dict:
    if mode == "bird":
        return run_din_bird(utterance, knowledge)
    return run_din_spider(utterance, knowledge)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["spider", "bird"], default="spider")
    ap.add_argument("--utterance", required=True)
    ap.add_argument("--knowledge", default="")
    args = ap.parse_args()
    knowledge = ""
    if args.mode == "bird":
        if args.knowledge and os.path.isfile(args.knowledge):
            knowledge = open(args.knowledge, "r", encoding="utf-8").read()
        else:
            knowledge = LOGICAL_CATALOG
    try:
        out = run_din(args.utterance, args.mode, knowledge)
    except Exception as e:
        out = {
            "status": "FAILED",
            "error": str(e),
            "usage": {"calls": 0},
            "provenance": {"method": "din-sql", "error": "load_failed", "detail": str(e)},
        }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
