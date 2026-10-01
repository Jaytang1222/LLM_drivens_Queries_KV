#!/usr/bin/env python3
"""Build docs/refine_2 deliverables from comparative_refine_2.md artifacts."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


def load_jsonl(path: Path) -> List[dict]:
    if not path or not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def arm_map(rows: List[dict], want: str) -> Dict[str, dict]:
    out = {}
    for r in rows:
        arm = str(r.get("arm") or "")
        if want.endswith("-"):
            if arm.startswith(want):
                out[r["query_id"]] = r
        elif arm == want:
            out[r["query_id"]] = r
    return out


def num(r: dict, *keys: str) -> Optional[float]:
    for k in keys:
        if k in r and r[k] is not None:
            try:
                return float(r[k])
            except (TypeError, ValueError):
                pass
    return None


def e2e_ms(r: dict) -> Optional[float]:
    v = num(r, "t_e2e_ms")
    if v is not None:
        return v
    p = num(r, "t_plan_ms")
    e = num(r, "t_exec_ms")
    if p is not None and e is not None:
        return p + e
    return p


def pair_rows(before: List[dict], after: List[dict], label: str) -> List[dict]:
    bc = arm_map(before, "cbo")
    bh = arm_map(before, "cbo-llm-proposal")
    ac = arm_map(after, "cbo")
    ah = arm_map(after, "cbo-llm-proposal")
    qids = sorted(set(bc) | set(bh) | set(ac) | set(ah))
    out = []
    for q in qids:
        row = {
            "dataset": label,
            "query_id": q,
            "cbo_e2e_before": e2e_ms(bc[q]) if q in bc else None,
            "hyb_e2e_before": e2e_ms(bh[q]) if q in bh else None,
            "cbo_e2e_after": e2e_ms(ac[q]) if q in ac else None,
            "hyb_e2e_after": e2e_ms(ah[q]) if q in ah else None,
        }
        if q in bh:
            row.update({
                "hyb_selected_before": bh[q].get("selected_plan_id") or bh[q].get("plan_id"),
                "hyb_prefer_before": bh[q].get("prefer_plan_id"),
                "hyb_llm_before": num(bh[q], "llm_latency_ms"),
                "hyb_spec_validate_before": num(bh[q], "t_spec_validate_ms"),
                "hyb_await_before": num(bh[q], "t_spec_await_ms"),
            })
        if q in ah:
            row.update({
                "hyb_selected_after": ah[q].get("selected_plan_id") or ah[q].get("plan_id"),
                "hyb_prefer_after": ah[q].get("prefer_plan_id"),
                "hyb_rank_hint_after": ah[q].get("prefer_plan_id"),
                "hyb_llm_after": num(ah[q], "llm_latency_ms"),
                "hyb_spec_validate_after": num(ah[q], "t_spec_validate_ms"),
                "hyb_await_after": num(ah[q], "t_spec_await_ms"),
                "hyb_compile_ms": num(ah[q], "t_fixed_compile_ms"),
                "hyb_safety_ms": num(ah[q], "t_fixed_safety_ms"),
                "hyb_features_ms": num(ah[q], "t_fixed_features_ms"),
                "hyb_region_ms": num(ah[q], "t_fixed_region_locate_ms"),
                "hyb_prepare_ms": num(ah[q], "t_fixed_prepare_ms"),
                "hyb_prepare_mode": ah[q].get("prepare_mode"),
                "hyb_llm_action": ah[q].get("llm_action"),
                "hyb_reason": ah[q].get("llm_reason_code"),
                "hyb_prompt": ah[q].get("proposal_prompt_version"),
                "hyb_fallback": ah[q].get("fallback_reason"),
                "hyb_spec_waste": ah[q].get("speculative_waste"),
                "hyb_spec_used": ah[q].get("speculative_used"),
                "hyb_n_scan": ah[q].get("n_scan_tasks"),
            })
        for side, ckey, hkey, gkey in (
            ("before", "cbo_e2e_before", "hyb_e2e_before", "G_before"),
            ("after", "cbo_e2e_after", "hyb_e2e_after", "G_after"),
        ):
            c, h = row.get(ckey), row.get(hkey)
            row[gkey] = (c - h) if c is not None and h is not None else None
        out.append(row)
    return out


def md_table(headers: List[str], rows: List[List[Any]]) -> str:
    lines = ["| " + " | ".join(headers) + " |",
             "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        lines.append("| " + " | ".join(str(x) if x is not None else "" for x in r) + " |")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="docs/refine_2")
    ap.add_argument("--identity", default="")
    ap.add_argument("--paths", default="")
    ap.add_argument("--td-before", default="experiments/refine_pull/td_e2e.jsonl")
    ap.add_argument("--ais-before", default="experiments/refine_pull/ais_e2e.jsonl")
    ap.add_argument("--td-after", required=True)
    ap.add_argument("--ais-after", required=True)
    ap.add_argument("--prepare-full", default="")
    ap.add_argument("--ub-td", default="experiments/refine_pull/td_ub_corrected.md")
    ap.add_argument("--ub-ais", default="experiments/refine_pull/ais_ub_corrected.md")
    args = ap.parse_args()
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    identity = Path(args.identity).read_text(encoding="utf-8") if args.identity and Path(args.identity).exists() else ""
    paths = Path(args.paths).read_text(encoding="utf-8") if args.paths and Path(args.paths).exists() else ""
    td_b = load_jsonl(Path(args.td_before))
    ais_b = load_jsonl(Path(args.ais_before))
    td_a = load_jsonl(Path(args.td_after))
    ais_a = load_jsonl(Path(args.ais_after))
    prep = load_jsonl(Path(args.prepare_full)) if args.prepare_full else []

    write(out / "identity.txt", identity or "(missing)\n")
    write(out / "paths.txt", paths or "(missing)\n")
    write(out / "td_e2e.jsonl", Path(args.td_after).read_text(encoding="utf-8") if Path(args.td_after).exists() else "")
    write(out / "ais_e2e.jsonl", Path(args.ais_after).read_text(encoding="utf-8") if Path(args.ais_after).exists() else "")
    if args.prepare_full and Path(args.prepare_full).exists():
        write(out / "prepare_full_ais.jsonl", Path(args.prepare_full).read_text(encoding="utf-8"))

    # --- prepare_breakdown ---
    hyb_prep = arm_map(prep, "cbo-llm-proposal") if prep else arm_map(ais_a, "cbo-llm-proposal")
    focus = ["ais_topk_dtw_2week", "ais_topk_frechet_wide"]
    pb_rows = []
    pb_jsonl = []
    for q in focus + sorted(set(hyb_prep) - set(focus)):
        if q not in hyb_prep:
            continue
        r = hyb_prep[q]
        rec = {
            "query_id": q,
            "prepare_mode": r.get("prepare_mode"),
            "t_fixed_compile_ms": r.get("t_fixed_compile_ms"),
            "t_fixed_safety_ms": r.get("t_fixed_safety_ms"),
            "t_fixed_features_ms": r.get("t_fixed_features_ms"),
            "t_fixed_region_locate_ms": r.get("t_fixed_region_locate_ms"),
            "t_fixed_cost_ms": r.get("t_fixed_cost_ms"),
            "t_fixed_prepare_ms": r.get("t_fixed_prepare_ms"),
            "t_spec_validate_ms": r.get("t_spec_validate_ms"),
            "t_spec_exec_ms": r.get("t_spec_exec_ms"),
            "t_spec_await_ms": r.get("t_spec_await_ms"),
            "region_locate_calls": r.get("region_locate_calls"),
            "region_locate_cache_hits": r.get("region_locate_cache_hits"),
            "n_scan_tasks": r.get("n_scan_tasks"),
            "llm_latency_ms": r.get("llm_latency_ms"),
            "selected_plan_id": r.get("selected_plan_id") or r.get("plan_id"),
        }
        pb_jsonl.append(rec)
        pb_rows.append([
            q, r.get("prepare_mode"), r.get("t_fixed_compile_ms"), r.get("t_fixed_safety_ms"),
            r.get("t_fixed_features_ms"), r.get("t_fixed_region_locate_ms"),
            r.get("t_fixed_prepare_ms"), r.get("t_spec_validate_ms"),
            r.get("t_spec_exec_ms"), r.get("n_scan_tasks"),
            r.get("region_locate_calls"), r.get("region_locate_cache_hits"),
        ])
    write(out / "prepare_breakdown.jsonl",
          "\n".join(json.dumps(x, ensure_ascii=False) for x in pb_jsonl) + ("\n" if pb_jsonl else ""))
    src_note = "prepare_full AIS run" if prep else "safety_only E2E (full probe missing)"
    write(out / "prepare_breakdown.md", f"""# prepare_breakdown

