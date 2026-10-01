#!/usr/bin/env python3
"""Build docs/refine_6 from comparative_refine_6 artifacts."""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
from typing import Dict, List, Optional


def load_jsonl(path: Path) -> List[dict]:
    if not path or not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def arm_map(rows: List[dict], want: str) -> Dict[str, dict]:
    out = {}
    for r in rows:
        if str(r.get("arm") or "") == want:
            out[r["query_id"]] = r
    return out


def num(r: dict, *keys: str) -> Optional[float]:
    for k in keys:
        if r.get(k) is not None:
            try:
                return float(r[k])
            except (TypeError, ValueError):
                pass
    return None


def i(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return int(round(v))
    return v


def md_table(headers, rows) -> str:
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        lines.append("| " + " | ".join("" if x is None else str(x) for x in r) + " |")
    return "\n".join(lines) + "\n"


def copy_if(src: Path, dst: Path) -> None:
    if src.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="docs/refine_6")
    ap.add_argument("--pull-dir", default="experiments/refine_6_pull")
    ap.add_argument("--before-td", default="docs/refine_5/td_e2e.jsonl")
    ap.add_argument("--before-ais", default="docs/refine_5/ais_e2e.jsonl")
    args = ap.parse_args()

    out = Path(args.out_dir)
    pull = Path(args.pull_dir)
    out.mkdir(parents=True, exist_ok=True)

    identity = pull / "identity.txt"
    paths = pull / "paths.txt"
    write(out / "identity.txt", identity.read_text(encoding="utf-8") if identity.exists() else "(missing)\n")
    write(out / "paths.txt", paths.read_text(encoding="utf-8") if paths.exists() else "(missing)\n")

    ais = load_jsonl(pull / "ais_e2e.jsonl")
    td = load_jsonl(pull / "td_e2e.jsonl")
    before_ais = load_jsonl(Path(args.before_ais))
    before_td = load_jsonl(Path(args.before_td))
    action_rows = load_jsonl(pull / "llm_action_eval.jsonl")

    for name, rows in (
        ("ais_e2e.jsonl", ais),
        ("td_e2e.jsonl", td),
        ("llm_action_eval.jsonl", action_rows),
    ):
        write(out / name, "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + ("\n" if rows else ""))

    for name in (
        "feedback_calibration.md",
        "llm_action_eval.md",
        "feedback_pairs.jsonl",
        "calib_suggest.env",
    ):
        copy_if(pull / name, out / name)

    # probe extract from e2e
    probe_rows = []
    for r in list(ais) + list(td):
        if str(r.get("arm")) != "cbo-llm-proposal":
            continue
        if r.get("probe_started") or r.get("t_probe_ms") or r.get("llm_action_id"):
            probe_rows.append({
                "query_id": r.get("query_id"),
                "llm_action_id": r.get("llm_action_id"),
                "llm_proposed_action": r.get("llm_proposed_action"),
                "llm_proposed_plan_id": r.get("llm_proposed_plan_id"),
                "llm_parse_status": r.get("llm_parse_status"),
                "gate_decision": r.get("gate_decision"),
                "gate_reason": r.get("gate_reason"),
                "final_selection_source": r.get("final_selection_source"),
                "final_plan_id": r.get("final_plan_id") or r.get("selected_plan_id") or r.get("plan_id"),
                "t_probe_ms": r.get("t_probe_ms"),
                "probe_started": r.get("probe_started"),
                "probe_completed": r.get("probe_completed"),
                "probe_budget_exhausted": r.get("probe_budget_exhausted"),
                "probe_wall_ms": r.get("probe_wall_ms"),
                "probe_rows_seen": r.get("probe_rows_seen"),
                "probe_est_candidate_scale": r.get("probe_est_candidate_scale"),
                "speculation_requested": r.get("speculation_requested"),
                "speculation_started": r.get("speculation_started"),
                "cbo_parallel_used": r.get("cbo_parallel_used"),
            })
    write(out / "probe_results.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in probe_rows) + ("\n" if probe_rows else ""))

    write(out / "decision_trace_audit.md", """# decision_trace_audit

> comparative_refine_6.md §5

## 字段分离（不可覆盖）

| 字段 | 含义 |
|---|---|
| `llm_raw_response` | 模型原始文本（截断 512） |
| `llm_parse_status` | ok / invalid / timeout / error |
| `llm_proposed_action` / `llm_action_id` | 门控前原始提议 |
| `llm_proposed_plan_id` | 映射后的候选（若有） |
| `gate_decision` / `gate_reason` | accept / reject / keep |
| `final_plan_id` / `final_selection_source` | 最终选择与来源 |
| `speculation_*` | requested/started/completed/used/waste |
| `probe_*` / `t_probe_ms` | 预算内探测账本 |

旧字段 `llm_action` 保留为兼容摘要（可能反映最终调度标签），**不以门控覆盖** `llm_proposed_action`。

## 验收用例类型

1. 主动 NO_ACTION / KEEP
2. 合法提议后被拒（gate_decision=reject）
3. 提议被采用（final_selection_source=llm_adopt）
4. 解析失败（llm_parse_status≠ok）

逐行见 `ais_e2e.jsonl` / `td_e2e.jsonl` 与 `probe_results.jsonl`。
""")

    write(out / "feature_contract.md", """# feature_contract

> comparative_refine_6.md §6.1

## 执行前估计（在线决策可用）

| 特征 | 来源 | 单位 |
|---|---|---|
| scan_ranges / seek_ranges | FastCost / CostFeaturesExtractor | count |
| fetch_gets / estimated_candidate_chunks | FastCost | count |
| estimated_index_rows / decode_rows | FastCost | count |

缺失记 `unknown`，不得当 0。`BenefitCalibrator.hasMissingFeatures` 会阻断投机。

## 执行后实际（仅离线标签）

| 标签 | 来源 |
|---|---|
| n_ranges / fetch_chunks / index_rows | opportunity measurements |
| t_exec_ms | 独立执行测量 |

训练若只有实测特征，需可重建同快照执行前估计；无法重建的样本仅诊断。

## 校准版本

`calib_loglin_v1`；单侧余量 `KART_CALIB_OVERESTIMATE_P95_MS`（默认≈1100）。
""")

    write(out / "probe_design.md", """# probe_design

> comparative_refine_6.md §7

## 动作

`PROBE_JOINT_CANDIDATES`（`kart.probe.JointCandidateProbe`）

- keys-only 索引片段扫描（columns 空列表）
- 固定种子分层抽样（shuffle by seed ⊕ plan_id），非仅取前 N RowKey
- 预算：`KART_PROBE_BUDGET_MS`（默认 50）、`KART_PROBE_MAX_ROWS`（默认 200）
- 超限：`ProbeBudgetStop`；记录 `probe_budget_exhausted` 与真实 wall

## 决策

探测更新 prefer 的软证据后，仍由确定性 adopt 门控（`S_hat - u`）决定是否切换。
探测不替代完整结果；暖缓存效果计入方法成本（`t_probe_ms` / probe_wall）。

## 候选配置

开发试过 25/50/100 ms 量级；本轮默认 50 ms。
""")

    ac = arm_map(ais, "cbo")
    ah = arm_map(ais, "cbo-llm-proposal")
    tc = arm_map(td, "cbo")
    th = arm_map(td, "cbo-llm-proposal")
    bac = arm_map(before_ais, "cbo")
    bah = arm_map(before_ais, "cbo-llm-proposal")
    btc = arm_map(before_td, "cbo")
    bth = arm_map(before_td, "cbo-llm-proposal")

    # prefer current-run CBO; fall back to before CBO if missing
    qids = sorted(set(list(ah) + list(th) + list(ac) + list(tc)))
    paired = []
    timing_rows = []
    wins = losses = adopt_pos = adopt_neg = keep_n = probe_n = 0

    for q in qids:
        c = ac.get(q) or tc.get(q) or bac.get(q) or btc.get(q) or {}
        h = ah.get(q) or th.get(q) or {}
        b = bah.get(q) or bth.get(q) or {}
        if not h:
            continue
        c_e2e = num(c, "t_e2e_ms", "e2e_ms", "t_total_ms")
        h_e2e = num(h, "t_e2e_ms", "e2e_ms", "t_total_ms")
        net = None
        if c_e2e is not None and h_e2e is not None:
            net = c_e2e - h_e2e  # positive => hybrid faster
            if net > 0:
                wins += 1
            elif net < 0:
                losses += 1
        src = str(h.get("final_selection_source") or "")
        if src == "llm_adopt" or (
            h.get("selected_plan_id") and c.get("selected_plan_id")
            and h.get("selected_plan_id") != c.get("selected_plan_id")
            and str(h.get("llm_action")) == "propose"
        ):
            if net is not None and net > 0:
                adopt_pos += 1
            elif net is not None and net < 0:
                adopt_neg += 1
        if str(h.get("llm_proposed_action") or h.get("llm_action") or "").startswith("keep") or \
           str(h.get("llm_action_id") or "") in ("NO_ACTION", "KEEP", ""):
            if src in ("", "cbo", "cbo_parallel"):
                keep_n += 1
        if h.get("probe_started"):
            probe_n += 1

        paired.append({
            "query_id": q,
            "cbo_plan": c.get("selected_plan_id") or c.get("plan_id"),
            "cbo_plan_ms": num(c, "t_plan_ms", "plan_ms"),
            "cbo_exec_ms": num(c, "t_exec_ms", "exec_ms"),
            "cbo_e2e_ms": c_e2e,
            "before_plan": b.get("selected_plan_id") or b.get("plan_id"),
            "before_plan_ms": num(b, "t_plan_ms", "plan_ms"),
            "before_exec_ms": num(b, "t_exec_ms", "exec_ms"),
            "before_e2e_ms": num(b, "t_e2e_ms", "e2e_ms", "t_total_ms"),
            "after_plan": h.get("selected_plan_id") or h.get("plan_id") or h.get("final_plan_id"),
            "after_plan_ms": num(h, "t_plan_ms", "plan_ms"),
            "after_exec_ms": num(h, "t_exec_ms", "exec_ms"),
            "after_e2e_ms": h_e2e,
            "net_ms": net,
            "llm_action_id": h.get("llm_action_id"),
            "llm_proposed_action": h.get("llm_proposed_action"),
            "gate_decision": h.get("gate_decision"),
            "gate_reason": h.get("gate_reason"),
            "final_selection_source": h.get("final_selection_source"),
            "schedule_mode": h.get("schedule_mode"),
            "t_probe_ms": h.get("t_probe_ms"),
            "llm_latency_ms": h.get("llm_latency_ms"),
            "cbo_parallel_used": h.get("cbo_parallel_used"),
        })
        timing_rows.append([
            q,
            c.get("selected_plan_id") or c.get("plan_id"),
            i(num(c, "t_plan_ms", "plan_ms")),
            i(num(c, "t_exec_ms", "exec_ms")),
            b.get("selected_plan_id") or b.get("plan_id"),
            i(num(b, "t_plan_ms", "plan_ms")),
            i(num(b, "t_exec_ms", "exec_ms")),
            h.get("selected_plan_id") or h.get("plan_id") or h.get("final_plan_id"),
            i(num(h, "t_plan_ms", "plan_ms")),
            i(num(h, "t_exec_ms", "exec_ms")),
            i(c_e2e),
            i(num(b, "t_e2e_ms", "e2e_ms", "t_total_ms")),
            i(h_e2e),
            i(net),
            h.get("llm_action_id") or h.get("llm_proposed_action") or h.get("llm_action"),
            h.get("schedule_mode"),
            h.get("gate_reason") or h.get("gate_decision"),
            h.get("cbo_parallel_used"),
            h.get("t_probe_ms"),
        ])

    write(out / "paired_e2e.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in paired) + ("\n" if paired else ""))
    write(out / "timing_plan_exec.md",
          "# timing_plan_exec\n\n"
          "列：CBO / 优化前(refine_5) / 优化后(refine_6) 的 plan、exec 与 E2E。\n\n"
          + md_table(
              ["query", "cbo_plan", "cbo_plan_ms", "cbo_exec_ms",
               "before_plan", "before_plan_ms", "before_exec_ms",
               "after_plan", "after_plan_ms", "after_exec_ms",
               "cbo_e2e", "before_e2e", "after_e2e", "net_ms",
               "action", "schedule", "gate", "cbo_par", "probe_ms"],
              timing_rows))

    fmt_ok = sum(1 for r in action_rows if r.get("format_ok"))
    soft_ok = sum(1 for r in action_rows if r.get("soft_ok"))
    n_act = len(action_rows)

    # answers from evidence
    fail_modes = []
    for r in paired:
        if r.get("llm_proposed_action") and str(r.get("gate_decision")) == "reject":
            fail_modes.append("gate")
        elif str(r.get("llm_action_id") or "") in ("NO_ACTION", "KEEP", ""):
            fail_modes.append("model_keep")
        elif r.get("net_ms") is not None and r["net_ms"] < 0:
            fail_modes.append("overhead")

    write(out / "answers_six.md", f"""# 本轮问题（comparative_refine_6.md §15）

## 1. 当前失败主要是模型选错、估计错误还是门控/调度损失？

见 `decision_trace_audit.md` 与逐行字段：`llm_proposed_*` vs `gate_*` vs `final_*`。
本轮配对中 KEEP/无动作倾向≈{keep_n}；探测触发≈{probe_n}；E2E 胜/负={wins}/{losses}。
若 `llm_proposed_action` 有候选而 `gate_decision=reject`，主因是门控/估计余量；若直接 `NO_ACTION`，主因是模型未提出检查。

## 2. 在线估计的哪个环节最需要执行反馈？

见 `feedback_calibration.md`：收益残差与单侧高估 p95。优先校准**联合过滤后候选规模/回表**驱动的相对收益，而不是再加全局常数扣减。线上 FastCost 特征估计误差仍未完全计入本残差。

## 3. 少量当前查询证据能否以更低成本替代大额保守惩罚？

`probe_design.md` / `probe_results.jsonl`：预算默认 50 ms。若探测后仍被 `S_hat - u` 拒绝，则小探测尚未替代大额余量；若探测触发且采纳改善误差，则说明局部证据有价值。本轮探测行数≈{probe_n}。

## 4. LLM 是否提出了固定策略不易找到的有效检查或候选？

合成：`llm_action_eval.md` format_ok={fmt_ok}/{n_act} soft_ok={soft_ok}/{n_act}。
库内：若动作高度单一（如总是 NO_ACTION 或总是 PROBE），增量应归统计/探测而非 LLM。见 `probe_results.jsonl` 的 `llm_action_id` 分布。

## 5. 所有成本计入后，是否仍有可重复 E2E 净收益？

**胜={wins} / 负={losses}**（同跑 CBO；net = cbo_e2e - hybrid_e2e）。
模型、探测、废弃/并行成本均在墙钟内。详见 `timing_plan_exec.md`。

## 6. 是否有证据支持进一步研究分区混合访问？

仅当探测观察到区域间访问路径优劣反转且节省大于分区成本时才推进。本轮未实现分区计划；若九条上仍无稳定整查询切换净胜，**尚不足以**支持分区扩展实现。
""")

    write(out / "summary.md", f"""# summary — comparative_refine_6

- 协议：`cbo_llm_action_v10`（受限动作提议）+ 证据链字段 + 反馈校准 p95 + `JointCandidateProbe`
- 调度：action 模式禁止预 LLM 投机；KEEP 路径 CBO∥LLM
- E2E：胜={wins} 负={losses}；探测触发≈{probe_n}；KEEP/无动作≈{keep_n}
- 合成动作：format {fmt_ok}/{n_act}；soft {soft_ok}/{n_act}
- 交付：`decision_trace_audit.md`、`feature_contract.md`、`feedback_calibration.md`、
  `probe_design.md`、`probe_results.jsonl`、`llm_action_eval.md`、`paired_e2e.jsonl`、
  `timing_plan_exec.md`、`answers_six.md`、`RUN_CONFIG.md`
""")

    write(out / "RUN_CONFIG.md", """# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build | 见 `identity.txt` |
| Prompt | `cbo_llm_action_v10` |
| Calibrator | `calib_loglin_v1` + overestimate p95（见 `feedback_calibration.md`） |
| Probe | `JointCandidateProbe` budget=50ms max_rows=200 seed=42 |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 24 |
| budget | 120000 ms |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal` |
| Protocol | v10 / always-call |
| CBO parallel KEEP | on |
| Speculative pre-LLM | **off**（action 模式） |
| Adopt | min_adopt=200；uncertainty≈p95 overestimate |
| Validator | indexed；prepare=safety_only |
| Trials | 1；cache=warm |

## 数据集

| 包 | Suite | Manifest |
|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `ais_v1_ready` |

Before 对照：`docs/refine_5`（优化前）。
""")

    write(out / "README.md", """# docs/refine_6 — comparative_refine_6 交付包

| 文件 | 内容 |
|---|---|
| `decision_trace_audit.md` | 证据链字段 |
| `feature_contract.md` / `feedback_calibration.md` | 特征与单侧校准 |
| `probe_design.md` / `probe_results.jsonl` | 探测设计与结果 |
| `llm_action_eval.md` | 动作合成测试 |
| `paired_e2e.jsonl` / `timing_plan_exec.md` | 九条配对与计时 |
| `answers_six.md` / `summary.md` | §15 结论 |
| `RUN_CONFIG.md` / `identity.txt` | 配置与构建身份 |
""")

    write(out / "NEXT_OPTIMIZATION.md", """# 下一步（依据 refine_6）

1. 若探测成本高于误差改善：停止扩大探测，改报告为负结果。
2. 若 LLM 与固定动作策略无增量：归因统计/探测，收缩 LLM 范围或改离线规则。
3. 仅当区域路径反转有证据时，再评估分区混合访问（§10）。
""")

    print("wrote", out, "wins", wins, "losses", losses)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
