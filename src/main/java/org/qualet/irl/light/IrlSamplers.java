package org.qualet.irl.light;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import org.qualet.irl.light.shadow.PointShadowTiers;
import org.qualet.irl.light.shadow.SpotShadowEvsm;
import org.qualet.irl.light.shadow.SpotShadowPyramid;
import org.qualet.irl.light.shadow.SpotlightDepthAtlas;

/**
 * Central registry of the IRLite shadow textures that get bound into every Iris
 * program as dynamic samplers. Each entry pairs a sampler uniform name (the
 * rendering contract with the injected GLSL) with a live GL id supplier and the
 * real GL target of that texture.
 *
 * <p>Iris's TextureType enum has neither CUBE_MAP_ARRAY nor 2D_ARRAY, so every
 * sampler is registered as a plain 2D one; the array-backed textures carry their
 * true target here and are rebound to it on the same unit at bind time (see the
 * per-mod SamplerBinding mixin / {@code IrlSamplersBind.tryRebind}).</p>
 *
 * <p>Insertion order is preserved and is part of the contract: the names and GL
 * targets must stay byte-for-byte identical to what the shaders expect. The six
 * core-owned textures register themselves here; the per-mod cookie/gobo array is
 * registered from each mod's client init (its owner class lives per-mod).</p>
 *
 * <p>Registry only — no Iris or Minecraft types. The Iris-facing glue lives in
 * {@code org.qualet.irl.light.iris.IrlSamplersBind}.</p>
 */
public final class IrlSamplers
{
    /** Receives each registered sampler in insertion order. */
    @FunctionalInterface
    public interface SamplerConsumer
    {
        void accept(String name, IntSupplier glId, int glTarget);
    }

    private static final class Entry
    {
        final IntSupplier glId;
        final int glTarget;

        Entry(IntSupplier glId, int glTarget)
        {
            this.glId = glId;
            this.glTarget = glTarget;
        }
    }

    private static final Map<String, Entry> SAMPLERS = new LinkedHashMap<>();

    static
    {
        // Core-owned shadow textures. Order + names + targets mirror the GLSL contract.
        register("irl_spotShadowAtlas", SpotlightDepthAtlas::getGlTextureId, GL11.GL_TEXTURE_2D);
        register("irl_pointShadowArray", () -> PointShadowTiers.tier(0).getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        // F1a: min/max mip pyramid of the spot atlas (plain 2D, no target rebind needed).
        register("irl_spotShadowPyramid", SpotShadowPyramid::getGlTextureId, GL11.GL_TEXTURE_2D);
        // F1b: face-major point pyramid — a 2D array, rebound to GL_TEXTURE_2D_ARRAY.
        register("irl_pointShadowPyramid", () -> PointShadowTiers.tier(0).pyramid().getGlTextureId(), GL30.GL_TEXTURE_2D_ARRAY);
        // F2a: EVSM prefilter of the spot atlas (plain 2D + mips, no target rebind needed).
        register("irl_spotEvsm", SpotShadowEvsm::getGlTextureId, GL11.GL_TEXTURE_2D);
        // F2b: point MSM sampled through a CUBE_MAP_ARRAY view (hardware-seamless face edges).
        register("irl_pointEvsm", () -> PointShadowTiers.tier(0).evsm().getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        // I3 LOD tiers: the tier-1/2 point texture sets, appended AFTER the six
        // legacy entries — this order is the new FROZEN contract for the phase-I4
        // GLSL. Tier-0 names stay unsuffixed. Iris binds by NAME; registration
        // order only shifts texture-unit numbers, which no consumer depends on
        // (the per-mod cookie array still registers later, at mod init). Same GL
        // targets as the tier-0 counterparts.
        register("irl_pointShadowArray1", () -> PointShadowTiers.tier(1).getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        register("irl_pointShadowPyramid1", () -> PointShadowTiers.tier(1).pyramid().getGlTextureId(), GL30.GL_TEXTURE_2D_ARRAY);
        register("irl_pointEvsm1", () -> PointShadowTiers.tier(1).evsm().getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        register("irl_pointShadowArray2", () -> PointShadowTiers.tier(2).getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        register("irl_pointShadowPyramid2", () -> PointShadowTiers.tier(2).pyramid().getGlTextureId(), GL30.GL_TEXTURE_2D_ARRAY);
        register("irl_pointEvsm2", () -> PointShadowTiers.tier(2).evsm().getGlTextureId(), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
    }

    private IrlSamplers()
    {
    }

    /**
     * Registers a sampler under {@code name} bound to the texture id from
     * {@code glId}, sampled with the given GL target ({@link GL11#GL_TEXTURE_2D}
     * for plain 2D textures, {@link GL30#GL_TEXTURE_2D_ARRAY} or
     * {@link GL40#GL_TEXTURE_CUBE_MAP_ARRAY} for array-backed ones). Re-registering
     * an existing name replaces it in place, keeping its original ordering slot.
     */
    public static void register(String name, IntSupplier glId, int glTarget)
    {
        SAMPLERS.put(name, new Entry(glId, glTarget));
    }

    /** Visits every registered sampler in registration order. */
    public static void forEach(SamplerConsumer consumer)
    {
        for (Map.Entry<String, Entry> e : SAMPLERS.entrySet())
        {
            Entry entry = e.getValue();
            consumer.accept(e.getKey(), entry.glId, entry.glTarget);
        }
    }

    /**
     * GL target the named sampler is sampled with, or {@link GL11#GL_TEXTURE_2D}
     * if it isn't registered (the default 2D bind is then harmless).
     */
    public static int glTargetFor(String name)
    {
        Entry entry = SAMPLERS.get(name);
        return entry != null ? entry.glTarget : GL11.GL_TEXTURE_2D;
    }
}
