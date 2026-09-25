package kart.ir;

import kart.config.AppConfig;
import kart.exec.MemoryBackend;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IrBinder cross-field rules (design §5.3) and region/time binding.
 */
class IrBinderTest {

  @Test
  void topKWithoutSimilarityRejected() throws Exception {
    DraftIr d = baseDraft();
    d.result.mode = "TOP_K";
    d.result.k = 2;
    d.temporal = temporal();
    IrBinder.BindResult br = binder().bind(d);
    assertEquals(IrBinder.STATUS_BIND_ERROR, br.status);
  }

  @Test
  void trajectoryIdsWithSimilarityRejected() throws Exception {
    DraftIr d = baseDraft();
    d.result.mode = "TRAJECTORY_IDS";
    d.temporal = temporal();
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "DTW";
    d.similarity.reference_trajectory_id = "R";
    IrBinder.BindResult br = binder().bind(d);
    assertEquals(IrBinder.STATUS_BIND_ERROR, br.status);
  }

  @Test
  void nonDtwUnsupported() throws Exception {
    DraftIr d = baseDraft();
    d.result.mode = "TOP_K";
    d.result.k = 2;
    d.temporal = temporal();
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "EDIT_DISTANCE";
    d.similarity.reference_trajectory_id = "R";
    IrBinder.BindResult br = binder().bind(d);
    assertEquals(IrBinder.STATUS_UNSUPPORTED_QUERY, br.status);
  }

  @Test
  void missingReferenceIdErrors() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      DraftIr d = baseDraft();
      d.result.mode = "TOP_K";
      d.result.k = 2;
      d.temporal = temporal();
      d.spatial = regionSpatial();
      d.similarity = new DraftIr.Similarity();
      d.similarity.metric = "DTW";
      d.similarity.reference_trajectory_id = "NO_SUCH";
      IrBinder binder = new IrBinder(regions(), kv, FixtureBuilder.TABLE_META, 4,
          FixtureBuilder.MANIFEST_ID, "point_dtw_v1");
      IrBinder.BindResult br = binder.bind(d);
      assertEquals(IrBinder.STATUS_BIND_ERROR, br.status);
      assertTrue(br.error.contains("not found"));
    } finally {
      kv.close();
    }
  }

  @Test
  void unconstrainedRejected() throws Exception {
    DraftIr d = baseDraft();
    d.result.mode = "TRAJECTORY_IDS";
    IrBinder.BindResult br = binder().bind(d);
    assertEquals(IrBinder.STATUS_BIND_ERROR, br.status);
  }

  @Test
  void fixtureRegionBindsAsMeters() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      DraftIr d = baseDraft();
      d.result.mode = "TOP_K";
      d.result.k = 2;
      d.temporal = temporal();
      d.spatial = regionSpatial();
      d.similarity = new DraftIr.Similarity();
      d.similarity.metric = "DTW";
      d.similarity.reference_trajectory_id = "R";
      IrBinder binder = new IrBinder(regions(), kv, FixtureBuilder.TABLE_META, 4,
          FixtureBuilder.MANIFEST_ID, "point_dtw_v1");
      IrBinder.BindResult br = binder.bind(d);
      assertEquals(IrBinder.STATUS_OK, br.status, br.error);
      assertNotNull(br.bound);
      assertEquals(4.0, br.bound.spatial.min_x, 1e-9);
      assertEquals(8.0, br.bound.spatial.max_x, 1e-9);
      assertEquals(4L, br.bound.similarity.reference_tid);
    } finally {
      kv.close();
    }
  }

  @Test
  void parseShanghaiEpoch() throws Exception {
    long t = IrBinder.parseShanghai("2008-02-02T08:00:00+08:00");
    assertEquals(FixtureBuilder.T0, t);
  }

  private static IrBinder binder() {
    return new IrBinder(regions(), null, FixtureBuilder.TABLE_META, 4,
        FixtureBuilder.MANIFEST_ID, "point_dtw_v1");
  }

  private static AppConfig.RegionsConfig regions() {
    AppConfig.RegionsConfig rc = new AppConfig.RegionsConfig();
    AppConfig.Region box = new AppConfig.Region();
    box.name = "fixture_box";
    box.min_lon = 4;
    box.min_lat = 4;
    box.max_lon = 8;
    box.max_lat = 8;
    box.local_meters = true;
    AppConfig.Region bj = new AppConfig.Region();
    bj.name = "beijing_core";
    bj.min_lon = 116.28;
    bj.min_lat = 39.82;
    bj.max_lon = 116.48;
    bj.max_lat = 39.98;
    rc.regions = java.util.Arrays.asList(box, bj);
    return rc;
  }

  private static DraftIr baseDraft() {
    DraftIr d = new DraftIr();
    d.ir_version = "1.0";
    d.source = new DraftIr.Source();
    d.source.dataset_id = "fixture_v1";
    d.source.entity = "trajectory";
    d.semantics = new DraftIr.Semantics();
    d.semantics.mode = "OBSERVED_POINT";
    d.semantics.coupling = "SAME_POINT";
    d.result = new DraftIr.Result();
    d.missing = Collections.emptyList();
    return d;
  }

  private static DraftIr.Temporal temporal() {
    DraftIr.Temporal t = new DraftIr.Temporal();
    t.start = "2008-02-02T08:00:00+08:00";
    t.end = "2008-02-02T08:10:00+08:00";
    return t;
  }

  private static DraftIr.Spatial regionSpatial() {
    DraftIr.Spatial s = new DraftIr.Spatial();
    s.region_name = "fixture_box";
    return s;
  }
}
