package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/**
 * 2D depth atlas of perspective shadow maps, one tile per spotlight.
 * GRID_X x GRID_Y grid of TILE_SIZE^2 tiles; tile i = spot slot i at pixel
 * origin ((i%GRID_X)*TILE_SIZE, (i/GRID_X)*TILE_SIZE).
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
    public static final int MAX_TILES = GRID_X * GRID_Y;

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

    public static int tilePixelX(int tile)
    {
        return (tile % GRID_X) * TILE_SIZE;
    }

    public static int tilePixelY(int tile)
    {
        return (tile / GRID_X) * TILE_SIZE;
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
        GL43.glCopyImageSubData(
            staticTextureId, GL11.GL_TEXTURE_2D, 0, px, py, 0,
            glTextureId, GL11.GL_TEXTURE_2D, 0, px, py, 0,
            TILE_SIZE, TILE_SIZE, 1
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
