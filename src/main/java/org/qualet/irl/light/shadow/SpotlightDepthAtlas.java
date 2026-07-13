package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/**
 * 2D depth atlas of perspective shadow maps, one tile per spotlight.
 * The physical texture is a GRID_X x GRID_Y grid of TILE_SIZE^2 CELLS; a
 * static quadtree layout ({@link #TIER_CELLS}) subdivides some cells into
 * smaller tiles, so one atlas can hold more (lower-resolution) shadow maps
 * at the same VRAM. All tile rects come from the accessors below —
 * {@link #tilePixelX}, {@link #tilePixelY}, {@link #tileSizePx} — nothing
 * outside this class may do its own tile-rect math.
 *
 * FROZEN MIRROR CONTRACT (the injected GLSL replicates this flat-index ->
 * rect formula verbatim in phase I4, like LightBuffer's std430 mirror
 * comments — any change here is an ABI break with every generated patch):
 * <pre>
 *   Cells are the GRID_X*GRID_Y physical grid cells in row-major flat order
 *   (cell c at cellX = c % GRID_X, cellY = c / GRID_X), assigned to tiers
 *   contiguously: the first TIER_CELLS[0] cells are tier 0 (1 full tile
 *   each, div 1), the next TIER_CELLS[1] cells are tier 1 (4 half-size
 *   sub-tiles each, 2x2 row-major inside the cell, div 2), the last
 *   TIER_CELLS[2] cells are tier 2 (16 quarter-size sub-tiles each, 4x4
 *   row-major, div 4).
 *
 *   The flat TILE index enumerates tier-0 tiles first, then tier-1
 *   sub-tiles cell by cell, then tier-2. With N0/N1/N2 = TIER_CELLS:
 *     tier 0: tile in [0, N0)                    -> cell = tile,
 *             sub = (0, 0), div = 1
 *     tier 1: tile in [N0, N0 + 4*N1)            -> j = tile - N0,
 *             cell = N0 + j/4, sub = (j%4 % 2, j%4 / 2), div = 2
 *     tier 2: tile in [N0 + 4*N1, N0+4*N1+16*N2) -> j = tile - N0 - 4*N1,
 *             cell = N0 + N1 + j/16, sub = (j%16 % 4, j%16 / 4), div = 4
 *
 *   rect origin = (cellX*TILE_SIZE + subX*(TILE_SIZE/div),
 *                  cellY*TILE_SIZE + subY*(TILE_SIZE/div)),
 *   rect extent = TILE_SIZE / div.
 * </pre>
 * The per-tile tables cache cellX/cellY/subX/subY/div only (TILE_SIZE is
 * preset-mutable, so pixel coords are always computed on the fly). Every
 * tile origin is a multiple of its own extent (power-of-two subdivision),
 * so downstream mip math may shift origins right in lockstep with sizes.
 *
 * TWO layers: the LIVE atlas is what the shader samples; the STATIC atlas
 * holds a light's static-only content (model blocks + world blocks) so that
 * on frames with a dynamic caster the base can be restored with a GPU copy
 * ({@link #copyStaticToLive}) instead of re-rendering every static caster.
 * The static atlas is allocated lazily on the first overlay bake — a scene
 * with no dynamic casters near lamps never pays its VRAM.
 *
 * Format DEPTH_COMPONENT32F, NEAREST, manual compare in the shader (no
 * fixed-function compare). Lazy alloc: nothing until the first bake.
 */
public final class SpotlightDepthAtlas
{
    public static int TILE_SIZE = 1024;
    public static final int GRID_X = 4;
    public static final int GRID_Y = 4;

    /** Cells per tier: {full 1x1, split 2x2, split 4x4}. I3 layout {8, 6, 2}:
     *  8 full tiles + 6*4 = 24 half tiles + 2*16 = 32 quarter tiles = 64 tiles
     *  in the same physical atlas. See the class javadoc for the frozen
     *  index->rect formula. */
    private static final int[] TIER_CELLS = { 8, 6, 2 };

