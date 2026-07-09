package org.qualet.irl.light;

/**
 * Per-frame accumulator for all collected lights, from two sources:
 *  - the scanner (ModelBlocks) at renderWorld HEAD, and
 *  - the form render-path (live actors / film replays) during world render.
 *
 * Single buffer: {@link #flush()} (called at HEAD after the scanner) packs the
 * current set into {@link LightBuffer} and clears it. Render-path registrations
 * land after the flush and are uploaded on the next frame's flush (one frame
 * stale — acceptable for moving lights). Dedup by identity keeps a light that
 * gets rendered more than once per frame from registering twice.
 */
public final class LightRegistry
{
    private static final int MAX = LightBuffer.MAX_LIGHTS;

    private static final int[] type = new int[MAX];
    private static final float[] px = new float[MAX];
    private static final float[] py = new float[MAX];
    private static final float[] pz = new float[MAX];
    private static final float[] cr = new float[MAX];
    private static final float[] cg = new float[MAX];
    private static final float[] cb = new float[MAX];
    private static final float[] intensity = new float[MAX];
    private static final float[] radius = new float[MAX];
    private static final float[] dx = new float[MAX];
    private static final float[] dy = new float[MAX];
    private static final float[] dz = new float[MAX];
    private static final float[] cosOuter = new float[MAX];
    private static final float[] cosInner = new float[MAX];
    private static final boolean[] entitiesOnly = new boolean[MAX];
    private static final boolean[] blocksOnly = new boolean[MAX];
    private static final float[] anisotropy = new float[MAX];
    private static final float[] density = new float[MAX];
    private static final float[] beam = new float[MAX];
    private static final float[] bulbSize = new float[MAX];
    // Spot gobo/cookie (-1 layer = none); see LightBuffer cookie vec4. Point lights ignore these.
    private static final float[] cookieLayer = new float[MAX];
    private static final float[] cookieRot = new float[MAX];
    private static final float[] cookieScale = new float[MAX];
    private static final float[] cookieFlags = new float[MAX];
    private static final boolean[] shadows = new boolean[MAX];
    private static final int[] shadowTile = new int[MAX];
    private static final long[] id = new long[MAX];

    private static int count;

    private LightRegistry()
    {}

    public static void registerPoint(float x, float y, float z, float r, float g, float b, float in, float rad, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        int i = slot(identity);
        if (i < 0)
        {
            return;
        }

        type[i] = 0;
        px[i] = x; py[i] = y; pz[i] = z;
        cr[i] = r; cg[i] = g; cb[i] = b;
        intensity[i] = in; radius[i] = rad;
        dx[i] = 0F; dy[i] = 0F; dz[i] = 0F;
        cosOuter[i] = 1F; cosInner[i] = 1F;
        entitiesOnly[i] = eOnly; blocksOnly[i] = bOnly;
        anisotropy[i] = aniso; density[i] = dens; beam[i] = bm;
        bulbSize[i] = bulb; shadows[i] = castsShadows;
    }

    /** No-cookie overload (the BBS addon's call path): delegates with the gobo
     *  disabled (layer -1). Keeps the addon ABI stable across the cookie struct bump. */
    public static void registerSpot(float x, float y, float z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        registerSpot(x, y, z, ndx, ndy, ndz, r, g, b, in, range, cosO, cosI, eOnly, bOnly, aniso, dens, bm, bulb, castsShadows, -1F, 0F, 1F, 0F, identity);
    }

    public static void registerSpot(float x, float y, float z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, float cLayer, float cRot, float cScale, float cFlags, long identity)
    {
        int i = slot(identity);
        if (i < 0)
        {
            return;
        }

        type[i] = 1;
        px[i] = x; py[i] = y; pz[i] = z;
        cr[i] = r; cg[i] = g; cb[i] = b;
        intensity[i] = in; radius[i] = range;
        dx[i] = ndx; dy[i] = ndy; dz[i] = ndz;
        cosOuter[i] = cosO; cosInner[i] = cosI;
        entitiesOnly[i] = eOnly; blocksOnly[i] = bOnly;
        anisotropy[i] = aniso; density[i] = dens; beam[i] = bm;
        bulbSize[i] = bulb; shadows[i] = castsShadows;
        cookieLayer[i] = cLayer; cookieRot[i] = cRot; cookieScale[i] = cScale; cookieFlags[i] = cFlags;
    }

