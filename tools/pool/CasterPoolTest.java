package org.qualet.irl.light.shadow;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

public final class CasterPoolTest
{
    private static long checks;
    private static volatile long sink;
    private record Candidate(Object caster, int type, boolean isStatic,
                             float x, float y, float z, float radius,
                             float rh, float hv, long hash) {}

    private static void check(boolean condition, String label)
    {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void same(float a, float b, String label)
    {
        check(Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b), label);
    }

    private static void put(boolean old, Candidate c)
    {
        if (old) ReferencePool.put(c.caster, c.type, c.isStatic, c.x, c.y, c.z, c.radius, c.rh, c.hv, c.hash);
        else ProductionPool.put(c.caster, c.type, c.isStatic, c.x, c.y, c.z, c.radius, c.rh, c.hv, c.hash);
    }

    private static void compare()
    {
        check(ProductionPool.occCount == ReferencePool.occCount, "count");
        check(ProductionPool.farthestOccIdx == ReferencePool.farthestOccIdx, "first farthest slot");
        same(ProductionPool.farthestOccDist2, ReferencePool.farthestOccDist2, "farthest distance");
        for (int k = 0; k < ProductionPool.occCount; k++)
        {
            check(ProductionPool.occ[k] == ReferencePool.occ[k], "caster identity/slot");
            check(ProductionPool.occType[k] == ReferencePool.occType[k], "caster type");
            check(ProductionPool.oStatic[k] == ReferencePool.oStatic[k], "static flag");
            check(ProductionPool.ostatichash[k] == ReferencePool.ostatichash[k], "static hash");
            same(ProductionPool.ox[k], ReferencePool.ox[k], "x");
            same(ProductionPool.oy[k], ReferencePool.oy[k], "y");
            same(ProductionPool.oz[k], ReferencePool.oz[k], "z");
            same(ProductionPool.orad[k], ReferencePool.orad[k], "radius");
            same(ProductionPool.orh[k], ReferencePool.orh[k], "half width");
            same(ProductionPool.ohv[k], ReferencePool.ohv[k], "half height");
            same(ProductionPool.odist2[k], ReferencePool.odist2[k], "distance");
        }
    }

    private static Candidate[] candidates(int count, int order, long seed)
    {
        Random random = new Random(seed);
        Candidate[] values = new Candidate[count];
        for (int i = 0; i < count; i++)
        {
            float x = switch (order)
            {
                case 0 -> i;
                case 1 -> count - i;
                case 2 -> random.nextInt(30); // many exact ties
                default -> random.nextFloat() * count;
            };
            values[i] = new Candidate(new Object(), i % 6, (i & 1) != 0,
                x, order >= 4 ? random.nextFloat() : 0, order >= 4 ? random.nextFloat() : 0,
                i % 10, i % 9, i % 11, random.nextLong());
        }
        return values;
    }

    private static void differential(Candidate[] candidates, float camX, float camY, float camZ)
    {
        ProductionPool.reset();
        ReferencePool.reset();
        ProductionPool.occCamX = ReferencePool.occCamX = camX;
        ProductionPool.occCamY = ReferencePool.occCamY = camY;
        ProductionPool.occCamZ = ReferencePool.occCamZ = camZ;
        compare();
        for (Candidate candidate : candidates)
        {
            int oldDrops = ShadowClipTelemetry.drops;
            put(true, candidate);
            oldDrops = ShadowClipTelemetry.drops - oldDrops;
            int newDrops = ShadowClipTelemetry.drops;
            put(false, candidate);
            newDrops = ShadowClipTelemetry.drops - newDrops;
            check(newDrops == oldDrops, "pool-drop telemetry");
            compare();
        }
    }

