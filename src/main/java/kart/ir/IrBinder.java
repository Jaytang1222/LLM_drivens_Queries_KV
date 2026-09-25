package kart.ir;

import kart.config.AppConfig;
import kart.codec.Bytes;
import kart.exec.KvBackend;
import kart.geo.Projection;
import kart.geo.Rect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * DraftIR → BoundIR binder (T3.6): Asia/Shanghai times, lonlat→UTM, region_name, tid lookup.
 */
public final class IrBinder {

  public static final String STATUS_OK = "OK";
  public static final String STATUS_UNSUPPORTED_QUERY = "UNSUPPORTED_QUERY";
  public static final String STATUS_BIND_ERROR = "BIND_ERROR";
  /** Unknown region / incomplete facts that should return to clarification (design §6.2 OI-10). */
  public static final String STATUS_NEED_CLARIFICATION = "NEED_CLARIFICATION";

  private final AppConfig.RegionsConfig regions;
  private final KvBackend kv;
  private final String tableMeta;
  private final int shardCount;
  private final String manifestId;
  private final String semanticsVersion;
  private final Projection projection = new Projection();

  public IrBinder(AppConfig.RegionsConfig regions, KvBackend kv, String tableMeta,
                  int shardCount, String manifestId, String semanticsVersion) {
    this.regions = regions == null ? new AppConfig.RegionsConfig() : regions;
    this.kv = kv;
    this.tableMeta = tableMeta;
    this.shardCount = shardCount;
    this.manifestId = manifestId;
    this.semanticsVersion = semanticsVersion == null ? "point_similarity_v2" : semanticsVersion;
  }

  public static final class BindResult {
    public String status = STATUS_OK;
    public String error;
    public BoundIr bound;
    /** When status is NEED_CLARIFICATION, which logical field to ask about. */
    public String clarifyField;
  }

  public BindResult bind(DraftIr draft) {
    return bind(draft, System.currentTimeMillis());
  }

