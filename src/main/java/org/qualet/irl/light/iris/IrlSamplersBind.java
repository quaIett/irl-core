package org.qualet.irl.light.iris;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.texture.TextureType;
import org.lwjgl.opengl.GL11;

import org.qualet.irl.light.IrlSamplers;

/**
 * Iris glue for {@link IrlSamplers}: adds every registered IRLite shadow texture
 * to an Iris program as a dynamic sampler, and rebinds the array-backed ones to
 * their real GL target on the same unit at bind time.
 *
 * <p>Kept separate from the registry so {@code IrlSamplers} stays free of Iris
 * types. The per-mod {@code ProgramSamplersBuilderMixin} /
 * {@code SamplerBindingCubeArrayMixin} delegate here.</p>
 */
public final class IrlSamplersBind
{
    private IrlSamplersBind()
    {
    }

    /**
     * Registers every IRLite sampler on the given Iris program builder.
     * {@link ProgramSamplers.Builder#addDynamicSampler} is a no-op (returns false)
     * for programs that don't declare the uniform, so no texture unit is wasted.
     */
    public static void bindAll(ProgramSamplers.Builder builder)
    {
        // Iris 1.10.7 dropped the 2-arg addDynamicSampler(IntSupplier, String); the
        // surviving typed overload is
        // addDynamicSampler(TextureType, IntSupplier, Supplier<GlSampler>, String...).
        // Every IRLite sampler is registered as a plain TEXTURE_2D (TextureType has no
        // CUBE_MAP_ARRAY / 2D_ARRAY); the array-backed ones are rebound to their real
        // GL target on bind time by the per-mod SamplerBinding mixin (see tryRebind).
        // The Supplier<GlSampler> is () -> null so Iris uses the program's default sampler.
        IrlSamplers.forEach((name, glId, glTarget) ->
            builder.addDynamicSampler(TextureType.TEXTURE_2D, glId, () -> null, name));
    }

    /**
     * If {@code boundId} is one of our array-backed samplers, rebind it to its real
     * GL target on {@code textureUnit} and return true (the caller should then cancel
     * the default 2D bind). Plain 2D samplers and unknown ids return false, leaving
     * Iris's own bind in place.
     */
    public static boolean tryRebind(int boundId, int textureUnit)
    {
        if (boundId == 0)
        {
            return false;
        }

        boolean[] matched = {false};
        IrlSamplers.forEach((name, glId, glTarget) ->
        {
            if (matched[0] || glTarget == GL11.GL_TEXTURE_2D)
            {
                return;
            }
            int id = glId.getAsInt();
            if (id != 0 && id == boundId)
            {
                IrisRenderSystem.bindTextureToUnit(glTarget, textureUnit, boundId);
                matched[0] = true;
            }
        });
        return matched[0];
    }
}
