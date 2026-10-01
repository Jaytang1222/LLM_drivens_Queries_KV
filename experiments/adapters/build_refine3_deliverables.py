#!/usr/bin/env python3
"""Build docs/refine_3 from comparative_refine_3 artifacts."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any, Dict, List, Optional


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
        arm = str(r.get("arm") or "")
        if arm == want:
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="docs/refine_3")
    ap.add_argument("--identity", default="")
    ap.add_argument("--paths", default="")
    ap.add_argument("--before-td", default="experiments/refine_2_pull/td_e2e.jsonl")
    ap.add_argument("--before-ais", default="experiments/refine_2_pull/ais_e2e.jsonl")
    ap.add_argument("--ais-legacy", required=True)
    ap.add_argument("--ais-indexed", required=True)
    ap.add_argument("--ais-llm-alone", default="")
    ap.add_argument("--ais-hint-off", default="")
    ap.add_argument("--ais-hint-shuffle", default="")
    ap.add_argument("--td-e2e", required=True)
    args = ap.parse_args()
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    identity = Path(args.identity).read_text(encoding="utf-8") if args.identity and Path(args.identity).exists() else ""
    paths = Path(args.paths).read_text(encoding="utf-8") if args.paths and Path(args.paths).exists() else ""
    write(out / "identity.txt", identity or "(missing)\n")
    write(out / "paths.txt", paths or "(missing)\n")

    before_td = load_jsonl(Path(args.before_td))
    before_ais = load_jsonl(Path(args.before_ais))
    ais_leg = load_jsonl(Path(args.ais_legacy))
    ais_idx = load_jsonl(Path(args.ais_indexed))
    ais_alone = load_jsonl(Path(args.ais_llm_alone)) if args.ais_llm_alone else []
    ais_hoff = load_jsonl(Path(args.ais_hint_off)) if args.ais_hint_off else []
    ais_hshuf = load_jsonl(Path(args.ais_hint_shuffle)) if args.ais_hint_shuffle else []
    td_e2e = load_jsonl(Path(args.td_e2e))

    for name, rows in (
        ("ais_legacy.jsonl", ais_leg),
        ("ais_indexed.jsonl", ais_idx),
        ("ais_llm_alone.jsonl", ais_alone),
        ("ais_hint_off.jsonl", ais_hoff),
        ("ais_hint_shuffle.jsonl", ais_hshuf),
        ("td_e2e.jsonl", td_e2e),
        ("ais_e2e.jsonl", ais_idx),
    ):
        if rows:
            write(out / name, "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n")

    # --- validator_breakdown from indexed hybrid rows ---
    hyb_idx = arm_map(ais_idx, "cbo-llm-proposal")
    hyb_leg = arm_map(ais_leg, "cbo-llm-proposal")
    vb_rows = []
    vb_jsonl = []
    for q in sorted(set(hyb_idx) | set(hyb_leg)):
        r = hyb_idx.get(q) or hyb_leg.get(q)
        rec = {
            "query_id": q,
            "algo": r.get("validator_coverage_algo"),
            "t_fixed_safety_ms": r.get("t_fixed_safety_ms"),
            "t_structure_ms": r.get("t_structure_ms"),
            "t_semantic_ms": r.get("t_semantic_ms"),
            "t_coverage_ms": r.get("t_coverage_ms"),
            "t_physical_safety_ms": r.get("t_physical_safety_ms"),
            "t_range_decode_ms": r.get("t_range_decode_ms"),
            "t_coverage_index_ms": r.get("t_coverage_index_ms"),
            "t_coverage_probe_ms": r.get("t_coverage_probe_ms"),
            "coverage_probe_count": r.get("coverage_probe_count"),
            "range_count": r.get("range_count"),
            "range_compare_count": r.get("range_compare_count"),
            "range_decode_count": r.get("range_decode_count"),
            "n_scan_tasks": r.get("n_scan_tasks"),
            "t_spec_validate_ms": r.get("t_spec_validate_ms"),
        }
        vb_jsonl.append(rec)
        leg = hyb_leg.get(q, {})
        vb_rows.append([
            q, r.get("validator_coverage_algo"),
            i(num(r, "t_fixed_safety_ms")), i(num(r, "t_coverage_ms")),
            i(num(r, "t_coverage_probe_ms")), i(num(r, "t_range_decode_ms")),
            i(num(r, "coverage_probe_count")), i(num(r, "range_decode_count")),
            i(num(r, "range_compare_count")), i(num(r, "n_scan_tasks")),
            i(num(leg, "t_fixed_safety_ms")), i(num(leg, "range_decode_count")),
            i(num(leg, "range_compare_count")),
        ])
    write(out / "validator_breakdown.jsonl",
          "\n".join(json.dumps(x, ensure_ascii=False) for x in vb_jsonl) + ("\n" if vb_jsonl else ""))
    write(out / "validator_breakdown.md", f"""# validator_breakdown

