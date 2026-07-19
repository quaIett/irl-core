package org.qualet.irl.light.shadow;

/**
 * Shadow resolution presets. Switching a preset frees + re-inits both shadow
 * texture sets on next access (lazy). MEDIUM matches the default allocations.
 *
 * The point column is the TIER-0 face size F of the flat point atlas
 * ({@link PointDepthAtlas}, layout {2, 12, 16} blocks at F / F/2 / F/4 — the
 * pure right-shift ladder). The spot column is the atlas CELL size; the
 * quadtree sub-tiles derive as tileSize / (1|2|4) inside the same physical
 * atlas ({@link SpotlightDepthAtlas}), so more spot tiles cost no extra VRAM.
 *
 * VRAM, LIVE layer. The point depth atlas is one 6F x 6F DEPTH32F texture =
 * 144·F² bytes; the per-tier filters add pyramid RG32F ~48·F² + MSM RGBA32F
 * ~96·F² (base = face/2, mips ~4/3) -> ~288·F² point total:
 *   LOW    F=512  -> ~72 MiB point   + spot 512-cell atlas  ~48 MiB
 *   MEDIUM F=1024 -> ~288 MiB point  + spot 1024-cell atlas ~192 MiB
 *   HIGH   F=2048 -> ~1152 MiB point + spot 2048-cell atlas ~768 MiB
 *   ULTRA  F=4096 -> clamped: 6F = 24576 exceeds the common GL_MAX_TEXTURE_SIZE
 *                    16384, PointDepthAtlas.setTileSize steps F down to fit
 * (spot atlas total = depth 4·(4·TILE)² + pyramid ~2/3 + EVSM ~4/3 of it —
 * unchanged from the flat-grid layout; the quadtree only re-partitions it).
 * The STATIC overlay layer still lazily doubles the point depth atlas (and
 * one more spot depth atlas) only where a dynamic subject overlaps a lamp.
 * Format ladder (fp16 on LOW/MED) is a known open lever if these budgets bite.
 */
public enum IRLShadowQuality
{
    LOW(512, 512),
    MEDIUM(1024, 1024),
    HIGH(2048, 2048),
    ULTRA(4096, 4096);

    /** TIER-0 point face size; deeper tiers derive via the pure right-shift
     *  ladder inside {@link PointDepthAtlas} ({@code F >> t}). */
    public final int pointFaceSize;
    public final int spotTileSize;

    private static IRLShadowQuality current = MEDIUM;

    IRLShadowQuality(int pointFaceSize, int spotTileSize)
    {
        this.pointFaceSize = pointFaceSize;
        this.spotTileSize = spotTileSize;
    }

    public void apply()
    {
        current = this;
        PointDepthAtlas.setTileSize(this.pointFaceSize);
        SpotlightDepthAtlas.setTileSize(this.spotTileSize);
        System.out.println("[irl-core] quality: " + this.name()
            + " (point F=" + this.pointFaceSize + " -> effective " + PointDepthAtlas.getTileSize()
            + ", spot tile=" + this.spotTileSize + " -> effective " + SpotlightDepthAtlas.getTileSize() + ")");
        // ULTRA runs the spot EVSM chain at atlas/4 instead of atlas/2: the
        // filter re-runs per overlay tile per frame, and at 4096 tiles the
        // full-base chain alone (~30 ms for a 25-lamp scene) capped the frame
        // rate. Quality cost is one lod of minimum penumbra softness, only at
        // ULTRA. Ratio-aware packs keep EVSM; older patches gate down to PCF.
        SpotlightDepthAtlas.setEvsmShift(this == ULTRA ? 2 : 1);
    }

    /** Map a 0..3 setting value to a preset and apply it if it changed. */
    public static void applyFromSetting(int value)
    {
        int ord = Math.max(0, Math.min(values().length - 1, value));
        IRLShadowQuality q = values()[ord];
        if (q != current)
        {
            q.apply();
        }
    }
}
