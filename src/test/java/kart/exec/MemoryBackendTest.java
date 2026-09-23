package kart.exec;

import kart.codec.Bytes;
import kart.codec.RowKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBackendTest {

  @Test
  void scanHalfOpenAndOrderWithRowKeyCodec() throws Exception {
    MemoryBackend kv = new MemoryBackend();
    String table = "traj_raw_v1";
    for (long tid = 1; tid <= 3; tid++) {
      int shard = RowKeyCodec.shardOf(tid, 4) & 0xFF;
      byte[] key = RowKeyCodec.encodeRaw(shard, tid, 0);
      Map<String, byte[]> cols = new HashMap<String, byte[]>();
      cols.put("d:p", new byte[]{(byte) tid});
      kv.put(table, key, cols);
    }

    byte[] start = RowKeyCodec.encodeRaw(1, 0, 0); // tid 1 has shard 1
    // stop after tid=1 chunk=0 → only that row if start is exact
    byte[] stop = RowKeyCodec.encodeRaw(1, 1, 1);
    List<KvBackend.Row> rows = kv.scan(table, start, stop, Collections.singletonList("d:p"));
    assertEquals(1, rows.size());
    assertEquals(1, RowKeyCodec.decodeRaw(rows.get(0).key).tid);

    // ordering: scan full table
    List<KvBackend.Row> all = kv.scan(table, null, null, null);
    for (int i = 1; i < all.size(); i++) {
      assertTrue(Bytes.compareUnsigned(all.get(i - 1).key, all.get(i).key) < 0);
    }
    kv.close();
  }
}