    private static final int TILE_COUNT;
    /** Tier t's tiles occupy the flat-index range
     *  [TIER_FIRST_TILE[t], TIER_FIRST_TILE[t+1]). */
    private static final int[] TIER_FIRST_TILE = new int[4];
    // Per-tile layout tables — deliberately TILE_SIZE-independent (the tile
    // resolution is preset-mutable at runtime; only the grid topology is static).
    private static final int[] tileCellX;
    private static final int[] tileCellY;
    private static final int[] tileSubX;
    private static final int[] tileSubY;
    private static final int[] tileSizeDiv;

    static
    {
        if (TIER_CELLS.length != 3)
        {
            throw new IllegalStateException("SpotlightDepthAtlas: TIER_CELLS must have exactly 3 tiers, got " + TIER_CELLS.length);
        }
        int cells = TIER_CELLS[0] + TIER_CELLS[1] + TIER_CELLS[2];
        if (cells != GRID_X * GRID_Y)
        {
            throw new IllegalStateException("SpotlightDepthAtlas: TIER_CELLS sum " + cells + " != grid cells " + (GRID_X * GRID_Y));
        }
        int count = TIER_CELLS[0] + TIER_CELLS[1] * 4 + TIER_CELLS[2] * 16;
        if (count > 64)
        {
            throw new IllegalStateException("SpotlightDepthAtlas: tileCount " + count + " > 64 (downstream dirty masks are long)");
        }
        TILE_COUNT = count;
        tileCellX = new int[count];
        tileCellY = new int[count];
        tileSubX = new int[count];
        tileSubY = new int[count];
        tileSizeDiv = new int[count];

        int tile = 0;
        int cell = 0;
        for (int tier = 0; tier < 3; tier++)
        {
            TIER_FIRST_TILE[tier] = tile;
            int div = 1 << tier; // 1 | 2 | 4
            for (int c = 0; c < TIER_CELLS[tier]; c++, cell++)
            {
                int cx = cell % GRID_X;
                int cy = cell / GRID_X;
                for (int sy = 0; sy < div; sy++)
                {
                    for (int sx = 0; sx < div; sx++, tile++)
                    {
                        tileCellX[tile] = cx;
                        tileCellY[tile] = cy;
                        tileSubX[tile] = sx;
                        tileSubY[tile] = sy;
                        tileSizeDiv[tile] = div;
                    }
                }
            }
        }
        TIER_FIRST_TILE[3] = tile;
    }

    private static int glTextureId = 0;
    private static int glFboId = 0;
    private static boolean initialized = false;

    private static int staticTextureId = 0;
    private static int staticFboId = 0;
    private static boolean staticInitialized = false;

    private SpotlightDepthAtlas()
    {}

    public static int getAtlasWidth()
    {
        return TILE_SIZE * GRID_X;
    }

    public static int getAtlasHeight()
    {
        return TILE_SIZE * GRID_Y;
    }

    /** Lazy — returns 0 until the first bake allocates (keeps VRAM free when no spot exists). */
    public static int getGlTextureId()
    {
        return glTextureId;
    }

    /** FBO of the requested layer (false = live, true = static), allocating it
     *  on first use. */
    public static int getFboId(boolean staticLayer)
    {
        if (staticLayer)
        {
            if (!staticInitialized)
            {
                initStatic();
            }
            return staticFboId;
        }
        if (!initialized)
        {
            init();
        }
        return glFboId;
    }

    /** Number of addressable tiles in the quadtree layout (64 with the I3
     *  layout {8,6,2}). */
    public static int tileCount()
    {
        return TILE_COUNT;
    }

    /** First flat tile index of tier {@code t} — the tier ranges are
     *  contiguous: [0, 8), [8, 32), [32, 64) with the I3 layout {8,6,2}. */
    public static int tierStartTile(int t)
    {
        return TIER_FIRST_TILE[t];
    }

    /** Number of tiles in tier {@code t} (8 / 24 / 32 with {8,6,2}). */
    public static int tierTileCount(int t)
    {
        return TIER_FIRST_TILE[t + 1] - TIER_FIRST_TILE[t];
    }