> comparative_refine_2.md §4 — fixed-candidate prepare sub-stages.

Source: {src_note}.

## Fields

- `t_fixed_compile_ms` / `t_fixed_safety_ms` / `t_fixed_features_ms` /
  `t_fixed_region_locate_ms` (subset of features) / `t_fixed_cost_ms` /
  `t_fixed_prepare_ms` (wall of prepare).
- Region locate is **not** added again on top of features in sums.
- `t_spec_validate_ms` is the speculative-thread prepare wall (may equal prepare).

## AIS focus rows

{md_table(
    ["query", "mode", "compile", "safety", "features", "region", "prepare", "spec_val", "spec_exec", "n_scan", "loc_calls", "loc_hits"],
    pb_rows)}

## Interpretation

- If `features`/`region` dominate under `prepare_mode=full`, the prior ~24s/4.5s
  waits were Final cost-feature + Region locate, not PlanValidator alone.
- If `compile` dominates, range expansion is the bottleneck.
- Under `safety_only`, compile+safety should stay; features/region in prepare ≈ 0;
  remaining wait moves to speculative exec / scan affinity.
""")

    # --- prepare_reuse_review ---
    write(out / "prepare_reuse_review.md", """# prepare_reuse_review

> comparative_refine_2.md §5 — reusable safe prepare path.

