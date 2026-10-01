#!/usr/bin/env python3
"""Build docs/refine_5 from comparative_refine_5 artifacts."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Dict, List, Optional, Tuple


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


def collect_iso(pull: Path) -> List[dict]:
    rows = []
    for p in sorted(pull.glob("iso_*.jsonl")):
        for r in load_jsonl(p):
            r = dict(r)
            r["_iso_file"] = p.name
            rows.append(r)
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="docs/refine_5")
    ap.add_argument("--pull-dir", default="experiments/refine_5_pull")
    ap.add_argument("--before-td", default="docs/refine_4/td_e2e.jsonl")
    ap.add_argument("--before-ais", default="docs/refine_4/ais_e2e.jsonl")
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
    iso_rows = collect_iso(pull)
    syn = load_jsonl(pull / "decision_synthetic_eval.jsonl")

    for name, rows in (
        ("ais_e2e.jsonl", ais),
        ("td_e2e.jsonl", td),
        ("execution_isolation.jsonl", iso_rows),
        ("decision_synthetic_eval.jsonl", syn),
    ):
        write(out / name, "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + ("\n" if rows else ""))

    # copy residual / synthetic md if present
    for name in ("saving_residual_validation.md", "decision_synthetic_eval.md"):
        src = pull / name
        if src.exists():
            write(out / name, src.read_text(encoding="utf-8"))

    write(out / "decision_contract.md", """# decision_contract

> comparative_refine_5.md §4.1–4.2

## 字段

| 字段 | 含义 |
|---|---|
| `exec_saving_ms` | 预测 `E_cbo - E_candidate`（ms）；正值=候选执行更快；**未扣** LLM/准备关键路径 |
| `candidate_exec_ms` / `cbo_exec_ms` | 执行时间预测，非实测答案 |
| `saving_uncertainty_ms` | 校准不确定性；`unknown` 不得当作 0 |
| `schedule_mode` | `speculate_candidate` 或 `cbo_parallel` |
| `estimated_extra_critical_path_ms` | 相对 CBO 的额外关键路径估计（含调用量级提示） |

## 输出

`{"choice":"KEEP"}` 或 `{"choice":N}`；N 绑定当次 whitelist；解析后校验范围。

## 决策规则（门控确定性部分）

- 投机：`S_hat >= min_spec_s`（默认 500）且特征非 missing。
- 采纳：`S_hat >= min_adopt_s` 且 `S_hat - uncertainty >= min_adopt_s`。
- 证据不足或预计净收益非正 → KEEP。
- LLM 不能绕过安全验证与硬预算。