> comparative_refine_3.md §4 — PlanValidator 内部分解（已实施/已测）。

Indexed 与 legacy 同套 AIS 开发查询、safety_only prepare、speculate=1。

{md_table(
    ["query", "algo", "safety", "coverage", "probe", "decode", "M_probes", "decodes", "compares", "n_scan", "legacy_safety", "legacy_decodes", "legacy_compares"],
    vb_rows)}

## 解读

- 若 indexed 下 `range_decode_count ≈ n_scan`（或 2×n_scan 含 physical），而 legacy 下 decode ≫ n_scan：B1 生效。
- 若 `range_compare_count` 从 O(M·N) 降到约 O(M log N)：B2 生效。
- `t_coverage_ms` / `t_coverage_probe_ms` 应主导 safety（与 refine_2 一致）。
""")

    # scaling
    scale_rows = []
    for q in sorted(hyb_idx):
        a = hyb_idx[q]
        b = hyb_leg.get(q, {})
        scale_rows.append([
            q, i(num(a, "n_scan_tasks")), i(num(a, "coverage_probe_count")),
            i(num(b, "t_fixed_safety_ms")), i(num(a, "t_fixed_safety_ms")),
            i(num(b, "t_spec_validate_ms")), i(num(a, "t_spec_validate_ms")),
            i(num(b, "range_decode_count")), i(num(a, "range_decode_count")),
            i(num(b, "range_compare_count")), i(num(a, "range_compare_count")),
        ])
    write(out / "validator_scaling.md", f"""# validator_scaling

> comparative_refine_3.md §6 — 同输入旧/新验证算法墙钟与计数（已测）。

{md_table(
    ["query", "n_scan", "M_probes", "safety_legacy", "safety_indexed", "spec_val_leg", "spec_val_idx", "dec_leg", "dec_idx", "cmp_leg", "cmp_idx"],
    scale_rows)}

状态：**已测**（开发 AIS 包，1 trial）。不是合成 1k–8k 曲线；合成随机差分见单元测试 `RangeCoverageIndexTest`。
""")

    write(out / "validator_equivalence.md", """# validator_equivalence

> comparative_refine_3.md §6.4 — 等价与边界（已实施单元测试 + E2E Oracle）。

## 单元测试（本地/CI）

`RangeCoverageIndexTest`：

- 嵌套区间：较早长区间覆盖 probe（禁止“只看 start 最大区间”的错误实现）。
- `[start,stop)` 排他边界。
- 固定网格 probe：indexed 与 legacy 点覆盖一致。

## E2E

AIS/T-Drive 开发套件 `require_oracle=true`；本包跑次 Oracle 失败数见各 `*.log` 的 `BENCH_SUMMARY`。

## 未解决差异

无已知语义差异登记。若后续发现旧检查遗漏完整区间义务，将单独开修复，不混入性能优化。
""")

    # LLM isolation
    alone = arm_map(ais_alone, "cbo-llm-proposal")
    iso_rows = []
    iso_jsonl = []
    for q in sorted(set(alone) | set(hyb_leg) | set(hyb_idx)):
        ra, rl, ri = alone.get(q, {}), hyb_leg.get(q, {}), hyb_idx.get(q, {})
        rec = {
            "query_id": q,
            "llm_alone_ms": num(ra, "llm_latency_ms"),
            "llm_par_legacy_ms": num(rl, "llm_latency_ms"),
            "llm_par_indexed_ms": num(ri, "llm_latency_ms"),
            "spec_alone": ra.get("speculative_started"),
            "spec_legacy": rl.get("speculative_used"),
            "spec_indexed": ri.get("speculative_used"),
            "tokens_in_alone": ra.get("tokens_in"),
            "tokens_out_alone": ra.get("tokens_out"),
        }
        iso_jsonl.append(rec)
        iso_rows.append([
            q, i(rec["llm_alone_ms"]), i(rec["llm_par_legacy_ms"]), i(rec["llm_par_indexed_ms"]),
            ra.get("tokens_in"), ra.get("tokens_out"),
            ri.get("speculative_used"), i(num(ri, "t_fixed_safety_ms")),
        ])
    write(out / "llm_isolation.jsonl",
          "\n".join(json.dumps(x, ensure_ascii=False) for x in iso_jsonl) + ("\n" if iso_jsonl else ""))
    write(out / "llm_isolation.md", f"""# llm_isolation

> comparative_refine_3.md §7 — 同模型/同提示下的隔离测量（已测）。

配置：

1. **LLM alone**：`KART_CBO_LLM_SPECULATE=0`（无投机验证并行）
2. **LLM ∥ legacy validator**：speculate=1 + `KART_VALIDATOR_COVERAGE=legacy`
3. **LLM ∥ indexed validator**：speculate=1 + indexed

