#!/usr/bin/env python3
"""Unit tests for SQL/MQL → DraftIR translators (no LLM)."""
from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from sql_to_draft_ir import TranslateError, sql_to_draft_ir  # noqa: E402
from mql_to_draft_ir import mql_to_draft_ir  # noqa: E402
from mql_to_draft_ir import TranslateError as MqlErr  # noqa: E402


class SqlTranslateTest(unittest.TestCase):
    def test_temporal_vehicle(self):
        sql = """
        SELECT DISTINCT trajectory_id FROM trajectory_point
        WHERE event_time >= '2008-02-03T08:00:00+08:00'
          AND event_time < '2008-02-03T09:00:00+08:00'
          AND vehicle_id = '8857'
        """
        draft, prov = sql_to_draft_ir(sql)
        self.assertEqual(draft["ir_version"], "1.0")
        self.assertEqual(draft["source"]["entity"], "trajectory")
        self.assertEqual(draft["temporal"]["start"], "2008-02-03T08:00:00+08:00")
        self.assertEqual(draft["predicates"][0]["value"], "8857")
        self.assertIn("sql", prov)

    def test_region(self):
        sql = """
        SELECT DISTINCT trajectory_id FROM trajectory_point
        WHERE region_name = 'beijing_core'
          AND event_time >= '2008-02-03T08:00:00+08:00'
          AND event_time < '2008-02-03T09:00:00+08:00'
        """
        draft, _ = sql_to_draft_ir(sql)
        self.assertEqual(draft["spatial"]["region_name"], "beijing_core")

    def test_topk(self):
        sql = """
        SELECT DISTINCT trajectory_id FROM trajectory_point
        WHERE event_time >= '2008-02-03T08:00:00+08:00'
          AND event_time < '2008-02-04T08:00:00+08:00'
        ORDER BY KART_SIM(DTW, '8857-8857_14') ASC LIMIT 5
        """
        draft, _ = sql_to_draft_ir(sql)
        self.assertEqual(draft["result"]["mode"], "TOP_K")
        self.assertEqual(draft["result"]["k"], 5)
        self.assertEqual(draft["similarity"]["metric"], "DTW")

    def test_rejects_count(self):
        with self.assertRaises(TranslateError) as cm:
            sql_to_draft_ir("SELECT COUNT(*) FROM trajectory_point")
        self.assertEqual(cm.exception.code, "untranslatable_sql")


