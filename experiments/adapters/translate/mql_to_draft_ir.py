#!/usr/bin/env python3
"""
Deterministic logical-MQL → DraftIR translator (no LLM, no gold).

Dialect: kart_logical_mql/1.0
Pipeline whitelist:
  [
    {"$match": { <point fields> }},
    {"$group": {"_id": "$trajectory_id"}},
    optional Top-K Kart extension stages documented below
  ]

$match fields (same-point predicates):
  event_time: {$gte: ISO, $lt: ISO}
  vehicle_id: "..."
  region_name: registered
  lon / lat: {$gte,$lte} or nested $and

Top-K (KART extension, reported separately from base MQL):
  {"$kart_topk": {"metric":"DTW","reference_trajectory_id":"...","k":5}}
"""
from __future__ import annotations

import json
from typing import Any, List, Optional, Tuple

DIALECT = "kart_logical_mql/1.0"

REGISTERED_REGIONS = {
    "tdrive_smoke_anchor",
    "tdrive_topk_box",
    "tdrive_topk_wide",
    "tdrive_topk_s1",
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


class TranslateError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def extract_mql(text: str) -> Optional[list]:
    if not text:
        return None
    t = text.strip()
    if t.startswith("```"):
        lines = [ln for ln in t.split("\n") if not ln.strip().startswith("```")]
        t = "\n".join(lines).strip()
    # find JSON array
    start = t.find("[")
    end = t.rfind("]")
    if start < 0 or end <= start:
        return None
    try:
        obj = json.loads(t[start : end + 1])
    except Exception:
        return None
    return obj if isinstance(obj, list) else None


def mql_to_draft_ir(pipeline: list) -> Tuple[dict, dict]:
    if not isinstance(pipeline, list) or not pipeline:
        raise TranslateError("invalid_mql", "pipeline must be a non-empty JSON array")

    # Exact stage order: $match → $group → optional $kart_topk.
    # Do NOT normalize out-of-order stages (e.g. $group then $match ≠ filter-then-group).
    forbidden = {"$limit", "$sort", "$project", "$lookup", "$unwind", "$out", "$merge"}
    for stage in pipeline:
        if not isinstance(stage, dict) or len(stage) != 1:
            raise TranslateError("untranslatable_mql", "each stage must be a single-key object")
        op = next(iter(stage.keys()))
        if op in forbidden:
            raise TranslateError(
                "untranslatable_mql",
                op + " not allowed (would change Oracle set/Top-K semantics)",
            )

    if len(pipeline) < 2 or len(pipeline) > 3:
        raise TranslateError(
            "untranslatable_mql",
            "pipeline must be [$match, $group] or [$match, $group, $kart_topk]",
        )
    ops = [next(iter(s.keys())) for s in pipeline]
    expected = ["$match", "$group"]
    if len(ops) == 3:
        expected = ["$match", "$group", "$kart_topk"]
    if ops != expected:
        raise TranslateError(
            "untranslatable_mql",
            "stage order must be "
            + " → ".join(expected)
            + "; got "
            + " → ".join(ops),
        )

    match = pipeline[0]["$match"]
    group = pipeline[1]["$group"]
    topk = pipeline[2]["$kart_topk"] if len(pipeline) == 3 else None

    if not isinstance(group, dict):
        raise TranslateError("untranslatable_mql", "$group must be object")
    if group.get("_id") not in ("$trajectory_id", "trajectory_id"):
        raise TranslateError("untranslatable_mql", "$group._id must be $trajectory_id")
    # Only trajectory-id grouping; any extra accumulator changes set semantics.
    extra_group_keys = [k for k in group.keys() if k != "_id"]
    if extra_group_keys:
        raise TranslateError(
            "untranslatable_mql",
            "$group may only contain _id=$trajectory_id; extra=" + ",".join(extra_group_keys),
        )
    temporal: dict = {}
    spatial: Optional[dict] = None
    predicates: List[dict] = []
    similarity: Optional[dict] = None
    result = {"mode": "TRAJECTORY_IDS", "k": None, "tie_breaker": "TID_ASC"}

    _apply_match(match, temporal, predicates)
    # spatial from match
    if "region_name" in match:
        rn = match["region_name"]
        if rn not in REGISTERED_REGIONS:
            raise TranslateError("untranslatable_mql", "unknown region_name=" + str(rn))
        spatial = {
            "region_name": rn,
            "relation": "INTERSECTS",
            "boundary": "INCLUDED",
        }
    lon = match.get("lon")
    lat = match.get("lat")
    if isinstance(lon, dict) or isinstance(lat, dict):
        if spatial is not None:
            raise TranslateError("untranslatable_mql", "region_name and lon/lat both set")
        for axis_name, axis in (("lon", lon), ("lat", lat)):
            if not isinstance(axis, dict):
                raise TranslateError(
                    "untranslatable_mql", axis_name + " range must be an object"
                )
            extra_ops = [k for k in axis.keys() if k not in ("$gte", "$lte")]
            if extra_ops:
                raise TranslateError(
                    "untranslatable_mql",
                    axis_name + " allows only $gte/$lte; extra=" + ",".join(extra_ops),
                )
        lo_lon = lon.get("$gte")
        hi_lon = lon.get("$lte")
        lo_lat = lat.get("$gte")
        hi_lat = lat.get("$lte")
        if None in (lo_lon, hi_lon, lo_lat, hi_lat):
            raise TranslateError("untranslatable_mql", "incomplete lon/lat range")
        spatial = {
            "geometry": {
                "type": "RECTANGLE",
                "min_lon": float(lo_lon),
                "min_lat": float(lo_lat),
                "max_lon": float(hi_lon),
                "max_lat": float(hi_lat),
            },
            "relation": "INTERSECTS",
            "boundary": "INCLUDED",
        }

    if temporal and ("start" not in temporal or "end" not in temporal):
        raise TranslateError("untranslatable_mql", "event_time needs $gte and $lt")
    if temporal:
        temporal["boundary"] = "[start,end)"

    if topk is not None:
        metric = str(topk.get("metric") or "").upper()
        ref = topk.get("reference_trajectory_id")
        k = topk.get("k")
        # bool is a subclass of int; reject it and any non-integer (no int() truncation).
        if (
            metric not in ("DTW", "FRECHET", "HAUSDORFF")
            or not ref
            or isinstance(k, bool)
            or not isinstance(k, int)
            or k <= 0
        ):
            raise TranslateError(
                "untranslatable_mql",
                "$kart_topk.k must be a positive JSON integer",
            )
        similarity = {
            "metric": metric,
            "reference_trajectory_id": str(ref),
            "exclude_reference": True,
        }
        result = {"mode": "TOP_K", "k": k, "tie_breaker": "TID_ASC"}

    if not temporal and spatial is None and not predicates and similarity is None:
        raise TranslateError("untranslatable_mql", "empty constraints")

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
        "translator": "mql_to_draft_ir",
        "dialect": DIALECT,
        "pipeline": pipeline,
        "kart_topk_extension": topk is not None,
    }
    return draft, prov


