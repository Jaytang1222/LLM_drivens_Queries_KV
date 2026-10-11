"""Collapse force-mine paired.jsonl to one best-kart row per query_id vs cbo."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--paired", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--metric", choices=("t_e2e_ms", "t_exec_ms"), default="t_e2e_ms")
    args = ap.parse_args()
    by = defaultdict(lambda: {"cbo": None, "kart": []})
    for line in Path(args.paired).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        qid = r.get("query_id")
        arm = r.get("arm")
        if not qid or not arm:
            continue
        if arm == "cbo":
            by[qid]["cbo"] = r
        elif arm == "kart":
            by[qid]["kart"].append(r)
    out = Path(args.out)
    n = 0
    with out.open("w", encoding="utf-8") as w:
        for qid, pack in sorted(by.items()):
            cbo = pack["cbo"]
            if cbo is None or not pack["kart"]:
                continue
            best = min(pack["kart"], key=lambda r: float(r.get(args.metric) or 1e18))
            w.write(json.dumps(cbo, ensure_ascii=False) + "\n")
            w.write(json.dumps(best, ensure_ascii=False) + "\n")
            n += 1
    print(json.dumps({"queries": n, "out": str(out), "metric": args.metric}))


if __name__ == "__main__":
    main()