提示版本：`cbo_llm_short_v9`。
""")

    # isolation timing audit
    by_key: Dict[Tuple[str, str, str], List[dict]] = {}
    for r in iso_rows:
        if str(r.get("arm")) != "fixed-plan" or r.get("error"):
            continue
        plan = str(r.get("fixed_plan_id") or r.get("plan_id") or "?")
        mode = "par" if r.get("parallel_llm") else "alone"
        by_key.setdefault((str(r.get("query_id")), plan, mode), []).append(r)

    iso_rows_t = []
    for (q, plan, mode), rs in sorted(by_key.items()):
        execs = [num(r, "t_exec_ms") for r in rs if num(r, "t_exec_ms") is not None]
        calls = [num(r, "llm_call_ms", "llm_latency_ms") for r in rs if num(r, "llm_call_ms", "llm_latency_ms") is not None]
        awaits = [num(r, "llm_await_after_exec_ms") for r in rs if num(r, "llm_await_after_exec_ms") is not None]
        if not execs:
            continue
        iso_rows_t.append([
            q, plan, mode, len(execs),
            i(statistics.median(execs)), i(min(execs)), i(max(execs)),
            i(statistics.median(calls)) if calls else "",
            i(statistics.median(awaits)) if awaits else "",
            i(rs[0].get("tokens_in")), i(rs[0].get("tokens_out")),
        ])
    write(out / "isolation_timing_audit.md", "# isolation_timing_audit\n\n"
          "> comparative_refine_5.md §6 — `llm_call_ms`（完整请求）与 `llm_await_after_exec_ms`（执行后等待）分离。\n\n"
          + md_table(
              ["query", "plan", "mode", "n", "exec_med", "exec_min", "exec_max",
               "llm_call_med", "await_after_exec_med", "tok_in", "tok_out"],
              iso_rows_t)
          + "\n第四轮 `llm_latency_ms=0` 多为执行后等待已结束，不代表请求成本为 0。\n")
    write(out / "isolation_timing_audit.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in iso_rows) + ("\n" if iso_rows else ""))

    # paired / opportunity / timing
    def pack(rows):
        return arm_map(rows, "cbo"), arm_map(rows, "cbo-llm-proposal")

    bca, bha = pack(before_ais)
    bct, bht = pack(before_td)
    ac, ah = pack(ais)
    tc, th = pack(td)

    timing_rows = []
    paired = []
    wins = losses = 0
    adopt_pos = adopt_neg = keep_n = 0
    opp_lines = ["# opportunity_recovery\n", "> comparative_refine_5.md §8 — AIS 机会与对照。\n"]

    for label, bc, bh, ac_, ah_ in (
        ("AIS", bca, bha, ac, ah),
        ("TD", bct, bht, tc, th),
    ):
        for q in sorted(set(ac_) | set(ah_)):
            c, h = ac_.get(q, {}), ah_.get(q, {})
            hb = bh.get(q, {})
            if not c or not h:
                continue
            ce, cp = num(c, "t_exec_ms"), num(c, "t_plan_ms")
            he, hp = num(h, "t_exec_ms"), num(h, "t_plan_ms")
            cwall, hwall = num(c, "t_e2e_ms", "t_wall_ms"), num(h, "t_e2e_ms", "t_wall_ms")
            net = (cwall - hwall) if cwall is not None and hwall is not None else None
            if net is not None:
                if net > 0:
                    wins += 1
                else:
                    losses += 1
            sel = h.get("selected_plan_id") or h.get("plan_id")
            cbo_pid = h.get("cbo_plan_id") or c.get("plan_id")
            if h.get("llm_action") == "keep_cbo" or sel == cbo_pid:
                keep_n += 1
            elif sel and cbo_pid and sel != cbo_pid and he is not None and ce is not None:
                if he < ce:
                    adopt_pos += 1
                else:
                    adopt_neg += 1
            timing_rows.append([
                q, c.get("plan_id"), i(cp), i(ce),
                (hb.get("selected_plan_id") or hb.get("plan_id")), i(num(hb, "t_plan_ms")), i(num(hb, "t_exec_ms")),
                sel, i(hp), i(he), i(cwall), i(hwall), i(net),
                h.get("llm_action"), h.get("schedule_mode"),
                h.get("gate_reason") or h.get("fallback_reason"),
                h.get("cbo_parallel_used"), h.get("speculative_used"),
            ])
            paired.append({
                "query_id": q, "pack": label,
                "cbo_plan": c.get("plan_id"), "cbo_plan_ms": cp, "cbo_exec_ms": ce, "cbo_e2e_ms": cwall,
                "before_plan": hb.get("selected_plan_id") or hb.get("plan_id"),
                "before_plan_ms": num(hb, "t_plan_ms"), "before_exec_ms": num(hb, "t_exec_ms"),
                "after_plan": sel, "after_plan_ms": hp, "after_exec_ms": he, "after_e2e_ms": hwall,
                "net_vs_cbo_ms": net,
                "llm_latency_ms": h.get("llm_latency_ms"),
                "tokens_in": h.get("tokens_in"), "tokens_out": h.get("tokens_out"),
                "llm_action": h.get("llm_action"),
                "schedule_mode": h.get("schedule_mode"),
                "prefer_s_hat_ms": h.get("prefer_s_hat_ms"),
                "saving_uncertainty_ms": h.get("saving_uncertainty_ms"),
                "gate_reason": h.get("gate_reason"),
                "fallback_reason": h.get("fallback_reason"),
                "cbo_parallel_used": h.get("cbo_parallel_used"),
                "speculative_used": h.get("speculative_used"),
                "ok_oracle": h.get("ok_oracle"),
                "prompt": h.get("proposal_prompt_version"),
            })
            if q.startswith("ais_topk") or q.startswith("ais_st"):
                opp_lines.append(
                    f"- `{q}`: action={h.get('llm_action')} sel={sel} cbo={cbo_pid} "
                    f"S_hat={h.get('prefer_s_hat_ms')} sched={h.get('schedule_mode')} "
                    f"parallel_cbo={h.get('cbo_parallel_used')} spec={h.get('speculative_used')} "
                    f"exec {i(ce)}->{i(he)} e2e {i(cwall)}->{i(hwall)} net={i(net)}"
                )

    write(out / "paired_e2e.jsonl", "\n".join(json.dumps(r, ensure_ascii=False) for r in paired) + ("\n" if paired else ""))
    write(out / "timing_plan_exec.md",
          "# timing_plan_exec\n\n列：CBO / 优化前(refine_4) / 优化后(refine_5) 的 plan 与 exec。\n\n"
          + md_table(
              ["query", "cbo_plan", "cbo_plan_ms", "cbo_exec_ms",
               "before_plan", "before_plan_ms", "before_exec_ms",
               "after_plan", "after_plan_ms", "after_exec_ms",
               "cbo_e2e", "after_e2e", "net_ms", "llm_action", "schedule", "gate",
               "cbo_par", "spec"],
              timing_rows))
    write(out / "opportunity_recovery.md", "\n".join(opp_lines) + "\n")

    # short decision extract
    short_rows = []
    for r in list(ah.values()) + list(th.values()):
        short_rows.append({
            "query_id": r.get("query_id"),
            "prompt": r.get("proposal_prompt_version"),
            "tokens_in": r.get("tokens_in"),
            "tokens_out": r.get("tokens_out"),
            "llm_latency_ms": r.get("llm_latency_ms"),
            "llm_action": r.get("llm_action"),
            "schedule_mode": r.get("schedule_mode"),
            "prefer_s_hat_ms": r.get("prefer_s_hat_ms"),
            "saving_uncertainty_ms": r.get("saving_uncertainty_ms"),
            "gate_reason": r.get("gate_reason"),
            "fallback_reason": r.get("fallback_reason"),
            "cbo_parallel_used": r.get("cbo_parallel_used"),
            "speculative_used": r.get("speculative_used"),
            "selected_plan_id": r.get("selected_plan_id") or r.get("plan_id"),
        })
    write(out / "llm_short_decision.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in short_rows) + ("\n" if short_rows else ""))

    syn_pos = sum(1 for r in syn if r.get("kind") in ("pos", "unit_equiv") and r.get("match"))
    syn_pos_n = sum(1 for r in syn if r.get("kind") in ("pos", "unit_equiv"))
    syn_neg = sum(1 for r in syn if r.get("kind") in ("neg", "e2e_neg", "uncertain") and r.get("match"))
    syn_neg_n = sum(1 for r in syn if r.get("kind") in ("neg", "e2e_neg", "uncertain"))

    write(out / "answers_phase_gates.md", f"""# 本轮问题（comparative_refine_5.md §11）

