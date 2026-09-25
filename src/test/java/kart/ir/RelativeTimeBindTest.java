package kart.ir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RelativeTimeBindTest {

  @Test
  public void nowAndLast7d() throws Exception {
    long now = 1_700_000_000_000L;
    long end = IrBinder.parseTemporalInstant("now", now, false);
    long start = IrBinder.parseTemporalInstant("last_7d", now, true);
    assertEquals(now, end);
    assertEquals(now - 7L * 86_400_000L, start);
  }

  @Test
  public void relativeWithoutNowNeedsClarification() {
    assertThrows(IrBinder.NeedNowException.class,
        () -> IrBinder.parseTemporalInstant("last_2h", 0L, true));
  }

  @Test
  public void absoluteStillWorks() throws Exception {
    long ms = IrBinder.parseShanghai("2008-02-02T08:00:00+08:00");
    assertTrue(ms > 0);
  }
}
