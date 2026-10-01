#!/usr/bin/env python3
"""Build docs/refine deliverables from opportunity + e2e jsonl (comparative_refine.md)."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any, Dict, List, Optional


def load_jsonl(path: Path) -> List[dict]:
    if not path or not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def median(xs: List[float]) -> Optional[float]:
    if not xs:
        return None
    return float(statistics.median(xs))


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def arm_map(rows: List[dict], want: str) -> Dict[str, dict]:
    """Exact arm id, or prefix only when want ends with '-' (hybrid family)."""
    out = {}
    for r in rows:
        arm = str(r.get("arm") or "")
        if want.endswith("-"):
            if arm.startswith(want):
                out[r["query_id"]] = r
        elif arm == want:
            out[r["query_id"]] = r
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="docs/refine")
    ap.add_argument("--identity", default="")
    ap.add_argument("--td-ub-summary", default="")
    ap.add_argument("--ais-ub-summary", default="")
    ap.add_argument("--td-e2e", default="")
    ap.add_argument("--ais-e2e", default="")
    ap.add_argument("--dev-ub-corrected", default="experiments/opportunity/opportunity-dev-v1/corrected/summary.md")
    ap.add_argument("--ais-ub-corrected", default="experiments/opportunity/opportunity-ais-complex-v1/corrected/summary.md")
    args = ap.parse_args()
    out = Path(args.out_dir)

    identity = Path(args.identity).read_text(encoding="utf-8") if args.identity and Path(args.identity).exists() else "(identity not provided)\n"
    td_rows = load_jsonl(Path(args.td_e2e)) if args.td_e2e else []
    ais_rows = load_jsonl(Path(args.ais_e2e)) if args.ais_e2e else []

    # --- opportunity_upper_bound ---
    ub_parts = [
        "# opportunity_upper_bound\n",
        "\n> comparative_refine.md §3 — development diagnostic; not a deployable method score.\n",
        "\n## Build identity\n\n```\n",
        identity,
        "```\n",
        "\n## Prior corrected census (stable 3/3 repeats)\n\n",
    ]
    for label, p in (("T-Drive opportunity-dev-v1", args.dev_ub_corrected),
                     ("AIS opportunity-ais-complex-v1", args.ais_ub_corrected)):
        path = Path(p)
        ub_parts.append(f"### {label}\n\n")
        if path.exists():
            ub_parts.append(path.read_text(encoding="utf-8"))
            ub_parts.append("\n")
        else:
            ub_parts.append(f"(missing {path})\n\n")
    for label, p in (("T-Drive refine re-run", args.td_ub_summary),
                     ("AIS refine re-run", args.ais_ub_summary)):
        path = Path(p) if p else None
        ub_parts.append(f"### {label}\n\n")
        if path and path.exists():
            ub_parts.append(path.read_text(encoding="utf-8"))
            ub_parts.append("\n")
        else:
            ub_parts.append("(not available in this package)\n\n")

    ub_parts.append(
        """
## Interpretation (phase 1)

- Stable opportunities on the 4 T-Drive overhead queries and 4 AIS opportunity queries
  are **candidate_miss** of `P_T` (CBO selected `P_TZ` / similar; `P_T` not in CBO safe set).
- Median exec savings are typically **~200–500 ms** (T-Drive) and **~0.2–2 s** (AIS frechet/dtw).
- Against provisional hybrid extra-plan ~900–2000 ms, most savings alone are
  `candidate_fast_but_overhead_dominates` unless LLM/validate tax is compressed
  or overlapped below the saving.
- Classification: bottleneck is mixed — **search miss (omit P_T)** creates opportunity;
  **serial/overlapped planning tax** often still exceeds S; v6 FastCost ranking
  often prefers `P_Z` (selection signal error relative to measured winners).

