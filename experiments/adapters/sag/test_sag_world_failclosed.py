#!/usr/bin/env python3
"""Unit checks for sag-mql fail-closed world probe (no LLM, no HBase)."""
from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(ROOT / "experiments/adapters/translate"))

# Import after path setup
import sag_mql_bridge as bridge  # noqa: E402


class SagWorldFailClosedTest(unittest.TestCase):
    def setUp(self) -> None:
        self._prev = os.environ.pop("KART_WORLD_SAMPLE", None)
        self._prev_backend = os.environ.pop("KART_WORLD_BACKEND", None)

    def tearDown(self) -> None:
        if self._prev is None:
            os.environ.pop("KART_WORLD_SAMPLE", None)
        else:
            os.environ["KART_WORLD_SAMPLE"] = self._prev
        if self._prev_backend is None:
            os.environ.pop("KART_WORLD_BACKEND", None)
        else:
            os.environ["KART_WORLD_BACKEND"] = self._prev_backend

    def test_feedback_off_skips_world(self) -> None:
        probe = bridge._world_probe({"ir_version": "1.0"}, feedback_on=False)
        self.assertFalse(probe.get("unavailable"))
        self.assertEqual(probe.get("message"), "feedback_disabled")

    def test_feedback_on_without_backend_is_unavailable(self) -> None:
        probe = bridge._world_probe({"ir_version": "1.0"}, feedback_on=True)
        self.assertTrue(probe.get("unavailable"))
        self.assertIn("world_unavailable", str(probe.get("message")))

    def test_sample_json_not_formal(self) -> None:
        sample = Path(__file__).with_name("_tmp_sample_points.json")
        sample.write_text(
            json.dumps({"points": [{"trajectory_id": "t1", "t_ms": 1}]}),
            encoding="utf-8",
        )
        try:
            os.environ["KART_WORLD_SAMPLE"] = str(sample)
            probe = bridge._world_probe(
                {
                    "ir_version": "1.0",
                    "query_id": "q",
                    "source": {"dataset_id": "tdrive_v1", "entity": "trajectory"},
                    "temporal": None,
                    "spatial": None,
                    "predicates": [],
                    "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
                    "similarity": None,
                    "result": {"mode": "TRAJECTORY_IDS", "k": None, "tie_breaker": "TID_ASC"},
                },
                feedback_on=True,
            )
            self.assertTrue(probe.get("unavailable"))
            self.assertIn("sample_json", str(probe.get("message")))
        finally:
            sample.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
