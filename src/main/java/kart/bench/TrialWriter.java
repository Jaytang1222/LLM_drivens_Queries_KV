package kart.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only JSONL trial writer. */
public final class TrialWriter implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .disable(SerializationFeature.INDENT_OUTPUT);

  private final BufferedWriter out;

  public TrialWriter(Path jsonlPath) throws IOException {
    this(jsonlPath, false);
  }

  public TrialWriter(Path jsonlPath, boolean appendIfExists) throws IOException {
    Files.createDirectories(jsonlPath.getParent());
    boolean append = appendIfExists && Files.isRegularFile(jsonlPath);
    if (append) {
      this.out = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    } else {
      this.out = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
    }
  }

  public void write(Map<String, Object> row) throws IOException {
    out.write(MAPPER.writeValueAsString(row));
    out.write('\n');
    out.flush();
  }

  public static Map<String, Object> baseRow(String runId, String suiteKind, String suiteId,
                                            String stage, String queryId, String armOrFactor,
                                            int trial) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("run_id", runId);
    m.put("suite_kind", suiteKind);
    m.put("suite_id", suiteId);
    m.put("stage", stage);
    m.put("query_id", queryId);
    m.put("arm_or_factor", armOrFactor);
    m.put("trial", Integer.valueOf(trial));
    return m;
  }

  @Override
  public void close() throws IOException {
    out.close();
  }
}