    /** Returns the slot for this identity (existing = overwrite, else a new one), or -1 if full. */
    private static int slot(long identity)
    {
        for (int i = 0; i < count; i++)
        {
            if (id[i] == identity)
            {
                return i;
            }
        }

        if (count >= MAX)
        {
            return -1;
        }

        id[count] = identity;
        shadowTile[count] = -1;
        cookieLayer[count] = -1F;
        cookieScale[count] = 1F;
        return count++;
    }

    // --- accessors for the shadow baker (iterate spots, assign tiles) ---

    public static int getCount()
    {
        return count;
    }

    public static int getType(int i)
    {
        return type[i];
    }

    public static float getX(int i) { return px[i]; }
    public static float getY(int i) { return py[i]; }
    public static float getZ(int i) { return pz[i]; }
    public static float getDirX(int i) { return dx[i]; }
    public static float getDirY(int i) { return dy[i]; }
    public static float getDirZ(int i) { return dz[i]; }
    public static float getRange(int i) { return radius[i]; }
    public static float getCosOuter(int i) { return cosOuter[i]; }
    public static boolean getShadows(int i) { return shadows[i]; }

    /** Stable per-light identity (System.identityHashCode of the form). Used as
     *  the key for the block-shadow + VBO caches, since registry slots are
     *  reassigned every frame and aren't stable. */
    public static long getId(int i) { return id[i]; }

    public static void setShadowTile(int i, int tile)
    {
        if (i >= 0 && i < count)
        {
            shadowTile[i] = tile;
        }
    }

    /** Drop everything accumulated for this frame without touching the GPU.
     *  Used while shaders are off: the form render-path keeps registering
     *  lights (it runs regardless of Iris), but there is no consumer, and
     *  without a per-frame reset stale entries would linger until the next
     *  flush re-uploaded them. */
    public static void clear()
    {
        count = 0;
    }

    /** Pack the accumulated set into the GPU buffer (absolute world positions) and
     *  reset for the next frame. Kept for ABI compatibility — delegates to the
     *  camera-relative flush with a zero origin (= absolute). */
    public static void flush()
    {
        flush(0.0, 0.0, 0.0);
    }

    /** Pack the accumulated set into the GPU buffer with positions made RELATIVE to
     *  {@code origin} (the camera/eye), then reset for the next frame.
     *
     *  <p>Light positions are collected in absolute world coordinates (kept absolute in
     *  this registry so the shadow baker can query world blocks), but the SSBO — and the
     *  shader that reads it — must work in camera-relative space: at large world
     *  coordinates the absolute float positions and the shaderpack's reconstructed
     *  fragment position lose precision against each other and the light visibly stops
     *  lighting. Subtracting the camera origin here (and dropping the matching
     *  {@code + cameraPosition} reconstruction in the GLSL patches) keeps both sides of
     *  the {@code light.pos - fragPos} comparison small and precise. The subtraction is
     *  done in double so it stays exact regardless of distance from origin. Directions
     *  (spot) are NOT translated.</p> */
    public static void flush(double originX, double originY, double originZ)
    {
        LightBuffer.begin();

        for (int i = 0; i < count; i++)
        {
            // cone.z light mask: 0 = all, 1 = entities only, 2 = blocks only.
            // entities-only wins the (UI-prevented) both-set case.
            float lightMask = entitiesOnly[i] ? 1F : (blocksOnly[i] ? 2F : 0F);

            float rx = (float) ((double) px[i] - originX);
            float ry = (float) ((double) py[i] - originY);
            float rz = (float) ((double) pz[i] - originZ);

            if (type[i] == 0)
            {
                LightBuffer.addPoint(rx, ry, rz, cr[i], cg[i], cb[i], intensity[i], radius[i], lightMask, anisotropy[i], density[i], beam[i], (float) shadowTile[i], bulbSize[i]);
            }
            else
            {
                LightBuffer.addSpot(rx, ry, rz, dx[i], dy[i], dz[i], cr[i], cg[i], cb[i], intensity[i], radius[i], cosOuter[i], cosInner[i], lightMask, anisotropy[i], density[i], beam[i], (float) shadowTile[i], bulbSize[i], cookieLayer[i], cookieRot[i], cookieScale[i], cookieFlags[i]);
            }
        }

        LightBuffer.upload();
        count = 0;
    }
}
