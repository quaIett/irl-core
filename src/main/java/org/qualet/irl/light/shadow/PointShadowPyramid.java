package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Min/max mip pyramid over the LIVE point shadow cube-array (F1b). One INSTANCE
 * per {@link PointShadowArray} tier (owned by it, reached via
 * {@link PointShadowArray#pyramid()}); all sizes/slot counts come from the
 * owning array, so each tier's pyramid tracks its tier's resolution. The
 * compute programs are size-independent (sizes flow through uniforms) and stay
 * SHARED across instances. Stored as a plain GL_TEXTURE_2D_ARRAY, face-major
 * (layer = slot*6 + face), RG32F, base level = face/2, mip chain down to one
 * texel per face. The inject samples it as {@code irl_pointShadowPyramid}
 * (registered as 2D in Iris, rebound to 2D_ARRAY by the host mod's
 * SamplerBinding mixin, like the cookie array) for a 4-texelFetch conservative
 * classification before PCSS; taps whose footprint crosses a cube-face border
 * skip the early-out entirely (occluders on the adjacent face are invisible to
 * a face-local pyramid — the full PCSS path handles the seam via real cube
 * sampling).
 *
 * Same batched contract as {@link SpotShadowPyramid}: ShadowBaker marks slots
 * dirty after every write to their live faces and calls {@link #flushDirty()}
 * once after the point loop — one GL-state snapshot per batch, one barrier per
 * mip level. Pass 0 reads the depth cube through a samplerCubeArray at texel
 * centers (bilinear weights collapse to the exact texel there), reconstructing
 * the direction from (face, uv) with the inverse of the GL face-selection
 * table; deeper levels texelFetch the pyramid itself with an explicit lod.
 *
 * Cube-array / 2D-array texture bindings are NOT tracked by GlStateManager
 * (its cache is 2D-only), so raw glBindTexture + glGet-restore is used for
 * those targets; everything else mirrors SpotShadowPyramid.
 */
public final class PointShadowPyramid
{
    private final PointShadowArray owner;

    private int texId = 0;
    private int levels = 0;
    private int dirtyMask = 0;

    // Compute programs + uniform locations: SHARED across all tier instances —
    // the GLSL sources are size-independent, every size flows through uniforms.
    private static int progCube = 0;   // pass 0: depth cube -> pyramid lod 0
    private static int progMip = 0;    // lod L-1 -> lod L
    private static int uCubeSrc, uCubeSlot, uCubeDstSize;
    private static int uMipSrc, uMipSrcLod, uMipLayerBase, uMipDstSize;
    private static boolean programsAttempted = false;

    private static final String CUBE_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n" +
        "layout(rg32f, binding = 0) uniform writeonly image2DArray dstMip;\n" +
        "uniform samplerCubeArray srcCube;\n" +
        "uniform int slot;\n" +
        "uniform ivec2 dstSize;\n" +
        "vec3 faceDir(int face, vec2 st)\n" +      // inverse of the GL cube face-selection table
        "{\n" +
        "    if (face == 0) return vec3( 1.0, -st.y, -st.x);\n" +
        "    if (face == 1) return vec3(-1.0, -st.y,  st.x);\n" +
        "    if (face == 2) return vec3( st.x,  1.0,  st.y);\n" +
        "    if (face == 3) return vec3( st.x, -1.0, -st.y);\n" +
        "    if (face == 4) return vec3( st.x, -st.y,  1.0);\n" +
        "    return vec3(-st.x, -st.y, -1.0);\n" +
        "}\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int face = int(gl_GlobalInvocationID.z);\n" +
        "    float srcRes = float(dstSize.x * 2);\n" +
        "    float mn = 1.0;\n" +
        "    float mx = 0.0;\n" +
        "    for (int j = 0; j < 2; j++)\n" +
        "    for (int i = 0; i < 2; i++)\n" +
        "    {\n" +
        "        vec2 uv = (vec2(g * 2 + ivec2(i, j)) + 0.5) / srcRes;\n" +   // texel centers: bilinear collapses to the exact texel
        "        float d = textureLod(srcCube, vec4(faceDir(face, uv * 2.0 - 1.0), float(slot)), 0.0).r;\n" +
        "        mn = min(mn, d);\n" +
        "        mx = max(mx, d);\n" +
        "    }\n" +
        "    imageStore(dstMip, ivec3(g, slot * 6 + face), vec4(mn, mx, 0.0, 0.0));\n" +
        "}\n";

    private static final String MIP_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n" +
        "layout(rg32f, binding = 0) uniform writeonly image2DArray dstMip;\n" +
        "uniform sampler2DArray srcFlat;\n" +
        "uniform int srcLod;\n" +
        "uniform int layerBase;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int layer = layerBase + int(gl_GlobalInvocationID.z);\n" +
        "    ivec2 s = g * 2;\n" +
        "    vec2 a = texelFetch(srcFlat, ivec3(s,               layer), srcLod).rg;\n" +
        "    vec2 b = texelFetch(srcFlat, ivec3(s + ivec2(1, 0), layer), srcLod).rg;\n" +
        "    vec2 c = texelFetch(srcFlat, ivec3(s + ivec2(0, 1), layer), srcLod).rg;\n" +
        "    vec2 d = texelFetch(srcFlat, ivec3(s + ivec2(1, 1), layer), srcLod).rg;\n" +
        "    float mn = min(min(a.x, b.x), min(c.x, d.x));\n" +
        "    float mx = max(max(a.y, b.y), max(c.y, d.y));\n" +
        "    imageStore(dstMip, ivec3(g, layer), vec4(mn, mx, 0.0, 0.0));\n" +
        "}\n";

    PointShadowPyramid(PointShadowArray owner)
    {
        this.owner = owner;
    }

    /** Lazy — 0 until the first flush; bound by name via the host mod's sampler
     *  mixins (registered 2D, rebound to GL_TEXTURE_2D_ARRAY). */
    public int getGlTextureId()
    {
        return texId;
    }

    /** Mark one slot's 6 pyramid faces stale. Call after every bake/copy that
     *  changed any of the slot's live faces. */
    public void markDirty(int slot)
    {
        if (slot >= 0 && slot < owner.slotCount())
        {
            dirtyMask |= 1 << slot;
        }
    }

    /** Rebuild every dirty slot's pyramid (all 6 faces) in one batch. Call once
     *  per bake, after the point loop (before the SSBO flush / Iris passes). */
    public void flushDirty()
    {
        if (dirtyMask == 0)
        {
            return;
        }
        int mask = dirtyMask;
        dirtyMask = 0;
        int depthTex = owner.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId == 0 || progCube == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL pyrMax>0 guard falls back to full PCSS
        }

        // --- save the touched state once per batch ---
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevCube = GL11.glGetInteger(GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY);
        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        int base = owner.getFaceSize() / 2;

        // pass 0: depth cube -> pyramid lod 0, all dirty slots (z = 6 faces per dispatch), one barrier
        GlStateManager._glUseProgram(progCube);
        GL20.glUniform1i(uCubeSrc, unit);
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, depthTex);
        GL42.glBindImageTexture(0, texId, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG32F);
        GL20.glUniform2i(uCubeDstSize, base, base);
        for (int slot = 0; slot < owner.slotCount(); slot++)
        {
            if ((mask & (1 << slot)) == 0)
            {
                continue;
            }
            GL20.glUniform1i(uCubeSlot, slot);
            GL43.glDispatchCompute((base + 7) / 8, (base + 7) / 8, 6);
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // passes 1..levels-1: one image bind + one barrier per LEVEL, all dirty slots
        GlStateManager._glUseProgram(progMip);
        GL20.glUniform1i(uMipSrc, unit);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
        for (int lod = 1; lod < levels; lod++)
        {
            int dstW = base >> lod;
            GL20.glUniform1i(uMipSrcLod, lod - 1);
            GL42.glBindImageTexture(0, texId, lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG32F);
            GL20.glUniform2i(uMipDstSize, dstW, dstW);
            for (int slot = 0; slot < owner.slotCount(); slot++)
            {
                if ((mask & (1 << slot)) == 0)
                {
                    continue;
                }
                GL20.glUniform1i(uMipLayerBase, slot * 6);
                GL43.glDispatchCompute((dstW + 7) / 8, (dstW + 7) / 8, 6);
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        // --- restore ---
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, prevCube);
        GlStateManager._glUseProgram(prevProgram);
        if (prevImgName == 0)
        {
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
        }
        else
        {
            GL42.glBindImageTexture(0, prevImgName, prevImgLevel, prevImgLayered != 0, prevImgLayer, prevImgAccess, prevImgFormat);
        }
    }

    private void ensureResources()
    {
        if (!programsAttempted)
        {
            programsAttempted = true;
            progCube = buildProgram(CUBE_SRC, "cube");
            progMip = buildProgram(MIP_SRC, "mip");
            if (progCube != 0)
            {
                uCubeSrc = GL20.glGetUniformLocation(progCube, "srcCube");
                uCubeSlot = GL20.glGetUniformLocation(progCube, "slot");
                uCubeDstSize = GL20.glGetUniformLocation(progCube, "dstSize");
            }
            if (progMip != 0)
            {
                uMipSrc = GL20.glGetUniformLocation(progMip, "srcFlat");
                uMipSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMipLayerBase = GL20.glGetUniformLocation(progMip, "layerBase");
                uMipDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
        if (progCube == 0 || progMip == 0 || texId != 0)
        {
            return;
        }

        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
        // base = face/2; levels = log2(faceSize): the deepest lod is one texel per face
        levels = Integer.numberOfTrailingZeros(Integer.highestOneBit(owner.getFaceSize()));
        int base = owner.getFaceSize() / 2;
        GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, levels, GL30.GL_RG32F, base, base, owner.layerCount());
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
    }

    private static int buildProgram(String src, String tag)
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowPyramid " + tag + " compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowPyramid " + tag + " compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Free the texture (called with the owning PointShadowArray's delete(),
     *  e.g. preset switch). 2D-array bindings are not tracked by GlStateManager,
     *  so a raw delete is safe here (unlike the 2D spot pyramid). */
    public void delete()
    {
        if (texId != 0)
        {
            GL11.glDeleteTextures(texId);
            texId = 0;
        }
        levels = 0;
        dirtyMask = 0;
    }
}