"""
    )
    write(out / "opportunity_upper_bound.md", "".join(ub_parts))

    # --- timing_trace ---
    def timing_table(rows: List[dict], title: str) -> str:
        cbo = arm_map(rows, "cbo")
        hyb = arm_map(rows, "cbo-llm-proposal")
        lines = [
            f"### {title}\n\n",
            "| query | cbo_e2e | hyb_e2e | search | llm | await | spec_validate | spec_exec | prefer | selected | G |\n",
            "|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|\n",
        ]
        for qid in sorted(set(cbo) | set(hyb)):
            c, h = cbo.get(qid, {}), hyb.get(qid, {})
            ce = int(c.get("t_e2e_ms") or 0)
            he = int(h.get("t_e2e_ms") or 0)
            lines.append(
                f"| {qid} | {ce} | {he} | {h.get('t_cbo_search_ms')} | {h.get('llm_latency_ms')} | "
                f"{h.get('t_spec_await_ms')} | {h.get('t_spec_validate_ms')} | {h.get('t_spec_exec_ms')} | "
                f"{h.get('prefer_plan_id')} | {h.get('selected_plan_id') or h.get('plan_id')} | {ce - he} |\n"
            )
        lines.append("\n")
        return "".join(lines)

    tt = [
        "# timing_trace\n",
        "\n> comparative_refine.md §4 — speculative wall split; await overlaps side-task work.\n\n",
        "## Contract\n\n",
        "- Serial: `G = (P_cbo+E_cbo) - (P_hybrid+E_selected) = S - H`.\n",
        "- Speculative: `t_e2e` is wall clock; do not sum full plan+exec intervals.\n",
        "- `t_spec_validate_ms` / `t_spec_exec_ms` are side-thread phases; `t_spec_await_ms` is\n",
        "  main-thread wait after LLM (overlaps remaining side work).\n\n",
        timing_table(td_rows, "T-Drive E2E (this package)"),
        timing_table(ais_rows, "AIS E2E (this package)"),
        """
## Attribution notes

- If `t_spec_validate_ms ≫ t_spec_exec_ms`, long await is dominated by **fixed-plan
  validate/compile/cost**, not HBase scan.
- If `t_spec_exec_ms` ≈ await and llm small, wait is mostly **execution**.
- If `llm_latency_ms` ≈ hybrid wall − search and await≈0, exec finished during LLM.
- Resource contention: when speculative validate+exec overlaps LLM only (not CBO exec),
  HBase contention with the main arm is limited to the hybrid query itself.

""",
    ]
    write(out / "timing_trace.md", "".join(tt))

    # --- benefit_calibration ---
    cal = [
        "# benefit_calibration\n",
        "\n> comparative_refine.md §5 — relative benefit vs CBO; development only.\n\n",
        "## Labels\n\n",
        "`S(q,p) = E_cbo(q) - E_p(q)` from paired opportunity repeats / same-run E2E.\n\n",
        "## Online features used today\n\n",
        "| feature | kind | used in v6 |\n|---|---|---|\n",
        "| FastCost estimated_ms on novel envelopes | statistical estimate | yes (prefer/rank) |\n",
        "| CBO selectedCost.estimated_ms | statistical estimate | yes (cost-beat trigger) |\n",
        "| TOP_K / similarity / wide ST flags | query-visible | yes (uncertainty trigger) |\n",
        "| Measured t_exec / scan rows | post-hoc label | **not** used online |\n\n",
        "## Observed ranking error (v6 paired E2E)\n\n",
    ]
    hyb_td = {r["query_id"]: r for r in td_rows if str(r.get("arm", "")).startswith("cbo-llm-proposal")}
    cbo_td = {r["query_id"]: r for r in td_rows if r.get("arm") == "cbo"}
    if hyb_td:
        cal.append("| query | prefer | selected | S=E_cbo-E_hyb | regret_vs_P_T_census |\n|---|---|---|---:|---|\n")
        census_best = {
            "a2_tz_hard_d": "P_T",
            "a2_tz_hard_a": "P_T",
            "a2_topk_dtw_wide": "P_T",
            "topk_st_3": "P_T",
        }
        for qid in sorted(hyb_td):
            h, c = hyb_td[qid], cbo_td.get(qid, {})
            S = int(c.get("t_exec_ms") or 0) - int(h.get("t_exec_ms") or 0)
            pref = h.get("prefer_plan_id")
            sel = h.get("selected_plan_id") or h.get("plan_id")
            regret = "prefer≠census_best" if pref and pref != census_best.get(qid) else "aligned_or_unknown"
            cal.append(f"| {qid} | {pref} | {sel} | {S} | {regret} |\n")
    else:
        cal.append("(no T-Drive e2e rows in package)\n")
    cal.append(
        """
## Training / grouping status

- **Not yet trained**: no holdout-isolated tree/regression model in this package.
- Calibration conclusion from evidence: absolute FastCost ranks frequently prefer
  `P_Z` while measured opportunity winners are `P_T` → **high selection regret**.
- Recommended next fit: predict `S(q,p)` (or rank margin vs CBO) with features above,
  grouped by template / temporal span / spatial area; report MAE of S and top-1 regret
  on a held-out template group before deploying thresholds.

## Gate proposal (not yet enforced as formal metric)

