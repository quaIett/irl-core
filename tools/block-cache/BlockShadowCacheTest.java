package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import java.lang.reflect.Field;
import java.util.List;

public final class BlockShadowCacheTest {
    private static final ClientWorld WORLD = new ClientWorld();
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static List<BlockShadowEntry> get(long id, float x, float y, float z, float r) {
        return BlockShadowCache.getOrCompute(id, WORLD, x, y, z, r);
    }
    private static void reset() {
        BlockShadowCache.retainOnly(new LongOpenHashSet());
        BlockShadowCollector.empty = false;
        BlockShadowCollector.same = false;
    }
    private static Object sections(long id) throws Exception {
        Field byId = BlockShadowCache.class.getDeclaredField("byId");
        byId.setAccessible(true);
        Object entry = ((Long2ObjectOpenHashMap<?>) byId.get(null)).get(id);
        Field keys = entry.getClass().getDeclaredField("sectionKeys");
        keys.setAccessible(true);
        return keys.get(entry);
    }
    public static void main(String[] args) throws Exception {
        reset();
        for (int axis = 0; axis < 3; axis++) {
            float[] before = {0.9f, 0.9f, 0.9f};
            float[] after = before.clone();
            after[axis] = 1.1f;
            var a = get(1, before[0], before[1], before[2], 3);
            Object index = sections(1);
            var b = get(1, after[0], after[1], after[2], 3);
            check(a != b, "host cell crossing axis " + axis + " must recollect");
            check(!a.get(0).equals(b.get(0)), "collector receives new emitter host axis " + axis);
            check(index == sections(1), "host change retains section index");
            reset();
        }
        var negative = get(1, -0.1f, 0, 0, 3);
        check(negative != get(1, 0.1f, 0, 0, 3), "negative to positive host boundary");
        reset();
        var a = get(1, 5.1f, 5.1f, 5.1f, 1.1f);
        int calls = BlockShadowCollector.calls;
        check(a == get(1, 5.2f, 5.2f, 5.2f, 1.2f), "same snapped sphere and host reuses list");
        check(calls == BlockShadowCollector.calls, "cache hit avoids collector");
        Object index = sections(1);
        check(BlockShadowCache.invalidateAt(new BlockPos(5, 5, 5)), "near edit invalidates");
        var b = get(1, 5.1f, 5.1f, 5.1f, 1.1f);
        check(a != b, "edit gets fresh geometry");
        check(index == sections(1), "geometry edit retains section index");
        check(BlockShadowCache.invalidateAt(new BlockPos(5, 5, 5)), "retained index handles repeated edits");
        get(1, 6.1f, 5.1f, 5.1f, 1.1f);
        check(index == sections(1), "moving sphere with same section bounds retains index");
        check(!BlockShadowCache.invalidateAt(new BlockPos(15, 15, 15)), "coarse-section corner outside sphere ignored");
        get(1, 14.9f, 5, 5, 1);
        check(index != sections(1), "crossing section boundary replaces index");
        check(BlockShadowCache.invalidateAt(new BlockPos(16, 5, 5)), "new section indexed");
        get(1, 40, 5, 5, 1);
        check(!BlockShadowCache.invalidateAt(new BlockPos(16, 5, 5)), "departed sections removed");
        check(BlockShadowCache.invalidateAt(new BlockPos(40, 5, 5)), "new sphere stays indexed");
        get(2, 40, 5, 5, 1);
        BlockShadowCache.retainOnly(new LongOpenHashSet(new long[]{2}));
        check(BlockShadowCache.invalidateAt(new BlockPos(40, 5, 5)), "eviction preserves overlapping light");
        reset();
        check(!BlockShadowCache.invalidateAt(new BlockPos(40, 5, 5)), "clear removes section membership");
        BlockShadowCollector.empty = true;
        a = get(3, 5, 5, 5, 1);
        check(a.isEmpty(), "empty collection fixture");
        check(BlockShadowCache.invalidateAt(new BlockPos(5, 5, 5)), "empty section detects a future block placement");
        BlockShadowCollector.empty = false;
        check(!get(3, 5, 5, 5, 1).isEmpty(), "placement recollects previously empty geometry");
        a = get(3, 5, 5, 5, 1);
        BlockShadowCollector.same = true;
        check(!BlockShadowCache.invalidateChange(WORLD, new BlockPos(5, 5, 5), new BlockState(), new BlockState()), "unchanged silhouette does not invalidate");
        check(a == get(3, 5, 5, 5, 1), "unchanged silhouette keeps geometry");
        BlockShadowCollector.same = false;
        check(BlockShadowCache.invalidateChange(WORLD, new BlockPos(5, 5, 5), new BlockState(), new BlockState()), "changed silhouette invalidates");
        check(a != get(3, 5, 5, 5, 1), "changed silhouette recollects");
        reset();
        System.out.println("Block cache checks passed: " + checks);
    }
}
