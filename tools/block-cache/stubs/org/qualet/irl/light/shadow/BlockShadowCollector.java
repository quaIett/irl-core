package org.qualet.irl.light.shadow;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;

/** Only the world/geometry boundary is replaced; tests execute the production cache. */
public final class BlockShadowCollector {
    static int calls;
    static boolean same;
    static boolean empty;
    public static List<BlockShadowEntry> collectForLight(ClientWorld world,
            float x, float y, float z, float r, int hx, int hy, int hz) {
        calls++;
        List<BlockShadowEntry> result = new ArrayList<>();
        if (!empty) result.add(new BlockShadowEntry(hx, hy, hz));
        return result;
    }
    public static boolean sameSilhouette(BlockView world, BlockPos pos, BlockState a, BlockState b) {
        return same;
    }
}
