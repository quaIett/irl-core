package org.qualet.irl.light.shadow;

/**
 * The shadow-relevant slice of a mod's configuration, pulled by {@link ShadowBaker}
 * every bake. A strict SUBSET of each mod's config holder (redactor {@code LightConfig}
 * / addon {@code IrliteConfig}) — only the five getters the orchestration reads. Each
 * mod hands ONE instance to {@link ShadowEngine#install} at client init; the
 * orchestration never names the per-mod config class.
 *
 * <p>PULL, synchronous, side-effect-free: the addon side is backed by lazily-read BBS
 * {@code Value*} objects and the redactor side by plain mutable fields the ImGui editor
 * writes — neither has a push/listener source to invert. Getters are called inside
 * {@code bake()} and never cached across frames.
 *
 * <p>Frozen contract: {@code irl-core/docs/shadow-config-source-injection-spec.md}.
 */
public interface ShadowConfig
{
    /** Shadow resolution preset ordinal (0 LOW .. 3 ULTRA). A change frees +
     *  re-allocates the depth textures, so the orchestration forgets every cached
     *  map when it changes ({@link ShadowBaker} lastQuality). Default 1 (MEDIUM);
     *  clamped by {@link IRLShadowQuality#applyFromSetting}. */
    int shadowQuality();

    /** When true, depth maps are re-baked only when the scene changes; when false
     *  every light bakes into its live tile every frame. Default true. */
    boolean shadowCache();

    /** Max FULL STATIC bakes started per frame before the rest are deferred to a
     *  later frame (T2.4). {@code <= 0} disables throttling (bake everything every
     *  frame). First bakes / tile-reassign bakes are never deferred; dynamic overlays
     *  and static-&gt;live copies are never budgeted. Default 4. */
    int shadowBakeBudget();

    /** When true, world blocks cast shadows by their real shape. Default true. */
    boolean shadowBlocks();

    /** Block-shadow collection radius in blocks: world blocks farther than this from
     *  a light cast no shadow even when the light's range is larger. Bounds the
     *  per-light bbox walk. Default 24. */
    int shadowBlockRadius();

    /**
     * Canonical fallback ({@code 1 / true / 4 / true / 24}) so a mod that never
     * installs a config still bakes instead of NPEing. {@link ShadowEngine} uses this
     * until a mod calls {@link ShadowEngine#install}.
     */
    ShadowConfig DEFAULTS = new ShadowConfig()
    {
        public int shadowQuality()     { return 1; }
        public boolean shadowCache()   { return true; }
        public int shadowBakeBudget()  { return 4; }
        public boolean shadowBlocks()  { return true; }
        public int shadowBlockRadius() { return 24; }
    };
}
