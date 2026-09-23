package kart.codec;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytesAndRowKeyTest {

  @Test
  void u8u32u64RoundTrip() {
    assertEquals(255, Bytes.readU8(Bytes.u8(255), 0));
    assertEquals(0xFFFF_FFFFL, Bytes.readU32(Bytes.u32(0xFFFF_FFFFL), 0));
    long v = 0x0123456789ABCDEFL;
    assertEquals(v, Bytes.readU64(Bytes.u64(v), 0));
  }

  @Test
  void unsignedCompareMatchesNumericOrderForU64Keys() {
    byte[] a = Bytes.u64(1L);
    byte[] b = Bytes.u64(2L);
    byte[] c = Bytes.u64(0xFFFF_FFFF_FFFF_FFFFL);
    assertTrue(Bytes.compareUnsigned(a, b) < 0);
    assertTrue(Bytes.compareUnsigned(b, c) < 0);
  }

  @Property
  void rawRoundTrip(
      @ForAll @IntRange(min = 0, max = 3) int shard,
      @ForAll @LongRange(min = 0, max = 1_000_000) long tid,
      @ForAll @LongRange(min = 0, max = 10_000) long chunk) {
    byte[] key = RowKeyCodec.encodeRaw(shard, tid, chunk);
    RowKeyCodec.RawKey d = RowKeyCodec.decodeRaw(key);
    assertEquals(shard, d.shard);
    assertEquals(tid, d.tid);
    assertEquals(chunk, d.chunkId);
  }

  @Property
  void timeRoundTrip(
      @ForAll @IntRange(min = 0, max = 3) int shard,
      @ForAll @LongRange(min = 0, max = 1_000_000) long bucket,
      @ForAll @LongRange(min = 0, max = 1_000_000) long tid,
      @ForAll @LongRange(min = 0, max = 1000) long chunk) {
    byte[] key = RowKeyCodec.encodeTime(shard, bucket, tid, chunk);
    RowKeyCodec.TimeKey d = RowKeyCodec.decodeTime(key);
    assertEquals(shard, d.shard);
    assertEquals(bucket, d.bucket);
    assertEquals(tid, d.tid);
    assertEquals(chunk, d.chunkId);
  }

  @Property
  void zorderRoundTrip(
      @ForAll @IntRange(min = 0, max = 3) int shard,
      @ForAll @LongRange(min = 0, max = 1_000_000) long z,
      @ForAll @LongRange(min = 0, max = 1_000_000) long tid,
      @ForAll @LongRange(min = 0, max = 1000) long chunk) {
    byte[] key = RowKeyCodec.encodeZorder(shard, z, tid, chunk);
    RowKeyCodec.ZorderKey d = RowKeyCodec.decodeZorder(key);
    assertEquals(shard, d.shard);
    assertEquals(z, d.zCell);
    assertEquals(tid, d.tid);
    assertEquals(chunk, d.chunkId);
  }

  @Test
  void hashRoundTrip() {
    byte[] h = VehicleHash.hash128("3644");
    byte[] key = RowKeyCodec.encodeHash(1, RowKeyCodec.HASH_FIELD_VEHICLE, h, 9L, 2L);
    RowKeyCodec.HashKey d = RowKeyCodec.decodeHash(key);
    assertEquals(1, d.shard);
    assertEquals(RowKeyCodec.HASH_FIELD_VEHICLE, d.fieldTag);
    assertArrayEquals(h, d.hash128);
    assertEquals(9L, d.tid);
    assertEquals(2L, d.chunkId);
  }

  @Test
  void prefixSuccessorBasics() {
    assertArrayEquals(new byte[]{0x01}, PrefixSuccessor.of(new byte[]{0x00}).get());
    assertArrayEquals(new byte[]{0x01}, PrefixSuccessor.of(new byte[]{0x00, (byte) 0xFF}).get());
    Optional<byte[]> empty = PrefixSuccessor.of(new byte[]{(byte) 0xFF, (byte) 0xFF});
    assertFalse(empty.isPresent());
  }

  @Test
  void rawKeyOrderingFollowsShardThenTidThenChunk() {
    byte[] k1 = RowKeyCodec.encodeRaw(0, 1, 0);
    byte[] k2 = RowKeyCodec.encodeRaw(0, 1, 1);
    byte[] k3 = RowKeyCodec.encodeRaw(0, 2, 0);
    byte[] k4 = RowKeyCodec.encodeRaw(1, 0, 0);
    assertTrue(Bytes.compareUnsigned(k1, k2) < 0);
    assertTrue(Bytes.compareUnsigned(k2, k3) < 0);
    assertTrue(Bytes.compareUnsigned(k3, k4) < 0);
  }
}
