package kart.codec;

/**
 * RowKey encode/decode for the five MVP tables (design.md §4).
 */
public final class RowKeyCodec {

  public static final byte HASH_FIELD_VEHICLE = 0x01;

  private RowKeyCodec() {}

  public static byte shardOf(long tid, int shardCount) {
    if (shardCount <= 0) {
      throw new IllegalArgumentException("shardCount must be > 0");
    }
    return (byte) (Long.remainderUnsigned(tid, shardCount));
  }

  // --- traj_raw: U8 shard | U64 tid | U32 chunk (13 B) ---

  public static byte[] encodeRaw(int shard, long tid, long chunkId) {
    return Bytes.concat(Bytes.u8(shard), Bytes.u64(tid), Bytes.u32(chunkId));
  }

  public static RawKey decodeRaw(byte[] key) {
    requireLen(key, 13, "raw");
    return new RawKey(Bytes.readU8(key, 0), Bytes.readU64(key, 1), Bytes.readU32(key, 9));
  }

  // --- traj_meta: U8 shard | U64 tid (9 B) ---

  public static byte[] encodeMeta(int shard, long tid) {
    return Bytes.concat(Bytes.u8(shard), Bytes.u64(tid));
  }

  public static MetaKey decodeMeta(byte[] key) {
    requireLen(key, 9, "meta");
    return new MetaKey(Bytes.readU8(key, 0), Bytes.readU64(key, 1));
  }

  // --- idx_time: U8 shard | U64 bucket | U64 tid | U32 chunk (21 B) ---

  public static byte[] encodeTime(int shard, long bucket, long tid, long chunkId) {
    return Bytes.concat(Bytes.u8(shard), Bytes.u64(bucket), Bytes.u64(tid), Bytes.u32(chunkId));
  }

  public static TimeKey decodeTime(byte[] key) {
    requireLen(key, 21, "time");
    return new TimeKey(
        Bytes.readU8(key, 0),
        Bytes.readU64(key, 1),
        Bytes.readU64(key, 9),
        Bytes.readU32(key, 17));
  }

  // --- idx_zorder: U8 shard | U64 z_cell | U64 tid | U32 chunk (21 B) ---

  public static byte[] encodeZorder(int shard, long zCell, long tid, long chunkId) {
    return Bytes.concat(Bytes.u8(shard), Bytes.u64(zCell), Bytes.u64(tid), Bytes.u32(chunkId));
  }

  public static ZorderKey decodeZorder(byte[] key) {
    requireLen(key, 21, "zorder");
    return new ZorderKey(
        Bytes.readU8(key, 0),
        Bytes.readU64(key, 1),
        Bytes.readU64(key, 9),
        Bytes.readU32(key, 17));
  }

  // --- idx_hash: U8 shard | U8 tag | 16B hash | U64 tid | U32 chunk (30 B) ---

  public static byte[] encodeHash(int shard, byte fieldTag, byte[] hash128, long tid, long chunkId) {
    if (hash128 == null || hash128.length != 16) {
      throw new IllegalArgumentException("hash128 must be 16 bytes");
    }
    return Bytes.concat(Bytes.u8(shard), Bytes.u8(fieldTag & 0xFF), hash128, Bytes.u64(tid), Bytes.u32(chunkId));
  }

  public static HashKey decodeHash(byte[] key) {
    requireLen(key, 30, "hash");
    byte[] hash = new byte[16];
    System.arraycopy(key, 2, hash, 0, 16);
    return new HashKey(
        Bytes.readU8(key, 0),
        (byte) Bytes.readU8(key, 1),
        hash,
        Bytes.readU64(key, 18),
        Bytes.readU32(key, 26));
  }

  private static void requireLen(byte[] key, int n, String name) {
    if (key == null || key.length != n) {
      throw new IllegalArgumentException(name + " key length must be " + n
          + ", got " + (key == null ? "null" : key.length));
    }
  }

  public static final class RawKey {
    public final int shard;
    public final long tid;
    public final long chunkId;

    public RawKey(int shard, long tid, long chunkId) {
      this.shard = shard;
      this.tid = tid;
      this.chunkId = chunkId;
    }
  }

  public static final class MetaKey {
    public final int shard;
    public final long tid;

    public MetaKey(int shard, long tid) {
      this.shard = shard;
      this.tid = tid;
    }
  }

  public static final class TimeKey {
    public final int shard;
    public final long bucket;
    public final long tid;
    public final long chunkId;

    public TimeKey(int shard, long bucket, long tid, long chunkId) {
      this.shard = shard;
      this.bucket = bucket;
      this.tid = tid;
      this.chunkId = chunkId;
    }
  }

  public static final class ZorderKey {
    public final int shard;
    public final long zCell;
    public final long tid;
    public final long chunkId;

    public ZorderKey(int shard, long zCell, long tid, long chunkId) {
      this.shard = shard;
      this.zCell = zCell;
      this.tid = tid;
      this.chunkId = chunkId;
    }
  }

  public static final class HashKey {
    public final int shard;
    public final byte fieldTag;
    public final byte[] hash128;
    public final long tid;
    public final long chunkId;

    public HashKey(int shard, byte fieldTag, byte[] hash128, long tid, long chunkId) {
      this.shard = shard;
      this.fieldTag = fieldTag;
      this.hash128 = hash128;
      this.tid = tid;
      this.chunkId = chunkId;
    }
  }
}
