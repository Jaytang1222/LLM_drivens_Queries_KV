#!/usr/bin/env python3
"""Unit tests for corrected opportunity census pairing (no HBase)."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from summarize_opportunity_census import pair_repeat_savings, summarize


def row(qid, phase, plan_id, t_exec, cbo_sel, in_safe, ok=True):
    return {
        "query_id": qid,
        "phase": phase,
        "plan_id": plan_id,
        "plan_signature": f"sig-{plan_id}",
        "t_exec_ms": t_exec,
        "cbo_selected": cbo_sel,
        "in_cbo_safe_set": in_safe,
        "status": "OK" if ok else "FAIL",
        "ok_oracle": ok,
    }


class PairingTest(unittest.TestCase):
    def test_stable_three_positive(self):
        meas = []
        for i, ph in enumerate(["repeat_t1", "repeat_t2", "repeat_t3"]):
            meas.append(row("q1", ph, "P_TZ", 300 + i, True, True))
            meas.append(row("q1", ph, "P_T", 100 + i, False, False))
        out = pair_repeat_savings(meas)
        self.assertTrue(out["q1"]["best_stable"]["stable_faster"])
        self.assertEqual(out["q1"]["best_stable"]["plan_id"], "P_T")
        self.assertIn("search_miss_with_exec_gain", out["q1"]["labels"])
        self.assertIn("candidate_fast_but_overhead_dominates", out["q1"]["labels"])

    def test_coarse_ignored(self):
        meas = [
            row("q1", "coarse", "P_TZ", 500, True, True),
            row("q1", "coarse", "P_T", 10, False, False),
        ]
        out = pair_repeat_savings(meas)
        self.assertEqual(out, {})

    def test_one_negative_not_stable(self):
        meas = []
        diffs = [(300, 100), (300, 100), (100, 200)]  # third negative
        for (c, a), ph in zip(diffs, ["repeat_t1", "repeat_t2", "repeat_t3"]):
            meas.append(row("q1", ph, "P_TZ", c, True, True))
            meas.append(row("q1", ph, "P_T", a, False, False))
        out = pair_repeat_savings(meas)
        self.assertIsNone(out["q1"]["best_stable"])
        self.assertIn("no_faster_safe_plan", out["q1"]["labels"])

    def test_same_plan_id_different_signatures_are_not_merged(self):
        meas = []
        for ph in ("repeat_t1", "repeat_t2", "repeat_t3"):
            meas.append(row("q1", ph, "P_TZ", 300, True, True))
            a = row("q1", ph, "P_T", 100, False, False)
            a["plan_signature"] = "variant-A" if ph != "repeat_t3" else "variant-B"
            meas.append(a)
        out = pair_repeat_savings(meas)
        self.assertIsNone(out["q1"]["best_stable"])
        self.assertEqual(sorted(c["n_paired"] for c in out["q1"]["candidates"]), [1, 2])

    def test_fullscan_skip_not_true_censor_in_summary(self):
        with tempfile.TemporaryDirectory() as td:
            run = Path(td)
            (run / "meta.json").write_text(json.dumps({"run_id": "t", "cache": "warm"}), encoding="utf-8")
            (run / "search.jsonl").write_text(
                json.dumps({"kind": "cbo_search", "query_id": "q1", "cbo_selected_plan_id": "P_TZ"}) + "\n",
                encoding="utf-8",
            )
            lines = [
                {
                    "query_id": "q1",
                    "plan_id": "P_FULL",
                    "status": "fullscan_unmeasured",
                    "censor_reason": "fullscan_not_selected_budget_skip",
                    "phase": "fullscan_skip",
                },
                row("q1", "repeat_t1", "P_TZ", 200, True, True),
                row("q1", "repeat_t1", "P_T", 150, False, False),
                row("q1", "repeat_t2", "P_TZ", 210, True, True),
                row("q1", "repeat_t2", "P_T", 140, False, False),
                row("q1", "repeat_t3", "P_TZ", 205, True, True),
                row("q1", "repeat_t3", "P_T", 145, False, False),
                {"query_id": "q2", "oracle_msg": "missing_oracle", "phase": "coarse", "plan_id": "P_TZ"},
            ]
            (run / "measurements.jsonl").write_text(
                "\n".join(json.dumps(x) for x in lines) + "\n", encoding="utf-8"
            )
            s = summarize(run)
            self.assertEqual(s["n_fullscan_unmeasured_rows"], 1)
            self.assertEqual(s["n_true_censor_rows"], 0)
            self.assertEqual(s["n_missing_oracle_queries"], 1)
            self.assertEqual(s["n_stable_search_or_selection_miss"], 1)

    def test_missing_oracle_is_not_no_faster_or_valid_pair(self):
        with tempfile.TemporaryDirectory() as td:
            run = Path(td)
            (run / "meta.json").write_text(json.dumps({"run_id": "t"}), encoding="utf-8")
            (run / "search.jsonl").write_text(
                json.dumps({"kind": "cbo_search", "query_id": "q1"}) + "\n",
                encoding="utf-8",
            )
            lines = []
            for ph in ("repeat_t1", "repeat_t2", "repeat_t3"):
                for pid, selected in (("P_TZ", True), ("P_T", False)):
                    r = row("q1", ph, pid, 100, selected, selected, ok=False)
                    r["status"] = "OK"
                    r["oracle_msg"] = "missing_oracle"
                    lines.append(r)
            (run / "measurements.jsonl").write_text(
                "\n".join(json.dumps(x) for x in lines) + "\n", encoding="utf-8"
            )
            s = summarize(run)
            self.assertEqual(s["n_queries_with_repeat_pairs"], 0)
            self.assertEqual(s["label_counts"], {"missing_oracle": 1})


if __name__ == "__main__":
    unittest.main()
