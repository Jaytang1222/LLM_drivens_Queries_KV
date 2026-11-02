package kart.bench;

import kart.config.AppConfig;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parameter OAT grid expansion and query_type strata (spec/parameter_experiment.md). */
public final class ParamHarnessTest {

  @Test
  void formalSuitesAreOatKartV2() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec complex = SuiteSpec.load(root, Paths.get("param"), null);
    assertEquals("param", complex.kind);
    assertEquals("kart", complex.base_arm);
    assertEquals("oat", complex.resolvedGridMode());
    assertEquals("datasets/tdrive-query/complex_query.json", complex.workload);
    assertEquals(Integer.valueOf(3), toInt(complex.defaults.get("beam_width")));
    assertEquals(Integer.valueOf(4), toInt(complex.defaults.get("max_candidates")));
    assertNull(complex.defaults.get("max_llm_calls"));
    assertEquals(4, complex.grid.get("beam_width").size());
    assertEquals(4, complex.grid.get("max_candidates").size());
    assertFalse(complex.grid.containsKey("max_llm_calls"));

    SuiteSpec simple = SuiteSpec.load(root, Paths.get("param-simple"), null);
    assertEquals("param", simple.kind);
    assertEquals("kart", simple.base_arm);
    assertEquals("oat", simple.resolvedGridMode());
    assertEquals("datasets/tdrive-query/simple_query.json", simple.workload);
  }

  @Test
  void oatExpandsEightCellsWithPinnedDefaults() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec suite = SuiteSpec.load(root, Paths.get("param"), null);
    SuiteRunner.Options opt = newOptions(root);
    List<SuiteRunner.RunCell> cells =
        SuiteRunner.expandCells(suite, new ArmRegistry(root), opt, false);
    assertEquals(8, cells.size(), labels(cells).toString());
    assertFalse(cells.size() == 4 * 4, "must not be full factorial (16); OAT is 4+4");

    int beam = 0;
    int cand = 0;
    for (SuiteRunner.RunCell c : cells) {
      assertEquals("kart", c.armId);
      assertNotNull(c.overrides.get("beam_width"));
      assertNotNull(c.overrides.get("max_candidates"));
      assertNull(c.overrides.get("max_llm_calls"));
      String axis = String.valueOf(c.extras.get("param_axis"));
      if ("beam_width".equals(axis)) {
        beam++;
        assertEquals(4, toInt(c.overrides.get("max_candidates")).intValue());
      } else if ("max_candidates".equals(axis)) {
        cand++;
        assertEquals(3, toInt(c.overrides.get("beam_width")).intValue());
      }
    }
    assertEquals(4, beam);
    assertEquals(4, cand);
  }

  @Test
  void overlayAcceptsBeamAndCandidates() {
    AppConfig.PlannerConfig base = AppConfig.PlannerConfig.defaults();
    Map<String, Object> ov = new LinkedHashMap<String, Object>();
    ov.put("beam_width", Integer.valueOf(3));
    ov.put("max_candidates", Integer.valueOf(4));
    AppConfig.PlannerConfig p = PlannerOverlay.apply(base, ov);
    assertEquals(3, p.beam_width);
    assertEquals(4, p.max_candidates);
  }

  @Test
  void queryTypeFromCatalogAndFallback() {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    Map<String, String> roles = SuiteRunner.loadQueryTypeRoles(root);
    BoundIr srq = ir("s_srq_short_1", null, "TRAJECTORY_IDS");
    BoundIr str = ir("s_str_aoi_1", null, "TRAJECTORY_IDS");
    BoundIr ss = ir("s_ss_dtw_1", "DTW", "TOP_K");
    BoundIr topk = ir("s_topk_k3_1", "DTW", "TOP_K");
    if (!roles.isEmpty()) {
      assertEquals("SRQ", SuiteRunner.normalizeParamQueryType(roles.get("s_srq_short_1")));
      assertEquals("ST-Ranges", SuiteRunner.normalizeParamQueryType(roles.get("s_str_aoi_1")));
      assertEquals("SS", SuiteRunner.normalizeParamQueryType(roles.get("s_ss_dtw_1")));
      assertEquals("topk", SuiteRunner.normalizeParamQueryType(roles.get("s_topk_k3_1")));
      assertEquals("SRQ", SuiteRunner.classifyQueryType(srq, roles));
      assertEquals("ST-Ranges", SuiteRunner.classifyQueryType(str, roles));
      assertEquals("SS", SuiteRunner.classifyQueryType(ss, roles));
      assertEquals("topk", SuiteRunner.classifyQueryType(topk, roles));
    }
    // fallback from query_id / IR (canonical after workloads slim)
    assertEquals("SS", SuiteRunner.classifyQueryType(ss, null));
    assertEquals("topk", SuiteRunner.classifyQueryType(topk, null));
    assertEquals("SRQ", SuiteRunner.classifyQueryType(srq, null));
    assertEquals("ST-Ranges", SuiteRunner.classifyQueryType(str, null));
  }

  @Test
  void putQueryStrataWritesQueryType() {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    Map<String, String> roles = SuiteRunner.loadQueryTypeRoles(root);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    SuiteRunner.putQueryStrata(row, ir("s_ss_frechet_2", "FRECHET", "TOP_K"), roles);
    assertEquals("SS", row.get("query_type"));
    assertEquals("FRECHET", row.get("metric"));
  }

  private static BoundIr ir(String id, String metric, String mode) {
    BoundIr ir = new BoundIr();
    ir.query_id = id;
    if (metric != null) {
      ir.similarity = new BoundIr.Similarity();
      ir.similarity.metric = metric;
    }
    ir.result = new BoundIr.Result();
    ir.result.mode = mode;
    return ir;
  }

  private static SuiteRunner.Options newOptions(Path root) {
    SuiteRunner.Options opt = new SuiteRunner.Options();
    opt.root = root;
    return opt;
  }

  private static List<String> labels(List<SuiteRunner.RunCell> cells) {
    List<String> out = new ArrayList<String>();
    for (SuiteRunner.RunCell c : cells) {
      out.add(c.label);
    }
    return out;
  }

  private static Integer toInt(Object v) {
    if (v instanceof Number) {
      return Integer.valueOf(((Number) v).intValue());
    }
    if (v == null) {
      return null;
    }
    return Integer.valueOf(Integer.parseInt(String.valueOf(v)));
  }
}
