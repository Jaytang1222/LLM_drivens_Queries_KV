package kart.bench;

import kart.search.PlannerMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry of native + adapter arms. */
public final class ArmRegistry {

  private final Map<String, Arm> arms = new LinkedHashMap<String, Arm>();
  private final Map<String, ParseArm> parseArms = new LinkedHashMap<String, ParseArm>();

  public ArmRegistry(Path root) {
    register(new NativePolicyArm("rule", PlannerMode.RULE));
    register(new RboFixedArm());
    register(new NativePolicyArm("best_first", PlannerMode.BEST_FIRST));
    register(new NativePolicyArm("cbo", PlannerMode.BEST_FIRST));
    register(new NativePolicyArm("llm", PlannerMode.LLM));
    register(new NativePolicyArm("llm_direct", PlannerMode.LLM_DIRECT));
    register(new NativePolicyArm("kart", PlannerMode.LLM));
    register(new FullScanArm());

    register(new BaoPlanArm(root));
    register(new LlmOptPlanArm(root));

    // Keep unsupported stubs for din/sag as Arm (plan stage shouldn't use them)
    register(new UnsupportedArm("din-spider", "use parse stage"));
    register(new UnsupportedArm("din-bird", "use parse stage"));
    register(new UnsupportedArm("sag", "use parse stage"));

    registerParse(new KartParseArm());
    registerParse(ProcessParseArm.dinSpider(root));
    registerParse(ProcessParseArm.dinBird(root));
    registerParse(ProcessParseArm.sag(root));
  }

  /** @deprecated use {@link #ArmRegistry(Path)} */
  public ArmRegistry() {
    this(java.nio.file.Paths.get(".").toAbsolutePath().normalize());
  }

  public void register(Arm arm) {
    if (arm != null && arm.id() != null) {
      arms.put(normalize(arm.id()), arm);
    }
  }

  public void registerParse(ParseArm arm) {
    if (arm != null && arm.id() != null) {
      parseArms.put(normalize(arm.id()), arm);
    }
  }

  public Arm resolve(String raw) {
    if (raw == null) {
      return null;
    }
    String s = normalize(raw);
    Arm a = arms.get(s);
    if (a != null) {
      return a;
    }
    if ("full_scan".equals(s) || "p_full".equals(s)) {
      return arms.get("fullscan");
    }
    return null;
  }

  public ParseArm resolveParse(String raw) {
    if (raw == null) {
      return null;
    }
    return parseArms.get(normalize(raw));
  }

  public List<String> knownIds() {
    return new ArrayList<String>(arms.keySet());
  }

  private static String normalize(String id) {
    return id.trim().toLowerCase(Locale.ROOT);
  }
}
