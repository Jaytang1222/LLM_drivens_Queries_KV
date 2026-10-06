#!/usr/bin/env python3
"""
Deterministic logical-SQL → DraftIR translator (no LLM, no gold).

Dialect (versioned): kart_logical_sql/1.0
  SELECT DISTINCT trajectory_id
  FROM trajectory_point
  WHERE <point predicates>
  [ORDER BY KART_SIM(DTW|FRECHET|HAUSDORFF, '<ref_trajectory_id>') ASC LIMIT <k>]

Point predicates (AND only), all apply to the same observed point row:
  event_time >= 'ISO' / event_time < 'ISO'   (half-open [start,end))
  vehicle_id = '...'
  region_name = 'registered'
  lon BETWEEN lo AND hi AND lat BETWEEN lo AND hi
  lon >= / <=  and lat >= / <=  (rectangle)
"""
from __future__ import annotations

import re
from typing import Any, Optional, Tuple

DIALECT = "kart_logical_sql/1.0"

REGISTERED_REGIONS = {
    "tdrive_smoke_anchor",
    "tdrive_topk_box",
    "tdrive_topk_wide",
    "tdrive_topk_s1",
    "tdrive_topk_mid",
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

# Word-bounded: the token "path" must not match table name trajectory_point.
_UNTRANSLATABLE = (
    (r"\bcount\s*\(", "count"),
    (r"\bavg\s*\(", "avg"),
    (r"\bsum\s*\(", "sum"),
    (r"\bgroup\s+by\b", "group by"),
    (r"\bjoin\b", "join"),
    (r"\bunion\b", "union"),
    (r"\bcontinuous\s+path\b", "continuous path"),
    (r"\bedit_distance\b", "edit_distance"),
    (r"\browkey\b", "rowkey"),
    (r"\binsert\b", "insert"),
    (r"\bupdate\b", "update"),
    (r"\bdelete\b", "delete"),
)


class TranslateError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def extract_sql(text: str) -> Optional[str]:
    if not text:
        return None
    t = text.strip()
    if t.startswith("```"):
        lines = [ln for ln in t.split("\n") if not ln.strip().startswith("```")]
        t = "\n".join(lines).strip()
    # Prefer SELECT ... up to the first terminator, ignoring trailing prose.
    m = re.search(r"(?is)\bSELECT\b.+?(?=;|$)", t)
    if m:
        return m.group(0).strip().rstrip(";")
    if "FROM" in t.upper():
        return t.split(";")[0].rstrip()
    return None


def sql_to_draft_ir(sql: str) -> Tuple[dict, dict]:
    """
    Returns (draft_ir, provenance).
    Raises TranslateError with code untranslatable_sql / invalid_sql.
    """
    if not sql or not str(sql).strip():
        raise TranslateError("invalid_sql", "empty SQL")
    extracted = extract_sql(str(sql).strip())
    raw = (extracted or str(sql).strip()).rstrip(";")
    for pat, name in _UNTRANSLATABLE:
        if re.search(pat, raw, re.I):
            raise TranslateError("untranslatable_sql", "forbidden construct: " + name)

    # DISTINCT is optional; trajectory is a catalog synonym for trajectory_point.
    if not re.match(
        r"(?is)^\s*select\s+(?:distinct\s+)?trajectory_id\s+from\s+"
        r"(?:trajectory_point|traj_point|trajectory)(?:\s+(?:as\s+)?[a-z_][\w]*)?\b",
        raw,
    ):
        raise TranslateError(
            "untranslatable_sql",
            "must be SELECT [DISTINCT] trajectory_id FROM trajectory_point "
            "(no extra projection columns)",
        )

    # Split ORDER BY / LIMIT from WHERE body
    order_m = re.search(r"(?is)\border\s+by\b(.+)$", raw)
    where_m = re.search(r"(?is)\bwhere\b(.+?)(?:\border\s+by\b|$)", raw)
    where_body = where_m.group(1).strip() if where_m else ""
    order_body = order_m.group(1).strip() if order_m else ""

    temporal: dict = {}
    spatial: Optional[dict] = None
    predicates: list = []
    similarity: Optional[dict] = None
    result = {"mode": "TRAJECTORY_IDS"}

    # Parse AND-separated clauses (simple; no nested OR)
    if where_body:
        where_body = re.sub(r"[\[\]]", " ", where_body)
        parts = _split_and(where_body)
        lon_lo = lon_hi = lat_lo = lat_hi = None
        region = None
        for p in parts:
            p = p.strip().rstrip(";").strip()
            if not p:
                continue
            if "..." in p or "<registered>" in p or "<ref" in p:
                continue
            m = re.match(
                r"(?is)^(?:event_time|t_start)\s*>=\s*'([^']+)'\s*$", p
            ) or re.match(r'(?is)^(?:event_time|t_start)\s*>=\s*"([^"]+)"\s*$', p)
            if m:
                if "start" in temporal:
                    raise TranslateError("untranslatable_sql", "duplicate event_time >=")
                temporal["start"] = m.group(1)
                continue
            m = re.match(
                r"(?is)^(?:event_time|t_end)\s*(?:<|<=)\s*'([^']+)'\s*$", p
            ) or re.match(r'(?is)^(?:event_time|t_end)\s*(?:<|<=)\s*"([^"]+)"\s*$', p)
            if m:
                if "end" in temporal:
                    raise TranslateError("untranslatable_sql", "duplicate event_time <")
                temporal["end"] = m.group(1)
                continue
            m = re.match(
                r"(?is)^(?:event_time|t_start)\s+between\s+'([^']+)'\s+and\s+'([^']+)'\s*$", p
            ) or re.match(
                r'(?is)^(?:event_time|t_start)\s+between\s+"([^"]+)"\s+and\s+"([^"]+)"\s*$', p
            )
            if m:
                if "start" in temporal or "end" in temporal:
                    raise TranslateError("untranslatable_sql", "duplicate event_time")
                temporal["start"], temporal["end"] = m.group(1), m.group(2)
                continue
            m = re.match(
                r"(?is)^vehicle_id\s*=\s*'([^']+)'\s*$", p
            ) or re.match(r'(?is)^vehicle_id\s*=\s*"([^"]+)"\s*$', p)
            if m:
                if predicates:
                    raise TranslateError("untranslatable_sql", "duplicate vehicle_id")
                predicates.append({"field": "vehicle_id", "op": "EQ", "value": m.group(1)})
                continue
            m = re.match(
                r"(?is)^region_name\s*=\s*'([^']+)'\s*$", p
            ) or re.match(r'(?is)^region_name\s*=\s*"([^"]+)"\s*$', p)
            if m:
                region = m.group(1)
                if region not in REGISTERED_REGIONS:
                    raise TranslateError(
                        "untranslatable_sql", "unknown region_name=" + region
                    )
                continue
            m = re.match(
                r"(?is)^lon\s+between\s+(-?\d+(?:\.\d+)?)\s+and\s+(-?\d+(?:\.\d+)?)\s*$",
                p,
            )
            if m:
                lon_lo, lon_hi = float(m.group(1)), float(m.group(2))
                continue
            m = re.match(
                r"(?is)^lat\s+between\s+(-?\d+(?:\.\d+)?)\s+and\s+(-?\d+(?:\.\d+)?)\s*$",
                p,
            )
            if m:
                lat_lo, lat_hi = float(m.group(1)), float(m.group(2))
                continue
            m = re.match(r"(?is)^lon\s*>=\s*(-?\d+(?:\.\d+)?)\s*$", p)
            if m:
                lon_lo = float(m.group(1))
                continue
            m = re.match(r"(?is)^lon\s*<=\s*(-?\d+(?:\.\d+)?)\s*$", p)
            if m:
                lon_hi = float(m.group(1))
                continue
            m = re.match(r"(?is)^lat\s*>=\s*(-?\d+(?:\.\d+)?)\s*$", p)
            if m:
                lat_lo = float(m.group(1))
                continue
            m = re.match(r"(?is)^lat\s*<=\s*(-?\d+(?:\.\d+)?)\s*$", p)
            if m:
                lat_hi = float(m.group(1))
                continue
            raise TranslateError("untranslatable_sql", "unsupported predicate: " + p)

        has_rect_piece = any(v is not None for v in (lon_lo, lon_hi, lat_lo, lat_hi))
        if region and has_rect_piece:
            raise TranslateError(
                "untranslatable_sql",
                "region_name and lon/lat rectangle both set",
            )
        if region:
            spatial = {
                "region_name": region,
                "relation": "INTERSECTS",
                "boundary": "INCLUDED",
            }
        elif None not in (lon_lo, lon_hi, lat_lo, lat_hi):
            spatial = {
                "geometry": {
                    "type": "RECTANGLE",
                    "min_lon": lon_lo,
                    "min_lat": lat_lo,
                    "max_lon": lon_hi,
                    "max_lat": lat_hi,
                },
                "relation": "INTERSECTS",
                "boundary": "INCLUDED",
            }
        elif has_rect_piece:
            raise TranslateError(
                "untranslatable_sql", "incomplete lon/lat rectangle predicates"
            )

    if temporal and ("start" not in temporal or "end" not in temporal):
        raise TranslateError(
            "untranslatable_sql", "temporal requires both event_time >= and <"
        )
    if temporal:
        temporal["boundary"] = "[start,end)"

    if order_body:
        sim_m = re.match(
            r"(?is)^KART_SIM\s*\(\s*(DTW|FRECHET|HAUSDORFF)\s*,\s*'([^']+)'\s*\)\s*ASC"
            r"\s+LIMIT\s+(\d+)\s*$",
            order_body,
        ) or re.match(
            r'(?is)^KART_SIM\s*\(\s*(DTW|FRECHET|HAUSDORFF)\s*,\s*"([^"]+)"\s*\)\s*ASC'
            r"\s+LIMIT\s+(\d+)\s*$",
            order_body,
        )
        if not sim_m:
            raise TranslateError(
                "untranslatable_sql",
                "ORDER BY must be KART_SIM(metric, 'ref') ASC [LIMIT k]",
            )
        metric, ref, k_s = sim_m.group(1).upper(), sim_m.group(2), sim_m.group(3)
        if not k_s:
            raise TranslateError("untranslatable_sql", "TOP_K requires LIMIT k")
        similarity = {
            "metric": metric,
            "reference_trajectory_id": ref,
            "exclude_reference": True,
        }
        result = {"mode": "TOP_K", "k": int(k_s)}

    if not temporal and spatial is None and not predicates and similarity is None:
        raise TranslateError(
            "untranslatable_sql", "query has no temporal/spatial/predicate/similarity"
        )

    draft: dict[str, Any] = {
        "ir_version": "1.0",
        "source": {"dataset_id": "tdrive_v1", "entity": "trajectory"},
        "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
        "result": result,
    }
    if temporal:
        draft["temporal"] = temporal
    if spatial is not None:
        draft["spatial"] = spatial
    if predicates:
        draft["predicates"] = predicates
    if similarity is not None:
        draft["similarity"] = similarity

    prov = {
        "translator": "sql_to_draft_ir",
        "dialect": DIALECT,
        "sql": raw,
    }
    return draft, prov


def _split_and(where_body: str) -> list:
    protected = re.sub(
        r"(?is)\bbetween\s+('[^']+'|\"[^\"]+\"|-?\d+(?:\.\d+)?)\s+and\s+"
        r"('[^']+'|\"[^\"]+\"|-?\d+(?:\.\d+)?)",
        lambda m: "BETWEEN " + m.group(1) + " \x00AND\x00 " + m.group(2),
        where_body,
    )
    parts = []
    buf = []
    i = 0
    in_q = None
    while i < len(protected):
        c = protected[i]
        if in_q:
            buf.append(c)
            if c == in_q:
                in_q = None
            i += 1
            continue
        if c in ("'", '"'):
            in_q = c
            buf.append(c)
            i += 1
            continue
        if protected[i : i + 5].upper() == " AND ":
            parts.append("".join(buf).strip())
            buf = []
            i += 5
            continue
        buf.append(c)
        i += 1
    if buf:
        parts.append("".join(buf).strip())
    out = []
    for p in parts:
        if p:
            out.append(p.replace(" \x00AND\x00 ", " AND "))
    return out


LOGICAL_SQL_HINT = """
Emit ONLY logical SQL in dialect kart_logical_sql/1.0 (NOT SQLite, NOT DraftIR JSON):
SELECT DISTINCT trajectory_id
FROM trajectory_point
WHERE event_time >= 'ISO-8601 Asia/Shanghai' AND event_time < 'ISO-8601'
  [AND vehicle_id = '...']
  [AND region_name = '<registered>']
  [AND lon BETWEEN ... AND ... AND lat BETWEEN ... AND ...]
[ORDER BY KART_SIM(DTW|FRECHET|HAUSDORFF, '<ref_trajectory_id>') ASC LIMIT <k>];
UNSUPPORTED: COUNT/aggregation, JOIN, GROUP BY, continuous path, EDIT_DISTANCE, unknown places.
Registered regions: """ + ", ".join(sorted(REGISTERED_REGIONS)) + """
"""
