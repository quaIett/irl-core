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
 * EVSM4 prefilter of the LIVE point shadow cube-array (F2b). Face-major
 * GL_TEXTURE_2D_ARRAY (layer = slot*6 + face) like {@link PointShadowPyramid},
 * RGBA32F, base = face/2, Gaussian-blurred base + re-blurred mip chain like
 * {@link SpotShadowEvsm}. Sampled as {@code irl_pointEvsm} (registered 2D,
 * rebound to 2D_ARRAY by the host mixins) with one trilinear textureLod +
 * Chebyshev for wide point penumbras.
 *
 * Face seams: the blur is face-local (reads clamp at the face border), so
 * softness would mismatch across cube edges — the GLSL branch therefore takes
 * EVSM only when the whole filter footprint stays inside one face and falls
 * back to the cube-sampling PCF near edges (the pyramid edge-guard pattern).
 * No spherically-correct blur needed.
 *
 * Warp CONTRACT (mirror of the GLSL point branch): lz = (zdist - near)/(far -
 * near) where zdist is the linearized DOMINANT-AXIS distance decoded from the
 * stored perspective depth (near 0.05, far = the slot's light radius, passed
 * per slot at markDirty); wp = exp(+42 lz), wn = -exp(-8 lz).
 */
public final class PointShadowEvsm
{
    private static int texId = 0;      // EVSM: base = face/2, layers = 96, levels = log2(FACE_SIZE)
    private static int tempId = 0;     // 6-layer (face/2)^2 array, ping-pong for the separable blur of one slot
    private static int levels = 0;
    private static int progConvert = 0, progBlur = 0, progMip = 0;
    private static int uCvSrc, uCvFar, uCvSlot, uCvDstSize;
    private static int uBlSrc, uBlSrcLod, uBlHorizontal, uBlSrcLayerBase, uBlDstLayerBase, uBlSize;
    private static int uMpSrc, uMpSrcLod, uMpLayerBase, uMpDstSize;
    private static boolean programsAttempted = false;
    private static int dirtyMask = 0;
    private static final float[] slotFar = new float[PointShadowArray.MAX_SHADOWS];

    private static final String COMMON =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n" +
        "layout(rgba32f, binding = 0) uniform writeonly image2DArray dst;\n";

    // depth cube 2x2 -> linearized warped moments; dir from the inverse GL face table (see PointShadowPyramid)
    private static final String CONVERT_SRC = COMMON +
        "uniform samplerCubeArray srcCube;\n" +
        "uniform float far;\n" +
        "uniform int slot;\n" +
        "uniform ivec2 dstSize;\n" +
        "vec3 faceDir(int face, vec2 st)\n" +
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
        "    vec4 m = vec4(0.0);\n" +
        "    for (int j = 0; j < 2; j++)\n" +
        "    for (int i = 0; i < 2; i++)\n" +
        "    {\n" +
        "        vec2 uv = (vec2(g * 2 + ivec2(i, j)) + 0.5) / srcRes;\n" +
        "        float z01 = textureLod(srcCube, vec4(faceDir(face, uv * 2.0 - 1.0), float(slot)), 0.0).r;\n" +
        "        float ndcZ = z01 * 2.0 - 1.0;\n" +
        "        float zdist = (2.0 * far * 0.05) / max((far + 0.05) - ndcZ * (far - 0.05), 1e-6);\n" +
        "        float lz = clamp((zdist - 0.05) / (far - 0.05), 0.0, 1.0);\n" +
        "        float wp = exp(42.0 * lz);\n" +
        "        float wn = -exp(-8.0 * lz);\n" +
        "        m += vec4(wp, wp * wp, wn, wn * wn);\n" +
        "    }\n" +
        "    imageStore(dst, ivec3(g, slot * 6 + face), m * 0.25);\n" +
        "}\n";