Adopt / speculate only if `predicted_S > κ · predicted_critical_path_overhead`
(with κ≥1). Otherwise keep CBO execution; LLM may still be requested under LLM-on.

"""
    )
    write(out / "benefit_calibration.md", "".join(cal))

    # --- llm_decision_eval ---
    llm = [
        "# llm_decision_eval\n",
        "\n> comparative_refine.md §6 — attribution of LLM vs FastCost prefer.\n\n",
        "## Protocol\n\n",
        "- Prompt: `cbo_llm_compact_v6`, whitelist FastCost-ranked, `prefer=<cheapest novel>`.\n",
        "- Max 2 HTTP calls; soft retry toward prefer; fail assist `rule_prefer_fastcost_after_llm_fail`.\n",
        "- Cache: main paired rows use uncached `cbo-llm-proposal`.\n\n",
        "## Decision changes vs FastCost prefer\n\n",
    ]
    if hyb_td:
        llm.append("| query | prefer | proposal | selected | assist | llm_calls | adopted_novel |\n|---|---|---|---|---|---:|:---:|\n")
        for qid in sorted(hyb_td):
            h = hyb_td[qid]
            pref = h.get("prefer_plan_id")
            prop = h.get("proposal_plan_id")
            sel = h.get("selected_plan_id") or h.get("plan_id")
            assist = h.get("selection_assist")
            calls = h.get("llm_calls")
            novel = h.get("proposal_novel")
            llm.append(f"| {qid} | {pref} | {prop} | {sel} | {assist} | {calls} | {novel} |\n")
        same = sum(1 for h in hyb_td.values() if h.get("prefer_plan_id") and h.get("prefer_plan_id") == (h.get("selected_plan_id") or h.get("plan_id")))
        llm.append(f"\nFastCost prefer == selected: **{same}/{len(hyb_td)}** on T-Drive package rows.\n")
    hyb_ais = {r["query_id"]: r for r in ais_rows if str(r.get("arm", "")).startswith("cbo-llm-proposal")}
    if hyb_ais:
        llm.append("\n### AIS rows\n\n| query | prefer | selected | llm_lat | speculative_used |\n|---|---|---|---:|:---:|\n")
        for qid in sorted(hyb_ais):
            h = hyb_ais[qid]
            llm.append(
                f"| {qid} | {h.get('prefer_plan_id')} | {h.get('selected_plan_id') or h.get('plan_id')} | "
                f"{h.get('llm_latency_ms')} | {h.get('speculative_used', h.get('speculative_pt_used'))} |\n"
            )
    llm.append(
        """
## Attribution

- When selected == prefer, LLM did **not** demonstrate value beyond the ranker
  (may still be required by LLM-on protocol).
- When selected ≠ prefer and improves S, credit LLM; when worsens S, debit LLM.
- Current package: LLM largely follows FastCost prefer / soft-retry → **no clear
  extra decision value** beyond the (misfit) ranker on these dev queries.

"""
    )
    write(out / "llm_decision_eval.md", "".join(llm))

    # --- paired_e2e_summary ---
    def summarize(rows: List[dict], name: str) -> str:
        cbo = arm_map(rows, "cbo")
        hyb = arm_map(rows, "cbo-llm-proposal")
        if not cbo or not hyb:
            return f"### {name}\n\n(no paired rows)\n\n"
        Gs = []
        wins = ties = losses = 0
        lines = [
            f"### {name}\n\n",
            "| query | T_cbo | T_hyb | G | win | oracle_hyb |\n|---|---:|---:|---:|:---:|:---:|\n",
        ]
        for qid in sorted(cbo):
            if qid not in hyb:
                continue
            ce = int(cbo[qid].get("t_e2e_ms") or 0)
            he = int(hyb[qid].get("t_e2e_ms") or 0)
            G = ce - he
            Gs.append(G)
            if G > 0:
                wins += 1
                w = "W"
            elif G < 0:
                losses += 1
                w = "L"
            else:
                ties += 1
                w = "T"
            ok = hyb[qid].get("ok_oracle")
            lines.append(f"| {qid} | {ce} | {he} | {G} | {w} | {ok} |\n")
        lines.append(
            f"\n- n={len(Gs)} win/tie/loss={wins}/{ties}/{losses}\n"
            f"- mean G={sum(Gs)/len(Gs):.1f} ms; median G={median(Gs):.1f} ms\n"
            f"- total T_cbo={sum(int(cbo[q].get('t_e2e_ms') or 0) for q in cbo if q in hyb)}; "
            f"total T_hyb={sum(int(hyb[q].get('t_e2e_ms') or 0) for q in cbo if q in hyb)}\n\n"
        )
        return "".join(lines)

    pe = [
        "# paired_e2e_summary\n",
        "\n> comparative_refine.md §8–§9 — frozen **development** pairs (not unknown test).\n\n",
        "## Cache / resource\n\n",
        "- Arm: `cbo` vs `cbo-llm-proposal` (uncached).\n",
        "- LLM: local Ollama `qwen2.5:1.5b-instruct`, `max_tokens=32`, speculate k=1.\n",
        "- Warm cache protocol for HBase; plan-decision cache off.\n\n",
        summarize(td_rows, "T-Drive overhead 4q"),
        summarize(ais_rows, "AIS opportunity set"),
        """
