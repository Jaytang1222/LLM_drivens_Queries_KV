#!/usr/bin/env python3
"""Build docs/refine_4 from comparative_refine_4 artifacts."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


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
    ap.add_argument("--out-dir", default="docs/refine_4")
    ap.add_argument("--pull-dir", default="experiments/refine_4_pull")
    ap.add_argument("--before-td", default="docs/refine_3/td_e2e.jsonl")
    ap.add_argument("--before-ais", default="docs/refine_3/ais_e2e.jsonl")
    ap.add_argument("--ais-e2e", default="")
    ap.add_argument("--td-e2e", default="")
    ap.add_argument("--identity", default="")
    ap.add_argument("--paths", default="")
    args = ap.parse_args()

    out = Path(args.out_dir)
    pull = Path(args.pull_dir)
    out.mkdir(parents=True, exist_ok=True)

    ais_path = Path(args.ais_e2e) if args.ais_e2e else pull / "ais_e2e.jsonl"
    td_path = Path(args.td_e2e) if args.td_e2e else pull / "td_e2e.jsonl"
    identity = Path(args.identity) if args.identity else pull / "identity.txt"
    paths = Path(args.paths) if args.paths else pull / "paths.txt"

    id_txt = identity.read_text(encoding="utf-8") if identity.exists() else "(missing)\n"
    paths_txt = paths.read_text(encoding="utf-8") if paths.exists() else "(missing)\n"
    write(out / "identity.txt", id_txt)
    write(out / "paths.txt", paths_txt)

    ais = load_jsonl(ais_path)
    td = load_jsonl(td_path)
    before_ais = load_jsonl(Path(args.before_ais))
    before_td = load_jsonl(Path(args.before_td))
    iso_rows = collect_iso(pull)

    write(out / "ais_e2e.jsonl", "\n".join(json.dumps(r, ensure_ascii=False) for r in ais) + ("\n" if ais else ""))
    write(out / "td_e2e.jsonl", "\n".join(json.dumps(r, ensure_ascii=False) for r in td) + ("\n" if td else ""))
    write(out / "execution_isolation.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in iso_rows) + ("\n" if iso_rows else ""))

    # --- isolation md ---
    iso_lines = [
        "# execution_isolation",
        "",
        "> comparative_refine_4.md §4 — 同计划独立 / 并行执行诊断。",
        "",
        "配置：`fixed-plan` 臂；`KART_FIXED_PLAN_ID∈{P_T,P_TZ}`；"
        "alone=`PARALLEL_LLM=0`；par=`PARALLEL_LLM=1`（dummy short KEEP 与执行并行）。",
        "",
    ]
    by_key: Dict[Tuple[str, str, str], List[dict]] = {}
    for r in iso_rows:
        if str(r.get("arm")) != "fixed-plan":
            continue
        if r.get("error"):
            continue
        plan = str(r.get("fixed_plan_id") or r.get("plan_id") or "?")
        mode = "par" if r.get("parallel_llm") else "alone"
        q = str(r.get("query_id"))
        by_key.setdefault((q, plan, mode), []).append(r)

    table_rows = []
    for (q, plan, mode), rs in sorted(by_key.items()):
        execs = [num(r, "t_exec_ms") for r in rs if num(r, "t_exec_ms") is not None]
        plans = [num(r, "t_plan_ms") for r in rs if num(r, "t_plan_ms") is not None]
        llm = [num(r, "llm_latency_ms") for r in rs if num(r, "llm_latency_ms") is not None]
        if not execs:
            continue
        table_rows.append([
            q, plan, mode, len(execs),
            i(statistics.median(execs)),
            i(min(execs)), i(max(execs)),
            i(statistics.median(plans)) if plans else "",
            i(statistics.median(llm)) if llm else "",
            i(rs[0].get("n_ranges")),
            i(rs[0].get("fetch_chunks")),
        ])
    iso_lines.append(md_table(
        ["query", "plan", "mode", "n", "exec_med", "exec_min", "exec_max",
         "plan_med", "llm_med", "n_ranges", "fetch"],
        table_rows))
    # Branch summary for key AIS pairs
    iso_lines.append("## 分支解读（开发诊断）\n")
    for q in sorted({k[0] for k in by_key}):
        for plan in ("P_T", "P_TZ"):
            alone = by_key.get((q, plan, "alone"), [])
            par = by_key.get((q, plan, "par"), [])
            if not alone:
                continue
            a = statistics.median([num(r, "t_exec_ms") for r in alone if num(r, "t_exec_ms") is not None])
            p = statistics.median([num(r, "t_exec_ms") for r in par if num(r, "t_exec_ms") is not None]) if par else None
            if p is None:
                iso_lines.append(f"- `{q}` / `{plan}` alone exec_med={i(a)}（无并行对照）")
            else:
                delta = p - a
                branch = "并行竞争显著" if delta > max(50.0, 0.15 * a) else "独立/并行接近（计划本身主导）"
                iso_lines.append(
                    f"- `{q}` / `{plan}`: alone={i(a)} par={i(p)} Δ={i(delta)} → {branch}")
    write(out / "execution_isolation.md", "\n".join(iso_lines) + "\n")

    # --- calibration docs ---
    write(out / "calibration_dataset.md", """# calibration_dataset