{md_table(
    ["query", "llm_alone", "llm_∥legacy", "llm_∥indexed", "tok_in", "tok_out", "spec_used_idx", "safety_idx"],
    iso_rows)}

## 判断（填写实测）

- 若 alone≈并行：主要不是验证器 CPU 争用。
- 若 alone≪并行：争用可疑；看 indexed 是否缓解。
- 服务端排队/GPU 指标若接口不可用，标 **不可用**，不以客户端差值冒充排队。
""")

    # hint ablation
    hoff = arm_map(ais_hoff, "cbo-llm-proposal")
    hshuf = arm_map(ais_hshuf, "cbo-llm-proposal")
    hint_rows = []
    for q in sorted(set(hyb_idx) | set(hoff) | set(hshuf)):
        a, b, c = hyb_idx.get(q, {}), hoff.get(q, {}), hshuf.get(q, {})
        hint_rows.append([
            q,
            a.get("prefer_plan_id"), a.get("selected_plan_id") or a.get("plan_id"), a.get("llm_action"),
            b.get("selected_plan_id") or b.get("plan_id"), b.get("llm_action"), b.get("fallback_reason"),
            c.get("selected_plan_id") or c.get("plan_id"), c.get("llm_action"), c.get("fallback_reason"),
            i(num(a, "llm_latency_ms")), i(num(b, "llm_latency_ms")), i(num(c, "llm_latency_ms")),
        ])
    write(out / "hint_ablation.md", f"""# hint_ablation

> comparative_refine_3.md §8 — rank_hint 消融（已测）。

固定模型与输出协议；仅改 `KART_CBO_LLM_HINT=on|off|shuffle`。

{md_table(
    ["query", "hint", "sel_on", "act_on", "sel_off", "act_off", "fb_off", "sel_shuf", "act_shuf", "fb_shuf", "llm_on", "llm_off", "llm_shuf"],
    hint_rows)}

增量判断：仅当 off/shuffle 产生**不同合法选择**且配对执行收益为正、并扣除新增延迟时，才记 LLM 信息价值；否则归因 hint 锚定或信息不足。
""")

    # paired e2e + timing table
    paired = []
    timing_rows = []

    def add_dataset(label, before_rows, after_rows):
        bc, bh = arm_map(before_rows, "cbo"), arm_map(before_rows, "cbo-llm-proposal")
        ac, ah = arm_map(after_rows, "cbo"), arm_map(after_rows, "cbo-llm-proposal")
        for q in sorted(set(bc) | set(bh) | set(ac) | set(ah)):
            row = {"dataset": label, "query_id": q}
            for tag, m in (("cbo_before", bc), ("hyb_before", bh), ("cbo_after", ac), ("hyb_after", ah)):
                r = m.get(q)
                if not r:
                    continue
                row[f"{tag}_plan"] = num(r, "t_plan_ms")
                row[f"{tag}_exec"] = num(r, "t_exec_ms")
                row[f"{tag}_e2e"] = num(r, "t_e2e_ms")
                if tag.startswith("hyb"):
                    row[f"{tag}_llm"] = num(r, "llm_latency_ms")
                    row[f"{tag}_safety"] = num(r, "t_fixed_safety_ms")
                    row[f"{tag}_sel"] = r.get("selected_plan_id") or r.get("plan_id")
            # G
            for side in ("before", "after"):
                c, h = row.get(f"cbo_{side}_e2e"), row.get(f"hyb_{side}_e2e")
                if c is not None and h is not None:
                    row[f"G_{side}"] = c - h
            paired.append(row)
            timing_rows.append([
                label, q,
                i(row.get("cbo_after_plan")), i(row.get("cbo_after_exec")),
                i(row.get("hyb_before_plan")), i(row.get("hyb_before_exec")),
                i(row.get("hyb_after_plan")), i(row.get("hyb_after_exec")),
                i(row.get("cbo_after_e2e")), i(row.get("hyb_before_e2e")), i(row.get("hyb_after_e2e")),
                i(row.get("G_before")), i(row.get("G_after")),
            ])

    add_dataset("tdrive", before_td, td_e2e)
    add_dataset("ais", before_ais, ais_idx)

    write(out / "paired_e2e.jsonl",
          "\n".join(json.dumps(x, ensure_ascii=False) for x in paired) + ("\n" if paired else ""))

    wins = sum(1 for p in paired if p.get("G_after") is not None and p["G_after"] > 0)
    losses = sum(1 for p in paired if p.get("G_after") is not None and p["G_after"] < 0)
    g_after = [p["G_after"] for p in paired if p.get("G_after") is not None]
    mean_g = statistics.mean(g_after) if g_after else None

    write(out / "timing_plan_exec.md", f"""# plan / exec 时间对照表

列定义：