## Separation

| concept | implementation |
|---|---|
| compileAndValidate | `QueryEngine.runFixed(..., PrepareMode.SAFETY_ONLY)` |
| estimateForDecision | FastCost ranking (pre-LLM); Final cost card optional |
| executePrepared | `executeSelected` reuses `selected` SafePlanHandle |
| collectDiagnostics | Final extract deferred / Fast extract for trace estimates |

## Eliminated hybrid-only work

1. Speculative prepare no longer runs Final `extractFinal` + live per-scan Region
   locate before LLM returns (default `KART_CBO_LLM_PREPARE_MODE=safety_only`).
2. `executeSelected` reuses `selectedFeatures` when present; safety_only path uses
   `extractFast` for Coordinator estimate fields only (not a second Final locate pass).
3. `HBaseRegionMapping` caches locate results process-wide (public infra — CBO
   remeasured in the same after package).

## Fairness

- CBO search/select algorithm unchanged.
- Public locate cache benefits both arms; paired after CBO baseline is the one in
  `paired_before_after.jsonl` / after E2E files.
- Cross-query plan-decision cache remains **off** for main comparison
  (`cbo-llm-proposal` without cache).

## Version binding

Reuse binds to the same BoundIR / compiled SafePlanHandle within one arm run.
Cache arm still re-validates plan IDs; no answer cache.
""")

    # --- paired before/after ---
    pairs = pair_rows(td_b, td_a, "tdrive") + pair_rows(ais_b, ais_a, "ais")
    write(out / "paired_before_after.jsonl",
          "\n".join(json.dumps(x, ensure_ascii=False) for x in pairs) + ("\n" if pairs else ""))

    def summarize(ds: str) -> Tuple[str, List[List[Any]]]:
        rows = [p for p in pairs if p["dataset"] == ds]
        wins = sum(1 for p in rows if p.get("G_after") is not None and p["G_after"] > 0)
        loss = sum(1 for p in rows if p.get("G_after") is not None and p["G_after"] < 0)
        tie = sum(1 for p in rows if p.get("G_after") is not None and p["G_after"] == 0)
        g_after = [p["G_after"] for p in rows if p.get("G_after") is not None]
        g_before = [p["G_before"] for p in rows if p.get("G_before") is not None]
        mean_g = statistics.mean(g_after) if g_after else None
        mean_gb = statistics.mean(g_before) if g_before else None
        table = []
        for p in rows:
            table.append([
                p["query_id"],
                _i(p.get("cbo_e2e_before")), _i(p.get("hyb_e2e_before")), _i(p.get("G_before")),
                _i(p.get("cbo_e2e_after")), _i(p.get("hyb_e2e_after")), _i(p.get("G_after")),
                p.get("hyb_selected_after"), p.get("hyb_llm_action"), p.get("hyb_prepare_mode"),
                _i(p.get("hyb_prepare_ms")), _i(p.get("hyb_spec_validate_after")),
            ])
        head = (
            f"n={len(rows)} wins={wins} losses={loss} ties={tie}; "
            f"mean G_before={_i(mean_gb)} mean G_after={_i(mean_g)}"
        )
        return head, table

    def _i(v):
        if v is None:
            return ""
        if isinstance(v, float):
            return int(round(v))
        return v

    td_head, td_tab = summarize("tdrive")
    ais_head, ais_tab = summarize("ais")
    write(out / "paired_before_after.md", f"""# paired_before_after

