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
 *       uvec4 irlite_vlC;  // x = stepMax, y = shadowStride, z = noiseStride, w = flags (bit0 = VL shadows, bit1 = VL noise, bit2 = blue-noise dither, bit3 = temporal dither rotation, bit4 = VL cluster culling, bit5 = VL shadow Hi-Z segment skip, bit6 = depth-aware bilateral upsample, bit7 = GLOBALS VALID, bit8 = outline, bit9 = outline front, bit10 = outline glow, bits11-12 = outline target)
 *       vec4 irlite_vlD;   // x = noiseMorph (0 = morph off), y = bilateral depth sigma in blocks (0 = shader default), z/w = reserved1/2 (written as 0)
 *       vec4 irlite_vlE;   // outline: x = strength, y = fresnelPower, z = back, w = frontStrength
 *       vec4 irlite_vlF;   // outline: x = glowStrength, y = pixelSize (int-valued), z/w = reserved (written as 0)
 *   };
 *
 * Growing the block in the TAIL is binary-safe: std140 offsets 0..63 do not move,
 * so a pack built against the 4-vec4 version keeps reading the same bytes out of
 * the larger buffer. Never reorder existing fields or bits 0..6.
 */
public final class VlGlobalsBuffer
{
    public static final int BINDING = 7;

    private static final int CAPACITY = 96;     // 6 × vec4 (std140)

    /** vlC.w bit7. Set on every upload (never via a setter, so it cannot be
     *  forgotten): it tells the shader the whole block carries real data. An
     *  unbound UBO reads as zeros, so the bit is false and every knob falls back
     *  to its compile-time define. A zero sentinel cannot serve here — 0 is a
     *  legal user value for most of the outline scalars. */
    private static final int FLAG_GLOBALS_VALID = 1 << 7;

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
    private static float noiseMorph = 0F;       // runtime-only knob (no compile-time define): fog puff reshape speed; 0 = off (a second noise tap per refresh — measured pricier than the noise itself)
    private static int stepMax = 48;            // IRLITE_VL_STEPS 48
    private static int shadowStride = 2;        // IRLITE_VL_SHADOW_STRIDE 2
    private static int noiseStride = 2;         // IRLITE_VL_NOISE_STRIDE 2
    private static int flags = 0x7F;            // bit0 = VL shadows, bit1 = VL noise, bit2 = blue-noise dither (default on), bit3 = temporal dither rotation (default on), bit4 = VL cluster culling (default on), bit5 = VL shadow Hi-Z segment skip (default on), bit6 = depth-aware bilateral upsample (default on)

    // Outline block (wave 1). Defaults mirror the pack's compile-time defines so
    // behaviour is unchanged until a mod pushes its config. Held in their OWN
    // flags word rather than folded into `flags` above: setVlGlobals callers
    // rebuild `flags` from the VL toggles alone (the dev sweep in particular),
    // and merging the two would let a partial VL push silently clear the outline
    // state. upload() ORs them together instead.
    private static float outlineStrength = 0.65F;      // IRLITE_OUTLINE_STRENGTH 0.65
    private static float outlineFresnelPower = 2.2F;   // IRLITE_OUTLINE_FRESNEL_POWER 2.2
    private static float outlineBack = 1F;             // IRLITE_OUTLINE_BACK 1.0
    private static float outlineFrontStrength = 0.3F;  // IRLITE_OUTLINE_FRONT_STRENGTH 0.3
    private static float outlineGlowStrength = 0.12F;  // IRLITE_OUTLINE_GLOW_STRENGTH 0.12
    private static float outlinePixelSize = 6F;        // IRLITE_OUTLINE_PIXEL_SIZE 6
    private static int outlineFlags = (1 << 8) | (1 << 11);   // outline on, front off, glow off, target 1 (entities)

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

    /** Pushes the outline knob set for the next upload. Separate from set() on
     *  purpose — see the outlineFlags comment above. target is 0 all / 1 entities
     *  / 2 blocks; pixelSize is the depth-edge tap offset in pixels. */
    public static void setOutline(boolean enabled, int target, float strength, float fresnelPower,
                                  float back, boolean front, float frontStrength,
                                  boolean glow, float glowStrength, int pixelSize)
    {
        VlGlobalsBuffer.outlineStrength = strength;
        VlGlobalsBuffer.outlineFresnelPower = Math.max(1e-3F, fresnelPower);   // pow() exponent
        VlGlobalsBuffer.outlineBack = back;
        VlGlobalsBuffer.outlineFrontStrength = frontStrength;
        VlGlobalsBuffer.outlineGlowStrength = glowStrength;
        VlGlobalsBuffer.outlinePixelSize = Math.max(1, Math.min(6, pixelSize));
        VlGlobalsBuffer.outlineFlags = (enabled ? 1 << 8 : 0)
            | (front ? 1 << 9 : 0)
            | (glow ? 1 << 10 : 0)
            | ((Math.max(0, Math.min(2, target)) & 3) << 11);
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
        scratch.putInt(stepMax).putInt(shadowStride).putInt(noiseStride).putInt(flags | outlineFlags | FLAG_GLOBALS_VALID);
        scratch.putFloat(noiseMorph).putFloat(0F).putFloat(0F).putFloat(0F);  // vlD: x = noiseMorph, y = bilateral sigma (0 = shader default, no setter yet), z/w reserved
        scratch.putFloat(outlineStrength).putFloat(outlineFresnelPower).putFloat(outlineBack).putFloat(outlineFrontStrength);  // vlE
        scratch.putFloat(outlineGlowStrength).putFloat(outlinePixelSize).putFloat(0F).putFloat(0F);  // vlF: z/w reserved
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
