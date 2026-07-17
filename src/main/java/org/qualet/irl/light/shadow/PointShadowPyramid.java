package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Min/max mip pyramid over the LIVE point shadow atlas (F1b). Flat static like
 * {@link SpotShadowPyramid}, but the storage stays PER-TIER: one
 * GL_TEXTURE_2D_ARRAY per {@link PointDepthAtlas} tier (three GLSL sampler
 * sets, irl_pointShadowPyramid/1/2), face-major within the tier
 * (layer = localBlock*6 + face), RG32F, base level = face/2, mip chain down to
 * one texel per face. The inject samples it (registered as 2D in Iris, rebound
 * to 2D_ARRAY by the host mod's SamplerBinding mixin, like the cookie array)
 * for a 4-texelFetch conservative classification before PCSS; taps whose
 * footprint crosses a cube-face border skip the early-out entirely (occluders
 * on the adjacent face are invisible to a face-local pyramid — the full PCSS
 * path handles the seam via per-tap face re-select).
 *
 * Same batched contract as {@link SpotShadowPyramid}: ShadowBaker marks blocks
 * dirty (GLOBAL block index, one bit per block — all 6 faces always dirty
 * together) after every write to their live faces and calls
 * {@link #flushDirty()} once after the point loop — one GL-state snapshot per
 * batch, one barrier per mip level per tier. Pass 0 texelFetches the depth
 * atlas directly: the block's 3x2 face rect is resolved from the blockOrigin
 * uniform + the compile-time FACE_OFF table (the atlas mirror of
 * PointDepthAtlas.FACE_COL/FACE_ROW); deeper levels texelFetch the pyramid
 * itself with an explicit lod.
 *
 * 2D-array texture bindings are NOT tracked by GlStateManager (its cache is
 * 2D-only), so raw glBindTexture + glGet-restore is used for that target;
 * everything else mirrors SpotShadowPyramid.
 */
public final class PointShadowPyramid
{
    private static final int[] texId = new int[3];   // one 2D-array per tier
    private static final int[] levels = new int[3];
    /** One bit per GLOBAL block (1L << block); long because the atlas layout
     *  allows up to 64 blocks (guarded in PointDepthAtlas). */
    private static long dirtyMask = 0L;

    // Compute programs + uniform locations — the GLSL sources are
    // size-independent, every size flows through uniforms.
    private static int progCube = 0;   // pass 0: depth atlas -> pyramid lod 0
    private static int progMip = 0;    // lod L-1 -> lod L
    private static int uCubeSrc, uCubeSlot, uCubeDstSize, uCubeBlockOrigin;
    private static int uMipSrc, uMipSrcLod, uMipLayerBase, uMipDstSize;
    private static boolean programsAttempted = false;

    private static final String CUBE_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n" +
        "layout(rg32f, binding = 0) uniform writeonly image2DArray dstMip;\n" +
        "uniform sampler2D srcAtlas;\n" +
        "uniform int slot;\n" +                      // LOCAL block index inside the tier (dst layer base / 6)
        "uniform ivec2 dstSize;\n" +
        "uniform ivec2 blockOrigin;\n" +             // pixel origin of the block (its col-0/row-0 face corner)
        "const ivec2 FACE_OFF[6] = ivec2[6](ivec2(0,0), ivec2(1,0), ivec2(2,0),\n" +
        "                                   ivec2(0,1), ivec2(1,1), ivec2(2,1));\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int face = int(gl_GlobalInvocationID.z);\n" +
        "    int srcRes = dstSize.x * 2;\n" +
        "    ivec2 faceBase = blockOrigin + FACE_OFF[face] * srcRes;\n" +
        "    float mn = 1.0;\n" +
        "    float mx = 0.0;\n" +
        "    for (int j = 0; j < 2; j++)\n" +
        "    for (int i = 0; i < 2; i++)\n" +
        "    {\n" +
        "        float d = texelFetch(srcAtlas, faceBase + g * 2 + ivec2(i, j), 0).r;\n" +
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

    private PointShadowPyramid()
    {}

    /** Lazy — 0 until the first flush; bound by name via the host mod's sampler
     *  mixins (registered 2D, rebound to GL_TEXTURE_2D_ARRAY). */
    public static int getGlTextureId(int tier)
    {
        return texId[tier];
    }

    /** Mark one GLOBAL block's 6 pyramid faces stale. Call after every
     *  bake/copy that changed any of the block's live faces. */
    public static void markDirty(int block)
    {
        if (block >= 0 && block < PointDepthAtlas.blockCount())
        {
            dirtyMask |= 1L << block;
        }
    }

    public static boolean isDirty(int block)
    {
        return (dirtyMask & (1L << block)) != 0L;
    }

    public static void clearBit(int block)
    {
        dirtyMask &= ~(1L << block);
    }

    public static void clearAll()
    {
        dirtyMask = 0L;
    }

    /** Rebuild every dirty block's pyramid (all 6 faces) in one batch. Call once
     *  per bake, after the point loop (before the SSBO flush / Iris passes). */
    public static void flushDirty()
    {
        if (dirtyMask == 0L)
        {
            return;
        }
        long mask = dirtyMask;
        dirtyMask = 0L;
        int depthTex = PointDepthAtlas.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId[0] == 0 || progCube == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL pyrMax>0 guard falls back to full PCSS
        }

        // --- save the touched state once per batch ---
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // pass 0: depth atlas -> pyramid lod 0, all dirty blocks tier by tier
        // (blocks are tier-contiguous), one barrier for the whole pass
        GlStateManager._glUseProgram(progCube);
        GL20.glUniform1i(uCubeSrc, unit);
        GlStateManager._bindTexture(depthTex);
        for (int t = 0; t < 3; t++)
        {
            int base = PointDepthAtlas.tierFaceSizePx(t) / 2;
            int start = PointDepthAtlas.tierStartBlock(t);
            int end = start + PointDepthAtlas.tierBlockCount(t);
            boolean bound = false;
            for (int b = start; b < end; b++)
            {
                if ((mask & (1L << b)) == 0L)
                {
                    continue;
                }
                if (!bound)
                {
                    GL42.glBindImageTexture(0, texId[t], 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG32F);
                    GL20.glUniform2i(uCubeDstSize, base, base);
                    bound = true;
                }
                GL20.glUniform1i(uCubeSlot, b - start);
                GL20.glUniform2i(uCubeBlockOrigin, PointDepthAtlas.blockPixelX(b), PointDepthAtlas.blockPixelY(b));
                GL43.glDispatchCompute((base + 7) / 8, (base + 7) / 8, 6);
            }
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // passes 1..levels-1, tier by tier: one image bind + one barrier per
        // LEVEL, all the tier's dirty blocks (same barrier count as the three
        // per-tier flushes of the cube-array layout)
        GlStateManager._glUseProgram(progMip);
        GL20.glUniform1i(uMipSrc, unit);
        for (int t = 0; t < 3; t++)
        {
            int start = PointDepthAtlas.tierStartBlock(t);
            int end = start + PointDepthAtlas.tierBlockCount(t);
            long tierBits = 0L;
            for (int b = start; b < end; b++)
            {
                tierBits |= mask & (1L << b);
            }
            if (tierBits == 0L)
            {
                continue;
            }
            int base = PointDepthAtlas.tierFaceSizePx(t) / 2;
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId[t]);
            for (int lod = 1; lod < levels[t]; lod++)
            {
                int dstW = base >> lod;
                GL20.glUniform1i(uMipSrcLod, lod - 1);
                GL42.glBindImageTexture(0, texId[t], lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG32F);
                GL20.glUniform2i(uMipDstSize, dstW, dstW);
                for (int b = start; b < end; b++)
                {
                    if ((mask & (1L << b)) == 0L)
                    {
                        continue;
                    }
                    GL20.glUniform1i(uMipLayerBase, (b - start) * 6);
                    GL43.glDispatchCompute((dstW + 7) / 8, (dstW + 7) / 8, 6);
                }
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            }
        }

        // --- restore ---
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
        GlStateManager._bindTexture(prevTex);
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

    private static void ensureResources()
    {
        if (!programsAttempted)
        {
            programsAttempted = true;
            progCube = buildProgram(CUBE_SRC, "cube");
            progMip = buildProgram(MIP_SRC, "mip");
            if (progCube != 0)
            {
                uCubeSrc = GL20.glGetUniformLocation(progCube, "srcAtlas");
                uCubeSlot = GL20.glGetUniformLocation(progCube, "slot");
                uCubeDstSize = GL20.glGetUniformLocation(progCube, "dstSize");
                uCubeBlockOrigin = GL20.glGetUniformLocation(progCube, "blockOrigin");
            }
            if (progMip != 0)
            {
                uMipSrc = GL20.glGetUniformLocation(progMip, "srcFlat");
                uMipSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMipLayerBase = GL20.glGetUniformLocation(progMip, "layerBase");
                uMipDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
        if (progCube == 0 || progMip == 0 || texId[0] != 0)
        {
            return;
        }

        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        for (int t = 0; t < 3; t++)
        {
            texId[t] = GL11.glGenTextures();
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId[t]);
            // base = face/2; levels = log2(faceSize): the deepest lod is one texel per face
            levels[t] = Integer.numberOfTrailingZeros(Integer.highestOneBit(PointDepthAtlas.tierFaceSizePx(t)));
            int base = PointDepthAtlas.tierFaceSizePx(t) / 2;
            GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, levels[t], GL30.GL_RG32F, base, base, PointDepthAtlas.tierBlockCount(t) * 6);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, levels[t] - 1);
        }
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

    /** Free the textures (called with PointDepthAtlas.delete(), e.g. preset
     *  switch). 2D-array bindings are not tracked by GlStateManager, so a raw
     *  delete is safe here (unlike the 2D spot pyramid). */
    public static void delete()
    {
        for (int t = 0; t < 3; t++)
        {
            if (texId[t] != 0)
            {
                GL11.glDeleteTextures(texId[t]);
                texId[t] = 0;
            }
            levels[t] = 0;
        }
        clearAll();
    }
}
