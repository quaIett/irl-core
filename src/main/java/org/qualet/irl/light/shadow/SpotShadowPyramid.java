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
 * Min/max mip pyramid over the LIVE spot depth atlas (F1a of the shadow
 * filtering refactor). RG32F, base level = atlas/2 (one texel = min/max of a
 * 2x2 depth quad), mip chain down to one texel per tile. The inject samples it
 * as {@code irl_spotShadowPyramid} for a 4-texelFetch conservative
 * fully-lit / fully-shadowed classification before entering PCSS.
 *
 * BATCHED: ShadowBaker marks tiles dirty ({@link #markDirty}) after every
 * write to their live depth (dirty static bake, every overlay frame) and calls
 * {@link #flushDirty()} once at the end of the spot loop — one GL-state
 * snapshot and one barrier per mip LEVEL per frame instead of per tile
 * (mirrors the savePassState "snapshot once per bake" invariant). The pyramid
 * therefore always matches the live atlas content the shader samples this
 * frame (bake runs before the SSBO flush and all Iris passes).
 *
 * Built with compute passes (GL43 is already the baseline: glCopyImageSubData)
 * so no FBO/viewport/scissor/VAO state is touched; the touched state (current
 * program, one texture binding, image unit 0) is saved and restored around
 * the flush, texture binds go through GlStateManager to keep its cache
 * coherent (same pattern as SpotlightDepthAtlas.createAtlas).
 *
 * Regions of tiles that never baked hold garbage (storage is uncleared) — safe
 * today because the shader enters the pyramid block only for vlParams.w >= 0,
 * which is only ever set on a path that baked (and flushed) that tile.
 */
public final class SpotShadowPyramid
{
    private static int texId = 0;
    private static int levels = 0;
    private static int program = 0;
    private static int uSrcTex, uSrcLod, uSrcIsDepth, uSrcOrigin, uDstOrigin, uDstSize;
    private static boolean programAttempted = false;
    private static int dirtyMask = 0;

    private static final String COMPUTE_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8) in;\n" +
        "layout(rg32f, binding = 0) uniform writeonly image2D dstMip;\n" +
        "uniform sampler2D srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform int srcIsDepth;\n" +           // 1 = depth atlas (.r), 0 = own RG min/max mip
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    ivec2 s = srcOrigin + g * 2;\n" +
        "    vec2 a, b, c, d;\n" +
        "    if (srcIsDepth == 1)\n" +
        "    {\n" +
        "        a = vec2(texelFetch(srcTex, s,               0).r);\n" +
        "        b = vec2(texelFetch(srcTex, s + ivec2(1, 0), 0).r);\n" +
        "        c = vec2(texelFetch(srcTex, s + ivec2(0, 1), 0).r);\n" +
        "        d = vec2(texelFetch(srcTex, s + ivec2(1, 1), 0).r);\n" +
        "    }\n" +
        "    else\n" +
        "    {\n" +
        "        a = texelFetch(srcTex, s,               srcLod).rg;\n" +
        "        b = texelFetch(srcTex, s + ivec2(1, 0), srcLod).rg;\n" +
        "        c = texelFetch(srcTex, s + ivec2(0, 1), srcLod).rg;\n" +
        "        d = texelFetch(srcTex, s + ivec2(1, 1), srcLod).rg;\n" +
        "    }\n" +
        "    float mn = min(min(a.x, b.x), min(c.x, d.x));\n" +
        "    float mx = max(max(a.y, b.y), max(c.y, d.y));\n" +
        "    imageStore(dstMip, dstOrigin + g, vec4(mn, mx, 0.0, 0.0));\n" +
        "}\n";

    private SpotShadowPyramid()
    {}

    /** Lazy — 0 until the first flush (mirrors the atlas); bound by name via the host mod's sampler mixin. */
    public static int getGlTextureId()
    {
        return texId;
    }

    /** Mark one tile's pyramid region stale. Call after every bake pass (or
     *  static->live copy + overlay) that changed the tile's live depth. */
    public static void markDirty(int tile)
    {
        if (tile >= 0 && tile < SpotlightDepthAtlas.MAX_TILES)
        {
            dirtyMask |= 1 << tile;
        }
    }

    /** Rebuild every dirty tile's pyramid region from the LIVE atlas depth in
     *  one batch. Call once per bake, after the spot loop (before the SSBO
     *  flush / Iris passes). */
    public static void flushDirty()
    {
        if (dirtyMask == 0)
        {
            return;
        }
        int mask = dirtyMask;
        dirtyMask = 0;
        int depthTex = SpotlightDepthAtlas.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId == 0 || program == 0)
        {
            return; // allocation/compile failed once; stays inert — the GLSL pyrMax>0 guard falls back to full PCSS
        }

        // --- save the state we touch (current program, one 2D texture binding, image unit 0): once per batch ---
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        // order last frame's image stores against this frame's re-writes of the same texels (WAW, spec-strict)
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        GlStateManager._glUseProgram(program);
        GL20.glUniform1i(uSrcTex, unit);

        int tileSize = SpotlightDepthAtlas.TILE_SIZE;
        int base = tileSize / 2; // tile region width at pyramid lod 0

        // pass 0: depth atlas -> pyramid lod 0, all dirty tiles, one barrier
        GlStateManager._bindTexture(depthTex);
        GL20.glUniform1i(uSrcIsDepth, 1);
        GL20.glUniform1i(uSrcLod, 0);
        bindDstLevel(0);
        for (int tile = 0; tile < SpotlightDepthAtlas.MAX_TILES; tile++)
        {
            if ((mask & (1 << tile)) == 0)
            {
                continue;
            }
            int tx = tile % SpotlightDepthAtlas.GRID_X;
            int ty = tile / SpotlightDepthAtlas.GRID_X;
            GL20.glUniform2i(uSrcOrigin, tx * tileSize, ty * tileSize);
            dispatchTile(tx * base, ty * base, base);
        }
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // passes 1..levels-1: lod L-1 -> lod L for all dirty tiles, one barrier per LEVEL
        // (texelFetch with explicit lod; no feedback loop: image write and sampled level differ)
        GlStateManager._bindTexture(texId);
        GL20.glUniform1i(uSrcIsDepth, 0);
        for (int lod = 1; lod < levels; lod++)
        {
            int srcW = base >> (lod - 1);
            int dstW = base >> lod;
            GL20.glUniform1i(uSrcLod, lod - 1);
            bindDstLevel(lod);
            for (int tile = 0; tile < SpotlightDepthAtlas.MAX_TILES; tile++)
            {
                if ((mask & (1 << tile)) == 0)
                {
                    continue;
                }
                int tx = tile % SpotlightDepthAtlas.GRID_X;
                int ty = tile / SpotlightDepthAtlas.GRID_X;
                GL20.glUniform2i(uSrcOrigin, tx * srcW, ty * srcW);
                dispatchTile(tx * dstW, ty * dstW, dstW);
            }
            // next level's texelFetches (and finally the deferred pass) read what this level stored
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        // --- restore ---
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

    private static void bindDstLevel(int dstLod)
    {
        GL42.glBindImageTexture(0, texId, dstLod, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG32F);
    }

    private static void dispatchTile(int dstOrigX, int dstOrigY, int dstW)
    {
        GL20.glUniform2i(uDstOrigin, dstOrigX, dstOrigY);
        GL20.glUniform2i(uDstSize, dstW, dstW);
        int groups = (dstW + 7) / 8;
        GL43.glDispatchCompute(groups, groups, 1);
    }

    private static void ensureResources()
    {
        if (!programAttempted)
        {
            programAttempted = true; // one attempt; a failure leaves program at 0 and the feature inert
            program = buildProgram();
            if (program != 0)
            {
                uSrcTex = GL20.glGetUniformLocation(program, "srcTex");
                uSrcLod = GL20.glGetUniformLocation(program, "srcLod");
                uSrcIsDepth = GL20.glGetUniformLocation(program, "srcIsDepth");
                uSrcOrigin = GL20.glGetUniformLocation(program, "srcOrigin");
                uDstOrigin = GL20.glGetUniformLocation(program, "dstOrigin");
                uDstSize = GL20.glGetUniformLocation(program, "dstSize");
            }
        }
        if (program == 0 || texId != 0)
        {
            return;
        }

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        texId = GlStateManager._genTexture();
        GlStateManager._bindTexture(texId);
        // base = atlas/2; levels = log2(TILE_SIZE): the deepest lod is one texel per tile — deeper would mix tiles
        levels = Integer.numberOfTrailingZeros(Integer.highestOneBit(SpotlightDepthAtlas.TILE_SIZE));
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_RG32F,
            SpotlightDepthAtlas.getAtlasWidth() / 2, SpotlightDepthAtlas.getAtlasHeight() / 2);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GlStateManager._bindTexture(prevTex);
    }

    private static int buildProgram()
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, COMPUTE_SRC);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowPyramid compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowPyramid compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Free the texture (called with SpotlightDepthAtlas.delete(), e.g. preset
     *  switch — the base size changes). The compute program is size-independent
     *  and survives; a failed program stays 0 (inert). Deletion goes through
     *  GlStateManager so its per-unit binding cache drops the id (raw
     *  glDeleteTextures would leave a stale cache entry that silently skips a
     *  future bind when the driver reuses the name). */
    public static void delete()
    {
        if (texId != 0)
        {
            GlStateManager._deleteTexture(texId);
            texId = 0;
        }
        levels = 0;
        dirtyMask = 0;
    }
}
