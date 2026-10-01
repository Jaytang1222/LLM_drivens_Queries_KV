#!/usr/bin/env python3
"""Freeze immutable CBO baseline hashes (docs/kart_cbo_opportunity_followup.md §2.1)."""
from __future__ import annotations

import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "experiments/opportunity/cbo_baseline_freeze_v1.json"

FILES = [
    "src/main/java/kart/bench/ArmRegistry.java",
    "src/main/java/kart/bench/NativePolicyArm.java",
    "src/main/java/kart/search/PlannerMode.java",
    "src/main/java/kart/search/BestFirstPolicy.java",
    "src/main/java/kart/cost/PlanSelector.java",
    "src/main/java/kart/cost/CostModel.java",
    "src/main/java/kart/cost/FastCost.java",
    "src/main/java/kart/plan/PlanBuilder.java",
    "src/main/java/kart/validation/PlanValidator.java",
    "config/planner.yaml",
]


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def git(cmd: list[str]) -> str:
    try:
        return subprocess.check_output(["git"] + cmd, cwd=ROOT, text=True).strip()
    except Exception as e:
        return f"error:{e}"


def main() -> int:
    files = {}
    for rel in FILES:
        p = ROOT / rel
        files[rel] = {
            "exists": p.is_file(),
            "sha256": sha256(p) if p.is_file() else None,
            "bytes": p.stat().st_size if p.is_file() else None,
        }
    doc = {
        "freeze_id": "cbo_baseline_freeze_v1",
        "frozen_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Do not modify these files while optimizing KART arms; if shared code must change, label original vs changed CBO separately.",
        "cbo_arm": {
            "arm_id": "cbo",
            "registration": "NativePolicyArm(cbo, PlannerMode.BEST_FIRST)",
            "path": "src/main/java/kart/bench/ArmRegistry.java",
        },
        "git": {
            "commit": git(["rev-parse", "HEAD"]),
            "commit_short": git(["rev-parse", "--short", "HEAD"]),
            "dirty": bool(git(["status", "--porcelain"])),
            "status_porcelain_sha256": hashlib.sha256(
                git(["status", "--porcelain"]).encode("utf-8")
            ).hexdigest(),
        },
        "files": files,
        "census_run_reference": "experiments/opportunity/opportunity-dev-v1",
        "corrected_summary": "experiments/opportunity/opportunity-dev-v1/corrected/summary.json",
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")
    print(json.dumps({"commit": doc["git"]["commit_short"], "dirty": doc["git"]["dirty"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
