#!/usr/bin/env python3
"""Generate the bundled 128x128 R8 spatial blue-noise dither pattern (VL CP2).

Void-and-cluster (Ulichney 1993) on a torus, numpy-only:

  1. Energy field = binary pattern convolved (FFT, wrap-around) with a
     toroidal gaussian, sigma = 1.9 px.
  2. Prototype: 10% random ones, relaxed by moving the tightest cluster
     into the largest void until the removed pixel IS the largest void.
  3. Rank phase I  (count-1 .. 0): repeatedly remove the tightest cluster.
  4. Rank phase II+III (count .. N*N-1): repeatedly insert into the largest
     void. On a torus the classic phase III ("tightest cluster of the
     minority zeros") is the SAME pixel: E_ones(p) + E_zeros(p) is constant,
     so argmax E_zeros == argmin E_ones — the two phases collapse into one.

The result is a full permutation rank[y][x] in 0..16383. Bytes are written
row-major as rank // 64 — the exact-uniform realization of scaling rank to a
byte (every value 0..255 appears exactly 64 times). NOTE: the naive
round(rank*255/16383) puts only ~33 ranks into each half-width edge bin
(values 0 and 255), which breaks the "64 per value +-1" acceptance gate;
rank // 64 differs from it by at most 1 LSB and satisfies the gate exactly.

Validation (printed, script fails hard if a gate breaks):
  (a) histogram: every byte value appears exactly 64 times;
  (b) radially averaged power spectrum: mean energy below 0.1*Nyquist
      (|f| < 0.05 cycles/px, DC excluded) over mean energy of the whole
      spectrum must be well under 0.5 (white noise would be ~1.0).

Deterministic: fixed PCG64 seed, so the shipped asset is reproducible.
Default output: ../src/main/resources/assets/irl-core/blue_noise_128.raw
"""

import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np

N = 128
SIGMA = 1.9
SEED = 0x1261C0DE          # fixed: the shipped asset must be reproducible
INITIAL_FRACTION = 0.1     # Ulichney's recommended prototype density
LOW_BAND = 0.05            # 0.1 * Nyquist (0.5 cycles/px)
LOW_RATIO_GATE = 0.5


def build_kernel():
    """Toroidal gaussian centered at (0, 0)."""
    ax = np.arange(N)
    d = np.minimum(ax, N - ax).astype(np.float64)
    dist2 = d[:, None] ** 2 + d[None, :] ** 2
    return np.exp(-dist2 / (2.0 * SIGMA * SIGMA))


def field_energy(pattern, kernel_f):
    """Full-field wrap-around convolution of a binary pattern with the kernel."""
    return np.fft.irfft2(np.fft.rfft2(pattern) * kernel_f, s=(N, N))


def kernel_at(kernel, flat_pos):
    """The kernel re-centered on a flat pixel index (incremental energy update)."""
    y, x = divmod(flat_pos, N)
    return np.roll(kernel, (y, x), axis=(0, 1))


def tightest_cluster(pattern, energy):
    """One-pixel with the highest energy."""
    return int(np.where(pattern > 0.5, energy, -np.inf).argmax())


def largest_void(pattern, energy):
    """Zero-pixel with the lowest energy."""
    return int(np.where(pattern > 0.5, np.inf, energy).argmin())


def make_prototype(kernel, kernel_f, rng):
    count = int(N * N * INITIAL_FRACTION)
    pattern = np.zeros((N, N), dtype=np.float64)
    pattern.flat[rng.choice(N * N, size=count, replace=False)] = 1.0
    energy = field_energy(pattern, kernel_f)

    for _ in range(N * N * 10):
        tight = tightest_cluster(pattern, energy)
        pattern.flat[tight] = 0.0
        energy -= kernel_at(kernel, tight)
        void = largest_void(pattern, energy)
        pattern.flat[void] = 1.0
        energy += kernel_at(kernel, void)
        if void == tight:      # moving it back where it came from: converged
            return pattern, energy, count
    raise RuntimeError("prototype relaxation did not converge")


def rank_pixels(kernel, kernel_f, rng):
    pattern, energy, count = make_prototype(kernel, kernel_f, rng)
    ranks = np.full((N, N), -1, dtype=np.int32)

    # Phase I: peel the prototype's ones off, tightest cluster first.
    p1, e1 = pattern.copy(), energy.copy()
    for rank in range(count - 1, -1, -1):
        tight = tightest_cluster(p1, e1)
        p1.flat[tight] = 0.0
        e1 -= kernel_at(kernel, tight)
        ranks.flat[tight] = rank

    # Phase II+III (torus-unified, see module docstring): fill the voids.
    for rank in range(count, N * N):
        void = largest_void(pattern, energy)
        pattern.flat[void] = 1.0
        energy += kernel_at(kernel, void)
        ranks.flat[void] = rank

    if not np.array_equal(np.sort(ranks.ravel()), np.arange(N * N)):
        raise RuntimeError("rank map is not a permutation of 0..N*N-1")
    return ranks


def spectrum_report(plane_bytes):
    """Radially averaged power spectrum stats of the byte plane."""
    f = plane_bytes.astype(np.float64) / 255.0
    f -= f.mean()
    power = np.abs(np.fft.fft2(f)) ** 2
    fr = np.fft.fftfreq(N)
    radius = np.sqrt(fr[:, None] ** 2 + fr[None, :] ** 2)

    non_dc = radius > 0
    mean_all = power[non_dc].mean()
    low = non_dc & (radius < LOW_BAND)
    low_ratio = power[low].mean() / mean_all

    # coarse radial profile (energy per band, normalized by the overall mean)
    edges = np.linspace(0.0, 0.5, 11)
    profile = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        band = non_dc & (radius >= lo) & (radius < hi)
        profile.append(power[band].mean() / mean_all if band.any() else 0.0)
    return low_ratio, profile


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    default_out = (Path(__file__).resolve().parent.parent
                   / "src/main/resources/assets/irl-core/blue_noise_128.raw")
    parser.add_argument("-o", "--out", type=Path, default=default_out,
                        help=f"output raw path (default: {default_out})")
    args = parser.parse_args()

    kernel = build_kernel()
    kernel_f = np.fft.rfft2(kernel)
    ranks = rank_pixels(kernel, kernel_f, np.random.default_rng(SEED))

    plane = (ranks // 64).astype(np.uint8)          # exact-uniform byte mapping
    naive = np.rint(ranks * 255.0 / 16383.0).astype(np.uint8)

    # (a) histogram gate
    hist = np.bincount(plane.ravel(), minlength=256)
    hist_naive = np.bincount(naive.ravel(), minlength=256)
    print(f"histogram (rank//64):            min={hist.min()} max={hist.max()} (gate: 64 +-1)")
    print(f"histogram (round(r*255/16383)):  min={hist_naive.min()} max={hist_naive.max()} (edge bins are half-width -> ~33)")
    if hist.min() < 63 or hist.max() > 65:
        raise SystemExit("FAIL: histogram gate")

    # (b) spectrum gate
    low_ratio, profile = spectrum_report(plane)
    print(f"low-band energy ratio (|f| < {LOW_BAND} c/px vs spectrum mean): {low_ratio:.4f} (gate: << {LOW_RATIO_GATE})")
    print("radial profile (10 bands to Nyquist, /mean): "
          + " ".join(f"{v:.3f}" for v in profile))
    if low_ratio >= LOW_RATIO_GATE:
        raise SystemExit("FAIL: spectrum gate")

    data = plane.tobytes()                          # row-major, 16384 bytes
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(f"wrote {len(data)} bytes -> {args.out}")
    print(f"md5 = {hashlib.md5(data).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
