package kart.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