  /**
   * @param nowMs fixed wall-clock for relative time (Asia/Shanghai); required when draft uses relative times
   */
  public BindResult bind(DraftIr draft, long nowMs) {
    BindResult out = new BindResult();
    try {
      if (draft == null) {
        out.status = STATUS_BIND_ERROR;
        out.error = "null draft";
        return out;
      }
      if (containsForbiddenKeys(draft)) {
        out.status = STATUS_BIND_ERROR;
        out.error = "DraftIR contains forbidden physical keys";
        return out;
      }

      BoundIr b = new BoundIr();
      b.ir_version = draft.ir_version == null ? "1.0" : draft.ir_version;
      b.query_id = draft.query_id != null ? draft.query_id : ("q_" + UUID.randomUUID().toString().substring(0, 8));
      b.source = new BoundIr.Source();
      if (draft.source == null || isBlank(draft.source.dataset_id)) {
        out.status = STATUS_BIND_ERROR;
        out.error = "source.dataset_id required";
        return out;
      }
      b.source.dataset_id = draft.source.dataset_id;
      b.source.entity = draft.source.entity == null ? "trajectory" : draft.source.entity;

      b.semantics = new BoundIr.Semantics();
      if (draft.semantics != null) {
        if (draft.semantics.mode != null) {
          b.semantics.mode = draft.semantics.mode;
        }
        if (draft.semantics.coupling != null) {
          b.semantics.coupling = draft.semantics.coupling;
        }
      }

      if (draft.result == null || isBlank(draft.result.mode)) {
        out.status = STATUS_BIND_ERROR;
        out.error = "result.mode required";
        return out;
      }
      String mode = draft.result.mode;
      if ("COUNT".equalsIgnoreCase(mode)) {
        out.status = STATUS_UNSUPPORTED_QUERY;
        out.error = "COUNT not supported";
        return out;
      }
      b.result = new BoundIr.Result();
      b.result.mode = mode;
      b.result.k = draft.result.k;
      b.result.tie_breaker = "TID_ASC";

      if (draft.temporal != null) {
        try {
          b.temporal = new BoundIr.Temporal();
          b.temporal.start_ms = parseTemporalInstant(draft.temporal.start, nowMs, true);
          b.temporal.end_ms = parseTemporalInstant(draft.temporal.end, nowMs, false);
        } catch (NeedNowException e) {
          out.status = STATUS_NEED_CLARIFICATION;
          out.error = e.getMessage();
          out.clarifyField = "temporal";
          return out;
        }
        if (b.temporal.start_ms >= b.temporal.end_ms) {
          out.status = STATUS_BIND_ERROR;
          out.error = "temporal start must be < end";
          return out;
        }
      }

      if (draft.spatial != null) {
        if (draft.spatial.region_name != null && !draft.spatial.region_name.trim().isEmpty()
            && findRegion(draft.spatial.region_name.trim()) == null) {
          out.status = STATUS_NEED_CLARIFICATION;
          out.error = "unknown region_name: " + draft.spatial.region_name.trim()
              + "; provide a registered region or lon/lat rectangle";
          out.clarifyField = "spatial";
          return out;
        }
        Rect rect = resolveSpatial(draft.spatial);
        if (rect == null) {
          out.status = STATUS_NEED_CLARIFICATION;
          out.error = "incomplete spatial geometry; provide region_name or rectangle";
          out.clarifyField = "spatial";
          return out;
        }
        b.spatial = new BoundIr.Spatial();
        b.spatial.min_x = rect.minX;
        b.spatial.min_y = rect.minY;
        b.spatial.max_x = rect.maxX;
        b.spatial.max_y = rect.maxY;
        b.spatial.relation = "INTERSECTS";
        b.spatial.boundary = "INCLUDED";
        if (draft.spatial.region_name != null && !draft.spatial.region_name.trim().isEmpty()) {
          b.spatial.region_name = draft.spatial.region_name.trim();
        }
      }

      if (draft.predicates != null) {
        b.predicates = new ArrayList<BoundIr.Predicate>();
        for (DraftIr.Predicate p : draft.predicates) {
          if (p == null) {
            continue;
          }
          BoundIr.Predicate bp = new BoundIr.Predicate();
          bp.field = p.field;
          bp.op = p.op;
          bp.value = p.value;
          b.predicates.add(bp);
        }
      }

      if ("TOP_K".equals(mode)) {
        if (draft.similarity == null) {
          out.status = STATUS_BIND_ERROR;
          out.error = "TOP_K requires similarity";
          return out;
        }
        String metric = draft.similarity.metric == null ? null : draft.similarity.metric.trim();
        if (metric == null || metric.isEmpty()) {
          out.status = STATUS_BIND_ERROR;
          out.error = "similarity.metric required (DTW|FRECHET|HAUSDORFF); not defaulted";
          return out;
        }
        if (!kart.exec.TrajectorySimilarity.isSupported(metric)) {
          out.status = STATUS_UNSUPPORTED_QUERY;
          out.error = "unsupported metric=" + metric
              + "; supported: DTW, FRECHET, HAUSDORFF";
          return out;
        }
        if (isBlank(draft.similarity.reference_trajectory_id)) {
          out.status = STATUS_BIND_ERROR;
          out.error = "reference_trajectory_id required";
          return out;
        }
        Long tid = lookupTid(draft.similarity.reference_trajectory_id);
        if (tid == null) {
          out.status = STATUS_BIND_ERROR;
          out.error = "reference trajectory not found: " + draft.similarity.reference_trajectory_id;
          return out;
        }
        b.similarity = new BoundIr.Similarity();
        b.similarity.metric = metric;
        b.similarity.reference_tid = tid;
        b.similarity.scope = "FULL_TRAJECTORY";
        b.similarity.exclude_reference = draft.similarity.exclude_reference == null
            || draft.similarity.exclude_reference;
        b.similarity.local_distance = "EUCLIDEAN";
        b.similarity.normalization = "NONE";
        b.similarity.reference_chunk_count = lookupChunkCount(tid);
        if (b.result.k == null || b.result.k < 1) {
          out.status = STATUS_BIND_ERROR;
          out.error = "TOP_K requires k >= 1";
          return out;
        }
      } else if ("TRAJECTORY_IDS".equals(mode)) {
        if (draft.similarity != null) {
          out.status = STATUS_BIND_ERROR;
          out.error = "TRAJECTORY_IDS must not have similarity";
          return out;
        }
      } else {
        out.status = STATUS_UNSUPPORTED_QUERY;
        out.error = "unsupported result.mode: " + mode;
        return out;
      }

      // Cross-field rules (design §5.3)
      boolean noPred = b.temporal == null && b.spatial == null
          && (b.predicates == null || b.predicates.isEmpty());
      if (noPred) {
        out.status = STATUS_BIND_ERROR;
        out.error = "refuse unconstrained query (no temporal/spatial/predicates)";
        return out;
      }

      b.snapshot = new BoundIr.Snapshot();
      b.snapshot.manifest_id = manifestId != null ? manifestId : "tdrive_v1_ready";
      b.snapshot.semantics_version = semanticsVersion;

      out.bound = b;
      out.status = STATUS_OK;
      return out;
    } catch (ParseException e) {
      out.status = STATUS_BIND_ERROR;
      out.error = "time parse: " + e.getMessage();
      return out;
    } catch (Exception e) {
      out.status = STATUS_BIND_ERROR;
      out.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return out;
    }
  }