    // separable Gaussian 1-4-6-4-1, face-local (reads clamp at the face border); z = face
    private static final String BLUR_SRC = COMMON +
        "uniform sampler2DArray srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform int horizontal;\n" +
        "uniform int srcLayerBase;\n" +
        "uniform int dstLayerBase;\n" +
        "uniform ivec2 size;\n" +
        "const float W[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= size.x || g.y >= size.y) return;\n" +
        "    int z = int(gl_GlobalInvocationID.z);\n" +
        "    vec4 acc = vec4(0.0);\n" +
        "    for (int k = -2; k <= 2; k++)\n" +
        "    {\n" +
        "        ivec2 o = (horizontal == 1) ? ivec2(clamp(g.x + k, 0, size.x - 1), g.y)\n" +
        "                                    : ivec2(g.x, clamp(g.y + k, 0, size.y - 1));\n" +
        "        acc += W[k + 2] * texelFetch(srcTex, ivec3(o, srcLayerBase + z), srcLod);\n" +
        "    }\n" +
        "    imageStore(dst, ivec3(g, dstLayerBase + z), acc);\n" +
        "}\n";

    // 2x2 average mip down, z = face
    private static final String MIP_SRC = COMMON +
        "uniform sampler2DArray srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform int layerBase;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int layer = layerBase + int(gl_GlobalInvocationID.z);\n" +
        "    ivec2 s = g * 2;\n" +
        "    vec4 m = texelFetch(srcTex, ivec3(s,               layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(1, 0), layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(0, 1), layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(1, 1), layer), srcLod);\n" +
        "    imageStore(dst, ivec3(g, layer), m * 0.25);\n" +
        "}\n";

    private PointShadowEvsm()
    {}

    public static int getGlTextureId()
    {
        return texId;
    }

    /** radius = the light's far plane, needed to linearize depth before the warp. */
    public static void markDirty(int slot, float radius)
    {
        if (slot >= 0 && slot < PointShadowArray.MAX_SHADOWS)
        {
            dirtyMask |= 1 << slot;
            slotFar[slot] = Math.max(radius, 0.1f);
        }
    }

    /** Convert + blur + mip every dirty slot (6 faces each) in one batch. Call
     *  once per bake, after the point loop. */
    public static void flushDirty()
    {
        if (dirtyMask == 0)
        {
            return;
        }
        int mask = dirtyMask;
        dirtyMask = 0;
        int depthTex = PointShadowArray.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId == 0 || progConvert == 0 || progBlur == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL size gate falls back to the PCF path
        }

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

        int base = PointShadowArray.FACE_SIZE / 2;
        int groups = (base + 7) / 8;

        for (int slot = 0; slot < PointShadowArray.MAX_SHADOWS; slot++)
        {
            if ((mask & (1 << slot)) == 0)
            {
                continue;
            }
            // convert: depth cube -> linearized warped moments, lod 0, all 6 faces
            GlStateManager._glUseProgram(progConvert);
            GL20.glUniform1i(uCvSrc, unit);
            GL20.glUniform1f(uCvFar, slotFar[slot]);
            GL20.glUniform1i(uCvSlot, slot);
            GL20.glUniform2i(uCvDstSize, base, base);
            GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, depthTex);
            GL42.glBindImageTexture(0, texId, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
            GL43.glDispatchCompute(groups, groups, 6);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

            blurSlotLevel(unit, slot, 0, base);
        }