## Acceptance vs §10.3

- These queries were repeatedly used for development → **not** a frozen unknown
  validation set. Results guide next optimization only.
- No claim of full-workload win over CBO.

""",
    ]
    write(out / "paired_e2e_summary.md", "".join(pe))

    # --- answers to six questions ---
    answers = [
        "# answers_six_questions\n",
        "\n> comparative_refine.md §11\n\n",
        "## 1. 当前候选池中，多少查询存在足以覆盖 LLM 成本的机会？\n\n",
        "- Corrected T-Drive census: **4/60** repeat-stable opportunities (`P_T` candidate_miss), "
        "median S ≈ 197–285 ms — **usually below** observed LLM latencies (hundreds–thousands ms) "
        "unless heavily overlapped.\n",
        "- AIS corrected: **4** stable opportunities; frechet/dtw S ≈ 1.5–2.0 s can cover a "
        "sub-second LLM **if** validate await does not dominate.\n",
        "- Strict answer under current L≈0.3–2 s: **few queries** have S large enough in serial; "
        "speculative path may open frechet-class only after validate tax is cut.\n\n",
        "## 2. 机会主要来自 CBO 候选遗漏还是成本排序失准？\n\n",
        "- Upper bound labels are **`search_miss` / candidate_miss** (`P_T` absent from CBO safe set).\n",
        "- Online v6 also shows **ranking misfit**: FastCost prefers `P_Z` while measured best is `P_T`.\n",
        "- Both exist; **primary opportunity class is omission of `P_T`**, secondary is bad relative ranking among novels.\n\n",
        "## 3. AIS 的长等待究竟花在验证、执行还是调度/清理？\n\n",
        "- Prior AIS e2e: `t_spec_await` large while `t_exec` small ⇒ wait ≠ pure scan.\n",
        "- This package adds `t_spec_validate_ms` vs `t_spec_exec_ms` (see `timing_trace.md`). "
        "If validate ≫ exec, bottleneck is **runFixed/compile/cost**, not Coordinator scan.\n\n",
        "## 4. 校准后的排序是否在未知查询上减少 regret？\n\n",
        "- **Not yet**: no holdout-trained calibrator shipped. v6 FastCost ranking still incurs "
        "prefer≠measured-best regret on the known opportunity set.\n\n",
        "## 5. LLM 是否在已有排序之上产生额外决策价值？\n\n",
        "- On packaged T-Drive rows, selected usually equals FastCost prefer → **no demonstrated "
        "extra value** beyond the ranker under v6 prompts.\n\n",
        "## 6. 收益是否转化为真实 E2E 改善，代价是什么？\n\n",
        "- Packaged paired E2E: **no net win** vs CBO (see `paired_e2e_summary.md`).\n",
        "- Cost: LLM latency + speculative validate/exec (CPU/HBase) + occasional waste when prefer≠truth.\n",
        "- Next direction: calibrate `S` prediction + adopt gate; compress validate; do not hardcode `P_T`.\n",
    ]
    write(out / "answers_six_questions.md", "".join(answers))

    readme = [
        "# docs/refine — comparative_refine 交付包\n\n",
        "依据 `docs/comparative_refine.md` 建议产物：\n\n",
        "1. `opportunity_upper_bound.md`\n",
        "2. `timing_trace.md`\n",
        "3. `benefit_calibration.md`\n",
        "4. `llm_decision_eval.md`\n",
        "5. `paired_e2e_summary.md`\n",
        "6. `answers_six_questions.md`（§11 六问）\n\n",
        "另见 `README.md`（本文件）与可选 `identity.txt` / 原始 jsonl 引用路径。\n\n",
        "**边界**：开发诊断，非全量正式主表结论；不弱化 CBO；不写入 query→赢家映射。\n",
    ]
    write(out / "README.md", "".join(readme))
    print("Wrote deliverables under", out.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