  private Rect resolveSpatial(DraftIr.Spatial spatial) {
    if (spatial.region_name != null && !spatial.region_name.trim().isEmpty()) {
      AppConfig.Region reg = findRegion(spatial.region_name.trim());
      if (reg == null) {
        return null;
      }
      if (reg.local_meters) {
        return new Rect(reg.min_lon, reg.min_lat, reg.max_lon, reg.max_lat);
      }
      return projection.metersEnvelope(reg.min_lon, reg.min_lat, reg.max_lon, reg.max_lat);
    }
    if (spatial.geometry != null
        && spatial.geometry.min_lon != null && spatial.geometry.min_lat != null
        && spatial.geometry.max_lon != null && spatial.geometry.max_lat != null) {
      double a = spatial.geometry.min_lon;
      double b = spatial.geometry.min_lat;
      double c = spatial.geometry.max_lon;
      double d = spatial.geometry.max_lat;
      return projection.metersEnvelope(a, b, c, d);
    }
    return null;
  }

  private AppConfig.Region findRegion(String name) {
    if (regions.regions == null) {
      return null;
    }
    for (AppConfig.Region r : regions.regions) {
      if (r != null && name.equalsIgnoreCase(r.name)) {
        return r;
      }
    }
    return null;
  }

  private Long lookupTid(String extId) throws IOException {
    if (kv == null) {
      return null;
    }
    Map<String, Long> map = loadExtToTid();
    return map.get(extId);
  }

  /** Catalog meta {@code d:cc} chunk count for cost-model ref_len. */
  private Integer lookupChunkCount(long tid) throws IOException {
    if (kv == null || tableMeta == null) {
      return null;
    }
    int shard = kart.codec.RowKeyCodec.shardOf(tid, shardCount) & 0xFF;
    Map<String, byte[]> cols = kv.get(tableMeta, kart.codec.RowKeyCodec.encodeMeta(shard, tid));
    if (cols == null || cols.isEmpty()) {
      return null;
    }
    byte[] cc = cols.get("d:cc");
    if (cc == null || cc.length < 4) {
      return null;
    }
    return Integer.valueOf(java.nio.ByteBuffer.wrap(cc).getInt());
  }

