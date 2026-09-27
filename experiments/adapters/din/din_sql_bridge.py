#!/usr/bin/env python3
"""
Deep DIN transplant: upstream schema-link / classify / generate, then Kart
logical SQL → DraftIR.

Repair (max 2) calls the upstream self-correction prompt, then the deterministic
translator:
  spider — DIN-SQL.py ``debuger(question, db, sql)``
  bird   — SYSTEM_SELF_CORRECTION_PROMPT + HUMAN_SELF_CORRECTION_PROMPT

A short dialect constraint is appended so the corrected text stays
kart_logical_sql/1.0. That suffix is an intentional transplant change: there is
still no Spider/BIRD SQL executor.

Arm ids (do not overwrite din-spider / din-bird DraftIR-direct diagnostics):
  din-sql-spider, din-sql-bird
Label in provenance: din-sql-kart-transplant.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters"))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters", "din"))
sys.path.insert(0, os.path.join(ROOT, "experiments", "adapters", "translate"))

from llm_client import LlmHttpError, LOGICAL_CATALOG, chat, merge_usage  # noqa: E402
from sql_to_draft_ir import (  # noqa: E402
    LOGICAL_SQL_HINT,
    TranslateError,
    extract_sql,
    sql_to_draft_ir,
)

# Reuse upstream loaders from din_bridge without circular main
import din_bridge as din  # noqa: E402


def _llm_text(prompt: str, usage: dict) -> str:
    content, u = chat(
        [{"role": "user", "content": prompt}], temperature=0.0, json_mode=False
    )
    merge_usage(usage, u)
    return content or ""


def _sql_prompt_suffix() -> str:
    return (
        "\n\nIMPORTANT: Output ONLY logical SQL (dialect kart_logical_sql/1.0). "
        "Do NOT output DraftIR JSON.\n"
        + LOGICAL_SQL_HINT
        + "\n"
        + LOGICAL_CATALOG
    )


def _dialect_constraint() -> str:
    """Intentional suffix: keep repaired text in the KART logical SQL dialect."""
    return (
        "\n\nCONSTRAINT (KART transplant; not a Spider/BIRD executor): "
        "reply with ONLY logical SQL in dialect kart_logical_sql/1.0. "
        "Do NOT output DraftIR JSON.\n"
        + LOGICAL_SQL_HINT
    )


def spider_repair_prompt(debuger, utterance: str, db: str, sql: str, error: str) -> str:
    """Build a repair prompt from upstream DIN-SQL.py debuger(). Empty prompts fail closed."""
    if debuger is None:
        raise RuntimeError("upstream DIN-SQL.py debuger is not loaded")
    base = debuger(utterance, db, sql or "")
    if base is None or not str(base).strip():
        raise RuntimeError("upstream debuger returned an empty prompt")
    return (
        str(base)
        + _dialect_constraint()
        + "\nTranslator/SQL error: "
        + (error or "")
    )


def bird_repair_prompt(system_prompt: str, human_template: str, fields: dict, error: str) -> str:
    """Build a repair prompt from upstream BIRD self-correction templates."""
    if not system_prompt or not str(system_prompt).strip():
        raise RuntimeError("upstream SYSTEM_SELF_CORRECTION_PROMPT is empty")
    if not human_template or not str(human_template).strip():
        raise RuntimeError("upstream HUMAN_SELF_CORRECTION_PROMPT is empty")
    try:
        human = human_template.format(**fields)
    except (KeyError, IndexError, ValueError) as e:
        raise RuntimeError("upstream HUMAN_SELF_CORRECTION_PROMPT format failed: " + str(e))
    if not str(human).strip():
        raise RuntimeError("upstream HUMAN_SELF_CORRECTION_PROMPT rendered empty")
    return (
        str(system_prompt)
        + "\n"
        + str(human)
        + _dialect_constraint()
        + "\nTranslator/SQL error: "
        + (error or "")
    )


def _repair_prompt(mode: str, repair_ctx: dict, utterance: str, sql: str, error: str) -> str:
    if mode == "spider":
        return spider_repair_prompt(
            repair_ctx.get("debuger"),
            utterance,
            repair_ctx.get("db") or "kart_tdrive",
            sql or "",
            error,
        )
    fields = dict(repair_ctx.get("fields") or {})
    fields["sql_query"] = sql or ""
    fields.setdefault("question", utterance)
    return bird_repair_prompt(
        repair_ctx.get("system_prompt") or "",
        repair_ctx.get("human_template") or "",
        fields,
        error,
    )


def _translate_with_repair(
    sql_text: str,
    usage: dict,
    utterance: str,
    mode: str,
    repair_ctx: dict,
    max_repair: int = 2,
):
    attempts = []
    cur = sql_text
    for round_i in range(max_repair + 1):
        sql = extract_sql(cur)
        entry = {
            "round": round_i,
            "raw": (cur or "")[:2000],
            "sql": sql,
            "repair": "upstream_self_correction" if round_i else "generate",
        }
        if sql is None:
            entry["error"] = "no SQL extracted"
            attempts.append(entry)
            if round_i >= max_repair:
                break
            cur = _llm_text(
                _repair_prompt(mode, repair_ctx, utterance, cur or "", entry["error"]),
                usage,
            )
            continue
        try:
            draft, prov = sql_to_draft_ir(sql)
            entry["ok"] = True
            attempts.append(entry)
            return draft, prov, attempts
        except TranslateError as e:
            entry["error"] = e.code + ": " + e.message
            attempts.append(entry)
            if round_i >= max_repair:
                break
            cur = _llm_text(
                _repair_prompt(mode, repair_ctx, utterance, sql, entry["error"]),
                usage,
            )
    return None, None, attempts


def run_din_sql_spider(utterance: str, knowledge: str) -> dict:
    path = os.path.join(ROOT, "experiments", "third_party", "din-sql", "DIN-SQL.py")
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    mod = din._load_spider_module(path)
    din._patch_schema(mod, knowledge)
    db = "kart_tdrive"

    sl_out = _llm_text(mod.schema_linking_prompt_maker(utterance, db), usage)
    schema_links = din._extract_schema_links(sl_out)
    cls_out = _llm_text(mod.classification_prompt_maker(utterance, db, schema_links), usage)
    label = din._extract_label(cls_out)

    if label == "EASY":
        gen_prompt = mod.easy_prompt_maker(utterance, db, schema_links)
    elif label == "NON-NESTED":
        gen_prompt = mod.medium_prompt_maker(utterance, db, schema_links)
    else:
        sub_q = utterance
        import re

        m = re.search(r'questions\s*=\s*\["([^"]*)"\]', cls_out)
        if m:
            sub_q = m.group(1)
        gen_prompt = mod.hard_prompt_maker(utterance, db, schema_links, sub_q)

    # Keep SQL surface (do NOT retarget to DraftIR)
    gen_out = _llm_text(gen_prompt + _sql_prompt_suffix(), usage)
    if not hasattr(mod, "debuger"):
        raise RuntimeError("upstream DIN-SQL.py debuger is not loaded")
    draft, prov, attempts = _translate_with_repair(
        gen_out,
        usage,
        utterance,
        "spider",
        {"debuger": mod.debuger, "db": db},
    )
    return _pack(draft, prov, attempts, usage, path, "spider", label)


def run_din_sql_bird(utterance: str, knowledge: str) -> dict:
    path = os.path.join(ROOT, "experiments", "third_party", "din-sql", "DIN-SQL_BIRD.py")
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    usage = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    tpl = din._extract_bird_templates(path)
    helpers = tpl["_helpers"]
    schema = din._kart_schema_block(knowledge)
    cols = "Column descriptions: see KART logical catalog above."
    hint = knowledge.strip() or "Use OBSERVED_POINT; registered regions only."

    sl = tpl["SYSTEM_SCHEMA_LINKING_TEMPLATE"] + "\n" + tpl["HUMAN_SCHEMA_LINKING_TEMPLATE"].format(
        schema=schema, columns_descriptions=cols, question=utterance, hint=hint
    )
    sl_out = _llm_text(sl, usage)
    extract_sl = helpers.get("extract_schema_links")
    schema_links = str(extract_sl(sl_out) or []) if extract_sl else din._extract_schema_links(sl_out)

    cls = tpl["SYSTEM_CLASSIFICATION_TEMPLATE"] + "\n" + tpl["HUMAN_CLASSIFICATION_TEMPLATE"].format(
        schema=schema,
        columns_descriptions=cols,
        question=utterance,
        hint=hint,
        schema_links=schema_links,
    )
    cls_out = _llm_text(cls, usage)
    extract_lab = helpers.get("extract_label_and_sub_questions")
    if extract_lab:
        label, sub_qs = extract_lab(cls_out)
        label = (label or din._extract_label(cls_out)).upper()
        sub_q = sub_qs[0] if sub_qs else utterance
    else:
        label = din._extract_label(cls_out)
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
        gen = tpl["SYSTEM_NESTED_CLASS_TEMPLATE"] + "\n" + tpl["HUMAN_NESTED_CLASS_TEMPLATE"].format(
            schema=schema,
            columns_descriptions=cols,
            question=utterance,
            hint=hint,
            schema_links=schema_links,
            sub_questions=json.dumps([sub_q]),
        )

    gen_out = _llm_text(gen + _sql_prompt_suffix(), usage)
    draft, prov, attempts = _translate_with_repair(
        gen_out,
        usage,
        utterance,
        "bird",
        {
            "system_prompt": tpl["SYSTEM_SELF_CORRECTION_PROMPT"],
            "human_template": tpl["HUMAN_SELF_CORRECTION_PROMPT"],
            "fields": {
                "schema": schema,
                "columns_descriptions": cols,
                "question": utterance,
                "hint": hint,
                "sql_query": "",
            },
        },
    )
    return _pack(draft, prov, attempts, usage, path, "bird", label)


def _pack(draft, prov, attempts, usage, path, mode, label):
    provenance = {
        "method": "din-sql-kart-transplant",
        "mode": mode,
        "self_correction": (
            "upstream_debuger_then_kart_translate"
            if mode == "spider"
            else "upstream_bird_self_correction_then_kart_translate"
        ),
        "upstream_file": os.path.relpath(path, ROOT).replace("\\", "/"),
        "intermediate": "logical_sql",
        "translator": "sql_to_draft_ir",
        "dialect": "kart_logical_sql/1.0",
        "classification_label": label,
        "translate_attempts": attempts,
        "intentional_changes": [
            "generation target = logical SQL subset (not DraftIR direct)",
            "deterministic SQL AST → DraftIR (no LLM in translator)",
            "no silent DraftIR decode fallback on translate failure",
            "repair prompt is upstream Spider debuger or BIRD "
            "SYSTEM_/HUMAN_SELF_CORRECTION_PROMPT (max 2)",
            "dialect constraint appended so repaired text stays kart_logical_sql/1.0; "
            "no Spider/BIRD SQL executor",
        ],
    }
    if draft is None:
        err = "untranslatable_sql"
        if attempts:
            err = attempts[-1].get("error") or err
        return {
            "status": "UNSUPPORTED_QUERY",
            "error": err,
            "usage": usage,
            "complexity": label,
            "provenance": provenance,
            "untranslatable_sql": True,
        }
    if prov:
        provenance["sql"] = prov.get("sql")
    return {
        "status": "OK",
        "draft": draft,
        "usage": usage,
        "complexity": label,
        "provenance": provenance,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["spider", "bird"], required=True)
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
        if args.mode == "bird":
            out = run_din_sql_bird(args.utterance, knowledge)
        else:
            out = run_din_sql_spider(args.utterance, knowledge)
    except LlmHttpError as e:
        out = {
            "status": "INFRASTRUCTURE_FAILURE",
            "error": str(e),
            "usage": e.usage,
            "provenance": {"method": "din-sql-deep", "http_status": e.status},
        }
    except Exception as e:
        out = {
            "status": "FAILED",
            "error": str(e),
            "usage": {"calls": 0},
            "provenance": {"method": "din-sql-deep", "error": "failed"},
        }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
