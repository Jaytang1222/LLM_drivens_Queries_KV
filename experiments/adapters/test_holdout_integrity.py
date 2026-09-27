#!/usr/bin/env python3
"""Unit tests for holdout integrity + v2 group-disjoint generation."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from holdout_integrity import assert_holdout_integrity, canonical_ir_hash  # noqa: E402


class HoldoutIntegrityTest(unittest.TestCase):
    def test_rejects_cross_split_group(self) -> None:
        qs = [
            {"query_id": "a", "temporal": {"start_ms": 1}},
            {"query_id": "b", "temporal": {"start_ms": 2}},
        ]
        cat = {
            "a": {"split": "train", "group": "g1"},
            "b": {"split": "test", "group": "g1"},
        }
        with self.assertRaises(ValueError) as cm:
            assert_holdout_integrity(qs, cat)
        self.assertIn("leaks across splits", str(cm.exception))

    def test_rejects_cross_split_canonical_hash(self) -> None:
        body = {"temporal": {"start_ms": 1}, "spatial": None}
        qs = [
            {"query_id": "a", **body},
            {"query_id": "b", **body},
        ]
        cat = {
            "a": {"split": "train", "group": "g1"},
            "b": {"split": "test", "group": "g2"},
        }
        self.assertEqual(canonical_ir_hash(qs[0]), canonical_ir_hash(qs[1]))
        with self.assertRaises(ValueError) as cm:
            assert_holdout_integrity(qs, cat)
        self.assertIn("hash collision", str(cm.exception))

    def test_accepts_group_disjoint(self) -> None:
        qs = [
            {"query_id": "a", "temporal": {"start_ms": 1}},
            {"query_id": "b", "temporal": {"start_ms": 1}},  # same body, same split OK
            {"query_id": "c", "temporal": {"start_ms": 9}},
        ]
        cat = {
            "a": {"split": "train", "group": "g1"},
            "b": {"split": "train", "group": "g1"},
            "c": {"split": "test", "group": "g2"},
        }
        assert_holdout_integrity(qs, cat)

    def test_v2_generator_and_split(self) -> None:
        gen = ROOT / "experiments/adapters/generate_holdout_workload_v2.py"
        split = ROOT / "experiments/adapters/split_holdout_workloads.py"
        with tempfile.TemporaryDirectory() as tmp:
            workdir = Path(tmp)
            workloads = workdir / "experiments/workloads"
            workloads.mkdir(parents=True)
            shutil.copy2(ROOT / "experiments/workloads/bound_ir_v1.json", workloads)
            env = {**os.environ, "KART_HOLDOUT_ROOT": str(workdir)}
            r = subprocess.run(
                [sys.executable, str(gen)], cwd=str(workdir), env=env,
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
            wl = json.loads((workloads / "bound_ir_holdout_v2.json").read_text(encoding="utf-8"))
            assert_holdout_integrity(wl["queries"], wl["catalog"])
            self.assertEqual(wl.get("evaluation_role"), "diagnostic_generalization_stress")
            r2 = subprocess.run(
                [sys.executable, str(split), "--version", "v2"], cwd=str(workdir),
                env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(r2.returncode, 0, r2.stderr + r2.stdout)
            for sp in ("train", "val", "test"):
                self.assertTrue((workloads / f"bound_ir_holdout_v2_{sp}.json").is_file())
            provenance = workloads / "bound_ir_holdout_v2.provenance.json"
            frozen = json.loads(provenance.read_text(encoding="utf-8"))
            frozen["oracle_status"] = "frozen"
            provenance.write_text(json.dumps(frozen), encoding="utf-8")
            for script, args in ((gen, []), (split, ["--version", "v2"])):
                refused = subprocess.run(
                    [sys.executable, str(script), *args], cwd=str(workdir), env=env,
                    capture_output=True, text=True, check=False,
                )
                self.assertNotEqual(refused.returncode, 0)
                self.assertIn("frozen", refused.stderr)


if __name__ == "__main__":
    unittest.main()
