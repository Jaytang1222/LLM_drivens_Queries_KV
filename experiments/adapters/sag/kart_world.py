#!/usr/bin/env python3
"""
KartWorldAccess: bounded read-only world for SAG feedback.

Backends (priority):
  1) hbase — KART_JAR + java query-draft (DraftIR → Binder → force P_FULL).
     Enabled when KART_WORLD_BACKEND=hbase (default if KART_JAR/kart jar resolvable)
     and not forced to sample.
  2) sample_json — KART_WORLD_SAMPLE points file (plumbing only; NOT formal).
  3) unavailable — caller must not claim full SAG feedback.

Formal feedback requires backend starting with "hbase_" and sample Oracle alignment.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
from datetime import datetime
from typing import Any, Dict, List, Optional


def _parse_iso(s: str) -> Optional[int]:
    if not s:
        return None
    try:
        t = s.replace("Z", "+00:00")
        return int(datetime.fromisoformat(t).timestamp() * 1000)
    except Exception:
        return None


def _resolve_jar() -> Optional[str]:
    jar = os.environ.get("KART_JAR", "").strip()
    if jar and os.path.isfile(jar):
        return jar
    root = os.environ.get("KART_ROOT", "").strip()
    if not root:
        # adapters/sag → repo root
        root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    for rel in (
        "target/kart-0.1.0-SNAPSHOT.jar",
        "target/kart.jar",
    ):
        p = os.path.join(root, rel)
        if os.path.isfile(p):
            return p
    return None


def _resolve_root() -> str:
    root = os.environ.get("KART_ROOT", "").strip()
    if root:
        return root
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


class KartWorldAccess:
    def __init__(self, sample_path: Optional[str] = None):
        self.sample_path = sample_path or os.environ.get("KART_WORLD_SAMPLE", "")
        self.points: List[dict] = []
        self.backend = None
        self.jar = _resolve_jar()
        self.root = _resolve_root()
        self.manifest = os.environ.get("KART_WORLD_MANIFEST", "tdrive_v1_ready")
        mode = os.environ.get("KART_WORLD_BACKEND", "").strip().lower()

        # Formal HBase probe must be opted in (KART_WORLD_BACKEND=hbase).
        # Auto-picking a local jar would break fail-closed unit tests and hide
        # missing WorldAccess in CI.
        if mode in ("hbase", "query-draft") and self.jar:
            self.backend = "hbase_query_draft"
            return

        if self.sample_path and os.path.isfile(self.sample_path):
            with open(self.sample_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                self.points = data
            elif isinstance(data, dict):
                pts = data.get("points")
                self.points = pts if isinstance(pts, list) else []
            else:
                self.points = []
            self.backend = "sample_json:" + self.sample_path if self.points else None
        else:
            self.points = []
            self.backend = None

    def is_available(self) -> bool:
        if self.backend and str(self.backend).startswith("hbase"):
            return bool(self.jar)
        return bool(self.backend) and bool(self.points)

    def is_formal(self) -> bool:
        return bool(self.backend) and str(self.backend).startswith("hbase")

    def execute_draft(self, draft: dict) -> Dict[str, Any]:
        if not self.is_available():
            return {
                "ok": False,
                "error": "world_unavailable",
                "backend": None,
                "trajectory_ids": [],
            }
        if self.backend and str(self.backend).startswith("hbase"):
            return self._execute_hbase_draft(draft)
        return self._execute_sample(draft)

    def _execute_hbase_draft(self, draft: dict) -> Dict[str, Any]:
        assert self.jar
        fd, path = tempfile.mkstemp(prefix="kart-world-draft-", suffix=".json")
        os.close(fd)
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(draft, f, ensure_ascii=False)
            cmd = [
                "java",
                "-Dkart.root=" + self.root,
                "-jar",
                self.jar,
                "query-draft",
                "--draft",
                path,
                "--manifest",
                self.manifest,
                "--config-root",
                self.root,
                "--force-plan",
                os.environ.get("KART_WORLD_FORCE_PLAN", "P_FULL"),
            ]
            env = os.environ.copy()
            proc = subprocess.run(
                cmd,
                cwd=self.root,
                capture_output=True,
                text=True,
                timeout=float(os.environ.get("KART_WORLD_TIMEOUT_SEC", "180")),
                env=env,
            )
            line = ""
            for raw in (proc.stdout or "").splitlines():
                s = raw.strip()
                if s.startswith("{"):
                    line = s
            if not line:
                return {
                    "ok": False,
                    "error": "query_draft_no_json",
                    "backend": None,
                    "trajectory_ids": [],
                    "stderr": (proc.stderr or "")[:800],
                    "returncode": proc.returncode,
                }
            try:
                obj = json.loads(line)
            except Exception as e:
                return {
                    "ok": False,
                    "error": "query_draft_bad_json:" + str(e),
                    "backend": None,
                    "trajectory_ids": [],
                }
            if not obj.get("backend"):
                obj["backend"] = None
            return obj
        except subprocess.TimeoutExpired:
            return {
                "ok": False,
                "error": "query_draft_timeout",
                "backend": None,
                "trajectory_ids": [],
            }
        finally:
            try:
                os.remove(path)
            except OSError:
                pass

    def _execute_sample(self, draft: dict) -> Dict[str, Any]:
        temporal = draft.get("temporal") or {}
        t0 = _parse_iso(temporal.get("start"))
        t1 = _parse_iso(temporal.get("end"))
        spatial = draft.get("spatial") or {}
        region = spatial.get("region_name")
        geom = spatial.get("geometry") or {}
        preds = draft.get("predicates") or []
        vehicle = None
        for p in preds:
            if p.get("field") == "vehicle_id" and p.get("op") == "EQ":
                vehicle = p.get("value")

        ids = set()
        for pt in self.points:
            if t0 is not None and pt.get("t_ms", 0) < t0:
                continue
            if t1 is not None and pt.get("t_ms", 0) >= t1:
                continue
            if vehicle is not None and str(pt.get("vehicle_id")) != str(vehicle):
                continue
            if region and pt.get("region_name") and pt.get("region_name") != region:
                continue
            if geom.get("type") == "RECTANGLE":
                lon, lat = pt.get("lon"), pt.get("lat")
                if lon is None or lat is None:
                    continue
                if not (
                    geom["min_lon"] <= lon <= geom["max_lon"]
                    and geom["min_lat"] <= lat <= geom["max_lat"]
                ):
                    continue
            tid = pt.get("trajectory_id")
            if tid:
                ids.add(tid)
        return {
            "ok": True,
            "backend": self.backend,
            "trajectory_ids": sorted(ids),
            "top_k": None,
            "note": "sample_world_probe_not_fullscan_oracle",
        }
