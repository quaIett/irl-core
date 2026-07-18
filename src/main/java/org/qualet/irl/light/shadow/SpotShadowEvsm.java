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
 * EVSM4 prefilter of the LIVE spot depth atlas (F2a of the shadow filtering
 * refactor). RGBA32F, base level = atlas/2 (one texel = the averaged warped
 * moments of a 2x2 depth quad), Gaussian-blurred base + averaged mip chain
 * down to one texel per tile. The inject samples it as {@code irl_spotEvsm}
 * with one trilinear textureLod (lod picked from the PCSS penumbra estimate)
 * and a Chebyshev bound — replacing the whole PCF loop for wide penumbras
 * with a deterministic, grain-free filter.
 *
 * Moment warp CONTRACT (mirrored byte-for-byte by the GLSL Chebyshev branch):
 *   wp = exp(+42 * lz), wn = -exp(-8 * lz), where lz = (dist - near)/(far - near)
 *   is the LINEAR depth (near 0.05, far = the tile's light range, passed per
 *   tile at markDirty time). Linear metric per Lauritzen: warping the raw
 *   perspective z01 collapses to plain VSM at far receivers (bleeding) and
 *   makes the variance floor grow quadratically with distance. Channels =
 *   (E[wp], E[wp^2], E[wn], E[wn^2]).
 *
 * Same batched contract as SpotShadowPyramid: markDirty per baked tile,
 * flushDirty once after the spot loop. The raw depth atlas stays untouched —
 * the blocker search, the contact-PCF branch and the volumetrics keep reading
 * it; EVSM is additive. Blur/mip passes clamp their reads inside the tile
 * region, so ANY quadtree tile region (full, half or quarter cell — see the
 * SpotlightDepthAtlas layout contract) stays gutter-free; the GLSL side
 * clamps its UV half a coarse-mip texel inside the tile before the
 * trilinear fetch.
 */
public final class SpotShadowEvsm
{
    private static int texId = 0;      // EVSM atlas: base = atlas/2, levels = log2(tileSize)
    private static int tempId = 0;     // one tile region (tileSize/2)^2, ping-pong for the separable blur
    private static int levels = 0;
    private static int progConvert = 0, progBlur = 0, progMip = 0;
    private static int uCvSrc, uCvFar, uCvSrcOrigin, uCvDstOrigin, uCvDstSize;
    private static int uBlSrc, uBlSrcLod, uBlHorizontal, uBlSrcOrigin, uBlDstOrigin, uBlSize;
    private static int uMpSrc, uMpSrcLod, uMpSrcOrigin, uMpDstOrigin, uMpDstSize;
    private static boolean programsAttempted = false;
    /** One bit per flat atlas tile (1L << tile); long because the quadtree
     *  layout allows up to 64 tiles (guarded in SpotlightDepthAtlas). */
    private static long dirtyMask = 0L;
    private static final float[] tileFar = new float[SpotlightDepthAtlas.tileCount()]; // light range per dirty tile (linear depth metric)

