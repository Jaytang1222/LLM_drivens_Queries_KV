#!/usr/bin/env python3
"""Feedback calibration + one-sided overestimate margin (comparative_refine_6 §6)."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional

B0, BR, BF, BI = -4368.95402037, 616.58721963, 211.02887576, 142.47608618


def pred_exec(r: dict) -> float:
    ranges = float(r.get("n_ranges") or 0)
    fetch = float(r.get("fetch_chunks") or 0)
    index = float(r.get("index_rows") or 0)
    return B0 + BR * math.log1p(ranges) + BF * math.log1p(fetch) + BI * math.log1p(index)


def quantile(xs: List[float], q: float) -> Optional[float]:
    if not xs:
        return None
    ys = sorted(xs)
    if len(ys) == 1:
        return ys[0]
    pos = q * (len(ys) - 1)
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return ys[lo]
    w = pos - lo
    return ys[lo] * (1 - w) + ys[hi] * w


def fam(q: str) -> str:
    s = str(q).lower()
    if s.startswith("ais") or "ais" in s:
        return "ais"
    if s.startswith("a2") or s.startswith("topk") or s.startswith("st_"):
        return "td"
    return "other"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--measurements",
        default="experiments/opportunity/opportunity-dev-v1/measurements.jsonl",
    )
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--p", type=float, default=0.95)
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

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
    pairs: List[dict] = []
    groups: Dict[str, List[float]] = defaultdict(list)
    missing_feat = 0
    pos_opp = 0
    pos_recall_200 = 0
    harm_adopt_200 = 0

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
            d = sp - sa  # r = S_pred - S_actual (overestimate when >0)
            residuals.append(d)
            abs_err.append(abs(d))
            groups[fam(q)].append(d)
            if sa > 0:
                pos_opp += 1
                if sp >= 200:
                    pos_recall_200 += 1
            if sa <= 0 and sp >= 200:
                harm_adopt_200 += 1
            pairs.append({
                "query_id": q,
                "plan_id": pid,
                "cbo_plan_id": cp,
                "S_actual": sa,
                "S_pred": sp,
                "residual": d,
                "family": fam(q),
            })

    mae = statistics.mean(abs_err) if abs_err else float("nan")
    # §6.3: upper-side quantile of r = S_pred - S_actual (full residual dist).
    p95 = quantile(residuals, args.p)
    overs = [x for x in residuals if x > 0]
    p95_pos_only = quantile(overs, args.p) if overs else None
    p95_over = p95  # use full-distribution upper quantile as the margin

    # coverage: fraction of pairs where r ≤ margin
    margin = p95_over if p95_over is not None else 1100.0
    covered = sum(1 for d in residuals if d <= margin)
    coverage = covered / len(residuals) if residuals else float("nan")

    write_jsonl = out_dir / "feedback_pairs.jsonl"
    write_jsonl.write_text(
        "\n".join(json.dumps(p, ensure_ascii=False) for p in pairs) + ("\n" if pairs else ""),
        encoding="utf-8",
    )

    def summ(xs: List[float]) -> str:
        if not xs:
            return "n=0"
        return (
            f"n={len(xs)} mae={statistics.mean(abs(x) for x in xs):.1f} "
            f"med={statistics.median(xs):.1f} "
            f"p95={quantile(xs, 0.95):.1f}"
        )

    md = f"""# feedback_calibration

> comparative_refine_6.md §6 — feature contract uses census measured ranges/fetch/index
> into the same log-linear predictor as online FastCost drivers (not a holdout).

## 数据

- 来源：`{args.measurements}`
- OK 行：{len(ok)}；候选对：{len(residuals)}
- 特征缺失计数：{missing_feat}
- 实际值仅作离线标签；不泄漏到同次在线决策

## 残差 `r = S_pred - S_actual`

总体：{summ(residuals)}

| 分组 | 残差摘要 |
|---|---|
"""
    for g, xs in sorted(groups.items()):
        md += f"| {g} | {summ(xs)} |\n"

    md += f"""
## 单侧高估余量（预先固定分位）

| 项 | 值 |
|---|---|
| 分位水平 | p{int(args.p * 100)}（事先固定，全体残差上侧） |
| 全体残差 p{int(args.p * 100)} | {p95:.1f} ms（用于 `KART_CALIB_OVERESTIMATE_P95_MS`） |
| 正高估子集 p{int(args.p * 100)}（诊断） | {p95_pos_only if p95_pos_only is None else f'{p95_pos_only:.1f} ms'} |
| 经验覆盖率 `r ≤ margin` | {covered}/{len(residuals)} = {coverage:.3f} |
| MAE | {mae:.1f} ms |

**注意：** 经验分位数不是无条件概率保证；同查询候选/重复应同组留出。开发集不可当未知 holdout。

## 机会与误采纳（门槛 200 ms，未扣 uncertainty）

| 指标 | 值 |
|---|---|
| 正机会对 | {pos_opp} |
| 正机会召回 (S_pred≥200) | {pos_recall_200}/{pos_opp} |
| 负收益误采纳 (S_actual≤0 ∧ S_pred≥200) | {harm_adopt_200} |

## 特征契约（摘要）

见同目录 `feature_contract.md`。线上用 FastCost 估计特征；本报告用普查实测代入同式，用于选择单侧余量，**未完整计入线上特征估计误差**。

建议环境变量：`KART_CALIB_OVERESTIMATE_P95_MS={int(round(p95_over)) if p95_over is not None else 1100}`
"""
    (out_dir / "feedback_calibration.md").write_text(md, encoding="utf-8")

    # also emit suggested env for runner
    suggest = int(round(p95_over)) if p95_over is not None else 1100
    (out_dir / "calib_suggest.env").write_text(
        f"KART_CALIB_OVERESTIMATE_P95_MS={suggest}\n"
        f"KART_CALIB_UNCERTAINTY_MS={suggest}\n",
        encoding="utf-8",
    )
    print(f"CALIB_OK pairs={len(residuals)} p95_over={suggest} coverage={coverage:.3f}")
    print(f"wrote {out_dir / 'feedback_calibration.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