> comparative_refine_2.md — before = docs/refine E2E (v6); after = refine_2 E2E (v7 + safety_only).

## T-Drive

{td_head}

{md_table(
    ["query", "cbo_b", "hyb_b", "G_b", "cbo_a", "hyb_a", "G_a", "sel", "action", "prep_mode", "prep_ms", "spec_val"],
    td_tab)}

## AIS

{ais_head}

{md_table(
    ["query", "cbo_b", "hyb_b", "G_b", "cbo_a", "hyb_a", "G_a", "sel", "action", "prep_mode", "prep_ms", "spec_val"],
    ais_tab)}

Raw lines: `paired_before_after.jsonl`.
""")

    # --- llm_independent_decision ---
    hyb_all = list(arm_map(td_a, "cbo-llm-proposal").values()) + list(
        arm_map(ais_a, "cbo-llm-proposal").values())
    same_prefer = 0
    diff_ok = 0
    keep = 0
    fail = 0
    waste = 0
    llm_rows = []
    for r in hyb_all:
        action = r.get("llm_action")
        sel = r.get("selected_plan_id") or r.get("plan_id")
        hint = r.get("prefer_plan_id")
        cbo = r.get("cbo_plan_id")
        if action == "keep_cbo" or r.get("fallback_reason") == "llm_keep_cbo":
            keep += 1
        elif r.get("fallback_reason") and not r.get("proposal_valid"):
            fail += 1
        elif sel and hint and sel == hint:
            same_prefer += 1
        elif sel and hint and sel != hint and sel != cbo:
            diff_ok += 1
        if r.get("speculative_waste") is True:
            waste += 1
        llm_rows.append([
            r.get("query_id"), action, r.get("llm_reason_code"), hint, sel, cbo,
            r.get("llm_calls"), _i(num(r, "llm_latency_ms")),
            r.get("speculative_used"), r.get("speculative_waste"),
            r.get("fallback_reason"), r.get("proposal_prompt_version"),
        ])
    write(out / "llm_independent_decision.md", f"""# llm_independent_decision

