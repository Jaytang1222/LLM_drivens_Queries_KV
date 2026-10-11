package kart.codec;

/** Inclusive [lo, hi] index-code range (ported from sfcurve IndexRange). */
public final class CodeRange {
  public final long lo;
  public final long hi;
  public final boolean contained;

  public CodeRange(long lo, long hi, boolean contained) {
    this.lo = lo;
    this.hi = hi;
    this.contained = contained;
  }
}
