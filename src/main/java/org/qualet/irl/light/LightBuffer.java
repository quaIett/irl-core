package org.qualet.irl.light;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Owns the GPU light SSBO and packs collected lights into it each frame.
 *
 * std430 contract (the patcher's GLSL must mirror this exactly):
 *
 *   struct Light {
 *       vec4 posRadius;       // xyz = world position, w = radius
 *       vec4 colorIntensity;  // rgb = linear color, a = intensity
 *       vec4 dirType;         // xyz = direction (spot, normalized), w = type (0 point, 1 spot)
 *       vec4 cone;            // x = cos(outerAngle/2), y = cos(innerAngle/2), z = lightMask (0 all, 1 entities only, 2 blocks only), w = bulbSize (0 = use global)
 *       vec4 vlParams;        // x = anisotropy (HG g), y = vlDensity, z = beamStrength, w = shadowTile (-1 = none)
 *   };
 *
 *   layout(std430, binding = BINDING) buffer IrliteLights {
 *       uint irlite_lightCount;
 *       uint _pad0, _pad1, _pad2;
 *       Light irlite_lights[];
 *   };
 */
public final class LightBuffer
{
    public static final int BINDING = 7;
    // Hard ceiling on simultaneous lights in the SSBO. Raised to 2048 to back the
    // editor's "Max sources" slider (auto block-lights). NOTE: the injected GLSL
    // loops over irlite_lightCount PER FRAGMENT, so the real-world limit is
    // performance, not this number — pushing the slider into the hundreds/thousands
    // gets very expensive. The shader array is unbounded (std430 []), so no pack
    // needs regenerating for a larger ceiling. SSBO size = 16 + 2048*80 ≈ 164 KB.
    public static final int MAX_LIGHTS = 2048;

    private static final int HEADER_BYTES = 16;     // uint count + 12 B pad (std430 vec4 align)
    private static final int LIGHT_BYTES = 80;      // 5 × vec4
    private static final int CAPACITY = HEADER_BYTES + MAX_LIGHTS * LIGHT_BYTES;

    private static int ssbo = 0;
    private static ByteBuffer scratch = null;
    private static boolean initialized = false;
    private static int count = 0;

    private LightBuffer()
    {}

    private static void init()
    {
        if (initialized)
        {
            return;
        }

        ssbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, CAPACITY, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        scratch = MemoryUtil.memAlloc(CAPACITY);

        initialized = true;
    }

    public static void begin()
    {
        if (!initialized)
        {
            init();
        }

        count = 0;
        scratch.clear();
        scratch.position(HEADER_BYTES);
    }

    public static void addPoint(float x, float y, float z, float r, float g, float b, float intensity, float radius, float lightMask, float anisotropy, float density, float beam, float shadowTile, float bulbSize)
    {
        if (count >= MAX_LIGHTS)
        {
            return;
        }

        scratch.putFloat(x).putFloat(y).putFloat(z).putFloat(radius);
        scratch.putFloat(r).putFloat(g).putFloat(b).putFloat(intensity);
        scratch.putFloat(0F).putFloat(0F).putFloat(0F).putFloat(0F);  // dir=0, type=point
        scratch.putFloat(1F).putFloat(1F).putFloat(lightMask).putFloat(bulbSize);  // cone: full, z=lightMask (0 all/1 entities/2 blocks), w=bulbSize
        scratch.putFloat(anisotropy).putFloat(density).putFloat(beam).putFloat(shadowTile);

        count++;
    }

    public static void addSpot(float x, float y, float z, float dx, float dy, float dz, float r, float g, float b, float intensity, float radius, float cosOuter, float cosInner, float lightMask, float anisotropy, float density, float beam, float shadowTile, float bulbSize)
    {
        if (count >= MAX_LIGHTS)
        {
            return;
        }

        scratch.putFloat(x).putFloat(y).putFloat(z).putFloat(radius);
        scratch.putFloat(r).putFloat(g).putFloat(b).putFloat(intensity);
        scratch.putFloat(dx).putFloat(dy).putFloat(dz).putFloat(1F);   // type=spot
        scratch.putFloat(cosOuter).putFloat(cosInner).putFloat(lightMask).putFloat(bulbSize);  // z=lightMask (0 all/1 entities/2 blocks), w=bulbSize
        scratch.putFloat(anisotropy).putFloat(density).putFloat(beam).putFloat(shadowTile);

        count++;
    }

    public static void upload()
    {
        if (!initialized)
        {
            return;
        }

        scratch.putInt(0, count);

        int used = scratch.position();
        scratch.position(0);
        scratch.limit(used);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, scratch);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        scratch.clear();
    }

    /** Zero the GPU-side light count so a pipeline (re)enabled later can't
     *  read a stale light set out of the SSBO. No-op if the buffer was never
     *  created — this path must not allocate anything. */
    public static void uploadEmpty()
    {
        if (!initialized)
        {
            return;
        }

        begin();
        upload();
    }

    public static void delete()
    {
        if (ssbo != 0)
        {
            GL15.glDeleteBuffers(ssbo);
            ssbo = 0;
        }

        if (scratch != null)
        {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }

        initialized = false;
    }
}