  private Map<String, Long> loadExtToTid() throws IOException {
    Map<String, Long> out = new HashMap<String, Long>();
    List<KvBackend.Row> rows = kv.scan(tableMeta, null, null, null);
    for (KvBackend.Row row : rows) {
      if (row.key == null || row.key.length != 9) {
        continue;
      }
      long tid = Bytes.readU64(row.key, 1);
      byte[] ext = row.columns.get("d:ext");
      if (ext != null) {
        out.put(new String(ext, StandardCharsets.UTF_8), tid);
      }
    }
    return out;
  }

  public static long parseShanghai(String iso) throws ParseException {
    if (iso == null) {
      throw new ParseException("null time", 0);
    }
    String s = iso.trim();
    // Try several patterns
    String[] patterns = {
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    };
    ParseException last = null;
    for (String p : patterns) {
      try {
        SimpleDateFormat fmt = new SimpleDateFormat(p);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        fmt.setLenient(false);
        // XXX may not work on all Java 8 — normalize +08:00
        String normalized = s;
        if (p.contains("Z") && normalized.matches(".*[+-]\\d{2}:\\d{2}$")) {
          normalized = normalized.replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");
        }
        return fmt.parse(normalized).getTime();
      } catch (ParseException e) {
        last = e;
      }
    }
    // Manual offset parse for +08:00
    if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}")) {
      String core = s.substring(0, 19);
      SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      return fmt.parse(core).getTime();
    }
    throw last == null ? new ParseException(s, 0) : last;
  }

  /**
   * Absolute Shanghai ISO, or relative: {@code now}, {@code last_&lt;n&gt;d}, {@code last_&lt;n&gt;h},
   * {@code -&lt;n&gt;d}, {@code -&lt;n&gt;h}. Relative forms require a fixed {@code nowMs}.
   */
  public static long parseTemporalInstant(String raw, long nowMs, boolean isStart)
      throws ParseException, NeedNowException {
    if (raw == null || raw.trim().isEmpty()) {
      throw new ParseException("null time", 0);
    }
    String s = raw.trim();
    if (isRelativeToken(s)) {
      if (nowMs <= 0L) {
        throw new NeedNowException(
            "relative temporal '" + s + "' requires fixed now/timezone context");
      }
      return resolveRelative(s, nowMs, isStart);
    }
    return parseShanghai(s);
  }

  static boolean isRelativeToken(String s) {
    String t = s.trim().toLowerCase();
    return "now".equals(t)
        || t.matches("last_\\d+[dh]")
        || t.matches("-\\d+[dh]")
        || t.matches("p\\d+d")
        || t.matches("pt\\d+h");
  }

  static long resolveRelative(String raw, long nowMs, boolean isStart) throws ParseException {
    String t = raw.trim().toLowerCase();
    if ("now".equals(t)) {
      return nowMs;
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:last_|-)?(\\d+)([dh])$")
        .matcher(t);
    if (m.matches()) {
      long n = Long.parseLong(m.group(1));
      long unit = "d".equals(m.group(2)) ? 86_400_000L : 3_600_000L;
      // last_Nd as start → now - n*unit; as end alone would be unusual — treat as offset before now
      return nowMs - n * unit;
    }
    if (t.matches("p\\d+d")) {
      long n = Long.parseLong(t.substring(1, t.length() - 1));
      return nowMs - n * 86_400_000L;
    }
    if (t.matches("pt\\d+h")) {
      long n = Long.parseLong(t.substring(2, t.length() - 1));
      return nowMs - n * 3_600_000L;
    }
    throw new ParseException("unsupported relative time: " + raw, 0);
  }

  public static final class NeedNowException extends Exception {
    public NeedNowException(String message) {
      super(message);
    }
  }

  private static boolean containsForbiddenKeys(DraftIr draft) {
    // Schema already rejects; binder double-checks serialized form via reflection-ish walk of known bad
    try {
      String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft);
      String lower = json.toLowerCase();
      return lower.contains("\"startrow\"") || lower.contains("\"stoprow\"")
          || lower.contains("\"start_row\"") || lower.contains("\"stop_row\"")
          || lower.contains("\"rowkey\"");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
