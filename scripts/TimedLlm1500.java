import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Five HTTP chat calls with connect/read timeout = 1500ms (same budget as CboLlmProposalArm). */
public class TimedLlm1500 {
  public static void main(String[] args) throws Exception {
    String base = System.getenv("LLM_BASE_URL");
    String key = System.getenv("LLM_API_KEY");
    String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-flash");
    if (base == null || key == null) {
      throw new IllegalStateException("missing LLM_BASE_URL / LLM_API_KEY");
    }
    String url = base.replaceAll("/$", "") + "/chat/completions";
    String body =
        "{"
            + "\"model\":\""
            + model
            + "\","
            + "\"temperature\":0,"
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":\"Pick one whitelist plan_id. Reply with only JSON {\\\"plan_id\\\":\\\"P_...\\\"}.\"},"
            + "{\"role\":\"user\",\"content\":\"prompt_version=cbo_llm_compact_v3\\nReply ONLY JSON: {\\\"plan_id\\\":\\\"P_...\\\"}\\ncbo_plan_id=P_TZ\\nwhitelist=P_T,P_Z\\n\"}"
            + "]}";
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    int timeoutMs = 1500;
    int ok = 0;
    for (int i = 1; i <= 5; i++) {
      long t0 = System.nanoTime();
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(timeoutMs);
      conn.setReadTimeout(timeoutMs);
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Authorization", "Bearer " + key);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      try {
        try (OutputStream os = conn.getOutputStream()) {
          os.write(payload);
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String resp = new String(readAll(in), StandardCharsets.UTF_8);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        boolean has = resp.contains("choices") || resp.contains("plan_id");
        String result;
        if (code == 200 && has && ms <= 1500) {
          ok++;
          result = "OK";
        } else if (code == 200 && has) {
          result = "LATE";
        } else {
          result = "FAIL";
        }
        System.out.println(
            "java_trial" + i + " " + result + " http=" + code + " ms=" + ms + " bytes=" + resp.length());
      } catch (SocketTimeoutException e) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("java_trial" + i + " TIMEOUT ms=" + ms);
      } catch (Exception e) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println(
            "java_trial" + i + " ERR ms=" + ms + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
      } finally {
        conn.disconnect();
      }
    }
    System.out.println("java_1p5_success=" + ok + "/5");
  }

  static byte[] readAll(InputStream in) throws IOException {
    if (in == null) {
      return new byte[0];
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) >= 0) {
      bos.write(buf, 0, n);
    }
    return bos.toByteArray();
  }
}
