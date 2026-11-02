"""Bootstrap ParamTree upstream imports without live PG / AL-only deps."""
from __future__ import annotations

import os
import pickle
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PT = ROOT / "experiments" / "adapters" / "upstream" / "paramtree"


def stub_optional_deps():
  stubs = [
      "psycopg2",
      "psycopg2.extras",
      "paramiko",
      "doepy",
      "doepy.build",
      "SALib",
      "SALib.sample",
      "SALib.sample.saltelli",
      "aim",
  ]
  for name in stubs:
    if name not in sys.modules:
      sys.modules[name] = types.ModuleType(name)
  # aim.Run used only in AL path
  if not hasattr(sys.modules["aim"], "Run"):
    sys.modules["aim"].Run = object  # type: ignore[attr-defined]


def patch_database_info():
  stub_optional_deps()
  import database_util.database_info as di

  def _init(self, db_name):
    self.db_name = db_name
    self.db_connector = None
    info_file = f"./data/temporary/schemeinfo/scheme_{db_name}_histogram_info.pickle"
    if not os.path.exists(info_file):
      raise FileNotFoundError(info_file)
    with open(info_file, "rb") as f:
      result = pickle.load(f)
    self.config_info = result["config"]
    self.table_features = result["table"]
    self.index_features = result["index"]

  di.Database_info.__init__ = _init


def stub_active_learning():
  """NORMAL_TRAIN/predict do not need ActiveLearningTool; avoid heavy AL deps."""
  stub_optional_deps()
  # Ensure package 'model' is importable first
  import model as model_pkg  # noqa: F401

  fake = types.ModuleType("model.active_learning")

  class ActiveLearningTool:  # noqa: N801 — match upstream name
    pass

  fake.ActiveLearningTool = ActiveLearningTool
  sys.modules["model.active_learning"] = fake
  model_pkg.active_learning = fake


def patch_predict_execute_by_learn():
  """
  Upstream QueryInference.predict_op always calls get_op_info(..., execute=True).

  That is correct for *training* (Actual* = labels) and for post-hoc q-error on
  ANALYZE trees, but wrong for *pre-execution* plan selection: Actual* would leak
  measured/proxy runtime into features.

  Honesty shims (disclosed in TRANSPLANT.md):
    1) execute=learn —
         learn=True  → Actual* / Actual Rows (fit labels)
         learn=False → Plan Rows / Plan Cost (optimizer-time estimate)
    2) When learn=False, upstream execute=False sets Loops=0 (meant for
       never-executed ANALYZE subnodes). MobTree leaves trained on Loops≥1 then
       score 0. For optimizer selection we set Loops=1 (plan runs once) —
       still no Actual Total Time / no estimated_ms-as-Actual.
  """
  from model.query_inference import QueryInference  # noqa: E402

  _orig = QueryInference.predict_op

  def predict_op(self, plan, config, Left_start_time, Left_Total_time,
                 Right_start_time, Right_Total_time, learn):
    plan_tool = self.plan_tool
    _goi = plan_tool.get_op_info

    def _goi_honest(p, execute=True):
      info = _goi(p, execute=bool(learn))
      if not learn:
        for k, v in list(info.items()):
          if isinstance(k, str) and k.endswith("Loops") and v == 0:
            info[k] = 1
      return info

    plan_tool.get_op_info = _goi_honest
    try:
      return _orig(self, plan, config, Left_start_time, Left_Total_time,
                   Right_start_time, Right_Total_time, learn)
    finally:
      plan_tool.get_op_info = _goi

  QueryInference.predict_op = predict_op


def chdir_and_prepare():
  os.chdir(str(PT))
  if str(PT) not in sys.path:
    sys.path.insert(0, str(PT))
  stub_optional_deps()
  patch_database_info()
  stub_active_learning()
  patch_predict_execute_by_learn()
