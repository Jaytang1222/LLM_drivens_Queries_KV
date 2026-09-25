package kart.bench;

import kart.compile.LayoutContext;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Documented cold/warm protocol. This harness does not claim OS page-cache
 * coldness unless {@code KART_DROP_PAGE_CACHE=1} actually succeeds.
 */
public final class CacheProtocol {

  public final String mode;
  public final int warmupPasses;
  public String hbaseBlockCacheFlush = "not_attempted";
  public boolean osPageCacheFlushed;
  public int hbaseFlushAttempts;
  public int hbaseFlushOk;
  public int osFlushAttempts;
  public int osFlushOk;
  public String osFlushNote = "not_attempted";
  /** Recorded by the runner. Rotation does not remove carry-over. */
  public String armOrderPolicy = "suite_order";
  /**
   * Warm untimed passes: {@code n/a} for cold; rotated like timed trials for warm.
   */
  public String warmupArmOrderPolicy = "n/a";

  private CacheProtocol(String mode, int warmupPasses) {
    this.mode = mode;
    this.warmupPasses = warmupPasses;
  }

  public static CacheProtocol from(String cache) {
    String mode = cache == null ? "cold" : cache.trim().toLowerCase();
    if (!"warm".equals(mode) && !"cold".equals(mode)) {
      mode = "cold";
    }
    int warm = 0;
    if ("warm".equals(mode)) {
      warm = envInt("KART_WARMUP_PASSES", 2);
      if (warm < 1) {
        warm = 2;
      }
    }
    return new CacheProtocol(mode, warm);
  }

  public boolean cacheEnforced() {
    return "cold".equals(mode)
        && hbaseFlushAttempts > 0
        && hbaseFlushOk == hbaseFlushAttempts
        && osFlushAttempts > 0
        && osFlushOk == osFlushAttempts;
  }

  public String protocolLabel() {
    if ("warm".equals(mode)) {
      return "in_process_warmup_" + warmupPasses + "_then_timed_samples";
    }
    if (cacheEnforced()) {
      return "hbase_blockcache_and_os_pagecache_flush_before_each_trial";
    }
    if ("ok".equals(hbaseBlockCacheFlush)) {
      return "hbase_blockcache_flush_only_os_pagecache_not_dropped";
    }
    return "no_proven_cache_flush_first_run_or_mixed";
  }

  public String formalWarning(int trials) {
    if ("warm".equals(mode)) {
      return null;
    }
    if (cacheEnforced()) {
      return null;
    }
    if (trials > 1) {
      return "trials_after_first_may_be_mixed_cache; do_not_call_cold_p50";
    }
    return "first_run_without_os_pagecache_flush; not_a_proven_cold_cache";
  }

  /**
   * Best-effort HBase BlockCache clear. OS drop_caches only when explicitly enabled.
   */
  public void flushBeforeTrial(KvBackend kv, LayoutContext layout) {
    if (!"cold".equals(mode)) {
      return;
    }
    hbaseFlushAttempts++;
    if (!(kv instanceof HBaseBackend)) {
      hbaseBlockCacheFlush = "not_hbase";
      maybeDropPageCache();
      return;
    }
    try {
      org.apache.hadoop.hbase.client.Connection conn = ((HBaseBackend) kv).connection();
      if (conn == null || conn.isClosed()) {
        hbaseBlockCacheFlush = "connection_closed";
        maybeDropPageCache();
        return;
      }
      try (org.apache.hadoop.hbase.client.Admin admin = conn.getAdmin()) {
        Method clear = findClearBlockCache(admin.getClass());
        if (clear == null) {
          hbaseBlockCacheFlush = "api_unavailable";
          maybeDropPageCache();
          return;
        }
        String[] tables = new String[] {
            layout.tableRaw, layout.tableMeta, layout.tableTime, layout.tableZorder, layout.tableHash
        };
        int ok = 0;
        for (String name : tables) {
          if (name == null || name.isEmpty()) {
            continue;
          }
          org.apache.hadoop.hbase.TableName tn = org.apache.hadoop.hbase.TableName.valueOf(name);
          if (!admin.tableExists(tn)) {
            continue;
          }
          clear.invoke(admin, tn);
          ok++;
        }
        if (ok > 0) {
          hbaseFlushOk++;
          hbaseBlockCacheFlush = "ok";
        } else {
          hbaseBlockCacheFlush = "no_matching_tables";
        }
      }
    } catch (Exception e) {
      hbaseBlockCacheFlush = "failed:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
    maybeDropPageCache();
  }

  public Map<String, Object> toMeta(int trialCount) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("mode", mode);
    m.put("warmup_passes", Integer.valueOf(warmupPasses));
    m.put("cache_enforced", Boolean.valueOf(cacheEnforced()));
    m.put("protocol", protocolLabel());
    m.put("hbase_block_cache_flush", hbaseBlockCacheFlush);
    m.put("hbase_flush_attempts", Integer.valueOf(hbaseFlushAttempts));
    m.put("hbase_flush_ok", Integer.valueOf(hbaseFlushOk));
    m.put("os_page_cache_flushed", Boolean.valueOf(osPageCacheFlushed));
    m.put("os_flush_attempts", Integer.valueOf(osFlushAttempts));
    m.put("os_flush_ok", Integer.valueOf(osFlushOk));
    m.put("os_page_cache_note", osFlushNote);
    m.put("arm_order_policy", armOrderPolicy);
    m.put("warmup_arm_order_policy", warmupArmOrderPolicy);
    m.put("sequential_arm_cache_carryover", Boolean.TRUE);
    m.put("sequential_arm_note",
        "arms still share one RegionServer in one process; order rotates by query and trial, "
            + "but a later arm can still see a warmer cache");
    if ("warm".equals(mode)) {
      m.put("warmup_note",
          "untimed warmup uses the same rotation family as timed samples "
              + "(shift = warmup_pass + query_index); independently, timed trials use "
              + "shift = trial-1 + query_index");
    }
    String warn = formalWarning(trialCount);
    if (warn != null) {
      m.put("warning", warn);
    }
    return m;
  }

  private void maybeDropPageCache() {
    osFlushAttempts++;
    if (!"1".equals(env("KART_DROP_PAGE_CACHE", ""))) {
      osFlushNote = "skipped_KART_DROP_PAGE_CACHE_not_set";
      osPageCacheFlushed = false;
      return;
    }
    try {
      Process p = new ProcessBuilder("sudo", "-n", "sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches")
          .redirectErrorStream(true)
          .start();
      int code = p.waitFor();
      if (code == 0) {
        osFlushOk++;
        osFlushNote = "drop_caches_ok";
      } else {
        osFlushNote = "drop_caches_exit_" + code;
      }
    } catch (Exception e) {
      osFlushNote = "drop_caches_failed:" + e.getMessage();
    }
    osPageCacheFlushed = osFlushAttempts > 0 && osFlushOk == osFlushAttempts;
  }

  private static Method findClearBlockCache(Class<?> adminClass) {
    Class<?> c = adminClass;
    while (c != null) {
      try {
        return c.getMethod("clearBlockCache", org.apache.hadoop.hbase.TableName.class);
      } catch (NoSuchMethodException ignore) {
        c = c.getSuperclass();
      }
    }
    return null;
  }

  private static int envInt(String key, int def) {
    String v = env(key, null);
    if (v == null) {
      return def;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static String env(String key, String def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    return v.trim();
  }
}