> comparative_refine_4.md §5 — 标签与在线特征。

## 标签来源

- 主标签：`experiments/opportunity/opportunity-dev-v1/measurements.jsonl` 实测 `t_exec_ms`
- 独立/并行标签：本轮 `execution_isolation.jsonl`（fixed-plan alone vs parallel LLM）
- 保留正/负/无收益；超时缺失不计为赢家

## 在线特征（执行前）

FastCost `CostCard.features`：

- `scan_ranges` / `seek_ranges`
- `fetch_gets`（或 `estimated_candidate_chunks` 回退）
- `estimated_index_rows` / `decode_rows`

模型：`pred = b0 + bR·log1p(ranges) + bF·log1p(fetch) + bI·log1p(index)`  
`S_hat = pred(cbo) - pred(p)`（截距在差分中抵消）

## 系数（开发拟合，非 holdout）

| coef | value |
|---|---|
| b0 | -4368.954 |
| b_ranges | 616.587 |
| b_fetch | 211.029 |
| b_index | 142.476 |
| n | 958 |
| MAE (abs exec) | ≈1043 ms |

版本串：`calib_loglin_v1`。九条开发查询不得改名为未知 holdout。
""")

    # calib validation from e2e
    hyb_ais = arm_map(ais, "cbo-llm-proposal")
    cbo_ais = arm_map(ais, "cbo")
    hyb_td = arm_map(td, "cbo-llm-proposal")
    cbo_td = arm_map(td, "cbo")
    all_q = sorted(set(hyb_ais) | set(hyb_td))

    adopt_neg = 0
    adopt_pos = 0
    gate_keeps = 0
    slow_adopts = []
    ais_keep_adv = []
    for q, h in list(hyb_ais.items()) + list(hyb_td.items()):
        c = cbo_ais.get(q) or cbo_td.get(q)
        if not c:
            continue
        sel = h.get("selected_plan_id") or h.get("plan_id")
        cbo_pid = h.get("cbo_plan_id") or c.get("plan_id")
        he = num(h, "t_exec_ms")
        ce = num(c, "t_exec_ms")
        if h.get("gate_fallback") or h.get("fallback_reason") == "gate_negative_s" or h.get("llm_action") == "keep_cbo":
            if sel == cbo_pid or h.get("fallback_reason") in ("llm_keep_cbo", "gate_negative_s") or h.get("gate_reason"):
                gate_keeps += 1
        if sel and cbo_pid and sel != cbo_pid and he is not None and ce is not None:
            if he > ce:
                adopt_neg += 1
                slow_adopts.append((q, sel, i(he), i(ce)))
            else:
                adopt_pos += 1
                if q.startswith("ais_topk"):
                    ais_keep_adv.append((q, sel, i(he), i(ce)))

    write(out / "calibration_validation.md", f"""# calibration_validation

