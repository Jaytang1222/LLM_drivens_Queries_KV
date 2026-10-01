#!/usr/bin/env python3
"""Static readiness gate tests; no HBase or model calls."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from check_cbo_opportunity_verify import check


class VerifyGateTest(unittest.TestCase):
    def test_new_full_set_passes_and_relabelled_old_query_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            wl = root / "experiments/workloads"
            wl.mkdir(parents=True)
            prefix = "bound_ir_cbo_opportunity_verify_v2"
            old = {"query_id": "old", "temporal": {"start_ms": 1, "end_ms": 2}}
            (wl / "bound_ir_advantage_v2.json").write_text(
                json.dumps({"queries": [old]}), encoding="utf-8"
            )
            queries = [
                {"query_id": f"new_{i}", "temporal": {"start_ms": i + 10, "end_ms": i + 11}}
                for i in range(8)
            ]
            workload = wl / f"{prefix}.json"
            oracle = wl / f"{prefix}.oracle.json"
            provenance = wl / f"{prefix}.provenance.json"
            workload.write_text(json.dumps({"queries": queries}), encoding="utf-8")
            oracle.write_text(json.dumps({
                "oracle_status": "frozen", "source": "fresh_fullscan_build_oracle_cache",
                "answers": [{"query_id": q["query_id"]} for q in queries],
            }), encoding="utf-8")
            counts = {name: 2 for name in (
                "large_opportunity", "cost_misestimate", "semantic_boundary", "normal_control"
            )}

            def write_provenance():
                provenance.write_text(json.dumps({
                    "categories": counts, "vacancies": [], "sha256": {
                        "workload": hashlib.sha256(workload.read_bytes()).hexdigest(),
                        "oracle": hashlib.sha256(oracle.read_bytes()).hexdigest(),
                    },
                }), encoding="utf-8")

            write_provenance()
            errors, _ = check(root, "verify", prefix)
            self.assertEqual([], errors)
            queries[0] = {"query_id": "renamed_old", "temporal": old["temporal"]}
            workload.write_text(json.dumps({"queries": queries}), encoding="utf-8")
            oracle.write_text(json.dumps({
                "oracle_status": "frozen", "source": "fresh_fullscan_build_oracle_cache",
                "answers": [{"query_id": q["query_id"]} for q in queries],
            }), encoding="utf-8")
            write_provenance()
            errors, _ = check(root, "verify", prefix)
            self.assertTrue(any("semantic copies" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
