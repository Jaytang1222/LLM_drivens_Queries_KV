#!/usr/bin/env python3
"""Saving residual validation for comparative_refine_5 §5."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

B0, BR, BF, BI = -4368.95402037, 616.58721963, 211.02887576, 142.47608618


def pred_exec(r: dict) -> float:
    ranges = float(r.get("n_ranges") or 0)
    fetch = float(r.get("fetch_chunks") or 0)
    index = float(r.get("index_rows") or 0)
    return B0 + BR * math.log1p(ranges) + BF * math.log1p(fetch) + BI * math.log1p(index)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--measurements",
        default="experiments/opportunity/opportunity-dev-v1/measurements.jsonl",
    )
    ap.add_argument("--out-md", required=True)
    args = ap.parse_args()

    rows = [
        json.loads(l)
        for l in Path(args.measurements).read_text(encoding="utf-8").splitlines()
        if l.strip()
    ]
    ok = [r for r in rows if r.get("status") == "OK" and r.get("t_exec_ms") is not None]
    by_q: Dict[str, Dict[str, dict]] = defaultdict(dict)
    cbo: Dict[str, str] = {}
    for r in ok:
        by_q[r["query_id"]][r["plan_id"]] = r
        if r.get("cbo_selected"):
            cbo[r["query_id"]] = r["plan_id"]

    residuals: List[float] = []
    abs_err: List[float] = []
    over_pos = under_pos = miss_neg = 0
    groups = defaultdict(list)
    missing_feat = 0
    for q, plans in by_q.items():
        cp = cbo.get(q)
        if not cp or cp not in plans:
            continue
        ec = float(plans[cp]["t_exec_ms"])
        pc = pred_exec(plans[cp])
        for pid, r in plans.items():
            if pid == cp:
                continue
            for k in ("n_ranges", "fetch_chunks", "index_rows"):
                if r.get(k) is None:
                    missing_feat += 1
                    break
            sa = ec - float(r["t_exec_ms"])
            sp = pc - pred_exec(r)
            d = sp - sa
            residuals.append(d)
            abs_err.append(abs(d))
            fam = "ais" if str(q).startswith("ais") or "ais" in str(q).lower() else (
                "td" if str(q).startswith("a2") or str(q).startswith("topk") or str(q).startswith("st_") else "other"
            )
            groups[fam].append(d)
            if sa > 0 and sp >= 200 and sa < 0:
                pass
            if sa <= 0 and sp >= 200:
                miss_neg += 1  # would adopt harmful
            if sa > 0 and sp > sa:
                over_pos += 1
            if sa > 0 and sp < sa:
                under_pos += 1

    def summ(xs: List[float]) -> str:
        if not xs:
            return "n=0"
        return (
            f"n={len(xs)} mae={statistics.mean(abs(x) for x in xs):.1f} "
            f"med={statistics.median(xs):.1f} "
            f"p90={sorted(abs(x) for x in xs)[max(0, int(0.9 * len(xs)) - 1)]:.1f}"
        )

    # suggest uncertainty ~ mae of residual, thresholds unchanged unless residual demands
    mae = statistics.mean(abs_err) if abs_err else float("nan")
    suggest_u = int(round(mae)) if abs_err else None

    md = f"""# saving_residual_validation

> comparative_refine_5.md §5 — `S_actual = E_cbo - E_p`, `residual = S_pred - S_actual`.

## 数据

- 来源：`{args.measurements}`
- OK 实测行：{len(ok)}；含 CBO 选择的查询：{len(cbo)}
- 候选对：{len(residuals)}
- 特征缺失计数（任一 n_ranges/fetch_chunks/index_rows 为空）：{missing_feat}

线上 FastCost 使用同名驱动的估计值；本残差用普查**实测** ranges/fetch/index 代入同一 log-linear 式，用于开发模型选择，**不是未知 holdout**。

## 总体残差

{summ(residuals)}

| 分组 | 残差摘要 |
|---|---|
"""
    for g, xs in sorted(groups.items()):
        md += f"| {g} | {summ(xs)} |\n"

    md += f"""
## 误差方向

| 指标 | 值 |
|---|---|
| 正收益上高估次数 | {over_pos} |
| 正收益上低估次数 | {under_pos} |
| 负收益却 S_pred≥200（潜在误采纳） | {miss_neg} |

## 门槛依据

- 维持 `min_spec_s=500`、`min_adopt_s=200`（不因想获胜而降到 0）。
- 建议 `saving_uncertainty_ms≈{suggest_u}`（≈残差 MAE），采纳使用保守量 `S_hat - u`。
- 缺失特征：线上标记 `features_missing`，禁止当作 measured zero 打开门控。
- 九条开发查询不得改名为 holdout。
"""
    Path(args.out_md).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out_md).write_text(md, encoding="utf-8")
    print(md)
    print("SUGGEST_UNCERTAINTY_MS", suggest_u)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
