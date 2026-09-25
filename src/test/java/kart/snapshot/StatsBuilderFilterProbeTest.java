package kart.snapshot;

import kart.catalog.StatsSnapshot;
import kart.data.CanonicalPoint;
import kart.data.Chunk;
import kart.exec.ExactSTFilter;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** StatsBuilder filter_pass probe must use ExactSTFilter (same as executor). */
public final class StatsBuilderFilterProbeTest {

  @Test
  void probeMatchesExactSTFilterOnSameHalfWindow() throws Exception {
    List<CanonicalPoint> pts = new ArrayList<CanonicalPoint>();
    pts.add(new CanonicalPoint("v", "t", 1L, 0, 1000L, 10.0, 10.0));
    pts.add(new CanonicalPoint("v", "t", 1L, 1, 2000L, 20.0, 20.0));
    pts.add(new CanonicalPoint("v", "t", 1L, 2, 3000L, 30.0, 30.0));
    Chunk c = new Chunk(1L, 0, pts);

    StatsSnapshot s = new StatsSnapshot();
    Method m = StatsBuilder.class.getDeclaredMethod("sampleExactFilterProbe",
        Chunk.class, StatsSnapshot.class);
    m.setAccessible(true);
    m.invoke(null, c, s);

    assertEquals(1L, s.filter_pass_samples);

    // Rebuild the same half-window IR the probe uses and compare ExactSTFilter.
    long span = Math.max(1L, c.tMaxMs - c.tMinMs);
    long qStart = c.tMinMs + span / 4;
    long qEnd = c.tMaxMs - span / 4;
    if (qEnd <= qStart) {
      qEnd = c.tMaxMs + 1;
      qStart = c.tMinMs;
    }
    double midX = (c.minX + c.maxX) / 2.0;
    double midY = (c.minY + c.maxY) / 2.0;
    double halfW = Math.max(1e-6, (c.maxX - c.minX) / 4.0);
    double halfH = Math.max(1e-6, (c.maxY - c.minY) / 4.0);
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = qStart;
    ir.temporal.end_ms = qEnd;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = midX - halfW;
    ir.spatial.max_x = midX + halfW;
    ir.spatial.min_y = midY - halfH;
    ir.spatial.max_y = midY + halfH;

    List<Chunk.DecodedPoint> decoded = new ArrayList<Chunk.DecodedPoint>();
    for (CanonicalPoint p : pts) {
      decoded.add(new Chunk.DecodedPoint(p.timestampMs, p.xM, p.yM));
    }
    boolean expect = ExactSTFilter.matches(ir, decoded, null);
    assertEquals(expect, s.filter_pass_hits == 1L);
    assertTrue(s.filter_pass_samples > 0);
  }
}
