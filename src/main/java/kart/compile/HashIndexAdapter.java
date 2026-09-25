package kart.compile;

import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.VehicleHash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Emits idx_hash prefix scan tasks for a vehicle_id equality predicate.
 */
public final class HashIndexAdapter {

  /** shard(1) + tag(1) + hash128(16) prefix length. */
  public static final int PREFIX_LEN = 18;

  private final LayoutContext layout;

  public HashIndexAdapter(LayoutContext layout) {
    this.layout = layout;
  }

  public List<ScanTask> scanTasks(String vehicleId, String sourceNodeId) {
    byte[] hash = VehicleHash.hash128(vehicleId);
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      byte[] full = RowKeyCodec.encodeHash(shard, RowKeyCodec.HASH_FIELD_VEHICLE, hash, 0, 0);
      byte[] prefix = Arrays.copyOf(full, PREFIX_LEN);
      Optional<byte[]> succ = PrefixSuccessor.of(prefix);
      if (!succ.isPresent()) {
        throw new IllegalStateException("no prefix successor for hash prefix, shard=" + shard);
      }
      out.add(new ScanTask(layout.tableHash, prefix, succ.get(), sourceNodeId, shard,
          "hash:" + ScanTask.toHex(hash)));
    }
    return out;
  }
}
