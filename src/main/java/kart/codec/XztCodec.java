package kart.codec;

import java.util.List;

/**
 * XZT-style temporal index: embed [t0,t1] into start×end plane and reuse {@link XzStarSfc}.
 * Query window [qs,qe] expands to the classic interval region
 * {@code start ∈ [tLo, qe], end ∈ [qs, tHi]} (normalized).
 * Not TMan TR; TrajMesa XZT idea with XZ* upstream fidelity.
 */
public final class XztCodec {

  private final XzStarSfc sfc;
  private final long tLo;
  private final long tHi;
  private final double tSize;

  public XztCodec(short level, long tLoMs, long tHiMs) {
    if (tHiMs <= tLoMs) {
      throw new IllegalArgumentException("tHi must be > tLo");
    }
    this.tLo = tLoMs;
    this.tHi = tHiMs;
    this.tSize = (double) (tHiMs - tLoMs);
    this.sfc = XzStarSfc.create(level, 0.0, 1.0, 0.0, 1.0);
  }

  public long indexInterval(long t0Ms, long t1Ms) {
    long a = Math.min(t0Ms, t1Ms);
    long b = Math.max(t0Ms, t1Ms);
    double ns = norm(a);
    double ne = norm(b);
    if (ne < ns) {
      double tmp = ns;
      ns = ne;
      ne = tmp;
    }
    // XZT-style: time span as 2D MBR on the unit square (TrajMesa/XZ2 interval embedding).
    if (ne <= ns) {
      ne = Math.min(1.0, ns + Math.pow(0.5, 16));
    }
    return sfc.indexMbr(ns, ns, ne, ne, true);
  }

  public List<CodeRange> rangesCovering(long qStartMs, long qEndMs) {
    double qs = norm(Math.min(qStartMs, qEndMs));
    double qe = norm(Math.max(qStartMs, qEndMs));
    if (qe <= qs) {
      qe = Math.min(1.0, qs + Math.pow(0.5, 16));
    }
    // Classic interval overlap: start ∈ [tLo, qe], end ∈ [qs, tHi] (unit square).
    // Do not span-fill: collapsing [first.lo, last.hi] makes XZT ≈ full table
    // (force_mix: P_XZT/P_XZTXZ candidates≈48650). Same tradeoff as spatial XZ —
    // physical coarsening is CodeRangeBudget.mergeToBudgetGapAware in the adapter.
    return sfc.ranges(0.0, qs, qe, 1.0);
  }

  private double norm(long tMs) {
    double n = (tMs - tLo) / tSize;
    if (n < 0.0) {
      return 0.0;
    }
    if (n > 1.0) {
      return 1.0;
    }
    return n;
  }
}
