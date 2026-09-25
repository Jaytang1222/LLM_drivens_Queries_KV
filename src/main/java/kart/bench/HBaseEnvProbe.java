package kart.bench;

import kart.exec.HBaseBackend;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Best-effort HBase/JDK facts for meta.json. Never claims a clean 2.2.3 cluster
 * when the doctor/evidence file shows a mixed classpath.
 */
public final class HBaseEnvProbe {

  private HBaseEnvProbe() {}

  public static Map<String, Object> collect(HBaseBackend kv, Path evidenceFile) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    String[] clientVer = clientJarVersions();
    m.put("hbase_client_implementation_version", clientVer[0]);
    m.put("hbase_client_specification_version", clientVer[1]);
    m.put("hbase_client_jar", clientVer[2]);
    m.put("hbase_client_version_source", clientVer[3]);
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
          Object status = invoke(admin, "getClusterStatus");
          StringBuilder probeError = new StringBuilder();
          if (metrics == null && status == null) {
            m.put("cluster_probe", "no_getClusterMetrics_or_getClusterStatus");
          }
          String ver = invokeStr(metrics, "getHBaseVersion");
          if (ver == null) {
            ver = invokeStr(status, "getHBaseVersion");
          }
          if (ver != null) {
            m.put("hbase_cluster_version", ver);
          }
          Integer rsCount = regionServerCount(metrics, status, probeError);
          if (rsCount != null) {
            m.put("live_region_servers", rsCount);
            m.put("region_server_count", rsCount);
          } else {
            m.put("region_server_count", null);
            m.put("region_server_probe_error",
                probeError.length() == 0 ? "no_live_server_api" : probeError.toString());
          }
          String cid = invokeStr(metrics, "getClusterId");
          if (cid == null) {
            cid = invokeStr(status, "getClusterId");
          }
          if (cid != null) {
            m.put("cluster_id", cid);
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

  /**
   * Runtime HBase client version of the classes actually loaded.
   * A shaded {@code kart.jar} manifest is not the HBase manifest.
   * Returns {@code [impl, spec, codeSourcePath, source]}.
   */
  public static String[] clientJarVersions() {
    String[] out = new String[] {null, null, null, "unresolved"};
    String manifestImpl = null;
    String manifestSpec = null;
    try {
      Class<?> cls = org.apache.hadoop.hbase.client.Connection.class;
      java.security.CodeSource cs = cls.getProtectionDomain().getCodeSource();
      if (cs != null && cs.getLocation() != null) {
        java.io.File file = new java.io.File(cs.getLocation().toURI());
        out[2] = file.getPath();
        if (file.isFile() && isDedicatedHBaseClientJar(file.getName())) {
          JarFile jar = new JarFile(file);
          try {
            Manifest mf = jar.getManifest();
            if (mf != null) {
              Attributes main = mf.getMainAttributes();
              manifestImpl = attr(main, "Implementation-Version");
              manifestSpec = attr(main, "Specification-Version");
            }
          } finally {
            jar.close();
          }
        }
      }
    } catch (Exception ignore) {
      // fall through to classpath resources
    }
    String pomVersion = pomProperty("META-INF/maven/org.apache.hbase/hbase-client/pom.properties", "version");
    String loaded = versionInfoVersion();
    String[] fromLib = readDedicatedClientManifest(pomVersion);
    if (manifestImpl == null && fromLib[0] != null) {
      manifestImpl = fromLib[0];
    }
    if (manifestSpec == null && fromLib[1] != null) {
      manifestSpec = fromLib[1];
    }
    String[] resolved = resolveClientVersion(out[2], manifestImpl, manifestSpec, pomVersion, loaded,
        fromLib[0] != null);
    out[0] = resolved[0];
    out[1] = resolved[1];
    out[3] = resolved[2];
    return out;
  }

  /**
   * @return {@code [implementation, specification, source]}
   */
  static String[] resolveClientVersion(String codeSourcePath, String manifestImpl,
                                       String manifestSpec, String pomVersion,
                                       String versionInfo) {
    return resolveClientVersion(codeSourcePath, manifestImpl, manifestSpec, pomVersion,
        versionInfo, false);
  }

  static String[] resolveClientVersion(String codeSourcePath, String manifestImpl,
                                       String manifestSpec, String pomVersion,
                                       String versionInfo, boolean usedExternalClientJar) {
    if (isDedicatedHBaseClientJar(codeSourcePath) && manifestImpl != null) {
      return new String[] {manifestImpl, manifestSpec, "hbase_client_jar_manifest"};
    }
    if (pomVersion != null) {
      String source = usedExternalClientJar && manifestSpec != null
          ? "classpath_pom_plus_lib_hbase_client_manifest_spec"
          : "classpath_hbase_client_pom_properties";
      return new String[] {pomVersion, manifestSpec, source};
    }
    if (versionInfo != null) {
      return new String[] {versionInfo, manifestSpec,
          usedExternalClientJar ? "VersionInfo_plus_lib_manifest_spec" : "hbase_common_VersionInfo"};
    }
    if (manifestImpl != null) {
      return new String[] {manifestImpl, manifestSpec, "lib_hbase_client_jar_manifest"};
    }
    return new String[] {null, null, "unresolved"};
  }

  /** Prefer HBASE_HOME/lib, else Maven local, for Spec-Version only when classes are shaded. */
  private static String[] readDedicatedClientManifest(String expectedVersion) {
    String[] empty = new String[] {null, null};
    for (java.io.File jar : candidateClientJars(expectedVersion)) {
      try {
        JarFile jf = new JarFile(jar);
        try {
          Manifest mf = jf.getManifest();
          if (mf == null) {
            continue;
          }
          Attributes main = mf.getMainAttributes();
          String impl = attr(main, "Implementation-Version");
          String spec = attr(main, "Specification-Version");
          if (expectedVersion != null && impl != null && !expectedVersion.equals(impl)) {
            continue;
          }
          if (impl != null || spec != null) {
            return new String[] {impl, spec};
          }
        } finally {
          jf.close();
        }
      } catch (Exception ignore) {
        // try next
      }
    }
    return empty;
  }

  private static List<java.io.File> candidateClientJars(String expectedVersion) {
    List<java.io.File> out = new ArrayList<java.io.File>();
    String ver = expectedVersion == null ? "*" : expectedVersion;
    String hbaseHome = System.getenv("HBASE_HOME");
    if (hbaseHome != null && !hbaseHome.trim().isEmpty()) {
      java.io.File lib = new java.io.File(hbaseHome.trim(), "lib");
      addIfPresent(out, new java.io.File(lib, "hbase-client-" + ver + ".jar"));
    }
    String home = System.getProperty("user.home");
    if (home != null) {
      addIfPresent(out, new java.io.File(home,
          ".m2/repository/org/apache/hbase/hbase-client/" + ver + "/hbase-client-" + ver + ".jar"));
    }
    return out;
  }

  private static void addIfPresent(List<java.io.File> out, java.io.File f) {
    if (f != null && f.isFile()) {
      out.add(f);
    }
  }

  static boolean isDedicatedHBaseClientJar(String pathOrName) {
    if (pathOrName == null) {
      return false;
    }
    String name = pathOrName.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    return name.startsWith("hbase-client-") && name.endsWith(".jar");
  }

  public static Integer regionServerCount(Object metrics, Object status, StringBuilder errors) {
    Integer n = countOf(metrics, "getLiveServerMetrics", errors);
    if (n != null) {
      return n;
    }
    n = numberOf(metrics, "getServersSize", errors);
    if (n != null) {
      return n;
    }
    n = numberOf(status, "getServersSize", errors);
    if (n != null) {
      return n;
    }
    n = countOf(status, "getServers", errors);
    if (n != null) {
      return n;
    }
    n = countOf(status, "getLiveServerMetrics", errors);
    if (n != null) {
      return n;
    }
    return null;
  }

  private static String pomProperty(String resource, String key) {
    java.io.InputStream in = HBaseEnvProbe.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      return null;
    }
    try {
      java.util.Properties props = new java.util.Properties();
      props.load(in);
      String v = props.getProperty(key);
      if (v == null || v.trim().isEmpty()) {
        return null;
      }
      return v.trim();
    } catch (Exception e) {
      return null;
    } finally {
      try {
        in.close();
      } catch (Exception ignore) {
        //
      }
    }
  }

