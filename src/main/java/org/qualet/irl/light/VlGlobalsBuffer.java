package org.qualet.irl.light;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Owns the GPU UBO carrying the global volumetric-light knobs and re-uploads
 * it right after the light SSBO each frame (driven from LightBuffer.upload()).
 *
 * std140 contract (the patcher's GLSL must mirror this exactly):
 *
 *   layout(std140, binding = BINDING) uniform IrliteVlGlobals {
 *       vec4 irlite_vlA;   // x = intensity, y = maxDist, z = tipBoost, w = tipRadius
 *       vec4 irlite_vlB;   // x = noiseAmount, y = noiseScale, z = noiseSpeed, w = frameIndex (wraps at 4096)
 *       uvec4 irlite_vlC;  // x = stepMax, y = shadowStride, z = noiseStride, w = flags (bit0 = VL shadows, bit1 = VL noise, bit2 = blue-noise dither, bit3 = temporal dither rotation, bit4 = VL cluster culling)
 *       vec4 irlite_vlD;   // x = noiseMorph (0 = morph off), y/z/w = reserved0/1/2 (written as 0)
 *   };
 */
public final class VlGlobalsBuffer
{
    public static final int BINDING = 7;

    private static final int CAPACITY = 64;     // 4 × vec4 (std140)

    private static int ubo = 0;
    private static ByteBuffer scratch = null;
    private static boolean initialized = false;

    // Defaults before the first set() mirror the shader lib's compile-time
    // defines so packs behave identically until a mod pushes its config.
    private static float intensity = 1F;        // IRLITE_VL_INTENSITY 1.0
    private static float maxDist = 96F;         // IRLITE_VL_MAX_DIST 96.0
    private static float tipBoost = 1.5F;       // IRLITE_VL_TIP_BOOST 1.5
    private static float tipRadius = 1.5F;      // IRLITE_VL_TIP_RADIUS 1.5
    private static float noiseAmount = 0.6F;    // IRLITE_VL_NOISE_AMOUNT 0.6
    private static float noiseScale = 2F;       // IRLITE_VL_NOISE_SCALE 2.0
    private static float noiseSpeed = 0.25F;    // IRLITE_VL_NOISE_SPEED 0.25
    private static float noiseMorph = 1F;       // runtime-only knob (no compile-time define): fog puff reshape speed
    private static int stepMax = 48;            // IRLITE_VL_STEPS 48
    private static int shadowStride = 2;        // IRLITE_VL_SHADOW_STRIDE 2
    private static int noiseStride = 2;         // IRLITE_VL_NOISE_STRIDE 2
    private static int flags = 0x1F;            // bit0 = VL shadows, bit1 = VL noise, bit2 = blue-noise dither (default on), bit3 = temporal dither rotation (default on), bit4 = VL cluster culling (default on)

    /** Frame counter for the temporal dither rotation (flags bit3): written to
     *  irlite_vlB.w each upload, wrapped to 12 bits so the float stays exact.
     *  No setter — it only ever ticks here. */
    private static int frameIndex = 0;

    private VlGlobalsBuffer()
    {}

    private static void init()
    {
        if (initialized)
        {
            return;
        }

        ubo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, CAPACITY, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

        scratch = MemoryUtil.memAlloc(CAPACITY);

        initialized = true;
    }

    /** Pushes the full global VL knob set for the next upload.
     *  Intensity is clamped to &gt;= 1e-6 (NaN-safe, same rationale as
     *  LightBuffer's header clamp). noiseSpeed is quantized to 0.25 steps:
     *  the noise wind is defined in whole field-periods per 3600 s
     *  frameTimeCounter wrap-cycle, so off-grid speeds make the fog pop
     *  visibly every wrap. noiseMorph is quantized the same way: the shader's
     *  morph phase is frameTimeCounter * noiseMorph over a 900-slice cycle,
     *  and 3600 * (n * 0.25) is always a multiple of 900, so the wrap lands
     *  on a slice-congruent phase — off-grid speeds would pop there too.
     *  Steps/strides are clamped to the same sane ranges the injected GLSL
     *  clamps to. */
    public static void set(float intensity, float maxDist, float tipBoost, float tipRadius, float noiseAmount, float noiseScale, float noiseSpeed, float noiseMorph, int stepMax, int shadowStride, int noiseStride, int flags)
    {
        VlGlobalsBuffer.intensity = intensity >= 1e-6F ? intensity : 1e-6F;
        VlGlobalsBuffer.maxDist = maxDist;
        VlGlobalsBuffer.tipBoost = tipBoost;
        VlGlobalsBuffer.tipRadius = tipRadius;
        VlGlobalsBuffer.noiseAmount = noiseAmount;
        VlGlobalsBuffer.noiseScale = noiseScale;
        VlGlobalsBuffer.noiseSpeed = Math.round(noiseSpeed * 4F) / 4F;
        VlGlobalsBuffer.noiseMorph = Math.max(0F, Math.min(3F, Math.round(noiseMorph * 4F) / 4F));
        VlGlobalsBuffer.stepMax = Math.max(1, Math.min(96, stepMax));
        VlGlobalsBuffer.shadowStride = Math.max(1, Math.min(8, shadowStride));
        VlGlobalsBuffer.noiseStride = Math.max(1, Math.min(8, noiseStride));
        VlGlobalsBuffer.flags = flags;
    }

    public static void upload()
    {
        if (!initialized)
        {
            init();
        }

        scratch.clear();
        scratch.putFloat(intensity).putFloat(maxDist).putFloat(tipBoost).putFloat(tipRadius);
        scratch.putFloat(noiseAmount).putFloat(noiseScale).putFloat(noiseSpeed).putFloat(frameIndex);  // w = frameIndex
        scratch.putInt(stepMax).putInt(shadowStride).putInt(noiseStride).putInt(flags);
        scratch.putFloat(noiseMorph).putFloat(0F).putFloat(0F).putFloat(0F);  // vlD: x = noiseMorph, y/z/w reserved
        scratch.flip();

        frameIndex = (frameIndex + 1) & 4095;   // one tick per upload = per frame

        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, scratch);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING, ubo);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    public static void delete()
    {
        if (ubo != 0)
        {
            GL15.glDeleteBuffers(ubo);
            ubo = 0;
        }

        if (scratch != null)
        {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }

        initialized = false;
    }
}