> comparative_refine_4.md §5.4

## 开发集门控（本轮配对）

| 指标 | 值 |
|---|---|
| 查询数 | {len(all_q)} |
| 正收益采纳 | {adopt_pos} |
| 负收益误采纳 | {adopt_neg} |
| KEEP/门控回退行 | {gate_keeps} |
| min_spec_s_ms | 500 |
| min_adopt_s_ms | 200 |

负收益误采纳：{slow_adopts or "无"}

AIS Top-K 正收益保留：{ais_keep_adv or "无（或未采纳异于 CBO）"}

## 缺口

- 拟合用 opportunity-dev 普查，非分组 holdout；泛化未证明。
- 并行竞争惩罚未单独建模；若 isolation 显示显著竞争，投机标签需重校准。
""")

    # --- short decision ---
    short_rows = []
    for r in list(hyb_ais.values()) + list(hyb_td.values()):
        short_rows.append({
            "query_id": r.get("query_id"),
            "prompt": r.get("proposal_prompt_version"),
            "tokens_in": r.get("tokens_in"),
            "tokens_out": r.get("tokens_out"),
            "llm_latency_ms": r.get("llm_latency_ms"),
            "llm_action": r.get("llm_action"),
            "proposal_plan_id": r.get("proposal_plan_id"),
            "selected_plan_id": r.get("selected_plan_id") or r.get("plan_id"),
            "gate_reason": r.get("gate_reason"),
            "fallback_reason": r.get("fallback_reason"),
            "prefer_s_hat_ms": r.get("prefer_s_hat_ms"),
            "selected_s_hat_ms": r.get("selected_s_hat_ms"),
            "speculative_started": r.get("speculative_started"),
            "speculative_used": r.get("speculative_used"),
        })
    write(out / "llm_short_decision.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in short_rows) + ("\n" if short_rows else ""))
    lats = [num(r, "llm_latency_ms") for r in short_rows if num(r, "llm_latency_ms") is not None]
    tin = [num(r, "tokens_in") for r in short_rows if num(r, "tokens_in") is not None]
    tout = [num(r, "tokens_out") for r in short_rows if num(r, "tokens_out") is not None]
    write(out / "llm_short_decision.md", f"""# llm_short_decision

> comparative_refine_4.md §6 — `cbo_llm_short_v8`

协议：`{{\"choice\":\"KEEP\"}}` 或 `{{ \"choice\": N }}`（编号绑定请求内 whitelist）。

| 指标 | 值 |
|---|---|
| n | {len(short_rows)} |
| llm_latency med/min/max | {i(statistics.median(lats)) if lats else ''}/{i(min(lats)) if lats else ''}/{i(max(lats)) if lats else ''} |
| tokens_in med | {i(statistics.median(tin)) if tin else ''} |
| tokens_out med | {i(statistics.median(tout)) if tout else ''} |
| max_tokens | 16 |

