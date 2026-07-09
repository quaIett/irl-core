package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.qualet.irl.light.LightRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shadow bake driver. Collects nearby occluders (world entities) and, for each
 * spot/point in the {@link LightRegistry}, bakes its depth
 * map(s) and records the atlas tile / cube slot back into the registry
 * (-> SSBO vlParams.w). Runs at renderWorld HEAD, before Iris activates.
 *
 * Occluders: world blocks. A
 * per-light signature cache (see {@link #bake}) skips the GL depth render for
 * any light whose own geometry, in-range static occluders and world blocks are
 * unchanged. Atlas tiles / cube slots are STICKY per light id (see
 * {@link #acquireTile}), so a light appearing or dropping out does not shift —
 * and thereby re-bake — every other light's map.
 *
 * TWO-LAYER bake: a light with a live entity/replay in range runs in OVERLAY
 * mode — its static content (model blocks + world blocks) is baked into a
 * separate STATIC tile only when that content changes, then each frame the
 * base is GPU-copied into the live tile and just the dynamic casters render on
 * top. Re-tessellating cutout blocks / re-rendering model-block form trees no
 * longer happens per frame, so the per-frame cost of an animated scene depends
 * on the moving subjects, not on the scenery around the lamp.
 */
public final class ShadowBaker
{
    private static final int MAX_OCCLUDERS = 32;
    private static final float OVERLAP_MARGIN = 0.5f;

    private static final long FNV_OFFSET = 1469598103934665603L;
    private static final long FNV_PRIME = 1099511628211L;
    /** Odd multiplier folding the in-range static COUNT into a light's signature
     *  (seam INVARIANT 3); count 0 (every redactor light) leaves the sig untouched. */
    private static final long STATIC_COUNT_MIX = 0x9E3779B97F4A7C15L;

    private static final Object[] occ = new Object[MAX_OCCLUDERS];
    private static final int[] occType = new int[MAX_OCCLUDERS];
    private static final float[] ox = new float[MAX_OCCLUDERS];
    private static final float[] oy = new float[MAX_OCCLUDERS];
    private static final float[] oz = new float[MAX_OCCLUDERS];
    private static final float[] orad = new float[MAX_OCCLUDERS];
    /** Static-layer membership, INDEPENDENT of {@link #occType} (seam INVARIANT 2).
     *  True => baked into the never-rebaked static base; its silhouette changes only
     *  when {@link #ostatichash} changes. False (every redactor caster — all dynamic
     *  entities) => re-rendered every frame. */
    private static final boolean[] oStatic = new boolean[MAX_OCCLUDERS];
    /** Per-occluder signature of everything that changes a STATIC (model-block)
     *  caster's baked silhouette but isn't its center: form identity + transform
     *  translate/scale/rotate. Folded into a light's signature for model blocks
     *  in range; unused (0) for entity/replay casters, which are always treated
     *  dirty. */
    private static final long[] ostatichash = new long[MAX_OCCLUDERS];
    private static int occCount;

    // --- Per-light dirty cache (replaces the old global scene hash) ----------
    // Keyed by LightRegistry.getId (stable identity), like BlockShadowCache.
    // lastSig:   light geometry + sum of in-range model-block hashes last baked.
    // lastTile:  the atlas tile / cube slot the light last baked into. Also the
    //            "have we ever baked this light?" marker (containsKey).
    // lastBlocks:the world-block list instance last baked. BlockShadowCache
    //            returns the SAME instance until a block in range changes, so a
    //            reference compare detects terrain edits precisely.
    private static final Long2LongOpenHashMap lastSig = new Long2LongOpenHashMap();
    private static final Long2IntOpenHashMap lastTile = new Long2IntOpenHashMap();
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> lastBlocks = new Long2ObjectOpenHashMap<>();
    /** Ids whose LIVE tile currently contains dynamic (entity/replay) casters.
     *  Dynamic casters aren't in lastSig (they re-render every frame instead),
     *  so when the subject leaves range the signature returns to its earlier
     *  value and the cache would otherwise reuse the last map with the subject
     *  still baked in. While set, the light runs in overlay mode; the frame the
     *  subject is gone restores a clean static base (copy or bake) once. */
    private static final LongOpenHashSet wasDynamic = new LongOpenHashSet();

    // --- Static-layer (base) tile state, parallel to the live maps above -----
    // A light's STATIC tile/cube holds only its static content (model blocks +
    // world blocks) and is re-baked only when that content changes. On overlay
    // frames (dynamic subject in range) the base is GPU-copied into the live
    // tile and just the dynamic casters are re-rendered on top — the per-frame
    // cost no longer depends on how much static scenery surrounds the lamp.
    // Same id key + same eviction rules (purge/reset/retain) as the live maps.
    private static final Long2LongOpenHashMap lastStaticSig = new Long2LongOpenHashMap();
    private static final Long2IntOpenHashMap lastStaticTile = new Long2IntOpenHashMap();
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> lastStaticBlocks = new Long2ObjectOpenHashMap<>();
    /** Per-light 6-bit mask of cube faces a dynamic caster was drawn into on the
     *  LAST point overlay frame (T1.2). When the static base is unchanged, the
     *  per-frame restore copies only the faces that need it — the ones a dynamic
     *  caster touches now (dynFaceMaskScratch) OR touched last frame (this mask,
     *  so a vacated face restores instead of keeping a stale silhouette) —
     *  rather than blitting all 6. Absent key reads 0 (FastUtil default). Same
     *  lifecycle as lastSig (purge / retain / reset). */
    private static final Long2IntOpenHashMap lastFaceDynamic = new Long2IntOpenHashMap();

    /** Set true by {@link #scanInRange} when any in-range occluder is an entity
     *  or film replay (a dynamic subject) -> the light re-bakes every frame. */
    private static boolean dynamicInRangeScratch;
    /** Set by {@link #scanInRange} to the order-independent sum of the in-range
     *  model-block {@link #ostatichash} values. */
    private static long staticOccSigScratch;
    /** Set by {@link #scanInRange} to the number of in-range model-block
     *  (static) occluders. */
    private static int staticInRangeScratch;

    // --- Shortlist of in-range occluders (T1.1) ------------------------------
    // scanInRange records the occluders that passed its range (+ cone for spots)
    // test into shortIdx[0..shortCount), so the render passes that follow iterate
    // only those — without re-running the range/cone/face tests a second (and
    // third) time. shortFaceMask[s] is, for POINT scans (cone == false), the
    // 6-bit cube-face mask of occluder shortIdx[s] (which face frustums its
    // sphere touches), and dynFaceMaskScratch is the OR of that mask over the
    // DYNAMIC (entity/replay) occluders — replacing the per-face faceHasDynamic
    // walk in the point overlay. Spot scans leave shortFaceMask unused.
    //
    // Filled per-light right before that light's render passes; never reused
    // across two scanInRange calls (the spot loop fully precedes the point loop,
    // collect() fills occ[] once before both, so occ[] and these indices are
    // stable across a light's whole bake). One source of truth for the
    // scan == render invariant: the set rendered is exactly the set scanned.
    private static final int[] shortIdx = new int[MAX_OCCLUDERS];
    private static final int[] shortFaceMask = new int[MAX_OCCLUDERS];
    private static int shortCount;
    private static int dynFaceMaskScratch;

    // --- Sticky tile / cube-slot ownership ------------------------------------
    // A light KEEPS its atlas tile / cube slot across frames — including frames
    // where it is culled, toggled off, or has nothing in range — so one light
    // dropping out no longer shifts every later light onto a different tile
    // (which used to re-bake them all: a camera pan across a light's
    // behind-plane boundary re-baked the whole scene). A tileless light takes a
    // free tile first; only when none are free does it steal from the
    // most-stale owner that hasn't requested for >= STALE_FRAMES frames (so an
    // owner that merely iterates later in THIS frame is never robbed), and the
    // victim's dirty state is purged so it cleanly first-bakes if it returns.
    // A tile's content can only ever be its owner's, so a returning owner with
    // an unchanged signature safely RE-USES its old depth map — zero rebake.
    private static final long NO_OWNER = Long.MIN_VALUE;
    private static final int STALE_FRAMES = 2;
    private static final long[] spotTileOwner = new long[SpotlightDepthAtlas.MAX_TILES];
    private static final int[] spotTileActive = new int[SpotlightDepthAtlas.MAX_TILES];
    private static final long[] pointSlotOwner = new long[PointShadowArray.MAX_SHADOWS];
    private static final int[] pointSlotActive = new int[PointShadowArray.MAX_SHADOWS];
    /** Monotonic bake counter driving the staleness test (not wall time). */
    private static int frameIndex;
    /** Remaining FULL STATIC bakes this frame (T2.4). Reset each frame from
     *  {@link ShadowConfig#shadowBakeBudget()} ({@code <= 0} -> unlimited).
     *  Deferrable static bakes run only while this is positive; mandatory ones
     *  (first bake / tile reassigned, can't show a blank or foreign map) run
     *  regardless and still consume a unit, so they don't starve later lamps of
     *  a deferral. Dynamic overlays and static->live copies are NOT counted. */
    private static int staticBakeBudget;
    /** Last seen shadow-quality setting; a change frees + re-allocates the
     *  depth textures, so every cached map must be forgotten with it. */
    private static int lastQuality = Integer.MIN_VALUE;

    static
    {
        Arrays.fill(spotTileOwner, NO_OWNER);
        Arrays.fill(pointSlotOwner, NO_OWNER);
    }

    /** Scratch set of current tile owners for the defensive end-of-frame
     *  dirty-state sweep (dirty state may outlive a skipped frame — that is
     *  the sticky-tile win — but never its tile ownership). */
    private static final LongOpenHashSet ownerIdScratch = new LongOpenHashSet();

    // --- Optional bake profiler: -Dirlite.profileShadows=true -----------------
    // Logs once per second: avg/max bake() wall time, dirty re-bakes per kind,
    // occluder/light counts. Baseline + validation tool for the perf work.
    private static final boolean PROFILE = Boolean.getBoolean("irlite.profileShadows");
    private static long profWindowStart;
    private static long profNanos;
    private static long profMaxNanos;
    private static int profFrames;
    /** Full static-content bakes (clear + model blocks + world blocks). */
    private static int profSpotBakes;
    private static int profPointBakes;
    /** Overlay frames (static base copied / cleared + dynamic casters drawn). */
    private static int profSpotOverlays;
    private static int profPointOverlays;
    /** Reusable scratch set of all current light ids, used to evict the block-
     *  list + VBO caches for lights that disappeared. */
    private static final LongOpenHashSet liveIds = new LongOpenHashSet();

    private ShadowBaker()
    {}

    public static void bake(ClientWorld world, Vec3d cameraPos, Vec3d cameraForward, float tickDelta)
    {
        if (!PROFILE)
        {
            bakeInner(world, cameraPos, cameraForward, tickDelta);
            return;
        }

        long t0 = System.nanoTime();
        try
        {
            bakeInner(world, cameraPos, cameraForward, tickDelta);
        }
        finally
        {
            profRecordFrame(System.nanoTime() - t0);
        }
    }

    private static void bakeInner(ClientWorld world, Vec3d cameraPos, Vec3d cameraForward, float tickDelta)
    {
        if (world == null || cameraPos == null)
        {
            return;
        }
        if (LightRegistry.getCount() == 0)
        {
            // No lights — forget tiles + dirty state and drain every cache so
            // nothing lingers in VRAM/heap after walking away from all lamps.
            resetTileState();
            liveIds.clear();
            BlockShadowCache.retainOnly(liveIds);
            ShadowRenderer.retainBlockVbos(liveIds);
            return;
        }

        // Apply the shadow resolution preset (no-op unless it changed). On a
        // change the depth textures are freed + re-allocated, so every cached
        // map is gone: forget tiles + dirty state too, or a "clean" light would
        // skip its rebake and sample a blank (or not yet allocated) map.
        int quality = ShadowEngine.config().shadowQuality();
        IRLShadowQuality.applyFromSetting(quality);
        if (quality != lastQuality)
        {
            resetTileState();
            lastQuality = quality;
        }

        collect(world, cameraPos, tickDelta);
        // NOTE: no early-out on occCount == 0 — a light shining only on world
        // blocks (no entity/model/replay occluders nearby) still needs its
        // block silhouette baked. The per-light skip below also checks blocks.

        int n = LightRegistry.getCount();
        boolean cache = ShadowEngine.config().shadowCache();
        frameIndex++;
        // Per-frame full-static-bake budget (T2.4). <= 0 means unlimited.
        int budget = ShadowEngine.config().shadowBakeBudget();
        staticBakeBudget = budget <= 0 ? Integer.MAX_VALUE : budget;
        ShadowRenderer.beginBake();

        // Behind-camera cull inputs: a light whose whole influence sphere is
        // behind the camera plane lights no on-screen surface (diffuse/specular
        // only sample its shadow map for in-range fragments, and volumetrics
        // ignore the map), so its bake is skipped entirely (the per-light test
        // in each loop below). Conservative: only the fully-behind half-space is
        // culled, never a side-of-frustum light, so no shadow can go missing.
        double camX = cameraPos.x, camY = cameraPos.y, camZ = cameraPos.z;
        boolean haveFwd = cameraForward != null;
        double fwdX = haveFwd ? cameraForward.x : 0.0;
        double fwdY = haveFwd ? cameraForward.y : 0.0;
        double fwdZ = haveFwd ? cameraForward.z : 0.0;

        // --- spotlights: one perspective atlas tile each ---
        for (int i = 0; i < n; i++)
        {
            if (LightRegistry.getType(i) != 1)
            {
                continue;
            }

            float lx = LightRegistry.getX(i);
            float ly = LightRegistry.getY(i);
            float lz = LightRegistry.getZ(i);
            // DOUBLE light position feeds the light-relative bake anchor + view eye
            // (ShadowRenderer derives A = round((float) lxD) == round(lx), eye = L-A).
            // The float lx/ly/lz stay the cull / block-cache / signature ABI; by
            // construction (float) lxD == lx, so the anchor stays locked to the snap.
            double lxD = LightRegistry.getXd(i), lyD = LightRegistry.getYd(i), lzD = LightRegistry.getZd(i);
            float range = LightRegistry.getRange(i);
            if (range < 1e-3f)
            {
                continue;
            }
            // Whole sphere behind the camera -> skip. Its SSBO tile stays -1
            // (unshadowed, never sampled) while off; the sticky tile it owns is
            // kept, so when the camera turns back an unchanged light re-uses
            // its old map without any rebake.
            if (haveFwd && (lx - camX) * fwdX + (ly - camY) * fwdY + (lz - camZ) * fwdZ < -range)
            {
                continue;
            }
            boolean castsShadows = LightRegistry.getShadows(i);
            long id = LightRegistry.getId(i);

            // Spot axis + cone half-angle drive the cone cull in scanInRange /
            // renderInRangeCone: an occluder fully outside the lit cone can only
            // shadow unlit fragments, so it need not be baked (and an out-of-cone
            // subject must not dirty the light). Dir is stored normalized;
            // re-normalize defensively and disable the cull on a degenerate dir.
            float dx = LightRegistry.getDirX(i);
            float dy = LightRegistry.getDirY(i);
            float dz = LightRegistry.getDirZ(i);
            float cosOuter = LightRegistry.getCosOuter(i);
            float coneTheta = (float) Math.acos(MathHelper.clamp(cosOuter, -1f, 1f));
            float dlen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean cone = dlen > 1e-4f;
            float ndx = cone ? dx / dlen : 0f;
            float ndy = cone ? dy / dlen : 0f;
            float ndz = cone ? dz / dlen : 0f;

            // "Shadows" toggle (default on): when off this light casts no shadow
            // at all — neither entities nor world blocks. Forcing both inputs
            // empty drops it into the same "nothing in range" skip below, leaving
            // its shadow tile unassigned (-1 = none in the SSBO -> unshadowed).
            int entInRange = castsShadows ? scanInRange(lx, ly, lz, range, ndx, ndy, ndz, coneTheta, cone) : 0;
            // Collect blocks every frame (NOT gated on dirty): the skip/tile
            // decision must match the frame that actually baked, or the atlas
            // tile a light points to in the SSBO could drift off its baked
            // depth map. Cached by id -> O(1) on a hit, and the returned list
            // instance is stable until a block in range changes.
            List<BlockShadowEntry> blocks = castsShadows ? collectBlocks(id, world, lx, ly, lz, range) : Collections.emptyList();
            if (entInRange == 0 && blocks.isEmpty())
            {
                continue;
            }

            int myTile = acquireTile(spotTileOwner, spotTileActive, id);
            if (myTile < 0)
            {
                continue; // every tile owned by a recently-active light -> unshadowed
            }
            long sig = lightGeomSig(lx, ly, lz, dx, dy, dz, range, cosOuter, castsShadows) + staticOccSigScratch;
            sig ^= staticInRangeScratch * STATIC_COUNT_MIX; // fold static count (INVARIANT 3); 0 on redactor
            boolean dyn = dynamicInRangeScratch;
            boolean hasStatic = staticInRangeScratch > 0 || !blocks.isEmpty();
            float outerDeg = (float) Math.toDegrees(coneTheta * 2.0);
            LightRegistry.setShadowTile(i, myTile);

            if (!cache)
            {
                // Cache disabled: everything straight into the live tile, every frame.
                if (PROFILE)
                {
                    profSpotBakes++;
                }
                ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                if (entInRange > 0)
                {
                    renderInRangeCone(CASTERS_ALL, tickDelta);
                }
                if (!blocks.isEmpty())
                {
                    ShadowRenderer.renderBlocksDepth(id, blocks);
                }
                ShadowRenderer.endPass();
                SpotShadowPyramid.markDirty(myTile);
                SpotShadowEvsm.markDirty(myTile, range);
                rememberLive(id, sig, myTile, blocks, dyn);
                continue;
            }

            if (!dyn && !wasDynamic.contains(id))
            {
                // Pure static: bake straight into the live tile when something
                // changed; otherwise last frame's map is still exactly right.
                boolean dirty = !lastTile.containsKey(id)   // first bake
                    || lastSig.get(id) != sig               // geometry / static occluder moved
                    || lastBlocks.get(id) != blocks         // terrain in range changed
                    || lastTile.get(id) != myTile;          // assigned a different tile
                if (dirty)
                {
                    // First bake / tile reassigned can't be deferred — the live
                    // tile holds no map of our own to keep showing. A deferrable
                    // re-bake runs only within this frame's budget (T2.4).
                    boolean mustBake = !lastTile.containsKey(id) || lastTile.get(id) != myTile;
                    if (allowStaticBake(mustBake))
                    {
                        if (PROFILE)
                        {
                            profSpotBakes++;
                        }
                        ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                        if (staticInRangeScratch > 0)
                        {
                            renderInRangeCone(CASTERS_STATIC, tickDelta);
                        }
                        if (!blocks.isEmpty())
                        {
                            ShadowRenderer.renderBlocksDepth(id, blocks);
                        }
                        ShadowRenderer.endPass();
                        SpotShadowPyramid.markDirty(myTile);
                        SpotShadowEvsm.markDirty(myTile, range);
                        rememberLive(id, sig, myTile, blocks, false);
                    }
                    // else deferred: keep our own (older) live map and leave the
                    // dirty state untouched so this re-bake retries next frame —
                    // the SSBO already points at myTile (set above).
                }
                else
                {
                    rememberLive(id, sig, myTile, blocks, false);
                }
                continue;
            }

            // Overlay mode: a dynamic subject is in range (or just left). The
            // static base lives in the STATIC tile, re-baked only when it
            // changes; every frame it is GPU-copied into the live tile and only
            // the dynamic casters are re-rendered on top — the per-frame cost
            // no longer scales with the static scenery around the lamp.
            if (hasStatic)
            {
                boolean staticStale = !lastStaticTile.containsKey(id)
                    || lastStaticSig.get(id) != sig
                    || lastStaticBlocks.get(id) != blocks
                    || lastStaticTile.get(id) != myTile;
                // A static re-bake is deferrable only while a dynamic subject is
                // present (dyn) AND we still own a previously-baked static tile to
                // fall back on: the copy below restores that older base under the
                // live overlay until budget lets it re-bake (T2.4). The frame a
                // subject LEAVES (dyn false) must bake — the transition's
                // rememberLive() marks the light clean, so a deferred stale base
                // would never be noticed by the pure-static path again.
                boolean staticMustBake = !dyn
                    || !lastStaticTile.containsKey(id)
                    || lastStaticTile.get(id) != myTile;
                if (staticStale && allowStaticBake(staticMustBake))
                {
                    if (PROFILE)
                    {
                        profSpotBakes++;
                    }
                    ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, true, true);
                    if (staticInRangeScratch > 0)
                    {
                        renderInRangeCone(CASTERS_STATIC, tickDelta);
                    }
                    if (!blocks.isEmpty())
                    {
                        ShadowRenderer.renderBlocksDepth(id, blocks);
                    }
                    ShadowRenderer.endPass();
                    lastStaticSig.put(id, sig);
                    lastStaticTile.put(id, myTile);
                    lastStaticBlocks.put(id, blocks);
                }
                SpotlightDepthAtlas.copyStaticToLive(myTile);
                if (dyn)
                {
                    if (PROFILE)
                    {
                        profSpotOverlays++;
                    }
                    ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, false);
                    renderInRangeCone(CASTERS_DYNAMIC, tickDelta);
                    ShadowRenderer.endPass();
                }
            }
            else
            {
                // No static content at all: clear + dynamic casters only.
                if (PROFILE && dyn)
                {
                    profSpotOverlays++;
                }
                ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                if (dyn)
                {
                    renderInRangeCone(CASTERS_DYNAMIC, tickDelta);
                }
                ShadowRenderer.endPass();
            }
            // overlay mode rewrites the live tile every frame (copy + dynamics) -> pyramid + EVSM follow every frame
            SpotShadowPyramid.markDirty(myTile);
            SpotShadowEvsm.markDirty(myTile, range);

            if (dyn)
            {
                wasDynamic.add(id);
            }
            else
            {
                // Subject just left: the live tile holds pure static content again.
                rememberLive(id, sig, myTile, blocks, false);
            }
        }

        // one batched pyramid + EVSM pass over every tile the spot loop dirtied (before the SSBO flush / Iris passes)
        SpotShadowPyramid.flushDirty();
        SpotShadowEvsm.flushDirty();

        // --- point lights: cube-array, 6 faces each ---
        for (int i = 0; i < n; i++)
        {
            if (LightRegistry.getType(i) != 0)
            {
                continue;
            }

            float lx = LightRegistry.getX(i);
            float ly = LightRegistry.getY(i);
            float lz = LightRegistry.getZ(i);
            // DOUBLE light position for the light-relative bake anchor + view eye
            // (see the spot loop). Float lx/ly/lz remain the cull/cache/signature ABI.
            double lxD = LightRegistry.getXd(i), lyD = LightRegistry.getYd(i), lzD = LightRegistry.getZd(i);
            float radius = LightRegistry.getRange(i);
            if (radius < 1e-3f)
            {
                continue;
            }
            // Behind-camera cull (see the spot loop).
            if (haveFwd && (lx - camX) * fwdX + (ly - camY) * fwdY + (lz - camZ) * fwdZ < -radius)
            {
                continue;
            }
            boolean castsShadows = LightRegistry.getShadows(i);
            long id = LightRegistry.getId(i);

            // See the spot loop: "Shadows" off -> no entities, no blocks.
            // Points are omnidirectional -> no cone cull (cone=false); the 6 cube
            // faces are culled individually in renderInRangeFace below.
            int entInRange = castsShadows ? scanInRange(lx, ly, lz, radius, 0f, 0f, 0f, 0f, false) : 0;
            // Collected once, reused across all 6 cube faces (see spot note).
            List<BlockShadowEntry> blocks = castsShadows ? collectBlocks(id, world, lx, ly, lz, radius) : Collections.emptyList();
            if (entInRange == 0 && blocks.isEmpty())
            {
                continue;
            }

            int myLayer = acquireTile(pointSlotOwner, pointSlotActive, id);
            if (myLayer < 0)
            {
                continue; // every slot owned by a recently-active light -> unshadowed
            }
            long sig = lightGeomSig(lx, ly, lz, 0f, 0f, 0f, radius, 1f, castsShadows) + staticOccSigScratch;
            sig ^= staticInRangeScratch * STATIC_COUNT_MIX; // fold static count (INVARIANT 3); 0 on redactor
            boolean dyn = dynamicInRangeScratch;
            boolean hasStatic = staticInRangeScratch > 0 || !blocks.isEmpty();
            LightRegistry.setShadowTile(i, myLayer);

            if (!cache)
            {
                // Cache disabled: everything into all live faces, every frame.
                if (PROFILE)
                {
                    profPointBakes++;
                }
                for (int face = 0; face < 6; face++)
                {
                    ShadowRenderer.beginPointFace(myLayer, face, lxD, lyD, lzD, radius, false, true);
                    if (entInRange > 0)
                    {
                        renderInRangeFace(face, CASTERS_ALL, tickDelta);
                    }
                    if (!blocks.isEmpty())
                    {
                        ShadowRenderer.renderBlocksDepth(id, blocks);
                    }
                    ShadowRenderer.endPass();
                }
                PointShadowPyramid.markDirty(myLayer);
                PointShadowEvsm.markDirty(myLayer, radius);
                rememberLive(id, sig, myLayer, blocks, dyn);
                continue;
            }

            if (!dyn && !wasDynamic.contains(id))
            {
                // Pure static (see the spot loop).
                boolean dirty = !lastTile.containsKey(id)
                    || lastSig.get(id) != sig
                    || lastBlocks.get(id) != blocks
                    || lastTile.get(id) != myLayer;
                if (dirty)
                {
                    // First bake / tile reassigned can't be deferred; a deferrable
                    // re-bake runs only within this frame's budget (T2.4).
                    boolean mustBake = !lastTile.containsKey(id) || lastTile.get(id) != myLayer;
                    if (allowStaticBake(mustBake))
                    {
                        if (PROFILE)
                        {
                            profPointBakes++;
                        }
                        for (int face = 0; face < 6; face++)
                        {
                            ShadowRenderer.beginPointFace(myLayer, face, lxD, lyD, lzD, radius, false, true);
                            if (staticInRangeScratch > 0)
                            {
                                renderInRangeFace(face, CASTERS_STATIC, tickDelta);
                            }
                            if (!blocks.isEmpty())
                            {
                                ShadowRenderer.renderBlocksDepth(id, blocks);
                            }
                            ShadowRenderer.endPass();
                        }
                        PointShadowPyramid.markDirty(myLayer);
                        PointShadowEvsm.markDirty(myLayer, radius);
                        rememberLive(id, sig, myLayer, blocks, false);
                    }
                    // else deferred: keep our own (older) live cube; dirty state
                    // unchanged so the re-bake retries next frame (SSBO -> myLayer).
                }
                else
                {
                    rememberLive(id, sig, myLayer, blocks, false);
                }
                continue;
            }

            // Overlay mode (see the spot loop). The static base lives in the
            // STATIC cube, re-baked only when it changes; each frame it is
            // GPU-copied into the live cube and only the dynamic casters redraw,
            // into the faces their spheres actually touch.
            if (hasStatic)
            {
                boolean staticStale = !lastStaticTile.containsKey(id)
                    || lastStaticSig.get(id) != sig
                    || lastStaticBlocks.get(id) != blocks
                    || lastStaticTile.get(id) != myLayer;
                // Deferrable only while a dynamic subject is present and we own a
                // prior static cube to fall back on (see the spot overlay note).
                boolean staticMustBake = !dyn
                    || !lastStaticTile.containsKey(id)
                    || lastStaticTile.get(id) != myLayer;
                boolean bakedStatic = false;
                if (staticStale && allowStaticBake(staticMustBake))
                {
                    bakedStatic = true;
                    if (PROFILE)
                    {
                        profPointBakes++;
                    }
                    for (int face = 0; face < 6; face++)
                    {
                        ShadowRenderer.beginPointFace(myLayer, face, lxD, lyD, lzD, radius, true, true);
                        if (staticInRangeScratch > 0)
                        {
                            renderInRangeFace(face, CASTERS_STATIC, tickDelta);
                        }
                        if (!blocks.isEmpty())
                        {
                            ShadowRenderer.renderBlocksDepth(id, blocks);
                        }
                        ShadowRenderer.endPass();
                    }
                    lastStaticSig.put(id, sig);
                    lastStaticTile.put(id, myLayer);
                    lastStaticBlocks.put(id, blocks);
                }

                // Restore the static base into the live cube (T1.2). When the
                // static layer was just re-baked (bakedStatic) every live face
                // needs the new base, so blit all 6 in one call — this also
                // covers first-overlay and post-tile-steal (both force a bake via
                // an absent / purged lastStaticTile). Otherwise copy only the
                // faces that need it: the ones a dynamic caster touches now
                // (dynNow) OR touched last frame (lastFaceDynamic, so a vacated
                // face restores to static instead of keeping its stale
                // silhouette). Untouched faces already hold the static base —
                // including when a stale re-bake was DEFERRED (T2.4): the static
                // cube is unchanged, so the live cube's static faces still match.
                int dynNow = dynFaceMaskScratch;
                if (bakedStatic)
                {
                    PointShadowArray.copyStaticToLive(myLayer);
                }
                else
                {
                    int copyMask = dynNow | lastFaceDynamic.get(id);
                    for (int face = 0; face < 6; face++)
                    {
                        if ((copyMask & (1 << face)) != 0)
                        {
                            PointShadowArray.copyStaticFaceToLive(myLayer, face);
                        }
                    }
                }

                if (dyn)
                {
                    if (PROFILE)
                    {
                        profPointOverlays++;
                    }
                    for (int face = 0; face < 6; face++)
                    {
                        if ((dynNow & (1 << face)) == 0)
                        {
                            continue; // no dynamic caster reaches this face; the copy refreshed it
                        }
                        ShadowRenderer.beginPointFace(myLayer, face, lxD, lyD, lzD, radius, false, false);
                        renderInRangeFace(face, CASTERS_DYNAMIC, tickDelta);
                        ShadowRenderer.endPass();
                    }
                }
                lastFaceDynamic.put(id, dynNow);
            }
            else
            {
                // No static content: every face still clears (a vacated face
                // would keep a phantom shadow), dynamics drawn where they reach.
                if (PROFILE && dyn)
                {
                    profPointOverlays++;
                }
                for (int face = 0; face < 6; face++)
                {
                    ShadowRenderer.beginPointFace(myLayer, face, lxD, lyD, lzD, radius, false, true);
                    if (dyn)
                    {
                        renderInRangeFace(face, CASTERS_DYNAMIC, tickDelta);
                    }
                    ShadowRenderer.endPass();
                }
            }
            // overlay mode rewrites live faces every frame (copies + dynamics) -> pyramid + EVSM follow every frame
            PointShadowPyramid.markDirty(myLayer);
            PointShadowEvsm.markDirty(myLayer, radius);

            if (dyn)
            {
                wasDynamic.add(id);
            }
            else
            {
                rememberLive(id, sig, myLayer, blocks, false);
            }
        }

        // one batched pyramid + EVSM pass over every slot the point loop dirtied (before the SSBO flush / Iris passes)
        PointShadowPyramid.flushDirty();
        PointShadowEvsm.flushDirty();

        // Defensive invariant sweep: per-light dirty state may only exist for
        // current tile owners (steals and resets purge eagerly; this catches
        // anything they miss). Owners DO keep their state across frames they
        // skip — that is the sticky-tile win: a light that returns with its
        // tile and an unchanged signature re-uses its old map, zero rebake.
        ownerIdScratch.clear();
        for (int t = 0; t < spotTileOwner.length; t++)
        {
            if (spotTileOwner[t] != NO_OWNER)
            {
                ownerIdScratch.add(spotTileOwner[t]);
            }
        }
        for (int t = 0; t < pointSlotOwner.length; t++)
        {
            if (pointSlotOwner[t] != NO_OWNER)
            {
                ownerIdScratch.add(pointSlotOwner[t]);
            }
        }
        retainDirtyState(ownerIdScratch);

        // Block-list + VBO caches: keep ALL registered lights so a momentary
        // out-of-range frame doesn't thrash the (expensive) block re-collect.
        // Empty set when the feature is off -> both caches drain.
        liveIds.clear();
        if (ShadowEngine.config().shadowBlocks())
        {
            for (int i = 0; i < n; i++)
            {
                liveIds.add(LightRegistry.getId(i));
            }
        }
        BlockShadowCache.retainOnly(liveIds);
        ShadowRenderer.retainBlockVbos(liveIds);
    }

    /** Sticky allocation: the owner keeps its tile (and is marked active);
     *  otherwise prefer a free tile; otherwise steal the most-stale tile whose
     *  owner hasn't requested for {@link #STALE_FRAMES}+ frames — never one
     *  active this or last frame, so a light that merely iterates later in the
     *  same frame is not robbed (which would ping-pong tiles between two
     *  lights, re-baking both every frame). Returns -1 when nothing is
     *  available; the light then simply casts no shadow this frame. */
    private static int acquireTile(long[] owner, int[] active, long id)
    {
        int free = -1;
        int stale = -1;
        int staleAge = Integer.MAX_VALUE;
        for (int t = 0; t < owner.length; t++)
        {
            if (owner[t] == id)
            {
                active[t] = frameIndex;
                return t;
            }
            if (owner[t] == NO_OWNER)
            {
                if (free < 0)
                {
                    free = t;
                }
            }
            else if (frameIndex - active[t] >= STALE_FRAMES && active[t] < staleAge)
            {
                stale = t;
                staleAge = active[t];
            }
        }

        int take = free >= 0 ? free : stale;
        if (take < 0)
        {
            return -1;
        }
        if (free < 0)
        {
            purgeDirtyState(owner[take]); // victim first-bakes if it returns
        }
        owner[take] = id;
        active[take] = frameIndex;
        return take;
    }

    /** Budget gate for one FULL STATIC bake (T2.4). {@code mustBake} bakes
     *  (first bake / tile reassigned — no own map to fall back on; or a subject
     *  leaving, where the staleness bookkeeping can't carry over) always
     *  proceed; otherwise the bake runs only while this frame's budget remains.
     *  A bake that runs consumes one unit either way (mandatory bakes may push
     *  the budget below zero, which simply defers the frame's remaining
     *  deferrable bakes). Returns true to bake now, false to defer — the caller
     *  then keeps the existing map and leaves its dirty state untouched, so the
     *  same re-bake is retried next frame. */
    private static boolean allowStaticBake(boolean mustBake)
    {
        if (mustBake || staticBakeBudget > 0)
        {
            staticBakeBudget--;
            return true;
        }
        return false;
    }

    /** Drop one light's dirty state so its next bake is a clean first bake
     *  (used when a tile is stolen — its map content now belongs to another
     *  light). */
    private static void purgeDirtyState(long id)
    {
        lastSig.remove(id);
        lastTile.remove(id);
        lastBlocks.remove(id);
        lastStaticSig.remove(id);
        lastStaticTile.remove(id);
        lastStaticBlocks.remove(id);
        lastFaceDynamic.remove(id);
        wasDynamic.remove(id);
    }

    /** Forget all tile ownership + per-light dirty state (no lights left, or
     *  the depth textures were just re-allocated): everything that returns
     *  first-bakes into a fresh tile. */
    private static void resetTileState()
    {
        Arrays.fill(spotTileOwner, NO_OWNER);
        Arrays.fill(pointSlotOwner, NO_OWNER);
        lastSig.clear();
        lastTile.clear();
        lastBlocks.clear();
        lastStaticSig.clear();
        lastStaticTile.clear();
        lastStaticBlocks.clear();
        lastFaceDynamic.clear();
        wasDynamic.clear();
    }

    /** Shaders just went off: nothing samples the shadow maps anymore. Forget
     *  all tile ownership + dirty state and free the depth textures and the
     *  block/VBO caches (same drain as the no-lights path, plus the textures).
     *  Everything re-allocates lazily and first-bakes when shaders return. */
    public static void onShadersDisabled()
    {
        resetTileState();
        liveIds.clear();
        BlockShadowCache.retainOnly(liveIds);
        ShadowRenderer.retainBlockVbos(liveIds);
        SpotlightDepthAtlas.delete();
        PointShadowArray.delete();
    }

    /** Record that the LIVE tile now holds this light's pure static content
     *  (a static bake or a static->live copy), or — with {@code dyn} — that it
     *  contains dynamic casters and must run the overlay path until they go. */
    private static void rememberLive(long id, long sig, int tile, List<BlockShadowEntry> blocks, boolean dyn)
    {
        lastSig.put(id, sig);
        lastTile.put(id, tile);
        lastBlocks.put(id, blocks);
        if (dyn)
        {
            wasDynamic.add(id);
        }
        else
        {
            wasDynamic.remove(id);
        }
    }

    /** Accumulate one frame into the profiler window and log roughly once per
     *  second (only ever called with -Dirlite.profileShadows=true). */
    private static void profRecordFrame(long nanos)
    {
        profNanos += nanos;
        if (nanos > profMaxNanos)
        {
            profMaxNanos = nanos;
        }
        profFrames++;

        long now = System.nanoTime();
        if (profWindowStart == 0L)
        {
            profWindowStart = now;
            return;
        }
        if (now - profWindowStart < 1_000_000_000L)
        {
            return;
        }

        System.out.println(String.format(Locale.ROOT,
            "[irlite] shadows: bake avg %.2f ms, max %.2f ms | static bakes: %d spot, %d point(x6) | overlays: %d spot, %d point | occluders %d, lights %d | %d frames",
            profNanos / 1e6 / Math.max(profFrames, 1), profMaxNanos / 1e6,
            profSpotBakes, profPointBakes, profSpotOverlays, profPointOverlays,
            occCount, LightRegistry.getCount(), profFrames));

        profWindowStart = now;
        profNanos = 0L;
        profMaxNanos = 0L;
        profFrames = 0;
        profSpotBakes = 0;
        profPointBakes = 0;
        profSpotOverlays = 0;
        profPointOverlays = 0;
    }

    /** Drop per-light dirty state for ids not in {@code keep}. An empty set
     *  drains it. */
    private static void retainDirtyState(LongSet keep)
    {
        if (!lastSig.isEmpty())
        {
            lastSig.keySet().retainAll(keep);
        }
        if (!lastTile.isEmpty())
        {
            lastTile.keySet().retainAll(keep);
        }
        if (!lastBlocks.isEmpty())
        {
            lastBlocks.keySet().retainAll(keep);
        }
        if (!lastStaticSig.isEmpty())
        {
            lastStaticSig.keySet().retainAll(keep);
        }
        if (!lastStaticTile.isEmpty())
        {
            lastStaticTile.keySet().retainAll(keep);
        }
        if (!lastStaticBlocks.isEmpty())
        {
            lastStaticBlocks.keySet().retainAll(keep);
        }
        if (!lastFaceDynamic.isEmpty())
        {
            lastFaceDynamic.keySet().retainAll(keep);
        }
        if (!wasDynamic.isEmpty())
        {
            wasDynamic.retainAll(keep);
        }
    }

    /** Block occluders around a light, clamped to the configurable
     *  {@link ShadowConfig#shadowBlockRadius()} (default 24). Empty when the
     *  feature is off. Backed by the identity-keyed {@link BlockShadowCache}
     *  (O(1) on a hit; recollects on light-move, radius change, or a block change
     *  in range). */
    private static List<BlockShadowEntry> collectBlocks(long id, ClientWorld world, float lx, float ly, float lz, float range)
    {
        if (!ShadowEngine.config().shadowBlocks() || world == null)
        {
            return Collections.emptyList();
        }
        return BlockShadowCache.getOrCompute(id, world, lx, ly, lz, Math.min(range, (float) ShadowEngine.config().shadowBlockRadius()));
    }

    /** FNV-1a fold of one float (raw bits) into a running hash. */
    private static long mix(long h, float v)
    {
        return (h ^ (Float.floatToRawIntBits(v) & 0xffffffffL)) * FNV_PRIME;
    }

    /** SplitMix64 finalizer — a full-avalanche scramble of one 64-bit value, used to
     *  fold per-occluder static hashes order-independently without the additive
     *  cancellation a plain sum allows (seam INVARIANT 3). */
    private static long mix64(long z)
    {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Signature of a light's own bake-relevant geometry: position, direction,
     *  range, spot cone (cosOuter), and the shadows flag. Any change re-bakes. */
    private static long lightGeomSig(float lx, float ly, float lz, float dx, float dy, float dz, float range, float cosOuter, boolean shadows)
    {
        long h = FNV_OFFSET;
        h = mix(h, lx); h = mix(h, ly); h = mix(h, lz);
        h = mix(h, dx); h = mix(h, dy); h = mix(h, dz);
        h = mix(h, range); h = mix(h, cosOuter);
        h = (h ^ (shadows ? 1L : 0L)) * FNV_PRIME;
        return h;
    }

    /** Count in-range occluders and, as a side effect, set
     *  {@link #dynamicInRangeScratch} (any entity/replay in range) and
     *  {@link #staticOccSigScratch} (order-independent sum of in-range
     *  model-block hashes). reach = reachBase + occluderRadius. When {@code cone}
     *  is set (spotlights), occluders fully outside the lit cone (unit axis
     *  dirX/Y/Z, half-angle coneTheta) are skipped: they can only shadow unlit
     *  fragments, so excluding them both avoids the draw AND stops an out-of-cone
     *  subject from dirtying the light. Points pass cone=false (omnidirectional;
     *  the per-face frustum cull is in {@link #renderInRangeFace}). */
    private static int scanInRange(float lx, float ly, float lz, float reachBase,
                                   float dirX, float dirY, float dirZ, float coneTheta, boolean cone)
    {
        int sc = 0;
        int statics = 0;
        boolean dyn = false;
        long sig = 0L;
        int dynFaces = 0;
        for (int k = 0; k < occCount; k++)
        {
            float ddx = ox[k] - lx, ddy = oy[k] - ly, ddz = oz[k] - lz;
            float reach = reachBase + orad[k];
            if (ddx * ddx + ddy * ddy + ddz * ddz > reach * reach)
            {
                continue;
            }
            if (cone && !insideCone(dirX, dirY, dirZ, coneTheta, ddx, ddy, ddz, orad[k]))
            {
                continue;
            }
            // Passed range (+ cone for spots): shortlist it so the render passes
            // re-use this verdict instead of re-testing. For POINT scans
            // (cone == false) also record which cube faces this occluder's sphere
            // touches (k = radius·√2, exactly as in renderInRangeFace), so the
            // face render + overlay loop read the bit instead of recomputing it.
            int faceMask = 0;
            if (!cone)
            {
                float kr = orad[k] * SQRT2;
                for (int face = 0; face < 6; face++)
                {
                    if (sphereTouchesFace(face, ddx, ddy, ddz, kr))
                    {
                        faceMask |= 1 << face;
                    }
                }
            }
            shortIdx[sc] = k;
            shortFaceMask[sc] = faceMask;
            sc++;
            if (oStatic[k])
            {
                // INVARIANT 3: avalanche-mix each static hash before folding (a plain
                // additive sum is non-injective — compensating paired edits cancel).
                // Order-independent (commutative XOR of mixed values). Inert on
                // redactor (no static casters); live for IRLite model blocks.
                sig ^= mix64(ostatichash[k]);
                statics++;
            }
            else
            {
                dyn = true; // entity or film replay -> dynamic subject
                dynFaces |= faceMask; // per-face: which faces a dynamic caster reaches
            }
        }
        shortCount = sc;
        dynFaceMaskScratch = dynFaces;
        dynamicInRangeScratch = dyn;
        staticOccSigScratch = sig;
        staticInRangeScratch = statics;
        return sc;
    }

    /** Caster-type filters for the renderInRange* helpers: everything (legacy
     *  no-cache path), only model blocks (the static base layer), or only
     *  entities/replays (the per-frame dynamic overlay). */
    private static final int CASTERS_ALL = 0;
    private static final int CASTERS_STATIC = 1;
    private static final int CASTERS_DYNAMIC = 2;

    private static boolean casterMatches(int filter, boolean isStatic)
    {
        if (filter == CASTERS_STATIC)
        {
            return isStatic;
        }
        if (filter == CASTERS_DYNAMIC)
        {
            return !isStatic;
        }
        return true;
    }

    /** Render shortlisted occluders of the filtered type inside a spot's lit
     *  cone. The range + cone test already ran in {@link #scanInRange}, whose
     *  shortlist this walks, so the rendered set equals the counted set that
     *  gated this bake (the scan == render invariant). Casters are batched into a
     *  single GPU flush per pass (T2.2): the begin/buffer/end bracket wraps the
     *  loop so one immediate.draw submits them all. */
    private static void renderInRangeCone(int filter, float tickDelta)
    {
        ShadowRenderer.beginCasterBatch();
        for (int s = 0; s < shortCount; s++)
        {
            int k = shortIdx[s];
            if (!casterMatches(filter, oStatic[k]))
            {
                continue;
            }
            ShadowRenderer.emitCaster(ShadowEngine.source(), occ[k], occType[k], tickDelta);
        }
        ShadowRenderer.endCasterBatch();
    }

    /** Render shortlisted occluders of the filtered type whose sphere touches
     *  ONE point-cube face's 90° frustum (face index per
     *  {@link ShadowRenderer#beginPointFace}); the other five faces never see
     *  them, removing ~5/6 of the caster draws per point. The face membership
     *  is the {@code shortFaceMask} bit computed once in {@link #scanInRange}. */
    private static void renderInRangeFace(int face, int filter, float tickDelta)
    {
        int bit = 1 << face;
        ShadowRenderer.beginCasterBatch();
        for (int s = 0; s < shortCount; s++)
        {
            if ((shortFaceMask[s] & bit) == 0)
            {
                continue;
            }
            int k = shortIdx[s];
            if (!casterMatches(filter, oStatic[k]))
            {
                continue;
            }
            ShadowRenderer.emitCaster(ShadowEngine.source(), occ[k], occType[k], tickDelta);
        }
        ShadowRenderer.endCasterBatch();
    }

    /** Small angular slack (radians) added to the spot cone test so a subject
     *  right at the cone edge is never wrongly culled. */
    private static final float CONE_ANGLE_MARGIN = 0.05f;
    private static final float SQRT2 = 1.4142135f;

    /** True unless an occluder sphere (offset V = center - lightPos, radius r) is
     *  ENTIRELY outside the spot's lit cone (unit axis dir, half-angle coneTheta).
     *  phi = angle of V off the axis; alpha = the sphere's angular radius. If
     *  phi - alpha > coneTheta the whole sphere sits at a larger axis angle than
     *  any lit fragment, so it can shadow only unlit fragments and is safe to
     *  drop. Conservative otherwise (a kept occluder may still be clipped by the
     *  bake projection). */
    private static boolean insideCone(float dirX, float dirY, float dirZ, float coneTheta,
                                      float vx, float vy, float vz, float r)
    {
        float d2 = vx * vx + vy * vy + vz * vz;
        if (d2 <= r * r)
        {
            return true; // light sits inside the occluder sphere -> can't cull
        }
        float dist = (float) Math.sqrt(d2);
        float cosPhi = (vx * dirX + vy * dirY + vz * dirZ) / dist; // dir is unit
        float phi = (float) Math.acos(MathHelper.clamp(cosPhi, -1f, 1f));
        float alpha = (float) Math.asin(MathHelper.clamp(r / dist, 0f, 1f));
        return phi - alpha <= coneTheta + CONE_ANGLE_MARGIN;
    }

    /** Conservative sphere-vs-cube-face-frustum test. The 90° face frustum's four
     *  side planes pass through the light with inward normals (axis ± tangent);
     *  the sphere lies outside the frustum iff it is fully beyond one of them.
     *  {@code k} = sphere radius · √2 (the plane-normal magnitude folded in).
     *  pd = signed offset along the face axis, a/b = |offset| along the two
     *  tangents; keep iff pd + k reaches both tangents. */
    private static boolean sphereTouchesFace(int face, float vx, float vy, float vz, float k)
    {
        float pd, a, b;
        switch (face)
        {
            case 0:  pd =  vx; a = Math.abs(vy); b = Math.abs(vz); break; // +X
            case 1:  pd = -vx; a = Math.abs(vy); b = Math.abs(vz); break; // -X
            case 2:  pd =  vy; a = Math.abs(vx); b = Math.abs(vz); break; // +Y
            case 3:  pd = -vy; a = Math.abs(vx); b = Math.abs(vz); break; // -Y
            case 4:  pd =  vz; a = Math.abs(vx); b = Math.abs(vy); break; // +Z
            default: pd = -vz; a = Math.abs(vx); b = Math.abs(vy); break; // -Z
        }
        float lim = pd + k;
        return lim >= a && lim >= b;
    }

    /** The variant-specific caster source behind the seam (redactor: BBS-free,
     *  vanilla entities). {@link #collect} routes through it; the orchestration
     *  otherwise touches casters only as the faceless SoA + the two seam methods
     *  ({@code collect} / {@code emitOccluder}). */

    /** Allocation-free SoA writer the source fills (one slot per emit, dropped over
     *  {@link #MAX_OCCLUDERS}). {@code emitFromBox} computes the cull-pinned bounding
     *  sphere (mid-height center + circumscribing box-diagonal radius, INVARIANT 5)
     *  so no source can supply a foreign sphere. */
    private static final OccluderSink SINK = new OccluderSink()
    {
        @Override
        public void emitFromBox(Object caster, int type, boolean isStatic,
                                double interpX, double interpY, double interpZ,
                                Box box, float scale, long staticHash)
        {
            if (occCount >= MAX_OCCLUDERS)
            {
                return;
            }
            // Box edge lengths via stable public fields (yarn renamed getXLength()
            // -> getLengthX() after 1.20.1; fields are identical across versions).
            double ex = box.maxX - box.minX, ey = box.maxY - box.minY, ez = box.maxZ - box.minZ;
            float rad = (float) (0.5 * Math.sqrt(ex * ex + ey * ey + ez * ez) * scale) + OVERLAP_MARGIN;
            put(caster, type, isStatic, (float) interpX, (float) (interpY + ey * 0.5), (float) interpZ, rad, staticHash);
        }

        @Override
        public void emit(Object caster, int type, boolean isStatic,
                         float cx, float cy, float cz, float radius, long staticHash)
        {
            if (occCount >= MAX_OCCLUDERS)
            {
                return;
            }
            put(caster, type, isStatic, cx, cy, cz, radius, staticHash);
        }
    };

    /** Append one occluder into the fixed-32 SoA. */
    private static void put(Object caster, int type, boolean isStatic,
                            float cx, float cy, float cz, float radius, long staticHash)
    {
        occ[occCount] = caster;
        occType[occCount] = type;
        oStatic[occCount] = isStatic;
        ox[occCount] = cx;
        oy[occCount] = cy;
        oz[occCount] = cz;
        orad[occCount] = radius;
        ostatichash[occCount] = staticHash;
        occCount++;
    }

    private static void collect(ClientWorld world, Vec3d cameraPos, float tickDelta)
    {
        occCount = 0;
        ShadowEngine.source().collect(world, cameraPos, tickDelta, SINK);
    }
}
