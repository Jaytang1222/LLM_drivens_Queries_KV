package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One long-lived Python process. Model load and the discarded warmup RPC
 * happen in {@link #start} and are not part of per-query {@code t_plan}.
 * A dead process is not restarted inside a timed call.
 */
final class ResidentPyServer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Process proc;
  private BufferedWriter toProc;
  private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
  private final StringBuilder errTail = new StringBuilder();
  private volatile boolean dead;
  private boolean failed;
  private String failReason;
  private long loadMs;

  synchronized void start(List<String> command, Path directory, String warmupJson,
      long readyTimeoutMs, long rpcTimeoutMs) throws Exception {
    if (alive()) {
      return;
    }
    if (failed) {
      throw new IllegalStateException(failReason != null ? failReason : "resident server dead");
    }
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.directory(directory.toFile());
      pb.environment().put("PYTHONUNBUFFERED", "1");
      proc = pb.start();
      toProc = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
      startReader(proc.getInputStream(), lines);
      startErrDrain(proc.getErrorStream());
      JsonNode ready = awaitJson(readyTimeoutMs);
      if (!"READY".equals(ready.path("status").asText())) {
        throw new IllegalStateException(ready.path("error").asText("server not READY"));
      }
      if (ready.has("load_ms")) {
        loadMs = ready.path("load_ms").asLong(0);
      }
      if (warmupJson != null) {
        JsonNode warm = rpc(warmupJson, rpcTimeoutMs);
        if (!"OK".equals(warm.path("status").asText())) {
          throw new IllegalStateException(warm.path("error").asText("warmup failed"));
        }
      }
    } catch (Exception e) {
      failed = true;
      failReason = e.getMessage();
      close();
      throw e;
    }
  }

  synchronized JsonNode rpc(String jsonLine, long timeoutMs) throws Exception {
    if (!alive()) {
      failed = true;
      if (failReason == null) {
        failReason = "resident server dead";
      }
      throw new IllegalStateException(failReason);
    }
    toProc.write(jsonLine);
    toProc.write('\n');
    toProc.flush();
    JsonNode node = awaitJson(timeoutMs);
    if (proc == null || !proc.isAlive()) {
      failed = true;
      failReason = "resident server exited";
      dead = true;
    }
    return node;
  }

  long loadMs() {
    return loadMs;
  }

  private boolean alive() {
    return proc != null && proc.isAlive() && !dead && !failed;
  }

  private JsonNode awaitJson(long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (dead && lines.isEmpty()) {
        throw new IllegalStateException("server exited: " + errTail);
      }
      String line = lines.poll(200, TimeUnit.MILLISECONDS);
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (!trimmed.startsWith("{")) {
        continue;
      }
      return MAPPER.readTree(trimmed);
    }
    throw new IllegalStateException("timeout waiting for server: " + errTail);
  }

  private void startReader(final InputStream in, final BlockingQueue<String> queue) {
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
          String line;
          while ((line = br.readLine()) != null) {
            queue.offer(line);
          }
        } catch (IOException e) {
          appendErr(e.getMessage());
        } finally {
          dead = true;
        }
      }
    }, "resident-py-stdout");
    t.setDaemon(true);
    t.start();
  }

  private void startErrDrain(final InputStream err) {
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8));
          String line;
          while ((line = br.readLine()) != null) {
            appendErr(line);
          }
        } catch (IOException ignored) {
          // process is going away
        }
      }
    }, "resident-py-stderr");
    t.setDaemon(true);
    t.start();
  }

  private void appendErr(String line) {
    if (line == null) {
      return;
    }
    synchronized (errTail) {
      if (errTail.length() > 800) {
        errTail.delete(0, errTail.length() - 400);
      }
      errTail.append(line).append('\n');
    }
  }

  private void close() {
    if (proc != null) {
      proc.destroy();
      proc = null;
    }
  }
}
