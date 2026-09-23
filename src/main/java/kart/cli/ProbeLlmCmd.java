package kart.cli;

import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.llm.OpenAiCompatibleClient;
import picocli.CommandLine.Command;

import java.util.Collections;
import java.util.concurrent.Callable;

/**
 * OI-1 live probe: one chat/completions call, optionally with json_object.
 */
@Command(name = "probe-llm", description = "Probe live OpenAI-compatible LLM (OI-1)")
public final class ProbeLlmCmd implements Callable<Integer> {

  @Override
  public Integer call() {
    String key = env("LLM_API_KEY");
    String base = env("LLM_BASE_URL");
    String model = env("LLM_MODEL");
    if (key.isEmpty()) {
      System.err.println("LLM_API_KEY not set");
      return 2;
    }
    System.out.println("probe-llm base=" + base + " model=" + (model.isEmpty() ? "(default)" : model));
    OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    LlmOptions opts = LlmOptions.defaults();
    opts.jsonObjectFormat = !"false".equalsIgnoreCase(env("LLM_JSON_MODE", "true"));
    opts.temperature = 0;
    try {
      LlmResponse resp = client.chat(
          Collections.singletonList(LlmMessage.user(
              "Return a JSON object with keys ok (boolean true) and note (string 'kart-oi1').")),
          null,
          opts);
      System.out.println("latency_ms=" + resp.latencyMs);
      System.out.println("model=" + resp.model);
      System.out.println("prompt_tokens=" + resp.promptTokens);
      System.out.println("completion_tokens=" + resp.completionTokens);
      System.out.println("content=" + resp.content);
      System.out.println("json_mode_requested=" + opts.jsonObjectFormat);
      System.out.println("PROBE_LLM_OK");
      return 0;
    } catch (Exception e) {
      System.err.println("PROBE_LLM_FAILED: " + e.getMessage());
      if (opts.jsonObjectFormat) {
        System.err.println("hint: set LLM_JSON_MODE=false and retry");
      }
      return 1;
    }
  }

  private static String env(String k) {
    return env(k, "");
  }

  private static String env(String k, String def) {
    String v = System.getenv(k);
    return v == null || v.trim().isEmpty() ? def : v.trim();
  }
}