目标稳态 &lt;1s 仍为待验证；若 med 仍数秒，则当前硬件+模型下短协议未进入 AIS 预算。
""")

    # --- paired timing ---
    def pack_maps(rows):
        return arm_map(rows, "cbo"), arm_map(rows, "cbo-llm-proposal")

    b_cbo_a, b_hyb_a = pack_maps(before_ais)
    b_cbo_t, b_hyb_t = pack_maps(before_td)
    a_cbo, a_hyb = pack_maps(ais)
    t_cbo, t_hyb = pack_maps(td)

    timing_rows = []
    paired = []
    wins = losses = 0
    for label, bc, bh, ac, ah in (
        ("AIS", b_cbo_a, b_hyb_a, a_cbo, a_hyb),
        ("TD", b_cbo_t, b_hyb_t, t_cbo, t_hyb),
    ):
        for q in sorted(set(ac) | set(ah) | set(bc) | set(bh)):
            c = ac.get(q) or {}
            h = ah.get(q) or {}
            cb = bc.get(q) or {}
            hb = bh.get(q) or {}
            if not c or not h:
                continue
            ce, cp = num(c, "t_exec_ms"), num(c, "t_plan_ms")
            he, hp = num(h, "t_exec_ms"), num(h, "t_plan_ms")
            cwall = num(c, "t_e2e_ms", "t_wall_ms")
            hwall = num(h, "t_e2e_ms", "t_wall_ms")
            net = None
            if cwall is not None and hwall is not None:
                net = cwall - hwall
                if net > 0:
                    wins += 1
                else:
                    losses += 1
            timing_rows.append([
                q,
                c.get("plan_id"), i(cp), i(ce),
                (hb.get("selected_plan_id") or hb.get("plan_id")), i(num(hb, "t_plan_ms")), i(num(hb, "t_exec_ms")),
                (h.get("selected_plan_id") or h.get("plan_id")), i(hp), i(he),
                i(cwall), i(hwall), i(net),
                h.get("llm_action"), h.get("gate_reason") or h.get("fallback_reason"),
            ])
            paired.append({
                "query_id": q, "pack": label,
                "cbo_plan": c.get("plan_id"), "cbo_plan_ms": cp, "cbo_exec_ms": ce, "cbo_e2e_ms": cwall,
                "before_plan": hb.get("selected_plan_id") or hb.get("plan_id"),
                "before_plan_ms": num(hb, "t_plan_ms"), "before_exec_ms": num(hb, "t_exec_ms"),
                "after_plan": h.get("selected_plan_id") or h.get("plan_id"),
                "after_plan_ms": hp, "after_exec_ms": he, "after_e2e_ms": hwall,
                "net_vs_cbo_ms": net,
                "llm_latency_ms": h.get("llm_latency_ms"),
                "tokens_in": h.get("tokens_in"), "tokens_out": h.get("tokens_out"),
                "gate_reason": h.get("gate_reason"), "fallback_reason": h.get("fallback_reason"),
                "ok_oracle": h.get("ok_oracle"),
            })

    write(out / "paired_e2e.jsonl",
          "\n".join(json.dumps(r, ensure_ascii=False) for r in paired) + ("\n" if paired else ""))
    write(out / "timing_plan_exec.md", "# timing_plan_exec\n\n"
          "列：CBO / 优化前(refine_3) / 优化后(refine_4) 的 plan 与 exec；E2E 净收益=CBO−hybrid。\n\n"
          + md_table(
              ["query", "cbo_plan", "cbo_plan_ms", "cbo_exec_ms",
               "before_plan", "before_plan_ms", "before_exec_ms",
               "after_plan", "after_plan_ms", "after_exec_ms",
               "cbo_e2e", "after_e2e", "net_ms", "llm_action", "gate"],
              timing_rows))

    write(out / "RUN_CONFIG.md", f"""# 实验运行配置（核查）

## 主机与构建

| 项 | 值 |
|---|---|
| Host | `startserver02`（`kart-lab` / `/home/tyq` only） |
| Workspace | `/home/tyq/projects/llm-kv` |
| Build | 见 `identity.txt` |
| Prompt | `cbo_llm_short_v8` |
| Calibrator | `calib_loglin_v1` |

## LLM

| 项 | 值 |
|---|---|
| Provider | 本地 Ollama |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Model | `qwen2.5:1.5b-instruct` |
| max_tokens | 16 |
| budget | 120000 ms |
| 预热 | 跑前 `/api/generate` 一次 |

## 臂与开关

| 项 | 值 |
|---|---|
| Arms | `cbo` vs `cbo-llm-proposal`（无计划决策缓存） |
| Protocol | short / always-call |
| Prepare | `safety_only` |
| Speculative | k=1；`min_spec_s=500` |
| Adopt gate | `min_adopt_s=200` |
| Validator | `indexed` |
| Trials | 1；cache=`warm`；Oracle 必过 |

