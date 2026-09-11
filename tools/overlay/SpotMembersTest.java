package org.qualet.irl.light.shadow;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Differential test of the actual primitive/spot collection entry points. */
public final class SpotMembersTest
{
    private static final CasterRevision KNOWN = new CasterRevision(true, 1, 2, 3, 4, 5, 6);
    private static final Object BLOCKS = new Object();
    private static int checks;
    private static volatile long sink;

    private static void check(boolean condition, String label)
    {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void add(ShadowOverlayCache.Members members, ShadowOverlayCache.Member[] slots,
                            boolean shared, int slot, Object caster, int type,
                            CasterRevision revision, float radius, int faces)
    {
        if (shared && faces == 0)
            members.addSpot(slots, slot, caster, type, revision, slot, 2, 3, radius, 5, 6);
        else
            members.add(caster, type, revision, slot, 2, 3, radius, 5, 6, faces);
    }

    private static void snapshots()
    {
        Object caster = new Object();
        var slots = new ShadowOverlayCache.Member[128];
        var now = new ShadowOverlayCache.Members();
        var cache = new ShadowOverlayCache();
        add(now, slots, true, 0, caster, 1, KNOWN, 4, 0);
        var previous = slots[0];
        cache.completed(1, 1, 1, BLOCKS, 1, now, true);
        now.clear();
        add(now, slots, true, 0, caster, 1, KNOWN, 4, 0);
        check(previous == slots[0], "two spots share one record in a bake");
        Arrays.fill(slots, null);
        check(cache.reusable(1, 1, 1, BLOCKS, 1, now), "old snapshot survives reference reset");
        now.clear();
        add(now, slots, true, 0, caster, 2, KNOWN, 8, 0);
        check(previous != slots[0], "new bake uses new immutable record");
        check(previous.type() == 1 && previous.radius() == 4, "old record remains unchanged");
        check(!cache.reusable(1, 1, 1, BLOCKS, 1, now), "type and bounds invalidate");
        for (CasterRevision revision : new CasterRevision[]{null, CasterRevision.UNKNOWN})
        {
            Arrays.fill(slots, null);
            now.clear();
            add(now, slots, true, 0, caster, 1, revision, 4, 0);
            add(now, slots, true, 1, new Object(), 1, KNOWN, 4, 0);
            check(!now.known(), "unknown member poisons collection");
            check(slots[0] == null && slots[1] == null, "unknown gate allocates no records");
        }
        now.clear();
        add(now, slots, true, 0, null, 1, KNOWN, 4, 0);
        check(slots[0] == null && !now.known(), "null caster uses unknown fallback");
        now.clear(false);
        add(now, slots, true, 0, caster, 1, KNOWN, 4, 0);
        check(slots[0] == null && !now.known(), "disabled collection allocates no record");
        now.clear();
        add(now, slots, true, 0, caster, 1, KNOWN, 4, 7);
        check(slots[0] == null, "point or degenerate cone does not prepare spot records");
        now.clear();
        add(now, slots, true, 0, caster, 1, KNOWN, 4, 0);
        add(now, slots, true, 1, caster, 1, KNOWN, 4, 0);
        add(now, slots, true, 2, new Object(), 1, KNOWN, 4, 0);
        check(!now.known() && slots[2] == null, "duplicate poisons subsequent collection");
    }

    private static void differential()
    {
        var oldCache = new ShadowOverlayCache();
        var newCache = new ShadowOverlayCache();
        var oldMembers = new ShadowOverlayCache.Members();
        var newMembers = new ShadowOverlayCache.Members();
        var slots = new ShadowOverlayCache.Member[128];
        var casters = new Object[20];
        for (int i = 0; i < casters.length; i++) casters[i] = new Object();
        Random random = new Random(128026);
        long oldSpotReuse = 0, newSpotReuse = 0, oldPointReuse = 0, newPointReuse = 0;
        long oldDraw = 0, newDraw = 0;
        for (int frame = 0; frame < 300; frame++)
        {
            Arrays.fill(slots, null);
            boolean enabled = frame % 31 != 15 && frame % 37 != 18; // cache-off / reuse-off
            int count = frame % 23 == 11 ? 19 : frame % 29 == 0 ? 0 : 20;
            // Stable pairs of frames exercise hits; independent mutations exercise misses.
            int revisionFrame = frame / 3;
            var revision = new CasterRevision(true, 1, revisionFrame / 7, 3, 4, 5, 6);
            int type = 1 + revisionFrame / 11 % 2;
            float radius = 4 + revisionFrame / 13 % 2;
            for (int light = 0; light < 30; light++)
            {
                boolean point = light >= 26;
                int faces = point ? 1 + light % 63 : 0;
                oldMembers.clear(enabled);
                newMembers.clear(enabled);
                int start = random.nextInt(casters.length);
                for (int j = 0; j < count; j++)
                {
                    int slot = (start + j) % casters.length;
                    Object caster = frame % 41 == 20 && slot == 4 ? casters[3] : casters[slot];
                    CasterRevision current = frame % 17 == 8 && slot == 7 ? CasterRevision.UNKNOWN : revision;
                    add(oldMembers, slots, false, slot, caster, type, current, radius, faces);
                    add(newMembers, slots, true, slot, caster, type, current, radius, faces);
                }
                check(oldMembers.known() == newMembers.known(), "known verdict frame/light");
                boolean oldHit = oldCache.reusable(light, light, light, BLOCKS, 99, oldMembers);
                boolean newHit = newCache.reusable(light, light, light, BLOCKS, 99, newMembers);
                check(oldHit == newHit, "reuse verdict frame/light");
                if (point) { if (oldHit) oldPointReuse++; if (newHit) newPointReuse++; }
                else { if (oldHit) oldSpotReuse++; if (newHit) newSpotReuse++; }
                if (!oldHit) oldDraw++;
                if (!newHit) newDraw++;
                boolean success = frame % 43 != 12;
                // Cross-cache comparison proves all member values match even on misses.
                newCache.completed(light, light, light, BLOCKS, 99, oldMembers, true);
                check(newCache.reusable(light, light, light, BLOCKS, 99, newMembers) == newMembers.known(),
                    "exact member values from old/new paths");
                oldCache.completed(light, light, light, BLOCKS, 99, oldMembers, success);
                newCache.completed(light, light, light, BLOCKS, 99, newMembers, success);
            }
            if (frame % 53 == 31) { oldCache.clear(); newCache.clear(); }
        }
        check(oldSpotReuse == newSpotReuse && oldPointReuse == newPointReuse && oldDraw == newDraw,
            "spot/point reuse and draw-decision totals");
        System.out.printf("Differential 300 frames: sp.reuse=%d, pt.reuse=%d, draw decisions=%d (both paths)%n",
            newSpotReuse, newPointReuse, newDraw);
    }

    private static long workload(boolean shared, int frames, ShadowOverlayCache.Members members,
                                 ShadowOverlayCache.Member[] slots, Object[] casters)
    {
        long result = 0;
        for (int frame = 0; frame < frames; frame++)
        {
            if (shared) Arrays.fill(slots, null);
            for (int light = 0; light < 26; light++)
            {
                members.clear();
                for (int slot = 0; slot < casters.length; slot++)
                    add(members, slots, shared, slot, casters[slot], 1, KNOWN, 4, 0);
                if (members.known()) result++;
            }
        }
        return result;
    }

    private static void allocation()
    {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported(), "allocation counter supported");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        var members = new ShadowOverlayCache.Members();
        var slots = new ShadowOverlayCache.Member[128];
        var casters = new Object[20];
        for (int i = 0; i < casters.length; i++) casters[i] = new Object();
        for (int i = 0; i < 5; i++)
        {
            sink = workload(false, 1000, members, slots, casters);
            sink = workload(true, 1000, members, slots, casters);
        }
        int frames = 5000;
        long begin = bean.getThreadAllocatedBytes(thread);
        sink = workload(false, frames, members, slots, casters);
        long oldBytes = bean.getThreadAllocatedBytes(thread) - begin;
        begin = bean.getThreadAllocatedBytes(thread);
        sink = workload(true, frames, members, slots, casters);
        long newBytes = bean.getThreadAllocatedBytes(thread) - begin;
        check(newBytes < oldBytes / 10, "shared records reduce measured allocation by >90%");
        System.out.printf(Locale.ROOT, "Allocation, 26 spots x 20 known casters: old=%.1f B/bake, new=%.1f B/bake; Member count 520 -> 20%n",
            (double) oldBytes / frames, (double) newBytes / frames);
    }

    public static void main(String[] args)
    {
        snapshots();
        differential();
        allocation();
        System.out.println("Spot member checks passed: " + checks);
    }
}
