package kart.snapshot;

import kart.ir.BoundIr;

/**
 * Shared BoundIR builders for CLI demos and tests (fixture §18 Top-K).
 */
public final class FixtureQueries {

  private FixtureQueries() {}

  /** Spatiotemporal Top-2 vs reference R → expected [A,B]. */
  public static BoundIr fixtureTopK() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_fixture_001";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "fixture_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = FixtureBuilder.T0;
    ir.temporal.end_ms = FixtureBuilder.T0 + 10 * 60 * 1000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 4;
    ir.spatial.min_y = 4;
    ir.spatial.max_x = 8;
    ir.spatial.max_y = 8;
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
    ir.similarity.reference_tid = 4;
    ir.similarity.exclude_reference = true;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = 2;
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = FixtureBuilder.MANIFEST_ID;
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }
}
