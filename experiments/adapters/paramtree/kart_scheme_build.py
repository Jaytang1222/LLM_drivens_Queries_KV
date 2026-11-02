"""Build minimal ParamTree scheme pickle for KART (no live PostgreSQL)."""
from __future__ import annotations

import pickle
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PT = ROOT / "experiments" / "adapters" / "upstream" / "paramtree"
OUT = PT / "data" / "temporary" / "schemeinfo" / "scheme_kart_histogram_info.pickle"

# Match Relation Name / Index Name used by kart_pg_plan_encode (ParamTree mode).
TABLES = {
    "traj_raw": {
        "table_size": 50_000_000,
        "table_pages": 50_000,
        "tuple_num": 10_000_000.0,
        "idx": ["idx_t", "idx_z", "idx_h", "idx_ts", "idx_xz", "idx_xzt", "idx_xztxz"],
        "columns": {
            "tid": {"type": "int4", "width": 8, "index": 1, "corr": 1.0, "offset": 0},
            "t": {"type": "int8", "width": 8, "index": 2, "corr": 0.9, "offset": 8},
            "z": {"type": "int8", "width": 8, "index": 3, "corr": 0.5, "offset": 16},
        },
    }
}

INDEXES = {}
for name, pages, tupls, height in [
    ("idx_t", 8000, 8_000_000.0, 4),
    ("idx_z", 12000, 9_000_000.0, 4),
    ("idx_h", 5000, 5_000_000.0, 3),
    ("idx_ts", 9000, 7_000_000.0, 4),
    ("idx_xz", 10000, 8_500_000.0, 4),
    ("idx_xzt", 11000, 8_800_000.0, 4),
    ("idx_xztxz", 13000, 9_200_000.0, 5),
]:
  INDEXES[name] = {
      "pages": pages,
      "tupls": tupls,
      "tree_height": height,
      "columns": ["tid"],
      "key_column": 1,
      "table": "traj_raw",
      "indexCorrelation": 0.8,
      "type": "int4",
      "distinctnum": tupls,
  }

CONFIG = {
    "temp_buffers": 1024,
    "work_mem": 4096,
    "max_stack_depth": 2048,
    "effective_cache_size": 524288,
    "jit": 0,
}


def main():
  OUT.parent.mkdir(parents=True, exist_ok=True)
  payload = {"config": CONFIG, "table": TABLES, "index": INDEXES}
  with open(OUT, "wb") as f:
    pickle.dump(payload, f)
  print("wrote", OUT)


if __name__ == "__main__":
  main()
