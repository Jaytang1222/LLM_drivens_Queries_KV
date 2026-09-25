package kart.bench;

import kart.exec.HBaseBackend;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort HBase/JDK facts for meta.json. Never claims a clean 2.2.3 cluster
 * when the doctor/evidence file shows a mixed classpath.
 */
public final class HBaseEnvProbe {

  private HBaseEnvProbe() {}

  public static Map<String, Object> collect(HBaseBackend kv, Path evidenceFile) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    Package pkg = org.apache.hadoop.hbase.client.Connection.class.getPackage();
    if (pkg != null) {
      m.put("hbase_client_implementation_version", pkg.getImplementationVersion());
      m.put("hbase_client_specification_version", pkg.getSpecificationVersion());
    }
    m.put("conclusion_boundary",
        "single-node mixed HBase classpath; relative results only; do not extrapolate");
    if (kv != null && kv.connection() != null && !kv.connection().isClosed()) {
      try {
        org.apache.hadoop.hbase.client.Admin admin = kv.connection().getAdmin();
        try {
          Object metrics = invoke(admin, "getClusterMetrics");
          if (metrics == null) {
            metrics = invoke(admin, "getClusterStatus");
          }
          if (metrics != null) {
            String ver = invokeStr(metrics, "getHBaseVersion");
            if (ver != null) {
              m.put("hbase_cluster_version", ver);
            }
            Object liveMap = invoke(metrics, "getLiveServerMetrics");
            if (liveMap instanceof Map) {
              m.put("live_region_servers", Integer.valueOf(((Map<?, ?>) liveMap).size()));
            } else {
              Object n = invoke(metrics, "getServersSize");
              if (n instanceof Number) {
                m.put("live_region_servers", Integer.valueOf(((Number) n).intValue()));
              }
            }
            String cid = invokeStr(metrics, "getClusterId");
            if (cid != null) {
              m.put("cluster_id", cid);
            }
          } else {
            m.put("cluster_probe", "no_getClusterMetrics_or_getClusterStatus");
          }
        } finally {
          admin.close();
        }
      } catch (Exception e) {
        m.put("cluster_probe_error", e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : (": " + e.getMessage())));
      }
    } else {
      m.put("cluster_probe", "connection_closed_or_null");
    }
    if (evidenceFile != null && Files.isRegularFile(evidenceFile)) {
      m.put("evidence_path", evidenceFile.toString());
      m.put("doctor_excerpt", excerpt(evidenceFile, 48));
    }
    return m;
  }

  private static Object invoke(Object target, String method) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      return m.invoke(target);
    } catch (Exception e) {
      return null;
    }
  }

  private static String invokeStr(Object target, String method) {
    Object v = invoke(target, method);
    return v == null ? null : String.valueOf(v);
  }

  private static String excerpt(Path file, int maxLines) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      StringBuilder sb = new StringBuilder();
      int n = Math.min(maxLines, lines.size());
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          sb.append('\n');
        }
        sb.append(lines.get(i));
      }
      if (lines.size() > n) {
        sb.append("\n...");
      }
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }
}