        // mip chain: downsample all dirty slots at level L, then re-blur (width doubling contract)
        for (int lod = 1; lod < levels; lod++)
        {
            int dstW = base >> lod;
            GlStateManager._glUseProgram(progMip);
            GL20.glUniform1i(uMpSrc, unit);
            GL20.glUniform1i(uMpSrcLod, lod - 1);
            GL20.glUniform2i(uMpDstSize, dstW, dstW);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
            GL42.glBindImageTexture(0, texId, lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
            for (int slot = 0; slot < PointShadowArray.MAX_SHADOWS; slot++)
            {
                if ((mask & (1 << slot)) == 0)
                {
                    continue;
                }
                GL20.glUniform1i(uMpLayerBase, slot * 6);
                GL43.glDispatchCompute((dstW + 7) / 8, (dstW + 7) / 8, 6);
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            if (dstW >= 2)
            {
                for (int slot = 0; slot < PointShadowArray.MAX_SHADOWS; slot++)
                {
                    if ((mask & (1 << slot)) == 0)
                    {
                        continue;
                    }
                    blurSlotLevel(unit, slot, lod, dstW);
                }
            }
        }

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

    /** Separable Gaussian on all 6 faces of one slot at EVSM mip {@code lod}
     *  via the 6-layer temp array. */
    private static void blurSlotLevel(int unit, int slot, int lod, int w)
    {
        int groups = (w + 7) / 8;
        GlStateManager._glUseProgram(progBlur);
        GL20.glUniform1i(uBlSrc, unit);
        GL20.glUniform2i(uBlSize, w, w);

        // H: EVSM mip -> temp layers 0..5
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
        GL42.glBindImageTexture(0, tempId, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, lod);
        GL20.glUniform1i(uBlHorizontal, 1);
        GL20.glUniform1i(uBlSrcLayerBase, slot * 6);
        GL20.glUniform1i(uBlDstLayerBase, 0);
        GL43.glDispatchCompute(groups, groups, 6);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // V: temp -> EVSM mip (barrier above also orders this store after the H fetches)
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tempId);
        GL42.glBindImageTexture(0, texId, lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, 0);
        GL20.glUniform1i(uBlHorizontal, 0);
        GL20.glUniform1i(uBlSrcLayerBase, 0);
        GL20.glUniform1i(uBlDstLayerBase, slot * 6);
        GL43.glDispatchCompute(groups, groups, 6);
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
                uCvSrc = GL20.glGetUniformLocation(progConvert, "srcCube");
                uCvFar = GL20.glGetUniformLocation(progConvert, "far");
                uCvSlot = GL20.glGetUniformLocation(progConvert, "slot");
                uCvDstSize = GL20.glGetUniformLocation(progConvert, "dstSize");
            }
            if (progBlur != 0)
            {
                uBlSrc = GL20.glGetUniformLocation(progBlur, "srcTex");
                uBlSrcLod = GL20.glGetUniformLocation(progBlur, "srcLod");
                uBlHorizontal = GL20.glGetUniformLocation(progBlur, "horizontal");
                uBlSrcLayerBase = GL20.glGetUniformLocation(progBlur, "srcLayerBase");
                uBlDstLayerBase = GL20.glGetUniformLocation(progBlur, "dstLayerBase");
                uBlSize = GL20.glGetUniformLocation(progBlur, "size");
            }
            if (progMip != 0)
            {
                uMpSrc = GL20.glGetUniformLocation(progMip, "srcTex");
                uMpSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMpLayerBase = GL20.glGetUniformLocation(progMip, "layerBase");
                uMpDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
        if (progConvert == 0 || progBlur == 0 || progMip == 0 || texId != 0)
        {
            return;
        }

        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        levels = Integer.numberOfTrailingZeros(Integer.highestOneBit(PointShadowArray.FACE_SIZE));
        int base = PointShadowArray.FACE_SIZE / 2;

        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
        GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, levels, GL30.GL_RGBA32F, base, base, PointShadowArray.LAYER_COUNT);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);

        tempId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tempId);
        GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, 1, GL30.GL_RGBA32F, base, base, 6);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
    }

    private static int buildProgram(String src, String tag)
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowEvsm " + tag + " compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowEvsm " + tag + " compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Free the textures (called with PointShadowArray.delete()); raw delete is
     *  safe — 2D-array bindings are not tracked by GlStateManager. */
    public static void delete()
    {
        if (texId != 0)
        {
            GL11.glDeleteTextures(texId);
            texId = 0;
        }
        if (tempId != 0)
        {
            GL11.glDeleteTextures(tempId);
            tempId = 0;
        }
        levels = 0;
        dirtyMask = 0;
    }
}