    /** Pixel X of a tile's rect origin (see the class mirror contract). */
    public static int tilePixelX(int tile)
    {
        return tileCellX[tile] * TILE_SIZE + tileSubX[tile] * (TILE_SIZE / tileSizeDiv[tile]);
    }

    /** Pixel Y of a tile's rect origin (see the class mirror contract). */
    public static int tilePixelY(int tile)
    {
        return tileCellY[tile] * TILE_SIZE + tileSubY[tile] * (TILE_SIZE / tileSizeDiv[tile]);
    }

    /** Pixel extent (width == height) of one tile: TILE_SIZE / (1|2|4). */
    public static int tileSizePx(int tile)
    {
        return TILE_SIZE / tileSizeDiv[tile];
    }

    /** Tier (0|1|2) of a flat tile index — a piecewise range lookup over the
     *  contiguous tier ranges (see the class mirror contract). */
    public static int tileTier(int tile)
    {
        if (tile < TIER_FIRST_TILE[1])
        {
            return 0;
        }
        return tile < TIER_FIRST_TILE[2] ? 1 : 2;
    }

    /** GPU-copy one tile's depth from the static atlas into the live atlas —
     *  restores a light's static base before its dynamic casters are drawn on
     *  top, without re-rendering any static geometry. */
    public static void copyStaticToLive(int tile)
    {
        if (!initialized)
        {
            init();
        }
        if (!staticInitialized)
        {
            initStatic();
        }
        int px = tilePixelX(tile);
        int py = tilePixelY(tile);
        int ts = tileSizePx(tile);
        GL43.glCopyImageSubData(
            staticTextureId, GL11.GL_TEXTURE_2D, 0, px, py, 0,
            glTextureId, GL11.GL_TEXTURE_2D, 0, px, py, 0,
            ts, ts, 1
        );
    }

    private static void init()
    {
        int[] ids = createAtlas();
        glTextureId = ids[0];
        glFboId = ids[1];
        initialized = true;
    }

    private static void initStatic()
    {
        int[] ids = createAtlas();
        staticTextureId = ids[0];
        staticFboId = ids[1];
        staticInitialized = true;
    }

    /** Allocate one depth atlas texture + FBO, cleared to far plane. Returns
     *  {textureId, fboId}; restores the GL texture/FBO bindings it touched. */
    private static int[] createAtlas()
    {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        int textureId = GlStateManager._genTexture();
        GlStateManager._bindTexture(textureId);

        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
            getAtlasWidth(), getAtlasHeight(), 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null
        );

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

        int fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, textureId, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            throw new IllegalStateException("SpotlightDepthAtlas FBO incomplete: 0x" + Integer.toHexString(status));
        }

        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        GL11.glViewport(0, 0, getAtlasWidth(), getAtlasHeight());
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GlStateManager._bindTexture(prevTex);

        return new int[] { textureId, fboId };
    }

    public static void delete()
    {
        SpotShadowPyramid.delete(); // pyramid base size tracks the atlas size
        SpotShadowEvsm.delete();    // EVSM base size tracks the atlas size too
        // _deleteTexture (not raw glDeleteTextures): drops the id from GlStateManager's
        // per-unit binding cache — a raw delete leaves a stale entry that silently
        // skips a future bind when the driver reuses the name.
        if (initialized)
        {
            GlStateManager._deleteTexture(glTextureId);
            GL30.glDeleteFramebuffers(glFboId);
            glTextureId = 0;
            glFboId = 0;
            initialized = false;
        }
        if (staticInitialized)
        {
            GlStateManager._deleteTexture(staticTextureId);
            GL30.glDeleteFramebuffers(staticFboId);
            staticTextureId = 0;
            staticFboId = 0;
            staticInitialized = false;
        }
    }

    /** Switch tile resolution; frees + re-inits both atlases on next access. */
    public static void setTileSize(int newSize)
    {
        if (newSize == TILE_SIZE)
        {
            return;
        }
        TILE_SIZE = newSize;
        delete();
    }
}
