package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/**
 * Cube-map-array of depth shadow maps, one cubemap (6 faces) per shadowed point
 * light. Face f of shadow slot i lives at array layer i*6 + f.
 *
 * TWO layers: the LIVE array is what the shader samples; the STATIC array
 * holds a light's static-only content (model blocks + world blocks) so that
 * on frames with a dynamic caster the base can be restored with a single GPU
 * copy of all 6 faces ({@link #copyStaticToLive}) instead of re-rendering
 * every static caster into every face. The static array is allocated lazily
 * on the first overlay bake — no dynamic casters near lamps, no extra VRAM.
 *
 * Each face is a 90-degree perspective depth render from the light position
 * (near 0.05, far = radius). Shader test: sample with the world-space direction
 * lightPos->receiver + the slot index, compare against the dominant-axis
 * perspective depth (NOT Euclidean length — that gives a 6-pointed star).
 *
 * GL_TEXTURE_CUBE_MAP_ARRAY is not in Iris's TextureType enum, so the sampler
 * bind is fixed up by the per-mod {@code SamplerBindingCubeArrayMixin} (Iris integration).
 */
public final class PointShadowArray
{
    /** Max point lights that get a cube shadow (cube-array is expensive). */
    public static final int MAX_SHADOWS = 16;
    public static int FACE_SIZE = 512;
    public static final int LAYER_COUNT = 6 * MAX_SHADOWS;

    private static final int GL_TEXTURE_CUBE_MAP_SEAMLESS = 0x884F;

    private static int glTextureId = 0;
    private static int glFboId = 0;
    private static boolean initialized = false;

    private static int staticTextureId = 0;
    private static int staticFboId = 0;
    private static boolean staticInitialized = false;

    private PointShadowArray()
    {}

    public static int getGlTextureId()
    {
        return glTextureId;
    }

    /** Bind the FBO of the requested layer (false = live, true = static) with
     *  the given cube face attached, allocating the layer on first use. */
    public static void bindFaceForRender(int slot, int face, boolean staticLayer)
    {
        int fbo;
        int texture;
        if (staticLayer)
        {
            if (!staticInitialized)
            {
                initStatic();
            }
            fbo = staticFboId;
            texture = staticTextureId;
        }
        else
        {
            if (!initialized)
            {
                init();
            }
            fbo = glFboId;
            texture = glTextureId;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int layer = slot * 6 + face;
        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, texture, 0, layer);
    }

    /** GPU-copy one slot's whole cube (all 6 faces in one call) from the
     *  static array into the live array — restores a light's static base
     *  before its dynamic casters are drawn on top. */
    public static void copyStaticToLive(int slot)
    {
        if (!initialized)
        {
            init();
        }
        if (!staticInitialized)
        {
            initStatic();
        }
        GL43.glCopyImageSubData(
            staticTextureId, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, 0, 0, slot * 6,
            glTextureId, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, 0, 0, slot * 6,
            FACE_SIZE, FACE_SIZE, 6
        );
    }

    /** GPU-copy ONE face of a slot's cube (array layer slot*6 + face) from the
     *  static array into the live array. The overlay path copies only the faces
     *  a dynamic caster touches this frame or touched last frame, instead of all
     *  6, when the static base itself is unchanged (see ShadowBaker T1.2). */
    public static void copyStaticFaceToLive(int slot, int face)
    {
        if (!initialized)
        {
            init();
        }
        if (!staticInitialized)
        {
            initStatic();
        }
        int layer = slot * 6 + face;
        GL43.glCopyImageSubData(
            staticTextureId, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, 0, 0, layer,
            glTextureId, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, 0, 0, layer,
            FACE_SIZE, FACE_SIZE, 1
        );
    }

    private static void init()
    {
        int[] ids = createArray();
        glTextureId = ids[0];
        glFboId = ids[1];
        initialized = true;
    }

    private static void initStatic()
    {
        int[] ids = createArray();
        staticTextureId = ids[0];
        staticFboId = ids[1];
        staticInitialized = true;
    }

    /** Allocate one cube-map-array depth texture + FBO, every layer cleared to
     *  the far plane. Returns {textureId, fboId}; restores touched bindings. */
    private static int[] createArray()
    {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevCubeArray = GL11.glGetInteger(GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY);

        int textureId = GlStateManager._genTexture();
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, textureId);

        GL12.glTexImage3D(
            GL40.GL_TEXTURE_CUBE_MAP_ARRAY, 0, GL30.GL_DEPTH_COMPONENT32F,
            FACE_SIZE, FACE_SIZE, LAYER_COUNT, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null
        );

        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

        GL11.glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        int fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, textureId, 0, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            throw new IllegalStateException("PointShadowArray FBO incomplete: 0x" + Integer.toHexString(status));
        }

        for (int layer = 0; layer < LAYER_COUNT; layer++)
        {
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, textureId, 0, layer);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }
        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, textureId, 0, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, prevCubeArray);
        GlStateManager._bindTexture(prevTex);

        return new int[] { textureId, fboId };
    }

    public static void delete()
    {
        if (initialized)
        {
            GL11.glDeleteTextures(glTextureId);
            GL30.glDeleteFramebuffers(glFboId);
            glTextureId = 0;
            glFboId = 0;
            initialized = false;
        }
        if (staticInitialized)
        {
            GL11.glDeleteTextures(staticTextureId);
            GL30.glDeleteFramebuffers(staticFboId);
            staticTextureId = 0;
            staticFboId = 0;
            staticInitialized = false;
        }
    }

    /** Switch per-face resolution; frees + re-inits both arrays on next access. */
    public static void setFaceSize(int newSize)
    {
        if (newSize == FACE_SIZE)
        {
            return;
        }
        FACE_SIZE = newSize;
        delete();
    }
}