> comparative_refine_2.md §7 — no forced prefer / no prefer-corrective retry.

## Protocol (v7)

- Prompt: `cbo_llm_independent_v7`
- Max HTTP calls: 1
- Actions: `keep_cbo` | `propose`
- On illegal/timeout: fallback CBO (no rule-assist)
- Speculative ranker (pre-LLM): FastCost — tagged `speculative_ranker=fastcost`

## Counts (after E2E hybrid rows)

- same as rank_hint: {same_prefer}
- different legal propose: {diff_ok}
- keep_cbo: {keep}
- fail/fallback: {fail}
- speculative_waste rows: {waste}

{md_table(
    ["query", "action", "reason", "rank_hint", "selected", "cbo", "calls", "llm_ms", "spec_used", "waste", "fallback", "prompt"],
    llm_rows)}

## Incremental value

Compare LLM choice vs FastCost rank_hint on the same whitelist. Agreement does not
prove LLM value; disagreement needs positive paired G to count as incremental.
Rule-assist is disabled in v7, so assists cannot inflate LLM credit.
""")

    # --- calibration_validation ---
    ub_td = Path(args.ub_td).read_text(encoding="utf-8") if Path(args.ub_td).exists() else "(missing)"
    ub_ais = Path(args.ub_ais).read_text(encoding="utf-8") if Path(args.ub_ais).exists() else "(missing)"
    write(out / "calibration_validation.md", f"""# calibration_validation

> comparative_refine_2.md §8 — relative benefit labels; development only.

## Status

**Partial — not a trained holdout model.** Labels and ranking diagnostics below use
existing opportunity corrected summaries + after E2E pairs. The nine development
queries remain development data (not renamed into holdout).

## Labels

`S(q,p) = E_cbo(q) - E_p(q)` from opportunity repeats (multi-trial where available).
Missing/unmeasured FullScan stays marked — not imputed as zero.

## Prior opportunity corrected (development)

### T-Drive

{ub_td}

### AIS

{ub_ais}

## Ranking vs after E2E

FastCost rank_hint is still the speculative chooser before LLM returns. After v7,
LLM may diverge; see `llm_independent_decision.md`. Without a grouped holdout fit,
do **not** treat FastCost or LLM scores as calibrated confidence.

## Gate idea (not enforced)

Speculate/adopt only if predicted `S` exceeds critical-path overhead with κ≥1.
""")

    # --- answers / summary ---
    ais_pairs = [p for p in pairs if p["dataset"] == "ais"]
    frechet = next((p for p in ais_pairs if p["query_id"] == "ais_topk_frechet_wide"), None)
    dtw = next((p for p in ais_pairs if p["query_id"] == "ais_topk_dtw_2week"), None)

    def g_line(p, name):
        if not p:
            return f"- {name}: missing"
        return (f"- {name}: G_before={_i(p.get('G_before'))} → G_after={_i(p.get('G_after'))}; "
                f"hyb { _i(p.get('hyb_e2e_before')) }→{_i(p.get('hyb_e2e_after'))}; "
                f"spec_val {_i(p.get('hyb_spec_validate_before'))}→{_i(p.get('hyb_spec_validate_after'))}; "
                f"prep_mode={p.get('hyb_prepare_mode')} action={p.get('hyb_llm_action')}")

    any_win = any(p.get("G_after") is not None and p["G_after"] > 0 for p in pairs)
    write(out / "answers_phase_gates.md", f"""# 阶段推进条件回答（comparative_refine_2.md §10.3）

1. **主要耗时已定位到可解释子阶段吗？**  
   是（见 `prepare_breakdown.md`）。FULL prepare 下可拆分 compile / safety /
   features / region_locate；此前复合的 `t_spec_validate_ms` 不再整段归因于
   PlanValidator。

