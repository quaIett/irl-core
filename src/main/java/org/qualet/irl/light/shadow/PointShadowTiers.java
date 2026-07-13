package org.qualet.irl.light.shadow;

/**
 * Registry of the point-shadow LOD tiers — the importance-based
 * shadow-resolution ladder. Each tier is one {@link PointShadowArray} instance
 * (cube-map-array + its pyramid/MSM filter pair); tier order IS the importance
 * rank order: tier 0 holds the highest-resolution cubes, deeper tiers get
 * progressively smaller faces ({@code max(64, tier0FaceSize >> t)}) for less
 * important lights.
 *
 * <p>FROZEN MIRROR CONTRACT (the injected GLSL of phase I4 replicates this
 * piecewise mapping verbatim, like LightBuffer's std430 mirror comments — any
 * change here is an ABI break with every generated patch): the GLOBAL point
 * slot space is the concatenation of the tiers' local slot ranges in tier
 * order. With {@link #TIER_SLOTS} = {2, 8, 8} the global space is 0..17 =
 * tier 0 [0, 2), tier 1 [2, 10), tier 2 [10, 18). {@code vlParams.w} carries
 * the GLOBAL slot; {@link #tierOf}/{@link #localSlot} resolve it to the
 * (tier instance, local cube layer) pair on the Java side, and the GLSL
 * mirrors the same two-threshold piecewise decode.</p>
 *
 * <p>Construction is GL-free ({@link PointShadowArray} allocates lazily), so
 * the tiers are built eagerly at class init; {@link #tier(int)} never returns
 * null.</p>
 */
public final class PointShadowTiers
{
    /** Slots per tier, in importance-rank order (tier 0 = highest resolution).
     *  I3 layout: 2 full-res cubes + 8 half-res + 8 quarter-res = 18 slots. */
    private static final int[] TIER_SLOTS = { 2, 8, 8 };

    /** Default face size of tier 0 (MEDIUM preset); presets re-apply through
     *  {@link #applyFaceSize}. */
    private static final int DEFAULT_TIER0_FACE_SIZE = 1024;

    private static final PointShadowArray[] TIERS;
    private static final int TOTAL_SLOTS;
    /** Cumulative start of each tier in the GLOBAL slot space; one extra
     *  trailing entry so {@code TIER_BASE[t + 1]} is always the exclusive end
     *  ({@code TIER_BASE[tierCount()] == totalSlots()}). */
    private static final int[] TIER_BASE;

    static
    {
        TIERS = new PointShadowArray[TIER_SLOTS.length];
        TIER_BASE = new int[TIER_SLOTS.length + 1];
        int total = 0;
        for (int t = 0; t < TIER_SLOTS.length; t++)
        {
            TIERS[t] = new PointShadowArray(TIER_SLOTS[t], tierFaceSize(DEFAULT_TIER0_FACE_SIZE, t));
            TIER_BASE[t] = total;
            total += TIER_SLOTS[t];
        }
        TIER_BASE[TIER_SLOTS.length] = total;
        TOTAL_SLOTS = total;
    }

    private PointShadowTiers()
    {}

    public static int tierCount()
    {
        return TIERS.length;
    }

    /** The tier's shadow array; never null (tiers are built eagerly, GL-free). */
    public static PointShadowArray tier(int t)
    {
        return TIERS[t];
    }

    /** Sum of all tiers' slot counts — the global point shadow slot space. */
    public static int totalSlots()
    {
        return TOTAL_SLOTS;
    }

    /** First GLOBAL slot of tier {@code t} (cumulative sum of the preceding
     *  tiers' slot counts); {@code tierBase(tierCount()) == totalSlots()}, so
     *  tier t's global range is {@code [tierBase(t), tierBase(t + 1))}. */
    public static int tierBase(int t)
    {
        return TIER_BASE[t];
    }

    /** Tier a GLOBAL slot belongs to — the piecewise range lookup of the
     *  frozen mirror contract (see the class javadoc). */
    public static int tierOf(int globalSlot)
    {
        for (int t = 0; t < TIERS.length; t++)
        {
            if (globalSlot < TIER_BASE[t + 1])
            {
                return t;
            }
        }
        throw new IllegalArgumentException("PointShadowTiers: global slot " + globalSlot + " >= totalSlots " + TOTAL_SLOTS);
    }

    /** Local cube layer of a GLOBAL slot inside its own tier's
     *  {@link PointShadowArray} ({@code globalSlot - tierBase(tierOf(globalSlot))}). */
    public static int localSlot(int globalSlot)
    {
        return globalSlot - TIER_BASE[tierOf(globalSlot)];
    }

    /** Apply a preset's tier-0 face size: tier t gets {@code max(64,
     *  tier0FaceSize >> t)}. Each tier keeps its own early-return-if-unchanged
     *  semantics (an unchanged tier is never freed/re-allocated). */
    public static void applyFaceSize(int tier0FaceSize)
    {
        for (int t = 0; t < TIERS.length; t++)
        {
            TIERS[t].setFaceSize(tierFaceSize(tier0FaceSize, t));
        }
    }

    /** Free every tier's GL resources (textures + FBOs + filter pairs); each
     *  re-allocates lazily on next access. */
    public static void deleteAll()
    {
        for (int t = 0; t < TIERS.length; t++)
        {
            TIERS[t].delete();
        }
    }

    private static int tierFaceSize(int tier0FaceSize, int t)
    {
        return Math.max(64, tier0FaceSize >> t);
    }
}
