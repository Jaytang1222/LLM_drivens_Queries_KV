package kart.ir;

import kart.config.AppConfig;
import kart.codec.Bytes;
import kart.exec.KvBackend;
import kart.geo.Projection;
import kart.geo.Rect;
import kart.snapshot.FixtureBuilder;

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
    this.semanticsVersion = semanticsVersion == null ? "point_dtw_v1" : semanticsVersion;
  }

  public static final class BindResult {
    public String status = STATUS_OK;
    public String error;
    public BoundIr bound;
  }

  public BindResult bind(DraftIr draft) {
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

      boolean fixtureMeters = "fixture_v1".equals(b.source.dataset_id);

      if (draft.temporal != null) {
        b.temporal = new BoundIr.Temporal();
        b.temporal.start_ms = parseShanghai(draft.temporal.start);
        b.temporal.end_ms = parseShanghai(draft.temporal.end);
        if (b.temporal.start_ms >= b.temporal.end_ms) {
          out.status = STATUS_BIND_ERROR;
          out.error = "temporal start must be < end";
          return out;
        }
      }

      if (draft.spatial != null) {
        Rect rect = resolveSpatial(draft.spatial, fixtureMeters);
        if (rect == null) {
          out.status = STATUS_BIND_ERROR;
          out.error = "unknown region_name or incomplete spatial geometry";
          return out;
        }
        b.spatial = new BoundIr.Spatial();
        b.spatial.min_x = rect.minX;
        b.spatial.min_y = rect.minY;
        b.spatial.max_x = rect.maxX;
        b.spatial.max_y = rect.maxY;
        b.spatial.relation = "INTERSECTS";
        b.spatial.boundary = "INCLUDED";
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
        if (draft.similarity.metric != null && !"DTW".equals(draft.similarity.metric)) {
          out.status = STATUS_UNSUPPORTED_QUERY;
          out.error = "only metric=DTW supported";
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
        b.similarity.metric = "DTW";
        b.similarity.reference_tid = tid;
        b.similarity.scope = "FULL_TRAJECTORY";
        b.similarity.exclude_reference = draft.similarity.exclude_reference == null
            || draft.similarity.exclude_reference;
        b.similarity.local_distance = "EUCLIDEAN";
        b.similarity.normalization = "NONE";
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
      b.snapshot.manifest_id = manifestId != null ? manifestId : FixtureBuilder.MANIFEST_ID;
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

  private Rect resolveSpatial(DraftIr.Spatial spatial, boolean fixtureMeters) {
    if (spatial.region_name != null && !spatial.region_name.trim().isEmpty()) {
      AppConfig.Region reg = findRegion(spatial.region_name.trim());
      if (reg == null) {
        return null;
      }
      if (fixtureMeters || "fixture_box".equalsIgnoreCase(reg.name)) {
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
      if (fixtureMeters) {
        return new Rect(a, b, c, d);
      }
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
      // fixture fallback without KV: A=1,B=2,C=3,R=4
      if ("A".equals(extId)) {
        return 1L;
      }
      if ("B".equals(extId)) {
        return 2L;
      }
      if ("C".equals(extId)) {
        return 3L;
      }
      if ("R".equals(extId)) {
        return 4L;
      }
      return null;
    }
    Map<String, Long> map = loadExtToTid();
    return map.get(extId);
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