2. **准备路径是否真正减少工作，而非仅改计时边界？**  
   是：默认 `safety_only` 跳过 Final extract+locate；`executeSelected` 避免第二次
   Final；Region locate 结果缓存。对照 `paired_before_after` 的 spec_validate /
   await / hyb_e2e。

3. **计划安全和答案是否保持一致？**  
   安全验证仍经 `PlanValidator`；未删除检查。Oracle/合法性路径未故意削弱。
   具体答案一致性以本轮 E2E status 为准（见 summary）。

4. **E2E 改善是否超过运行波动，并保留负结果？**  
   见 `paired_before_after.md`。负结果全部保留。开发集仍可能整体未净胜——
   准备下降不等于实验胜出。

5. **改善来自公共优化、候选扩展、排序校准还是 LLM？**  
   - 公共：Region locate 缓存（两臂共享，after CBO 已重测）。  
   - Hybrid 流程：safety_only prepare + features 复用。  
   - LLM：v7 独立协议；增量见 `llm_independent_decision.md`。  
   - 校准：未上线模型门控（`calibration_validation.md` 标记 partial）。

## 开发焦点案例

{g_line(frechet, "ais_topk_frechet_wide")}
{g_line(dtw, "ais_topk_dtw_2week")}

任意净胜？ **{"yes" if any_win else "no"}**（开发配对，非全量）。
""")

    write(out / "summary.md", f"""# summary — comparative_refine_2

## 三项预期收获

1. **AIS 执行优势能否靠减少额外准备转为 E2E 净收益？**  
   见焦点案例与 `paired_before_after.md`。准备路径已压轻；是否净胜以 G_after 为准。

2. **取消强制 prefer 后 LLM 是否有真实决策增量？**  
   见 `llm_independent_decision.md` 的 agree / disagree / keep_cbo / waste。

3. **可获益场景与成本边界？**  
   短查询仍难覆盖 LLM 调用；长相似度查询取决于 `max(L, V+E)` 是否落入
   `T_cbo - C` 预算（见文档 §6）。校准门控尚未部署。

## 配置核查

见 `RUN_CONFIG.md` / `identity.txt`。

## 下一步

若两条 AIS 开发案例在 safety_only 后仍无稳定净胜空间，不宜立即扩大全量；
优先完成分组校准或进一步压缩 speculative exec / 扫描路径。
""")

    # RUN_CONFIG filled by shell/identity; placeholder if empty
    write(out / "RUN_CONFIG.md", f"""# 实验运行配置（核查）

```
{identity.strip() or "(see identity.txt)"}
```

```
{paths.strip() or "(see paths.txt)"}
```

固定边界：CBO-first；本地 Ollama；无计划决策缓存主比较臂；开发九条非未知测试集。
""")

    write(out / "README.md", """# docs/refine_2 — comparative_refine_2 交付包

| 文件 | 对应 |
|---|---|
| `prepare_breakdown.md/jsonl` | §4 阶段 A |
| `prepare_reuse_review.md` | §5 阶段 B |
| `paired_before_after.md/jsonl` | 优化前后配对 |
| `llm_independent_decision.md` | §7 阶段 C |
| `calibration_validation.md` | §8 阶段 D（partial） |
| `answers_phase_gates.md` | §10.3 五问 |
| `summary.md` | §12 总结 |
| `RUN_CONFIG.md` | 运行配置核查 |
| `td_e2e.jsonl` / `ais_e2e.jsonl` | after 原始行 |
| `prepare_full_ais.jsonl` | FULL prepare 分解跑次 |

重新生成：

```bash
python experiments/adapters/build_refine2_deliverables.py \\
  --out-dir docs/refine_2 \\
  --identity experiments/refine_2_pull/identity.txt \\
  --paths experiments/refine_2_pull/paths.txt \\
  --td-after experiments/refine_2_pull/td_e2e.jsonl \\
  --ais-after experiments/refine_2_pull/ais_e2e.jsonl \\
  --prepare-full experiments/refine_2_pull/prepare_full_ais.jsonl
```
""")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
