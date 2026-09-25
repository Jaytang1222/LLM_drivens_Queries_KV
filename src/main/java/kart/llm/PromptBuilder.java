package kart.llm;

import kart.config.AppConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds system + user prompts without physical table/rowkey vocabulary (T3.3).
 */
public final class PromptBuilder {

  private final List<String> regionNames;
  private final String datasetId;

  public PromptBuilder(AppConfig.RegionsConfig regions, String datasetId) {
    this.datasetId = datasetId == null ? "tdrive_v1" : datasetId;
    this.regionNames = new ArrayList<String>();
    if (regions != null && regions.regions != null) {
      for (AppConfig.Region r : regions.regions) {
        if (r != null && r.name != null) {
          regionNames.add(r.name);
        }
      }
    }
  }

  public String systemPrompt() {
    StringBuilder sb = new StringBuilder();
    sb.append("You are KART's trajectory query parser. Output ONLY a DraftIR JSON object.\n");
    sb.append("Logical catalog fields: temporal(start,end ISO-8601), spatial(geometry RECTANGLE ");
    sb.append("min_lon/min_lat/max_lon/max_lat OR region_name), predicates(vehicle_id EQ), ");
    sb.append("similarity(metric DTW|FRECHET|HAUSDORFF, reference_trajectory_id), ");
    sb.append("result(mode TRAJECTORY_IDS|TOP_K, k).\n");
    sb.append("Semantics are always OBSERVED_POINT / SAME_POINT (sampled GPS points, not continuous path).\n");
    sb.append("Four query classes: (1) spatiotemporal filter → TRAJECTORY_IDS, ");
    sb.append("(2) vehicle equality, (3) Top-K similarity (DTW / discrete Fréchet / Hausdorff), ");
    sb.append("(4) combinations.\n");
    sb.append("UNSUPPORTED (do not invent a substitute query): COUNT/aggregation; ");
    sb.append("continuous path / LINEAR_SEGMENT / polyline crossing; continuous Fréchet; ");
    sb.append("derived speed / dwell / stay-point predicates; pair co-location joins. ");
    sb.append("For those, put missing:[\"unsupported\"] and leave result incomplete.\n");
    sb.append("HONESTY RULES (critical):\n");
    sb.append("- Never invent similarity.metric if the user did not name DTW, FRECHET, or HAUSDORFF;");
    sb.append(" omit it and set missing:[\"similarity.metric\"].\n");
    sb.append("- Never invent region_name or lon/lat not stated by the user;");
    sb.append(" omit spatial and set missing:[\"spatial\"].\n");
    sb.append("- Never replace an unknown place name with beijing_core or another registered region.\n");
    sb.append("- For TOP_K similarity/neighbor queries: do NOT add predicates.vehicle_id EQ ");
    sb.append("just because the reference trajectory id contains a taxi number ");
    sb.append("(e.g. 8857-8857_14). Only add vehicle_id when the user explicitly restricts ");
    sb.append("the candidate set to that taxi (e.g. \"among taxi 8857 trajectories\").\n");
    sb.append("- Pair / co-location / \"within Xm of each other\" joins are UNSUPPORTED;");
    sb.append(" put missing:[\"unsupported\"].\n");
    sb.append("- If a required field is unknown, omit it and list its path in missing[].\n");
    sb.append("- Do not invent temporal windows when the user omitted dates.\n");
    sb.append("Registered region names ONLY: ").append(regionNames).append(".\n");
    sb.append("Chinese→region mapping when the user explicitly names that place: ");
    sb.append("中关村→zhongguancun, 望京→wangjing, 国贸/CBD→guomao or beijing_cbd, ");
    sb.append("天安门→tiananmen, 首都机场→capital_airport, 海淀→haidian_central, 朝阳→chaoyang_central, ");
    sb.append("smoke anchor / tdrive_smoke_anchor→tdrive_smoke_anchor, ");
    sb.append("topk box / tdrive_topk_box→tdrive_topk_box, ");
    sb.append("topk wide / tdrive_topk_wide→tdrive_topk_wide, ");
    sb.append("topk_s1 / tdrive_topk_s1→tdrive_topk_s1. ");
    sb.append("beijing_core is the inner-city demo box — NOT the T-Drive smoke oracle anchor.\n");
    sb.append("Default dataset_id=").append(datasetId).append(", entity=trajectory, ir_version=1.0.\n");
    sb.append("DraftIR Schema summary: additionalProperties=false; no physical storage fields.\n");
    sb.append("Examples:\n");
    sb.append("1) Complete ST filter with registered region the user named:\n");
    sb.append("{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"").append(datasetId)
        .append("\",\"entity\":\"trajectory\"},\"temporal\":{\"start\":\"2008-02-03T21:20:00+08:00\",")
        .append("\"end\":\"2008-02-03T21:30:00+08:00\"},\"spatial\":{\"region_name\":\"tdrive_smoke_anchor\"},")
        .append("\"predicates\":[{\"field\":\"vehicle_id\",\"op\":\"EQ\",\"value\":\"8857\"}],")
        .append("\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},")
        .append("\"result\":{\"mode\":\"TRAJECTORY_IDS\"},\"missing\":[]}\n");
    sb.append("2) Top-K missing metric → omit similarity.metric, missing:[\"similarity.metric\"].\n");
    sb.append("3) Missing date → omit temporal, missing:[\"temporal\"].\n");
    sb.append("4) Unknown place 火星广场 → omit spatial, missing:[\"spatial\"] ");
    sb.append("(do NOT write beijing_core).\n");
    return sb.toString();
  }

  public List<LlmMessage> buildMessages(String userUtterance, String sessionContext) {
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(systemPrompt()));
    StringBuilder user = new StringBuilder();
    if (sessionContext != null && !sessionContext.trim().isEmpty()) {
      user.append("Known context:\n").append(sessionContext.trim()).append("\n\n");
    }
    user.append("User request:\n").append(userUtterance);
    msgs.add(LlmMessage.user(user.toString()));
    return msgs;
  }

  /** Sanity: prompt must not mention physical storage vocabulary. */
  public static boolean containsPhysicalWords(String prompt) {
    String lower = prompt.toLowerCase();
    String[] banned = {
        "traj_raw", "traj_meta", "idx_time", "idx_zorder", "idx_hash",
        "rowkey", "row_key", "startrow", "stoprow", "shard", "bucket",
        "hbase", "zorder_level", "posting"
    };
    for (String b : banned) {
      if (lower.contains(b)) {
        return true;
      }
    }
    return false;
  }
}