## 数据集

| 包 | Suite | Workload | Manifest |
|---|---|---|---|
| T-Drive 4q | `e2e-cbo-llm-overhead-v1` | `bound_ir_cbo_llm_overhead_v1.json` | `tdrive_v1_ready` |
| AIS 5q | `e2e-ais-llm-overhead-v1` | `bound_ir_ais_opportunity_v1.json` | `ais_v1_ready` |

Before 对照：`docs/refine_3`（第三轮同包）。
""")

    write(out / "summary.md", f"""# summary — comparative_refine_4

- 实现：`BenefitCalibrator` + short protocol v8 + 两级门控 + `fixed-plan` 诊断臂
- 配对 E2E：E2E 胜={wins} 负={losses}（相对同跑 CBO）
- 负收益误采纳={adopt_neg}；正收益采纳={adopt_pos}
- 详见 `timing_plan_exec.md`、`execution_isolation.md`、`llm_short_decision.md`
""")

    write(out / "answers_phase_gates.md", f"""# 本轮问题（comparative_refine_4.md §11）

## execute 变慢来自计划本身、并行推理还是测量波动？

见 `execution_isolation.md`：对 `P_T`/`P_TZ` 比较 alone vs parallel。
若 alone≈par 且与 refine_3 hybrid exec 同量级 → **计划本身**；若 par≫alone → **并行竞争**。

## 哪些特征能预测范围减少与回表增加之间的净代价？

开发拟合使用 `log1p(scan_ranges)`、`log1p(fetch_gets)`、`log1p(index_rows)`（`calib_loglin_v1`）。
差分 `S_hat` 用于投机/采纳门控；非 holdout 证明。

## 校准后是否减少慢计划采纳并保留 AIS 优势？

负收益误采纳={adopt_neg}；正收益采纳={adopt_pos}；KEEP/门控回退≈{gate_keeps}。
AIS Top-K 保留情况：{ais_keep_adv or "见 timing 表"}。

## 短决策是否在降低延迟时保留有用选择能力？

`llm_short_decision.md`：latency med={i(statistics.median(lats)) if lats else "n/a"}，
tokens_out med={i(statistics.median(tout)) if tout else "n/a"}。合法 choice 解析后经 whitelist+门控。

## 净收益主要由校准、LLM、调度还是公共执行优化贡献？

本轮未改公共执行器；validator indexed 两臂共用。相对 CBO 的变化归因于 **短协议 LLM + 校准门控/投机**。
E2E 胜/负 = {wins}/{losses}。

## 新配对是否支持继续做未知查询验证？

仅当开发净收益可重复且 LLM 进入预算窗口。当前：胜={wins} 负={losses} —
{"可讨论冻结后扩未知集" if wins > 0 else "尚未满足推进条件，先不扩全量"}。
""")

    write(out / "README.md", """# docs/refine_4 — comparative_refine_4 交付包

| 文件 | 内容 |
|---|---|
| `execution_isolation.md/jsonl` | 同计划独立/并行 |
| `calibration_dataset.md` | 标签与特征 |
| `calibration_validation.md` | 门控验收 |
| `llm_short_decision.md/jsonl` | 短协议 |
| `paired_e2e.jsonl` / `timing_plan_exec.md` | 九条配对 |
| `summary.md` / `answers_phase_gates.md` | 汇总与六问 |
| `RUN_CONFIG.md` / `identity.txt` | 配置与构建身份 |
""")

    write(out / "NEXT_OPTIMIZATION.md", """# 下一步（依据 refine_4）

1. 若短协议 LLM 仍 &gt;2s：换部署/量化或接受跳过策略（须单列协议名）。
2. 若 isolation 显示并行竞争：给投机路径加竞争惩罚或资源隔离并重校准。
3. 开发净胜后再冻结阈值，生成未按胜负筛选的未知查询集。
""")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