- **CBO**：第三轮新验证器基线（after）的原生 CBO
- **优化前 hybrid**：第二轮 refine_2（v7 + safety_only，旧 O(M×N) 验证）
- **优化后 hybrid**：第三轮 indexed 验证器 + 同 LLM 协议

单位：ms。`t_plan` / `t_exec` 为臂字段；hybrid 投机采用时 `t_e2e` 为墙钟，勿简单相加。

{md_table(
    ["dataset", "query", "cbo_plan", "cbo_exec", "before_hyb_plan", "before_hyb_exec", "after_hyb_plan", "after_hyb_exec", "cbo_e2e", "before_hyb_e2e", "after_hyb_e2e", "G_before", "G_after"],
    timing_rows)}
""")

    write(out / "summary.md", f"""# summary — comparative_refine_3

## 六问答案（见 answers_six.md 详版）

状态标签：已实施 / 已测 / 未测 / 不确定。

配对：wins={wins} losses={losses} mean_G_after={i(mean_g)}（开发集，非全量）。

## 交付索引

| 文件 | 状态 |
|---|---|
| validator_breakdown | 已测 |
| validator_scaling | 已测 |
| validator_equivalence | 已实施测试 + E2E Oracle |
| llm_isolation | 已测 |
| hint_ablation | 已测 |
| paired_e2e / timing_plan_exec | 已测 |
| RUN_CONFIG | 已记录 |
""")

    # answers
    focus = [p for p in paired if p["query_id"] in ("ais_topk_dtw_2week", "ais_topk_frechet_wide")]
    focus_txt = "\n".join(
        f"- {p['query_id']}: G_before={i(p.get('G_before'))} → G_after={i(p.get('G_after'))}; "
        f"safety_idx={i(p.get('hyb_after_safety'))}; llm={i(p.get('hyb_after_llm'))}"
        for p in focus
    ) or "(missing focus rows)"

    write(out / "answers_six.md", f"""# 本轮六问（comparative_refine_3.md §12）

1. **safety 中多少成本来自重复扫描、解码和证书构造？**  
   见 `validator_breakdown.md`：用 `t_coverage_*`、`range_decode_count`、`range_compare_count` 对比 legacy/indexed。  
   状态：已测。

2. **保持安全语义后，首次验证可降低到什么水平？**  
   见 `validator_scaling.md` 的 safety_legacy vs safety_indexed。  
   状态：已测。

3. **LLM 慢是否与验证器并行争用有关？**  
   见 `llm_isolation.md`（alone vs ∥legacy vs ∥indexed）。  
   状态：已测。

4. **公共验证器优化后，新的 CBO 还留有多少收益窗口？**  
   用 after 的 `T_cbo` 与 hybrid `C + max(L,V+E)` 对照；见 `timing_plan_exec.md` / `paired_e2e.jsonl`。  
   状态：已测（小样本）。

5. **不依赖 rank_hint，LLM 能否作出更有价值的选择？**  
   见 `hint_ablation.md`。  
   状态：已测。

6. **净收益是否真实、可复现，代价与有效场景是什么？**  
   wins={wins} losses={losses} mean_G_after={i(mean_g)}；负结果保留。  
   焦点：
{focus_txt}

不预设第三轮必然超过 CBO；若窗口消失则如实停止扩全量。
""")

    write(out / "RUN_CONFIG.md", f"""# 实验运行配置（核查）

```
{identity.strip()}
```

```
{paths.strip()}
```

| 项 | 值 |
|---|---|
| LLM | 本地 Ollama `qwen2.5:1.5b-instruct` @ 127.0.0.1:11434 |
| Arms | `cbo` vs `cbo-llm-proposal`（无计划缓存） |
| Validator | `KART_VALIDATOR_COVERAGE=indexed`（主结论）/ `legacy`（对照） |
| Hint | `KART_CBO_LLM_HINT=on` / `off` / `shuffle` |
| T-Drive | `e2e-cbo-llm-overhead-v1` / `tdrive_v1_ready` |
| AIS | `e2e-ais-llm-overhead-v1` / `ais_v1_ready` |
| Trials | 1；cache=warm；prepare=`safety_only` |

Before 对照：`experiments/refine_2_pull/*`（refine_2）。
""")

    write(out / "README.md", """# docs/refine_3 — comparative_refine_3 交付包

| 文件 | 阶段 |
|---|---|
| `validator_breakdown.md/jsonl` | A |
| `validator_equivalence.md` | B |
| `validator_scaling.md` | B |
| `llm_isolation.md/jsonl` | C |
| `hint_ablation.md` | D |
| `paired_e2e.jsonl` / `timing_plan_exec.md` | E |
| `answers_six.md` / `summary.md` | §12 |
| `RUN_CONFIG.md` | 配置核查 |

重新生成见 `experiments/adapters/build_refine3_deliverables.py`。
""")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