## 1. 9/9 KEEP 是否由任务语义不完整造成，还是模型/信息限制？

合成诊断（`decision_synthetic_eval`）：清晰正例召回 {syn_pos}/{syn_pos_n}，反例/不确定 KEEP {syn_neg}/{syn_neg_n}。
若合成正例已能选编号而库内仍 KEEP，则更偏 **模型风险偏好/信息不足**；若合成也固定 KEEP，则 **契约/模型理解** 仍不足。
本轮 E2E KEEP 相关行≈{keep_n}；详见逐行 `llm_short_decision.jsonl`。

## 2. 校准对收益差值的误差多大，门槛依据是什么？

见 `saving_residual_validation.md`。门槛维持 min_spec=500 / min_adopt=200，并叠加 `saving_uncertainty_ms`（默认≈残差 MAE 量级 1000）做保守采纳：`S_hat - u`。

## 3. 并行实验中 LLM 实际运行多久、与执行重叠多少？

见 `isolation_timing_audit.md`：`llm_call_ms` 为完整请求墙钟，`llm_await_after_exec_ms` 为执行结束后的剩余等待。重叠 ≈ max(0, call - await_after_exec)（近似）。

## 4. 是否恢复有价值的候选采纳，同时保持反例拒绝能力？

正收益采纳={adopt_pos}；负收益误采纳={adopt_neg}；KEEP/回退≈{keep_n}。
合成侧反例拒绝见上；库内若仍全 KEEP 则机会未恢复。

