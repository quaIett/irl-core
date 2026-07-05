package org.qualet.irl.light.shadow;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

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

    /**
     * Named builder for a {@link ShadowConfig} shim backed by live suppliers — one per
     * getter, called fresh on every read (never cached). Replaces the identical
     * 5-getter anonymous-class shim each mod hand-wrote (addon {@code
     * IrliteShadowConfig}, redactor {@code LightConfig.SHADOW}). Named methods on
     * purpose: a positional {@code of(IntSupplier, BooleanSupplier, IntSupplier,
     * BooleanSupplier, IntSupplier)} factory would let two same-typed suppliers swap
     * silently with no compiler error.
     */
    static Builder builder()
    {
        return new Builder();
    }

    /** @see ShadowConfig#builder() */
    final class Builder
    {
        private IntSupplier shadowQuality;
        private BooleanSupplier shadowCache;
        private IntSupplier shadowBakeBudget;
        private BooleanSupplier shadowBlocks;
        private IntSupplier shadowBlockRadius;

        private Builder()
        {}

        public Builder shadowQuality(IntSupplier shadowQuality)
        {
            this.shadowQuality = shadowQuality;
            return this;
        }

        public Builder shadowCache(BooleanSupplier shadowCache)
        {
            this.shadowCache = shadowCache;
            return this;
        }

        public Builder shadowBakeBudget(IntSupplier shadowBakeBudget)
        {
            this.shadowBakeBudget = shadowBakeBudget;
            return this;
        }

        public Builder shadowBlocks(BooleanSupplier shadowBlocks)
        {
            this.shadowBlocks = shadowBlocks;
            return this;
        }

        public Builder shadowBlockRadius(IntSupplier shadowBlockRadius)
        {
            this.shadowBlockRadius = shadowBlockRadius;
            return this;
        }

        public ShadowConfig build()
        {
            IntSupplier quality = requireNonNull(shadowQuality, "shadowQuality");
            BooleanSupplier cache = requireNonNull(shadowCache, "shadowCache");
            IntSupplier bakeBudget = requireNonNull(shadowBakeBudget, "shadowBakeBudget");
            BooleanSupplier blocks = requireNonNull(shadowBlocks, "shadowBlocks");
            IntSupplier blockRadius = requireNonNull(shadowBlockRadius, "shadowBlockRadius");
            return new ShadowConfig()
            {
                public int shadowQuality()     { return quality.getAsInt(); }
                public boolean shadowCache()   { return cache.getAsBoolean(); }
                public int shadowBakeBudget()  { return bakeBudget.getAsInt(); }
                public boolean shadowBlocks()  { return blocks.getAsBoolean(); }
                public int shadowBlockRadius() { return blockRadius.getAsInt(); }
            };
        }

        private static <T> T requireNonNull(T value, String name)
        {
            if (value == null)
            {
                throw new IllegalStateException("ShadowConfig.builder(): missing " + name + "(...)");
            }
            return value;
        }
    }
}
