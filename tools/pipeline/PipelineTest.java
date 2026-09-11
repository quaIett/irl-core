package org.qualet.irl.light;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.qualet.irl.light.iris.IrlSamplersBind;

/** Runs production packing/rasterization/binding code; only GL/Iris boundaries are
 * doubled. Reference masks use the former fixed 64-word layout and per-light flood.
 * JOML is the same real library used by the mod, not a matrix test double. */
public final class PipelineTest
{
    private static final int TILES = 32 * 18;
    private static final int MAX_WORDS = 64;
    private static final int LEGACY_OFFSET = 16;
    private static final int WIDE_OFFSET = LEGACY_OFFSET + TILES * 8;
    private static int checks;
    private static int frames;

    private static void check(boolean condition, String name)
    {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static float[][] lights(int count, Random random, boolean flood)
    {
        float[][] lights = new float[count][4];
        for (int i = 0; i < count; i++)
        {
            lights[i] = switch (flood ? 0 : i % 7)
            {
                case 0 -> new float[]{0, 0, 0, 40}; // inside
                case 1 -> new float[]{200, 0, -10, 100}; // near-plane straddle outside eye sphere
                case 2 -> new float[]{0, 0, 200, 10}; // entirely behind
                case 3 -> new float[]{0, 0, -80, 1}; // small on-screen
                case 4 -> new float[]{1000, 1000, -20, 1}; // entirely offscreen
                case 5 -> new float[]{0, 0, -0.01F, 0.001F}; // pre-near eye margin
                default -> new float[]{random.nextFloat() * 200 - 100,
                    random.nextFloat() * 120 - 60, -5 - random.nextFloat() * 200,
                    0.05F + random.nextFloat() * 45};
            };
        }
        return lights;
    }

    private static void frame(float[][] lights, Matrix4f view, Matrix4f projection)
    {
        ClusterGridBuffer.begin();
        for (int i = 0; i < lights.length; i++)
        {
            float[] light = lights[i];
            ClusterGridBuffer.record(i, light[0], light[1], light[2], light[3]);
        }
        ClusterGridBuffer.markSnapshotFresh();
        check(ClusterGridBuffer.hasFreshSnapshot(), "finished snapshot is fresh");
        ClusterGridBuffer.buildAndUpload(view, projection);
        check(!ClusterGridBuffer.hasFreshSnapshot(), "build consumes freshness");

        ByteBuffer upload = GL15.lastUpload;
        int stride = Math.max(1, (lights.length + 31) / 32);
        check(upload.remaining() == WIDE_OFFSET + TILES * stride * 4, "compact upload length");
        check(upload.getInt(0) == 32 && upload.getInt(4) == 18, "fixed grid dimensions");
        check(upload.getInt(8) == 1 && upload.getInt(12) == stride, "runtime stride header");
        int[] reference = reference(lights, view, projection);
        for (int tile = 0; tile < TILES; tile++)
        {
            // Old-generation readers see the byte-identical fixed uvec2[576].
            check(upload.getInt(LEGACY_OFFSET + tile * 8) == reference[tile * MAX_WORDS], "legacy low mask");
            check(upload.getInt(LEGACY_OFFSET + tile * 8 + 4) == reference[tile * MAX_WORDS + 1], "legacy high mask");
            for (int word = 0; word < stride; word++)
            {
                check(upload.getInt(WIDE_OFFSET + (tile * stride + word) * 4)
                    == reference[tile * MAX_WORDS + word], "wide mask equals former algorithm");
            }
        }
        frames++;
    }

    // Frozen old rasterization: same numerical projection/near handling, full
    // 64-word stride and immediate writes to every tile for a flooded light.
    private static int[] reference(float[][] lights, Matrix4f view, Matrix4f projection)
    {
        int[] masks = new int[TILES * MAX_WORDS];
        Vector4f v = new Vector4f();
        for (int bit = 0; bit < lights.length; bit++)
        {
            float[] light = lights[bit];
            float r = light[3];
            view.transform(v.set(light[0], light[1], light[2], 1));
            float vx = v.x, vy = v.y, vz = v.z;
            float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            int x0 = 0, x1 = 31, y0 = 0, y1 = 17;
            if (len > r * 1.05F + 1.0F)
            {
                float d = -vz;
                if (d + r < 0.05F) continue;
                if (d - r > 0.05F)
                {
                    float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                    float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                    for (int corner = 0; corner < 8; corner++)
                    {
                        projection.transform(v.set(vx + ((corner & 1) == 0 ? -r : r),
                            vy + ((corner & 2) == 0 ? -r : r),
                            vz + ((corner & 4) == 0 ? -r : r), 1));
                        float inv = 1F / v.w;
                        float x = v.x * inv, y = v.y * inv;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                    float minU = minX * 0.5F + 0.5F, maxU = maxX * 0.5F + 0.5F;
                    float minV = minY * 0.5F + 0.5F, maxV = maxY * 0.5F + 0.5F;
                    if (maxU < 0 || minU > 1 || maxV < 0 || minV > 1) continue;
                    x0 = Math.max(0, (int) Math.floor(minU * 32) - 1);
                    x1 = Math.min(31, (int) Math.floor(maxU * 32) + 1);
                    y0 = Math.max(0, (int) Math.floor(minV * 18) - 1);
                    y1 = Math.min(17, (int) Math.floor(maxV * 18) + 1);
                }
            }
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++)
                    masks[(y * 32 + x) * MAX_WORDS + (bit >> 5)] |= 1 << (bit & 31);
        }
        return masks;
    }

    private static void lifecycle()
    {
        ClusterGridBuffer.markSnapshotFresh();
        ClusterGridBuffer.begin();
        check(!ClusterGridBuffer.hasFreshSnapshot(), "partial new snapshot invalidates previous one");
        ClusterGridBuffer.markSnapshotFresh();
        ClusterGridBuffer.setEnabled(false);
        ClusterGridBuffer.record(0, 0, 0, 0, 1);
        ClusterGridBuffer.markSnapshotFresh();
        check(!ClusterGridBuffer.hasFreshSnapshot(), "disabled snapshot cannot become fresh");
        ClusterGridBuffer.setEnabled(true);
        check(!ClusterGridBuffer.hasFreshSnapshot(), "reenable cannot revive stale snapshot");
        ClusterGridBuffer.markSnapshotFresh();
        ClusterGridBuffer.uploadEmpty();
        check(!ClusterGridBuffer.hasFreshSnapshot(), "empty upload invalidates snapshot");
        check(GL15.lastUpload.remaining() == 16 && GL15.lastUpload.getInt(8) == 0
            && GL15.lastUpload.getInt(12) == 0, "disabled header only");
        int uploads = GL15.uploads;
        int binds = GL30.binds;
        ClusterGridBuffer.uploadEmpty();
        check(GL15.uploads == uploads && GL30.binds == binds + 1, "empty rebind without duplicate upload");
        ClusterGridBuffer.markSnapshotFresh();
        ClusterGridBuffer.delete();
        check(!ClusterGridBuffer.hasFreshSnapshot(), "delete invalidates snapshot");
        frame(new float[0][4], new Matrix4f(), new Matrix4f().perspective(1F, 1.7F, 0.05F, 1000F));
        ClusterGridBuffer.delete();
        ClusterGridBuffer.uploadEmpty();
        check(GL15.lastUpload.remaining() == 16, "first-ever disabled upload remains bound and defined");
        ClusterGridBuffer.delete();
    }

    private static void samplers()
    {
        int[] calls = {0};
        int[] liveId = {701};
        IrlSamplers.register("testPlain", () -> { throw new AssertionError("2D supplier evaluated during array lookup"); }, GL11.GL_TEXTURE_2D);
        IrlSamplers.register("testArray", () -> { calls[0]++; return liveId[0]; }, GL30.GL_TEXTURE_2D_ARRAY);
        check(!IrlSamplersBind.tryRebind(0, 5) && calls[0] == 0, "zero id skips all suppliers");
        check(IrlSamplersBind.tryRebind(701, 5), "array target intercepted");
        check(IrisRenderSystem.target == GL30.GL_TEXTURE_2D_ARRAY && IrisRenderSystem.unit == 5
            && IrisRenderSystem.id == 701, "Iris receives exact array binding");
        liveId[0] = 702;
        check(!IrlSamplersBind.tryRebind(701, 5), "deleted id no longer matches");
        check(IrlSamplersBind.tryRebind(702, 6), "replacement GL id stays live");
        int[] laterCalls = {0};
        IrlSamplers.register("later", () -> { laterCalls[0]++; return 702; }, GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        check(IrlSamplersBind.tryRebind(702, 6) && laterCalls[0] == 0, "first match stops lookup");
        IrlSamplers.register("testArray", () -> 703, GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        check(IrlSamplersBind.tryRebind(703, 7) && IrisRenderSystem.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY,
            "reregister changes target and supplier");
        check(IrlSamplers.glTargetFor("testArray") == GL40.GL_TEXTURE_CUBE_MAP_ARRAY, "named target follows replacement");
        ArrayList<String> order = new ArrayList<>();
        IrlSamplers.forEach((name, supplier, target) -> order.add(name));
        check(order.indexOf("testArray") < order.indexOf("later"), "replacement retains insertion slot");
        IrlSamplers.register("testArray", () -> 703, GL11.GL_TEXTURE_2D);
        check(!IrlSamplersBind.tryRebind(703, 7), "replacement by 2D removes interception");
        int binds = IrisRenderSystem.binds;
        check(!IrlSamplersBind.tryRebind(999999, 7) && IrisRenderSystem.binds == binds, "unknown id keeps Iris default binding");
    }

    public static void main(String[] args)
    {
        Random random = new Random(0x51A7);
        int[] counts = {0, 1, 31, 32, 33, 63, 64, 65, 127, 128, 129, 2047, 2048,
            65, 1, 2048, 0, 33, 2047, 32};
        ClusterGridBuffer.setEnabled(true);
        for (int mode = 0; mode < 4; mode++)
        {
            Matrix4f view = mode % 2 == 0 ? new Matrix4f()
                : new Matrix4f().rotateXYZ(0.15F, 0.8F, -0.06F).translate(0.03F, -0.11F, 0.02F);
            Matrix4f projection = new Matrix4f().perspective(mode < 2 ? 1.22F : 1.9F,
                mode < 2 ? 16F / 9F : 9F / 16F, 0.05F, 1000F);
            for (int count : counts) frame(lights(count, random, mode == 2), view, projection);
            // Simulate cap / pending-shadow omissions followed by packed reindexing.
            float[][] source = lights(300, random, false);
            float[][] packed = Arrays.stream(source).filter(light -> light[2] < -0.1F).limit(65).toArray(float[][]::new);
            frame(packed, view, projection);
            for (int i = 0; i < 40; i++) frame(lights(random.nextInt(2049), random, false), view, projection);
        }
        lifecycle();
        samplers();
        System.out.println("Pipeline tests passed: " + checks + " checks, " + frames
            + " reference-mask frames; live sampler ids/replacement and snapshot lifecycle verified.");
    }
}
