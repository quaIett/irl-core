package org.qualet.irl.light.shadow;

/**
 * Shadow resolution presets. Switching a preset frees + re-inits both shadow
 * texture sets on next access (lazy). MEDIUM matches the default allocations.
 *
 * The point column is the TIER-0 cube face size F; the deeper LOD tiers
 * derive their faces as {@code max(64, F >> t)} (see {@link PointShadowTiers},
 * layout {2, 8, 8} cubes at F / F/2 / F/4). The spot column is the atlas CELL
 * size; the quadtree sub-tiles derive as TILE_SIZE / (1|2|4) inside the same
 * physical atlas ({@link SpotlightDepthAtlas}), so more spot tiles cost no
 * extra VRAM.
 *
 * VRAM, LIVE layer. One point cube of face f costs
 * depth 24·f² + pyramid ~16·f² + MSM ~32·f² = ~72·f² bytes; over the
 * {2, 8, 8} tiers that is 72·(2·F² + 8·(F/2)² + 8·(F/4)²) = 324·F² total:
 *   LOW    F=512  -> ~81 MiB point   + spot 512-cell atlas  ~48 MiB
 *   MEDIUM F=1024 -> ~324 MiB point  + spot 1024-cell atlas ~192 MiB
 *   HIGH   F=2048 -> ~1296 MiB point + spot 2048-cell atlas ~768 MiB
 *   ULTRA  F=4096 -> ~5184 MiB point + spot 4096-cell atlas ~3 GiB
 * (spot atlas total = depth 4·(4·TILE)² + pyramid ~2/3 + EVSM ~4/3 of it —
 * unchanged from the flat-grid layout; the quadtree only re-partitions it).
 * The STATIC overlay layer still lazily adds +24·f² per overlaid point cube
 * (and one more spot depth atlas) only where a dynamic subject overlaps a lamp.
 * Format ladder (fp16 on LOW/MED) is a known open lever if these budgets bite.
 */
public enum IRLShadowQuality
{
    LOW(512, 512),
    MEDIUM(1024, 1024),
    HIGH(2048, 2048),
    ULTRA(4096, 4096);

    /** TIER-0 point cube face size; deeper tiers derive via
     *  {@link PointShadowTiers#applyFaceSize} ({@code max(64, F >> t)}). */
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
        PointShadowTiers.applyFaceSize(this.pointFaceSize);
        SpotlightDepthAtlas.setTileSize(this.spotTileSize);
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