def _apply_match(match: dict, temporal: dict, predicates: list) -> None:
    if not isinstance(match, dict):
        raise TranslateError("invalid_mql", "$match must be object")
    if "event_time" in match:
        et = match["event_time"]
        if not isinstance(et, dict):
            raise TranslateError("untranslatable_mql", "event_time must be range object")
        if "$gte" in et:
            temporal["start"] = et["$gte"]
        if "$lt" in et:
            temporal["end"] = et["$lt"]
        for k in et:
            if k not in ("$gte", "$lt"):
                raise TranslateError("untranslatable_mql", "event_time op " + k)
    if "vehicle_id" in match:
        predicates.append(
            {"field": "vehicle_id", "op": "EQ", "value": str(match["vehicle_id"])}
        )
    allowed = {
        "event_time",
        "vehicle_id",
        "region_name",
        "lon",
        "lat",
        "$and",
    }
    for k in match:
        if k not in allowed:
            raise TranslateError("untranslatable_mql", "unsupported match field " + k)
    if "$and" in match:
        raise TranslateError("untranslatable_mql", "nested $and not supported; flatten fields")


LOGICAL_MQL_HINT = """
Emit ONLY a JSON array pipeline in dialect kart_logical_mql/1.0 (NOT DraftIR):
[
  {"$match": {
      "event_time": {"$gte": "ISO", "$lt": "ISO"},
      "vehicle_id": optional,
      "region_name": optional registered,
      "lon": {"$gte":..,"$lte":..}, "lat": {"$gte":..,"$lte":..}
  }},
  {"$group": {"_id": "$trajectory_id"}}
  , optional {"$kart_topk": {"metric":"DTW","reference_trajectory_id":"...","k":5}}
]
Forbidden: $limit/$sort/$project/$lookup. Registered regions: """ + ", ".join(
    sorted(REGISTERED_REGIONS)
) + """
"""
