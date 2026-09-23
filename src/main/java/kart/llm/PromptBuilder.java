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
    this.datasetId = datasetId == null ? "fixture_v1" : datasetId;
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
    sb.append("similarity(metric DTW, reference_trajectory_id), result(mode TRAJECTORY_IDS|TOP_K, k).\n");
    sb.append("Semantics are always OBSERVED_POINT / SAME_POINT (sampled GPS points, not continuous path).\n");
    sb.append("Four query classes: (1) spatiotemporal filter → TRAJECTORY_IDS, ");
    sb.append("(2) vehicle equality, (3) Top-K DTW similarity, (4) combinations.\n");
    sb.append("COUNT / aggregation / continuous-path semantics are unsupported — leave result ");
    sb.append("and set missing or refuse via missing=[\"unsupported_count\"].\n");
    sb.append("If unknown, omit the field and list its path in missing[].\n");
    sb.append("Registered region names: ").append(regionNames).append(".\n");
    sb.append("User utterances may be Chinese or English. When the user names a place in Chinese, ");
    sb.append("map it to a registered region_name when possible ");
    sb.append("(中关村→zhongguancun, 望京→wangjing, 国贸/CBD→guomao or beijing_cbd, ");
    sb.append("天安门→tiananmen, 首都机场→capital_airport, 海淀→haidian_central, 朝阳→chaoyang_central). ");
    sb.append("If unsure, put the name in missing[] instead of inventing coordinates.\n");
    sb.append("Default dataset_id=").append(datasetId).append(", entity=trajectory, ir_version=1.0.\n");
    sb.append("DraftIR Schema summary: additionalProperties=false; no physical storage fields.\n");
    sb.append("Examples:\n");
    sb.append("1) {\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"").append(datasetId)
        .append("\",\"entity\":\"trajectory\"},\"temporal\":{\"start\":\"2008-02-02T08:00:00+08:00\",")
        .append("\"end\":\"2008-02-02T08:10:00+08:00\"},\"spatial\":{\"region_name\":\"beijing_core\"},")
        .append("\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},")
        .append("\"result\":{\"mode\":\"TRAJECTORY_IDS\"},\"missing\":[]}\n");
    sb.append("2) Top-K with reference R and k=2, include similarity + result.mode=TOP_K.\n");
    sb.append("3) Missing date → omit temporal, missing:[\"temporal\"].\n");
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
