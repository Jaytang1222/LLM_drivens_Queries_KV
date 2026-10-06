import json
from admit_dual_rules import admit_from_pairs, is_tiny, MIN_SUPPORT


def test_tiny_box():
    q = {"temporal": {"start_ms": 0, "end_ms": 400000}, "spatial": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 100}}
    assert is_tiny(q)


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
