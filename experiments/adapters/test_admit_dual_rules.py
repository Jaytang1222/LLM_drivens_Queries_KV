import json

from admit_dual_rules import (
    ROOT,
    admit_from_pairs,
    alias_cbo_plan,
    is_simple_eligible,
    is_tiny,
    MIN_SUPPORT,
)


def test_tiny_box():
    q = {"temporal": {"start_ms": 0, "end_ms": 400000}, "spatial": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 100}}
    assert is_tiny(q)


def test_simple_eligible_excludes_complex_and_oversized():
    ban = {"c3_tz_c__s50_t85"}
    assert not is_simple_eligible("c3_tz_a", ban)
    assert not is_simple_eligible("a2_tz_hard_a", ban)
    assert not is_simple_eligible("st_ll_1", ban)
    compact = {
        "query_id": "st_grid_1",
        "temporal": {"start_ms": 0, "end_ms": 600_000},
        "spatial": {"min_x": 0, "min_y": 0, "max_x": 800, "max_y": 800},
    }
    assert is_simple_eligible("st_grid_1", ban, compact)


def test_simple_and_complex_workloads_disjoint():
    simple = ROOT / "experiments/workloads/bound_ir_simple_v1.json"
    complex_p = ROOT / "experiments/workloads/bound_ir_complex_v1.json"
    if not simple.is_file() or not complex_p.is_file():
        return
    s = {q["query_id"] for q in json.loads(simple.read_text(encoding="utf-8"))["queries"]}
    c = {q["query_id"] for q in json.loads(complex_p.read_text(encoding="utf-8"))["queries"]}
    assert len(s) >= 28
    assert s.isdisjoint(c)


def test_alias_cbo_plan_families():
    assert alias_cbo_plan("P_XZTXZ") == "P_TZ"
    assert alias_cbo_plan("P_XZTZ") == "P_TZ"
    assert alias_cbo_plan("P_XZ") == "P_Z"


def test_admit_three_same_program():
    base = {
        "source_sha256": "a" * 64,
        "training_queries": ["t1"],
        "rules": [{"id": "keep", "set": {"access": "keep"}, "scope": {"manifest_id": "m"}}],
    }
    rows = []
    for i in range(MIN_SUPPORT):
        qid = "q%d" % i
        feats = {"cbo_plan": "P_TZ", "chunks_keep": 8000 + i, "z_ranges": 70, "metric": "DTW"}
        rows.append({"arm": "cbo", "query_id": qid, "t_e2e_ms": 1000})
        rows.append({
            "arm": "kart",
            "query_id": qid,
            "t_e2e_ms": 700,
            "final_selection_source": "offline_llm_rule",
            "gate_decision": "OFFLINE_RULE",
            "llm_program": {"access": "drop_spatial_branch"},
            "llm_context": {"features": feats},
        })
    res = admit_from_pairs(base, rows)
    assert len(res["added"]) == 1
    assert res["doc"]["rules"][-1]["support"] == MIN_SUPPORT
    assert res["doc"]["rules"][-1]["admission"] == "train_measured_v1"
    assert ["metric", "==", "DTW"] in res["doc"]["rules"][-1]["when"]
    assert ["cbo_plan", "==", "P_TZ"] in res["doc"]["rules"][-1]["when"]
    assert MIN_SUPPORT >= 3


def test_admit_splits_by_cbo_plan():
    """Same set with mixed cbo_plan admits per-plan when each plan has support≥3."""
    base = {"source_sha256": "a" * 64, "rules": [{"id": "x", "set": {"noop": "1"}, "scope": {"manifest_id": "m"}}]}
    rows = []
    for plan, prefix in (("P_TZ", "t"), ("P_H", "h")):
        for i in range(MIN_SUPPORT):
            qid = "%s%d" % (prefix, i)
            rows.append({"arm": "cbo", "query_id": qid, "t_e2e_ms": 900})
            rows.append({
                "arm": "kart", "query_id": qid, "t_e2e_ms": 400,
                "final_selection_source": "force", "gate_decision": "FORCE",
                "program": {"access": "drop_spatial_branch", "meta_probe": "on"},
                "llm_context": {"features": {"cbo_plan": plan, "chunks_keep": 5000}},
            })
    res = admit_from_pairs(base, rows)
    assert len(res["added"]) == 2
    whens = {tuple(w for w in r["when"] if w[0] == "cbo_plan")[0][2]
             for r in res["doc"]["rules"] if r["id"] in res["added"]}
    assert whens == {"P_TZ", "P_H"}


def test_no_admit_on_cbo_keep():
    base = {"rules": [], "source_sha256": "a" * 64}
    rows = []
    for i in range(5):
        rows.append({"arm": "cbo", "query_id": "q%d" % i, "t_e2e_ms": 100})
        rows.append({"arm": "kart", "query_id": "q%d" % i, "t_e2e_ms": 90, "gate_decision": "SKIP",
                     "final_selection_source": "cbo"})
    assert admit_from_pairs(base, rows)["added"] == []


def test_admit_from_force_program():
    base = {"source_sha256": "a" * 64, "rules": [], "scope_dummy": True}
    base["rules"] = [{"id": "x", "set": {"noop": "1"}, "scope": {"manifest_id": "m"}}]
    rows = []
    for i in range(MIN_SUPPORT):
        qid = "f%d" % i
        rows.append({"arm": "cbo", "query_id": qid, "t_e2e_ms": 800})
        rows.append({
            "arm": "kart", "query_id": qid, "t_e2e_ms": 400,
            "final_selection_source": "force", "gate_decision": "FORCE",
            "program": {"fetch_batch": "b2000"},
            "llm_context": {"features": {"cbo_plan": "P_T", "chunks_keep": 2000}},
        })
    res = admit_from_pairs(base, rows)
    assert res["added"]
    assert res["doc"]["rules"][-1]["set"] == {"fetch_batch": "b2000"}
