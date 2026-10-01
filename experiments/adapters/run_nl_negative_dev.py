#!/usr/bin/env python3
"""Run the four NL boundary fixtures through the live entry without execution.

The valid case gets scripted 'n' at confirmation; negative cases get no answer
at clarification. Results are diagnostic and do not enter E2/E3 accuracy.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--timeout-seconds", type=int, default=90)
    args = ap.parse_args()
    if not args.run_id.startswith("cbo-opportunity-dev-negative-") or "/" in args.run_id:
        raise SystemExit("run-id must start with cbo-opportunity-dev-negative-")
    fixture = json.loads((ROOT / "experiments/workloads/nl_negative_verify_v1.json")
                         .read_text(encoding="utf-8"))
    out = ROOT / "experiments/results" / args.run_id
    out.mkdir(parents=True, exist_ok=False)
    answers = out / "decline-confirmation.txt"
    answers.write_text("n\n", encoding="utf-8")
    no_answers = out / "no-answers.txt"
    no_answers.write_text("", encoding="utf-8")
    rows = []
    for case in fixture["items"]:
        cmd = ["java", f"-Dkart.root={ROOT}", "-jar", str(ROOT / "target/kart.jar"),
               "query-nl", case["utterance"], "--manifest", "tdrive_v1_ready",
               "--dataset", "tdrive_v1", "--answers",
               str(answers if case["expected_status"] == "OK_OR_BOUND_IR" else no_answers),
               "--runs", str(out / "artifacts" / case["case_id"])]
        try:
            completed = subprocess.run(cmd, cwd=ROOT, env=os.environ.copy(),
                                       capture_output=True, text=True,
                                       timeout=args.timeout_seconds, check=False)
            stdout, stderr, exit_code = completed.stdout, completed.stderr, completed.returncode
            timed_out = False
        except subprocess.TimeoutExpired as exc:
            stdout = (exc.stdout or b"").decode("utf-8", "replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
            stderr = (exc.stderr or b"").decode("utf-8", "replace") if isinstance(exc.stderr, bytes) else (exc.stderr or "")
            exit_code, timed_out = None, True
        (out / f"{case['case_id']}.stdout.log").write_text(stdout, encoding="utf-8")
        (out / f"{case['case_id']}.stderr.log").write_text(stderr, encoding="utf-8")
        observed = "TIMEOUT" if timed_out else (
            "UNSUPPORTED_QUERY" if "UNSUPPORTED_QUERY:" in stdout else
            "ASKED_CLARIFICATION" if "Please answer (one line)" in stdout else
            "REACHED_CONFIRMATION" if "Confirm and plan?" in stdout else
            "BIND_ERROR" if "BIND_ERROR:" in stdout else
            "OTHER")
        row = {"case_id": case["case_id"], "expected_status": case["expected_status"],
               "observed_stage": observed, "exit_code": exit_code, "timed_out": timed_out}
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    (out / "summary.json").write_text(json.dumps({"run_id": args.run_id, "rows": rows},
                                                 ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
