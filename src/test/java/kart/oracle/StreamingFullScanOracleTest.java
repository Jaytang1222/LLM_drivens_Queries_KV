package kart.oracle;

import kart.data.Trajectory;
import kart.ir.BoundIr;
import kart.snapshot.FixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingFullScanOracleTest {

  @Test
  void streamingMatchesInMemoryFixtureTopK() {
    BoundIr ir = FullScanOracleAndFixtureTest.fixtureQuery();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    FullScanOracle.Answer mem = new FullScanOracle(trajs).evaluate(ir);

    Map<Long, Trajectory> refs = StreamingFullScanOracle.indexByTid(trajs);
    StreamingFullScanOracle.Session session =
        new StreamingFullScanOracle.Session(Arrays.asList(ir), refs);
    for (Trajectory t : trajs) {
      session.accept(t);
    }
    FullScanOracle.Answer stream = session.answer(0);

    assertEquals(mem.trajectoryIds, stream.trajectoryIds);
    assertEquals(mem.topK.size(), stream.topK.size());
    for (int i = 0; i < mem.topK.size(); i++) {
      assertEquals(mem.topK.get(i).tid, stream.topK.get(i).tid);
      assertEquals(mem.topK.get(i).trajectoryId, stream.topK.get(i).trajectoryId);
      assertEquals(mem.topK.get(i).distance, stream.topK.get(i).distance, 1e-9);
    }
  }
}
