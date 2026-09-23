package kart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic LLM stub keyed by SHA-256 of messages (T3.2).
 * Catalog JSON under {@code testdata/llm-mock/} may include:
 * <ul>
 *   <li>{@code hash} + {@code response} — direct hash key (full or prefix)</li>
 *   <li>{@code utterance} + {@code response} — registered later via {@link #bindUtterances}</li>
 *   <li>{@code <sha256>.json} filename — body is the response</li>
 * </ul>
 */
public final class MockLlmClient implements LlmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, List<String>> byHash = new HashMap<String, List<String>>();
  private final Map<String, Integer> callIndex = new HashMap<String, Integer>();
  private final List<CatalogEntry> pendingUtterances = new ArrayList<CatalogEntry>();

  private static final class CatalogEntry {
    final String utterance;
    final String datasetId;
    final List<String> responses;

    CatalogEntry(String utterance, String datasetId, List<String> responses) {
      this.utterance = utterance;
      this.datasetId = datasetId;
      this.responses = responses;
    }
  }

  public MockLlmClient(Path mockDir) throws IOException {
    if (!Files.isDirectory(mockDir)) {
      throw new IOException("llm-mock dir missing: " + mockDir);
    }
    DirectoryStream<Path> ds = Files.newDirectoryStream(mockDir, "*.json");
    try {
      for (Path p : ds) {
        String name = p.getFileName().toString();
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(text);
        if (root.has("utterance") && (root.has("response") || root.has("responses"))) {
          String utt = root.get("utterance").asText();
          String dsId = root.has("dataset_id") ? root.get("dataset_id").asText() : "fixture_v1";
          JsonNode resp = root.has("responses") ? root.get("responses") : root.get("response");
          pendingUtterances.add(new CatalogEntry(utt, dsId, responsesFromNode(resp)));
        }
        if (root.has("hash") && (root.has("response") || root.has("responses"))) {
          String hash = root.get("hash").asText().toLowerCase();
          JsonNode resp = root.has("responses") ? root.get("responses") : root.get("response");
          if (hash.matches("[0-9a-f]{16,}")) {
            byHash.put(hash, responsesFromNode(resp));
          }
        } else if (name.matches("(?i)[0-9a-f]{16,}\\.json")) {
          String hash = name.substring(0, name.length() - 5).toLowerCase();
          byHash.put(hash, Collections.singletonList(text));
        }
      }
    } finally {
      ds.close();
    }
  }

  /**
   * Bind catalog entries that declared {@code utterance} using the same PromptBuilder
   * the dialog will use, so message hashes match.
   */
  public void bindUtterances(PromptBuilder prompts) {
    for (CatalogEntry e : pendingUtterances) {
      List<LlmMessage> msgs = prompts.buildMessages(e.utterance, "");
      put(messagesHash(msgs), e.responses.toArray(new String[0]));
    }
  }

  /** Register a hash → response sequence (for unit tests). */
  public void put(String hash, String... responses) {
    List<String> list = new ArrayList<String>();
    Collections.addAll(list, responses);
    byHash.put(hash.toLowerCase(), list);
  }

  public static String messagesHash(List<LlmMessage> messages) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (LlmMessage m : messages) {
        md.update(m.role.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(m.content.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      byte[] dig = md.digest();
      StringBuilder sb = new StringBuilder(dig.length * 2);
      for (byte b : dig) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public LlmResponse chat(List<LlmMessage> messages, JsonNode responseSchemaHint, LlmOptions opts)
      throws LlmException {
    String hash = messagesHash(messages);
    List<String> seq = byHash.get(hash);
    if (seq == null || seq.isEmpty()) {
      for (Map.Entry<String, List<String>> e : byHash.entrySet()) {
        if (hash.startsWith(e.getKey()) || (e.getKey().length() >= 16
            && hash.startsWith(e.getKey().substring(0, 16)))) {
          seq = e.getValue();
          break;
        }
      }
    }
    if (seq == null || seq.isEmpty()) {
      throw new LlmException("MockLlmClient miss for hash=" + hash
          + " — add testdata/llm-mock entry or call bindUtterances()");
    }
    Integer idx = callIndex.get(hash);
    int i = idx == null ? 0 : idx;
    callIndex.put(hash, i + 1);
    String content = seq.get(Math.min(i, seq.size() - 1));
    LlmResponse r = new LlmResponse(content);
    r.model = "mock";
    r.latencyMs = 0;
    r.promptTokens = 0;
    r.completionTokens = 0;
    r.rawBody = content;
    return r;
  }

  private static List<String> responsesFromNode(JsonNode node) {
    List<String> out = new ArrayList<String>();
    if (node == null || node.isNull()) {
      return out;
    }
    if (node.isArray()) {
      for (JsonNode n : node) {
        out.add(stringify(n));
      }
    } else {
      out.add(stringify(node));
    }
    return out;
  }

  private static String stringify(JsonNode n) {
    if (n.isTextual()) {
      return n.asText();
    }
    return n.toString();
  }
}