## 5. KEEP 并行能否减少串行损失，是否引入竞争或浪费？

`schedule_mode=cbo_parallel` 且 `cbo_parallel_used=true` 时，墙钟≈C+max(L,E_c)。
隔离显示部分查询 par 执行变慢（竞争）。投机浪费与 CBO 并行浪费字段见 jsonl。

## 6. 真实 E2E 是否净胜，收益究竟来自哪里？

E2E 胜/负 = {wins}/{losses}。本轮未改公共执行器；变化归因于 v9 语义、不确定性门控与 KEEP∥CBO / 投机调度。
""")

    write(out / "summary.md", f"""# summary — comparative_refine_5

- 协议：`cbo_llm_short_v9` + 合成诊断 + 残差校验 + 隔离计时修正 + KEEP∥CBO 调度
- E2E：胜={wins} 负={losses}；正采纳={adopt_pos} 负采纳={adopt_neg} KEEP≈{keep_n}
- 合成：pos {syn_pos}/{syn_pos_n}；neg-keep {syn_neg}/{syn_neg_n}
- 详见 `timing_plan_exec.md`、`answers_phase_gates.md`、`RUN_CONFIG.md`
""")

    write(out / "RUN_CONFIG.md", """# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build | 见 `identity.txt` |
| Prompt | `cbo_llm_short_v9` |
| Calibrator | `calib_loglin_v1` + uncertainty |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 16 |
| budget | 120000 ms |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal` |
| Protocol | v9 / always-call |
| CBO parallel KEEP | on |
| Speculative | k=1；min_spec=500 |
| Adopt | min_adopt=200；uncertainty≈1000 |
| Validator | indexed；prepare=safety_only |
| Trials | 1；cache=warm |

## 数据集

| 包 | Suite | Manifest |
|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `ais_v1_ready` |

Before 对照：`docs/refine_4`。
""")

    write(out / "README.md", """# docs/refine_5 — comparative_refine_5 交付包

| 文件 | 内容 |
|---|---|
| `decision_contract.md` | 字段与净收益语义 |
| `decision_synthetic_eval.md/jsonl` | 合成正反例 |
| `saving_residual_validation.md` | 收益残差 |
| `isolation_timing_audit.md/jsonl` | 完整请求 vs 剩余等待 |
| `opportunity_recovery.md` | AIS 机会 |
| `paired_e2e.jsonl` / `timing_plan_exec.md` | 九条配对 |
| `summary.md` / `answers_phase_gates.md` | 汇总与六问 |
| `RUN_CONFIG.md` / `identity.txt` | 配置 |
""")
    write(out / "NEXT_OPTIMIZATION.md", """# 下一步（依据 refine_5）

1. 若合成正例通过而库内仍 KEEP：检查提示中净收益算术是否需确定性预计算标签（仍保留 LLM 为信息判断组件）。
2. 若 KEEP∥CBO 仍被 LLM 下界压住短查询：评估单列“可跳过 LLM”协议（不得混入主口径）。
3. 残差 MAE 与机会同量级时：补充代表性开发对，不盲目降门槛。
""")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
