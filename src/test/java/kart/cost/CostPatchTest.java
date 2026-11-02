package kart.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CostPatchTest {

  private static CostCard card(String plan) {
    CostCard c = new CostCard();
    c.plan_id = plan;
    c.l_hat_index = 100;
    c.l_hat_fetch = 50;
    c.estimated_ms = 150;
    c.features.put("estimated_index_rows", Long.valueOf(1000));
    return c;
  }

  @Test
  void firstMatchingPatchReweightsTermsAndAddsFeatures() throws Exception {
    String doc = "{\"patches\":[{\"id\":\"tz\",\"when\":[[\"plan_id\",\"==\",\"P_TZ\"],"
        + "[\"estimated_index_rows\",\">\",500]],\"scale\":{\"index\":2},"
        + "\"add\":[{\"feature\":\"estimated_index_rows\",\"coeff\":0.01},{\"feature\":\"missing\",\"coeff\":5}]},"
        + "{\"id\":\"all\",\"when\":[],\"scale\":{\"fetch\":0}}]}";
    CostCard tz = card("P_TZ");
    CostPatch.apply(new ObjectMapper().readTree(doc), tz);
    assertEquals(260.0, tz.estimated_ms, 1e-9);
    assertEquals("tz", tz.features.get("cost_patch"));
    CostCard t = card("P_T");
    CostPatch.apply(new ObjectMapper().readTree(doc), t);
    assertEquals(100.0, t.estimated_ms, 1e-9);
    CostCard none = card("P_T");
    CostPatch.apply(null, none);
    assertEquals(150.0, none.estimated_ms, 1e-9);
  }

  @Test
  void rewriteAdjustScalesResourceSlotsNotFeatures() throws Exception {
    ObjectMapper m = new ObjectMapper();
    ObjectNode ctx = m.createObjectNode();
    ctx.put("query", "TOP_K");
    ObjectNode feats = ctx.putObject("features");
    feats.put("cbo_plan", "P_Z");
    feats.put("k", 3);
    ObjectNode slots = ctx.putObject("slots");
    slots.putObject("access").putObject("options").put("keep", 0).put("drop_spatial_branch", -40);
    slots.putObject("topk").putObject("options").put("off", 0).put("lb2", -100);
    slots.putObject("fetch_batch").putObject("options").put("b500", 0).put("b2000", -50);
    String doc = "{\"patches\":[{\"id\":\"topk_queries\",\"when\":[[\"query\",\"==\",\"TOP_K\"]],"
        + "\"scale\":{\"topk\":1.3,\"fetch\":0.8}}]}";
    assertEquals("topk_queries", CostPatch.adjustRewriteResourceSlots(ctx, m.readTree(doc)));
    assertEquals(-130.0, slots.path("topk").path("options").path("lb2").asDouble(), 1e-9);
    assertEquals(-40.0, slots.path("fetch_batch").path("options").path("b2000").asDouble(), 1e-9);
    assertEquals(-40.0, slots.path("access").path("options").path("drop_spatial_branch").asDouble(), 1e-9);
    assertEquals(3, feats.path("k").asInt());
    assertNull(CostPatch.adjustRewriteResourceSlots(ctx, null));
  }
}
