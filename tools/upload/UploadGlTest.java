import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import org.qualet.irl.light.ClusterGridBuffer;
import org.qualet.irl.light.LightBuffer;
import org.qualet.irl.light.VlGlobalsBuffer;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Real driver + unchanged production classes. No Minecraft or GL stubs.
 * Checks payload delivery to the owned buffer, foreign bindings, cold/empty/
 * active/reinit paths and temporal frame progression; no FPS claims. */
public final class UploadGlTest {
    private static final int FOREIGN_BYTES = 200000;
    private static final int[] foreign = new int[5];
    private static long assertions;
    private static int uploads;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.json"), "{\"passed\":false}");
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err);
        callback.set();
        long window = 0;
        try {
            check(glfwInit(), "GLFW init");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(32, 32, "IRLights upload verification", 0, 0);
            check(window != 0, "hidden GL43 context");
            glfwMakeContextCurrent(window);
            check(GL.createCapabilities().OpenGL43, "OpenGL43 available");
            createForeign();
            for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
                LightBuffer.delete();
                ClusterGridBuffer.delete();
                stomp();
                LightBuffer.uploadEmpty();
                check(glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) == foreign[0], "cold light empty generic no-op");
                check(glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 7) == foreign[1], "cold light indexed no-op");
                check(glGetInteger(GL_UNIFORM_BUFFER_BINDING) == foreign[3], "cold light does not upload globals");
                check((int) field(LightBuffer.class, "ssbo") == 0, "cold light allocates nothing");

                stomp();
                int nextFrame = (int) field(VlGlobalsBuffer.class, "frameIndex");
                VlGlobalsBuffer.upload();
                verify(VlGlobalsBuffer.class, "ubo", GL_UNIFORM_BUFFER, GL_UNIFORM_BUFFER_BINDING, 7, 96);
                globalsFrame(nextFrame);
                check(glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) == foreign[0], "globals leaves SSBO generic");

                emptyCluster(); // first allocation as well as repeated empty rebinding
                emptyCluster();
                for (int count : new int[]{0, 1, 32, 33, 64, 65, 2048, 1, 0}) {
                    LightBuffer.begin();
                    for (int i = 0; i < count; i++) {
                        if ((i & 1) == 0) LightBuffer.addPoint(i, -i, -10F, .1F, .2F, .3F, 2F, 8F, 0F, .2F, .4F, 1F, -1F, .1F);
                        else LightBuffer.addSpot(i, -i, -20F, 0F, 0F, -1F, .3F, .2F, .1F, 3F, 12F, .5F, .8F, 0F, .3F, .2F, 1F, -1F, .2F, 2F, .1F, 1F, 0F);
                    }
                    LightBuffer.setVlGlobalIntensity(1.25F + lifecycle);
                    LightBuffer.setVlFlags(3);
                    stomp();
                    nextFrame = (int) field(VlGlobalsBuffer.class, "frameIndex");
                    LightBuffer.upload();
                    verify(LightBuffer.class, "ssbo", GL_SHADER_STORAGE_BUFFER, GL_SHADER_STORAGE_BUFFER_BINDING, 7, 16 + count * 96);
                    verify(VlGlobalsBuffer.class, "ubo", GL_UNIFORM_BUFFER, GL_UNIFORM_BUFFER_BINDING, 7, 96);
                    globalsFrame(nextFrame);
                    check(glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 6) == foreign[2], "light preserves SSBO6");
                    ByteBuffer lightScratch = (ByteBuffer) field(LightBuffer.class, "scratch");
                    check(lightScratch.getInt(0) == count, "light count");

                    ClusterGridBuffer.begin();
                    for (int i = 0; i < count; i++) ClusterGridBuffer.record(i, i % 20, 0F, -20F - i % 7, 4F);
                    ClusterGridBuffer.markSnapshotFresh();
                    stomp();
                    ClusterGridBuffer.buildAndUpload(new Matrix4f(), new Matrix4f().perspective(1.2F, 1.77F, .05F, 128F));
                    int bytes = 4624 + 576 * Math.max(1, (count + 31) / 32) * 4;
                    verify(ClusterGridBuffer.class, "ssbo", GL_SHADER_STORAGE_BUFFER, GL_SHADER_STORAGE_BUFFER_BINDING, 6, bytes);
                    check(glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 7) == foreign[1], "cluster preserves SSBO7");
                    check(glGetInteger(GL_UNIFORM_BUFFER_BINDING) == foreign[3], "cluster preserves UBO generic");
                    check(!ClusterGridBuffer.hasFreshSnapshot(), "cluster consumes freshness");
                    emptyCluster();
                    emptyCluster();
                    stomp();
                    nextFrame = (int) field(VlGlobalsBuffer.class, "frameIndex");
                    LightBuffer.uploadEmpty();
                    verify(LightBuffer.class, "ssbo", GL_SHADER_STORAGE_BUFFER, GL_SHADER_STORAGE_BUFFER_BINDING, 7, 16);
                    verify(VlGlobalsBuffer.class, "ubo", GL_UNIFORM_BUFFER, GL_UNIFORM_BUFFER_BINDING, 7, 96);
                    globalsFrame(nextFrame);
                }
            }
            // Every upload must keep advancing the dither, including the wrap.
            Field frameField = VlGlobalsBuffer.class.getDeclaredField("frameIndex");
            frameField.setAccessible(true);
            frameField.setInt(null, 4095);
            for (int expected : new int[]{4095, 0, 1}) {
                stomp();
                VlGlobalsBuffer.upload();
                verify(VlGlobalsBuffer.class, "ubo", GL_UNIFORM_BUFFER, GL_UNIFORM_BUFFER_BINDING, 7, 96);
                globalsFrame(expected);
            }
            foreignUnchanged();
            check(glGetError() == GL_NO_ERROR, "no GL errors");
            String renderer = glGetString(GL_RENDERER);
            String report = "{\"passed\":true,\"assertions\":" + assertions + ",\"verifiedUploads\":" + uploads
                + ",\"renderer\":\"" + renderer + "\",\"version\":\"" + glGetString(GL_VERSION) + "\"}";
            Files.writeString(out.resolve("report.json"), report);
            System.out.println("Upload GL PASS: " + assertions + " assertions, " + uploads + " uploads; " + renderer);
        } finally {
            if (window != 0) {
                LightBuffer.delete();
                ClusterGridBuffer.delete();
                for (int id : foreign) if (id != 0) glDeleteBuffers(id);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            callback.free();
        }
    }

    private static void emptyCluster() throws Exception {
        stomp();
        ClusterGridBuffer.uploadEmpty();
        verify(ClusterGridBuffer.class, "ssbo", GL_SHADER_STORAGE_BUFFER, GL_SHADER_STORAGE_BUFFER_BINDING, 6, 16);
        ByteBuffer scratch = (ByteBuffer) field(ClusterGridBuffer.class, "scratch");
        check(scratch.getInt(8) == 0 && scratch.getInt(12) == 0, "empty cluster header");
        check(glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 7) == foreign[1], "empty cluster preserves SSBO7");
    }

    private static void globalsFrame(int expected) throws Exception {
        ByteBuffer scratch = (ByteBuffer) field(VlGlobalsBuffer.class, "scratch");
        check(scratch.getFloat(28) == expected, "current dither frame in GPU payload");
        check((int) field(VlGlobalsBuffer.class, "frameIndex") == ((expected + 1) & 4095), "next dither frame");
        check((scratch.getInt(44) & 128) != 0, "globals valid bit");
    }

    private static void verify(Class<?> owner, String idField, int target, int binding, int index, int bytes) throws Exception {
        int id = (int) field(owner, idField);
        check(id != 0 && glGetIntegeri(binding, index) == id, owner.getSimpleName() + " indexed rebind");
        check(glGetInteger(binding) == 0, owner.getSimpleName() + " final generic zero");
        ByteBuffer expected = ((ByteBuffer) field(owner, "scratch")).duplicate();
        expected.clear();
        ByteBuffer actual = MemoryUtil.memAlloc(bytes);
        try {
            glBindBuffer(GL_COPY_READ_BUFFER, id);
            glGetBufferSubData(GL_COPY_READ_BUFFER, 0L, actual);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            for (int i = 0; i < bytes; i++) check(actual.get(i) == expected.get(i), owner.getSimpleName() + " byte " + i);
            check(glGetError() == GL_NO_ERROR, owner.getSimpleName() + " GL errors");
            uploads++;
        } finally { MemoryUtil.memFree(actual); }
    }

    private static void createForeign() {
        ByteBuffer data = MemoryUtil.memAlloc(FOREIGN_BYTES);
        try {
            for (int i = 0; i < FOREIGN_BYTES; i++) data.put(i, (byte) 0x5a);
            for (int i = 0; i < foreign.length; i++) {
                foreign[i] = glGenBuffers();
                glBindBuffer(GL_COPY_WRITE_BUFFER, foreign[i]);
                glBufferData(GL_COPY_WRITE_BUFFER, data, GL_DYNAMIC_DRAW);
            }
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        } finally { MemoryUtil.memFree(data); }
    }

    private static void stomp() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, foreign[1]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, foreign[2]);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, foreign[0]);
        glBindBufferBase(GL_UNIFORM_BUFFER, 7, foreign[4]);
        glBindBuffer(GL_UNIFORM_BUFFER, foreign[3]);
    }

    private static void foreignUnchanged() {
        ByteBuffer data = MemoryUtil.memAlloc(FOREIGN_BYTES);
        try {
            for (int id : foreign) {
                glBindBuffer(GL_COPY_READ_BUFFER, id);
                glGetBufferSubData(GL_COPY_READ_BUFFER, 0L, data);
                for (int i = 0; i < FOREIGN_BYTES; i++) check(data.get(i) == (byte) 0x5a, "foreign payload preserved");
            }
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
        } finally { MemoryUtil.memFree(data); }
    }

    private static Object field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