  private static String versionInfoVersion() {
    try {
      Class<?> cls = Class.forName("org.apache.hadoop.hbase.util.VersionInfo");
      Method m = cls.getMethod("getVersion");
      Object v = m.invoke(null);
      if (v == null) {
        return null;
      }
      String s = String.valueOf(v).trim();
      if (s.isEmpty() || "Unknown".equalsIgnoreCase(s)) {
        return null;
      }
      return s;
    } catch (Exception e) {
      return null;
    }
  }

  private static Integer numberOf(Object target, String method, StringBuilder errors) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      Object v = m.invoke(target);
      if (v instanceof Number) {
        return Integer.valueOf(((Number) v).intValue());
      }
      errors.append(method).append("=not_number; ");
    } catch (Exception e) {
      errors.append(method).append('=').append(e.getClass().getSimpleName()).append("; ");
    }
    return null;
  }

  private static Integer countOf(Object target, String method, StringBuilder errors) {
    if (target == null) {
      return null;
    }
    try {
      Method m = target.getClass().getMethod(method);
      Object v = m.invoke(target);
      if (v instanceof Map) {
        return Integer.valueOf(((Map<?, ?>) v).size());
      }
      if (v instanceof java.util.Collection) {
        return Integer.valueOf(((java.util.Collection<?>) v).size());
      }
      errors.append(method).append("=not_collection; ");
    } catch (Exception e) {
      errors.append(method).append('=').append(e.getClass().getSimpleName()).append("; ");
    }
    return null;
  }

  private static String attr(Attributes a, String key) {
    if (a == null) {
      return null;
    }
    String v = a.getValue(key);
    if (v == null || v.trim().isEmpty()) {
      return null;
    }
    return v.trim();
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
