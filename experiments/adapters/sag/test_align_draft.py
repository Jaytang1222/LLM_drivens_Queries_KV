"""Draft round-trip used by the KartWorldAccess Oracle gate."""
from __future__ import annotations

import unittest

from align_world_oracle import bound_query_to_draft


class BoundToDraftTest(unittest.TestCase):
    def test_temporal_second_aligned(self) -> None:
        ir = {
            "query_id": "t1",
            "source": {"dataset_id": "tdrive", "entity": "trajectory"},
            "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
            "result": {"mode": "TRAJECTORY_IDS"},
            "temporal": {"start_ms": 1_200_000_000_000, "end_ms": 1_200_086_400_000},
        }
        draft = bound_query_to_draft(ir)
        self.assertIsNotNone(draft)
        self.assertEqual("1.0", draft["ir_version"])
        self.assertEqual("[start,end)", draft["temporal"]["boundary"])
        self.assertTrue(draft["temporal"]["start"].startswith("2008-"))

    def test_subsecond_and_utm_are_skipped(self) -> None:
        base = {
            "query_id": "x",
            "source": {"dataset_id": "tdrive", "entity": "trajectory"},
            "result": {"mode": "TRAJECTORY_IDS"},
        }
        sub = dict(base)
        sub["temporal"] = {"start_ms": 1_200_000_000_001, "end_ms": 1_200_086_400_000}
        self.assertIsNone(bound_query_to_draft(sub))
        utm = dict(base)
        utm["spatial"] = {"min_x": 440000.0, "min_y": 4420000.0, "max_x": 441000.0, "max_y": 4421000.0}
        self.assertIsNone(bound_query_to_draft(utm))


if __name__ == "__main__":
    unittest.main()