    private static final String COMMON =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8) in;\n" +
        "layout(rgba32f, binding = 0) uniform writeonly image2D dst;\n";

    // depth 2x2 -> warped moments (averaging moments IS the correct 2x2 prefilter: they are linear statistics);
    // depth is linearized per tile (near 0.05, far = light range) before the warp — see the class contract
    private static final String CONVERT_SRC = COMMON +
        "uniform sampler2D srcDepth;\n" +
        "uniform float far;\n" +
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    ivec2 s = srcOrigin + g * 2;\n" +
        "    vec4 m = vec4(0.0);\n" +
        "    for (int j = 0; j < 2; j++)\n" +
        "    for (int i = 0; i < 2; i++)\n" +
        "    {\n" +
        "        float z01 = texelFetch(srcDepth, s + ivec2(i, j), 0).r;\n" +
        "        float ndcZ = z01 * 2.0 - 1.0;\n" +
        "        float dist = (2.0 * far * 0.05) / max((far + 0.05) - ndcZ * (far - 0.05), 1e-6);\n" +
        "        float lz = clamp((dist - 0.05) / (far - 0.05), 0.0, 1.0);\n" +
        "        float wp = exp(42.0 * lz);\n" +
        "        float wn = -exp(-8.0 * lz);\n" +
        "        m += vec4(wp, wp * wp, wn, wn * wn);\n" +
        "    }\n" +
        "    imageStore(dst, dstOrigin + g, m * 0.25);\n" +
        "}\n";

    // separable Gaussian 1-4-6-4-1, reads clamped inside the tile region (no cross-tile bleed);
    // runs on EVERY mip level after its downsample so the filter width actually doubles per lod
    private static final String BLUR_SRC = COMMON +
        "uniform sampler2D srcTex;\n" +
        "uniform int srcLod;\n" +               // EVSM mip being blurred (0 when reading the temp)
        "uniform int horizontal;\n" +           // 1: read EVSM mip (absolute), write temp (local); 0: read temp (local), write EVSM mip (absolute)
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 size;\n" +               // tile region size at this lod
        "const float W[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= size.x || g.y >= size.y) return;\n" +
        "    vec4 acc = vec4(0.0);\n" +
        "    for (int k = -2; k <= 2; k++)\n" +
        "    {\n" +
        "        ivec2 o = (horizontal == 1) ? ivec2(clamp(g.x + k, 0, size.x - 1), g.y)\n" +
        "                                    : ivec2(g.x, clamp(g.y + k, 0, size.y - 1));\n" +
        "        acc += W[k + 2] * texelFetch(srcTex, srcOrigin + o, srcLod);\n" +
        "    }\n" +
        "    imageStore(dst, dstOrigin + g, acc);\n" +
        "}\n";

    // 2x2 average mip down (moments stay valid under any linear filter)
    private static final String MIP_SRC = COMMON +
        "uniform sampler2D srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    ivec2 s = srcOrigin + g * 2;\n" +
        "    vec4 m = texelFetch(srcTex, s,               srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(1, 0), srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(0, 1), srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(1, 1), srcLod);\n" +
        "    imageStore(dst, dstOrigin + g, m * 0.25);\n" +
        "}\n";

    private SpotShadowEvsm()
    {}

    /** Lazy — 0 until the first flush; bound by name via the host mod's sampler mixin. */
    public static int getGlTextureId()
    {
        return texId;
    }

    /** range = the light's far plane, needed to linearize depth before the warp. */
    public static void markDirty(int tile, float range)
    {
        if (tile >= 0 && tile < SpotlightDepthAtlas.tileCount())
        {
            dirtyMask |= 1L << tile;
            tileFar[tile] = Math.max(range, 0.1f);
        }
    }

    /** Convert + blur + mip every dirty tile in one batch. Call once per bake,
     *  after the spot loop (the pyramid flush pattern). */
    public static void flushDirty()
    {
        if (dirtyMask == 0L)
        {
            return;
        }
        long mask = dirtyMask;
        dirtyMask = 0L;
        int depthTex = SpotlightDepthAtlas.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId == 0 || progConvert == 0 || progBlur == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL textureSize gate falls back to the PCF path
        }

        ShadowBakeProbe probe = ShadowEngine.bakeProbe();
        if (probe != null)
        {
            probe.counter("evsm.sp", Long.bitCount(mask));
        }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // All tile rects come from the SpotlightDepthAtlas accessors — the one
        // rect source of the quadtree layout. INVARIANT (power-of-two
        // subdivision): every tile origin is a multiple of its own tileSizePx,
        // so shifting origin and size right in lockstep is exact at every lod.
        for (int tile = 0; tile < SpotlightDepthAtlas.tileCount(); tile++)
        {
            if ((mask & (1L << tile)) == 0L)
            {
                continue;
            }
            int pixX = SpotlightDepthAtlas.tilePixelX(tile);
            int pixY = SpotlightDepthAtlas.tilePixelY(tile);
            int base = SpotlightDepthAtlas.tileSizePx(tile) >> 1; // tile region width at EVSM lod 0
            int ox = pixX >> 1, oy = pixY >> 1;

            // convert: depth -> linearized warped moments, EVSM mip0 (tile region)
            GlStateManager._glUseProgram(progConvert);
            GL20.glUniform1i(uCvSrc, unit);
            GL20.glUniform1f(uCvFar, tileFar[tile]);
            GlStateManager._bindTexture(depthTex);
            GL42.glBindImageTexture(0, texId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
            GL20.glUniform2i(uCvSrcOrigin, pixX, pixY);
            GL20.glUniform2i(uCvDstOrigin, ox, oy);
            GL20.glUniform2i(uCvDstSize, base, base);
            GL43.glDispatchCompute((base + 7) / 8, (base + 7) / 8, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

            blurTileLevel(unit, 0, ox, oy, base);
        }

        // mip chain: downsample all dirty tiles at level L, then re-blur each tile at L
        // (a bare 2x2-avg chain does NOT double the filter width per lod — reviewed fix)
        for (int lod = 1; lod < levels; lod++)
        {
            GlStateManager._glUseProgram(progMip);
            GL20.glUniform1i(uMpSrc, unit);
            GlStateManager._bindTexture(texId);
            GL20.glUniform1i(uMpSrcLod, lod - 1);
            GL42.glBindImageTexture(0, texId, lod, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
            for (int tile = 0; tile < SpotlightDepthAtlas.tileCount(); tile++)
            {
                if ((mask & (1L << tile)) == 0L)
                {
                    continue;
                }
                int pixX = SpotlightDepthAtlas.tilePixelX(tile);
                int pixY = SpotlightDepthAtlas.tilePixelY(tile);
                int dstW = SpotlightDepthAtlas.tileSizePx(tile) >> (lod + 1);
                if (dstW == 0)
                {
                    // A sub-tile's chain ends at one texel per sub-tile — a
                    // deeper lod would mix neighbouring tiles. Full-size tiles
                    // (the whole degenerate layout) never hit this: levels =
                    // log2(tileSize) already bottoms them out at one texel.
                    continue;
                }
                GL20.glUniform2i(uMpSrcOrigin, pixX >> lod, pixY >> lod);
                GL20.glUniform2i(uMpDstOrigin, pixX >> (lod + 1), pixY >> (lod + 1));
                GL20.glUniform2i(uMpDstSize, dstW, dstW);
                GL43.glDispatchCompute((dstW + 7) / 8, (dstW + 7) / 8, 1);
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            for (int tile = 0; tile < SpotlightDepthAtlas.tileCount(); tile++)
            {
                if ((mask & (1L << tile)) == 0L)
                {
                    continue;
                }
                int dstW = SpotlightDepthAtlas.tileSizePx(tile) >> (lod + 1);
                if (dstW < 2)
                {
                    continue; // 1-texel region: a 5-tap blur adds nothing (and a 0-texel one is past the chain's end)
                }
                blurTileLevel(unit, lod,
                    SpotlightDepthAtlas.tilePixelX(tile) >> (lod + 1),
                    SpotlightDepthAtlas.tilePixelY(tile) >> (lod + 1),
                    dstW);
            }
        }

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

    /** Separable Gaussian on one tile's region of EVSM mip {@code lod} via the
     *  temp texture. Caller guarantees the mip content is fetch-visible. */
    private static void blurTileLevel(int unit, int lod, int ox, int oy, int w)
    {
        int groups = (w + 7) / 8;
        GlStateManager._glUseProgram(progBlur);
        GL20.glUniform1i(uBlSrc, unit);
        GL20.glUniform2i(uBlSize, w, w);

        // H: EVSM mip -> temp (local coords)
        GlStateManager._bindTexture(texId);
        GL42.glBindImageTexture(0, tempId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, lod);
        GL20.glUniform1i(uBlHorizontal, 1);
        GL20.glUniform2i(uBlSrcOrigin, ox, oy);
        GL20.glUniform2i(uBlDstOrigin, 0, 0);
        GL43.glDispatchCompute(groups, groups, 1);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // V: temp -> EVSM mip (the barrier above also orders this store after the H-pass fetches of the same mip)
        GlStateManager._bindTexture(tempId);
        GL42.glBindImageTexture(0, texId, lod, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, 0);
        GL20.glUniform1i(uBlHorizontal, 0);
        GL20.glUniform2i(uBlSrcOrigin, 0, 0);
        GL20.glUniform2i(uBlDstOrigin, ox, oy);
        GL43.glDispatchCompute(groups, groups, 1);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private static void ensureResources()
    {
        if (!programsAttempted)
        {
            programsAttempted = true;
            progConvert = buildProgram(CONVERT_SRC, "convert");
            progBlur = buildProgram(BLUR_SRC, "blur");
            progMip = buildProgram(MIP_SRC, "mip");
            if (progConvert != 0)
            {
                uCvSrc = GL20.glGetUniformLocation(progConvert, "srcDepth");
                uCvFar = GL20.glGetUniformLocation(progConvert, "far");
                uCvSrcOrigin = GL20.glGetUniformLocation(progConvert, "srcOrigin");
                uCvDstOrigin = GL20.glGetUniformLocation(progConvert, "dstOrigin");
                uCvDstSize = GL20.glGetUniformLocation(progConvert, "dstSize");
            }
            if (progBlur != 0)
            {
                uBlSrc = GL20.glGetUniformLocation(progBlur, "srcTex");
                uBlSrcLod = GL20.glGetUniformLocation(progBlur, "srcLod");
                uBlHorizontal = GL20.glGetUniformLocation(progBlur, "horizontal");
                uBlSrcOrigin = GL20.glGetUniformLocation(progBlur, "srcOrigin");
                uBlDstOrigin = GL20.glGetUniformLocation(progBlur, "dstOrigin");
                uBlSize = GL20.glGetUniformLocation(progBlur, "size");
            }
            if (progMip != 0)
            {
                uMpSrc = GL20.glGetUniformLocation(progMip, "srcTex");
                uMpSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMpSrcOrigin = GL20.glGetUniformLocation(progMip, "srcOrigin");
                uMpDstOrigin = GL20.glGetUniformLocation(progMip, "dstOrigin");
                uMpDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
        if (progConvert == 0 || progBlur == 0 || progMip == 0 || texId != 0)
        {
            return;
        }

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        levels = Integer.numberOfTrailingZeros(Integer.highestOneBit(SpotlightDepthAtlas.getTileSize()));

        texId = GlStateManager._genTexture();
        GlStateManager._bindTexture(texId);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_RGBA32F,
            SpotlightDepthAtlas.getAtlasWidth() / 2, SpotlightDepthAtlas.getAtlasHeight() / 2);
        // trilinear: the shader picks a fractional lod from the penumbra width
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);

        tempId = GlStateManager._genTexture();
        GlStateManager._bindTexture(tempId);
        // Sized for the LARGEST tile's lod-0 region (tileSize/2)^2; every
        // quadtree sub-tile region at every lod is smaller, so it always fits.
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA32F,
            SpotlightDepthAtlas.getTileSize() / 2, SpotlightDepthAtlas.getTileSize() / 2);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);

        GlStateManager._bindTexture(prevTex);
    }

    private static int buildProgram(String src, String tag)
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowEvsm " + tag + " compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowEvsm " + tag + " compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Free the textures (called with SpotlightDepthAtlas.delete()). Deletion
     *  goes through GlStateManager — see SpotShadowPyramid.delete(). */
    public static void delete()
    {
        if (texId != 0)
        {
            GlStateManager._deleteTexture(texId);
            texId = 0;
        }
        if (tempId != 0)
        {
            GlStateManager._deleteTexture(tempId);
            tempId = 0;
        }
        levels = 0;
        dirtyMask = 0L;
    }
}