class MqlTranslateTest(unittest.TestCase):
    def test_basic(self):
        pipe = [
            {
                "$match": {
                    "event_time": {
                        "$gte": "2008-02-03T08:00:00+08:00",
                        "$lt": "2008-02-03T09:00:00+08:00",
                    },
                    "vehicle_id": "8857",
                }
            },
            {"$group": {"_id": "$trajectory_id"}},
        ]
        draft, prov = mql_to_draft_ir(pipe)
        self.assertEqual(draft["predicates"][0]["value"], "8857")
        self.assertEqual(prov["dialect"], "kart_logical_mql/1.0")

    def test_rejects_limit(self):
        pipe = [
            {"$match": {"vehicle_id": "1"}},
            {"$group": {"_id": "$trajectory_id"}},
            {"$limit": 10},
        ]
        with self.assertRaises(MqlErr):
            mql_to_draft_ir(pipe)

    def test_rejects_group_before_match(self):
        """$group then $match must not be rewritten as filter-then-group."""
        pipe = [
            {"$group": {"_id": "$trajectory_id"}},
            {
                "$match": {
                    "event_time": {
                        "$gte": "2008-02-03T08:00:00+08:00",
                        "$lt": "2008-02-03T09:00:00+08:00",
                    }
                }
            },
        ]
        with self.assertRaises(MqlErr) as cm:
            mql_to_draft_ir(pipe)
        self.assertIn("stage order", cm.exception.message)

    def test_rejects_extra_group_fields(self):
        pipe = [
            {"$match": {"vehicle_id": "1"}},
            {"$group": {"_id": "$trajectory_id", "n": {"$sum": 1}}},
        ]
        with self.assertRaises(MqlErr) as cm:
            mql_to_draft_ir(pipe)
        self.assertIn("$group may only contain _id", cm.exception.message)

    def test_rejects_duplicate_match(self):
        pipe = [
            {"$match": {"vehicle_id": "1"}},
            {"$match": {"vehicle_id": "2"}},
            {"$group": {"_id": "$trajectory_id"}},
        ]
        with self.assertRaises(MqlErr):
            mql_to_draft_ir(pipe)

    def test_topk_must_be_last(self):
        pipe = [
            {
                "$match": {
                    "event_time": {
                        "$gte": "2008-02-03T08:00:00+08:00",
                        "$lt": "2008-02-03T09:00:00+08:00",
                    }
                }
            },
            {"$kart_topk": {"metric": "DTW", "reference_trajectory_id": "t1", "k": 5}},
            {"$group": {"_id": "$trajectory_id"}},
        ]
        with self.assertRaises(MqlErr) as cm:
            mql_to_draft_ir(pipe)
        self.assertIn("stage order", cm.exception.message)

    def test_legal_topk_position(self):
        pipe = [
            {
                "$match": {
                    "event_time": {
                        "$gte": "2008-02-03T08:00:00+08:00",
                        "$lt": "2008-02-03T09:00:00+08:00",
                    }
                }
            },
            {"$group": {"_id": "$trajectory_id"}},
            {"$kart_topk": {"metric": "DTW", "reference_trajectory_id": "t1", "k": 5}},
        ]
        draft, prov = mql_to_draft_ir(pipe)
        self.assertEqual(draft["result"]["mode"], "TOP_K")
        self.assertTrue(prov["kart_topk_extension"])

    def test_fail_closed_semantics(self):
        base_where = (
            "event_time >= '2008-02-03T08:00:00+08:00' "
            "AND event_time < '2008-02-03T09:00:00+08:00'"
        )
        cases = [
            "SELECT DISTINCT trajectory_id, vehicle_id FROM trajectory_point WHERE "
            + base_where,
            "SELECT DISTINCT trajectory_id FROM trajectory_point WHERE "
            + base_where
            + " AND region_name = 'beijing_core' AND lon BETWEEN 116 AND 117 "
            + "AND lat BETWEEN 39 AND 40",
            "SELECT DISTINCT trajectory_id FROM trajectory_point WHERE "
            "event_time >= '2008-02-03T08:00:00+08:00' "
            "AND event_time >= '2008-02-03T08:30:00+08:00' "
            "AND event_time < '2008-02-03T09:00:00+08:00'",
            "SELECT DISTINCT trajectory_id FROM trajectory_point WHERE "
            + base_where
            + " ORDER BY KART_SIM(DTW, 't1') ASC LIMIT 5 trailing",
        ]
        for sql in cases:
            with self.assertRaises(TranslateError):
                sql_to_draft_ir(sql)


class MqlFailClosedExtraTest(unittest.TestCase):
    def _pipe(self, match, k):
        return [
            {"$match": match},
            {"$group": {"_id": "$trajectory_id"}},
            {"$kart_topk": {"metric": "DTW", "reference_trajectory_id": "t1", "k": k}},
        ]

    def test_unknown_range_op_and_bad_k(self):
        match = {
            "lon": {"$gte": 1, "$lte": 2, "$gt": 0},
            "lat": {"$gte": 3, "$lte": 4},
        }
        with self.assertRaises(MqlErr):
            mql_to_draft_ir(self._pipe(match, 5))
        ok_match = {
            "event_time": {
                "$gte": "2008-02-03T08:00:00+08:00",
                "$lt": "2008-02-03T09:00:00+08:00",
            }
        }
        for bad_k in (1.5, 0, -1, True):
            with self.assertRaises(MqlErr):
                mql_to_draft_ir(self._pipe(ok_match, bad_k))


if __name__ == "__main__":
    unittest.main()