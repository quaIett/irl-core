package org.qualet.irl.light.shadow;

/**
 * Shadow resolution presets. Switching a preset frees + re-inits both shadow
 * textures on next access (lazy). MEDIUM matches the default allocations.
 *
 * VRAM (16 spot tiles + 16 point cubes, LIVE layer; the STATIC overlay layer
 * lazily doubles each texture it is needed for):
 *   LOW    spot 512²  (16 MiB) + point 256² (24 MiB)  = ~40 MiB
 *   MEDIUM spot 1024² (64 MiB) + point 512² (96 MiB)  = ~160 MiB
 *   HIGH   spot 2048² (256MiB) + point 1024²(384 MiB) = ~640 MiB
 *   ULTRA  spot 4096² (1 GiB)  + point 2048²(1.5 GiB) = ~2.5 GiB
 * Plus the spot min/max pyramid (SpotShadowPyramid, RG32F base=atlas/2 + mips
 * = ~2/3 of the live spot atlas): LOW ~11 MiB, MEDIUM ~43 MiB, HIGH ~171 MiB,
 * ULTRA ~683 MiB.
 */
public enum IRLShadowQuality
{
    LOW(256, 512),
    MEDIUM(512, 1024),
    HIGH(1024, 2048),
    ULTRA(2048, 4096);

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
        PointShadowArray.setFaceSize(this.pointFaceSize);
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