    private static void malformed()
    {
        Candidate[] input = candidates(512, 1, 9876);
        float[] special = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.MAX_VALUE, -Float.MAX_VALUE, +0.0f, -0.0f, Float.MIN_VALUE};
        for (int i = 0; i < input.length; i++)
        {
            Candidate c = input[i];
            float v = special[i % special.length];
            input[i] = switch (i % 6)
            {
                case 0 -> new Candidate(c.caster, c.type, c.isStatic, v, c.y, c.z, c.radius, c.rh, c.hv, c.hash);
                case 1 -> new Candidate(c.caster, c.type, c.isStatic, c.x, v, c.z, c.radius, c.rh, c.hv, c.hash);
                case 2 -> new Candidate(c.caster, c.type, c.isStatic, c.x, c.y, v, c.radius, c.rh, c.hv, c.hash);
                case 3 -> new Candidate(c.caster, c.type, c.isStatic, c.x, c.y, c.z, v, c.rh, c.hv, c.hash);
                case 4 -> new Candidate(c.caster, c.type, c.isStatic, c.x, c.y, c.z, c.radius, v, c.hv, c.hash);
                default -> new Candidate(c.caster, c.type, c.isStatic, c.x, c.y, c.z, c.radius, c.rh, v, c.hash);
            };
        }
        differential(input, 0, 0, 0);
        differential(input, Float.POSITIVE_INFINITY, 0, 0);
        differential(input, Float.NaN, 0, 0);
        differential(input, -0.0f, +0.0f, -0.0f);
        // The real put rejects non-finite d2, and d2 sums turn zeros positive.
        // Also test the heap comparator directly so signed-zero ties stay explicit.
        float[] zeros = new float[128];
        for (int k = 0; k < zeros.length; k++) zeros[k] = (k & 1) == 0 ? -0.0f : +0.0f;
        var heap = new FarthestCasterHeap(zeros);
        check(heap.afterReplace() == 0, "signed zero keeps first maximum");
        zeros[0] = 1;
        heap.reset();
        check(heap.afterReplace() == 0, "maximum at slot zero");
        zeros[0] = -0.0f;
        check(heap.afterReplace() == 0, "replaced root zero still uses first slot tie");
    }

    private static long workload(boolean old, Candidate[] input, int frames)
    {
        long sum = 0;
        for (int frame = 0; frame < frames; frame++)
        {
            if (old) ReferencePool.reset(); else ProductionPool.reset();
            for (Candidate candidate : input) put(old, candidate);
            sum += old ? ReferencePool.farthestOccIdx : ProductionPool.farthestOccIdx;
        }
        return sum;
    }

    private static void benchmark(String name, Candidate[] input)
    {
        ProductionPool.occCamX = ReferencePool.occCamX = 0;
        ProductionPool.occCamY = ReferencePool.occCamY = 0;
        ProductionPool.occCamZ = ReferencePool.occCamZ = 0;
        for (int i = 0; i < 4; i++)
        {
            sink = workload(false, input, 128);
            sink = workload(true, input, 128);
        }
        long[] oldTimes = new long[7], newTimes = new long[7];
        int frames = 128;
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long oldBytes = 0, newBytes = 0;
        for (int round = 0; round < oldTimes.length; round++)
        {
            for (int turn = 0; turn < 2; turn++)
            {
                boolean old = (round + turn) % 2 == 0;
                long bytes = bean.getThreadAllocatedBytes(thread);
                long started = System.nanoTime();
                sink = workload(old, input, frames);
                long elapsed = System.nanoTime() - started;
                bytes = bean.getThreadAllocatedBytes(thread) - bytes;
                if (old) { oldTimes[round] = elapsed; oldBytes += bytes; }
                else { newTimes[round] = elapsed; newBytes += bytes; }
            }
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        double oldNs = (double) oldTimes[oldTimes.length / 2] / frames;
        double newNs = (double) newTimes[newTimes.length / 2] / frames;
        check(oldBytes == 0 && newBytes == 0, "no per-collect allocation: " + name);
        System.out.printf(Locale.ROOT, "%s (%d emits): old=%.1f ns/collect, new=%.1f ns/collect, delta=%.2f%%; allocated bytes old/new=%d/%d%n",
            name, input.length, oldNs, newNs, (newNs / oldNs - 1) * 100, oldBytes, newBytes);
    }

    private static void operations() throws ReflectiveOperationException
    {
        var heapComparisons = FarthestCasterHeap.class.getDeclaredField("comparisons");
        var scanComparisons = ReferencePool.class.getDeclaredField("comparisons");
        for (int order : new int[]{0, 1, 4})
        {
            Candidate[] input = candidates(4096, order, 4);
            heapComparisons.setLong(null, 0);
            scanComparisons.setLong(null, 0);
            sink = workload(true, input, 1);
            sink = workload(false, input, 1);
            long scan = scanComparisons.getLong(null), heap = heapComparisons.getLong(null);
            check(order == 0 ? heap == 0 && scan == 0 : heap < scan,
                "measured maximum-maintenance comparisons");
            System.out.printf("Maximum-maintenance comparisons, %s4096: scan=%d, heap=%d%n",
                order == 0 ? "ascending" : order == 1 ? "descending" : "random", scan, heap);
        }
    }

    public static void main(String[] args) throws ReflectiveOperationException
    {
        if (args.length > 0 && args[0].equals("--operations"))
        {
            operations();
            return;
        }
        int frames = 0;
        for (int count : new int[]{0, 1, 127, 128, 129, 4096})
        {
            for (int order = 0; order < 5; order++)
            {
                differential(candidates(count, order, count + order), 0, 0, 0);
                frames++;
            }
        }
        for (int frame = 0; frame < 128; frame++)
        {
            differential(candidates(400 + frame % 5, 4, frame), frame % 7 - 3, frame % 5, -frame % 3);
            frames++;
        }
        malformed();
        System.out.printf("Production put vs saved scan: %d bit-exact assertions, %d normal frames + 4 malformed frames%n", checks, frames);
        benchmark("small", candidates(127, 1, 1));
        benchmark("ascending/all rejected", candidates(4096, 0, 2));
        benchmark("descending/all replaced", candidates(4096, 1, 3));
        benchmark("random", candidates(4096, 4, 4));
        System.out.println("Caster pool checks passed: " + checks);
    }
}

final class ShadowClipTelemetry
{
    static final boolean ENABLED = true;
    static int drops;
    static void notePoolDrop() { drops++; }
}
