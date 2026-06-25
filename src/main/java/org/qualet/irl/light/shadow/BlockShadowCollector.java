package org.qualet.irl.light.shadow;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the bbox of a local light and gathers every block whose shape is
 * non-empty so its silhouette can be baked into per-light depth maps.
 *
 * Shape priority is cullingShape -> collisionShape -> outlineShape. For
 * fences / glass panes / iron bars cullingShape uses thin arms (radius
 * 1/16) so the post and arms decompose into distinct sub-AABBs with gaps
 * between them - exactly the lacy silhouette we want. collisionShape and
 * outlineShape use the same radius as the post, so on a connected block
 * they fuse into a single wide plank with no gaps.
 *
 * Fallbacks catch blocks where cullingShape is empty (signs, torches,
 * transparent decoratives), and finally outline for open trapdoors /
 * ladders / open doors where collision is also empty.
 *
 * The caller is responsible for clamping {@code radius} (the bake far-plane
 * may exceed the collection radius; blocks beyond it just cast no shadow).
 */
public final class BlockShadowCollector
{
    private BlockShadowCollector()
    {
    }

    public static List<BlockShadowEntry> collectForLight(ClientWorld world,
                                                         float lx, float ly, float lz,
                                                         float radius,
                                                         int hostX, int hostY, int hostZ)
    {
        List<BlockShadowEntry> out = new ArrayList<>();
        if (world == null || radius < 1e-3f)
        {
            return out;
        }

        int minX = (int) Math.floor(lx - radius);
        int minY = (int) Math.floor(ly - radius);
        int minZ = (int) Math.floor(lz - radius);
        int maxX = (int) Math.floor(lx + radius);
        int maxY = (int) Math.floor(ly + radius);
        int maxZ = (int) Math.floor(lz + radius);

        float r2 = radius * radius;
        BlockPos.Mutable mut = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++)
        {
            float dx = (x + 0.5f) - lx;
            float dx2 = dx * dx;
            if (dx2 > r2) continue;

            for (int z = minZ; z <= maxZ; z++)
            {
                float dz = (z + 0.5f) - lz;
                float dxz2 = dx2 + dz * dz;
                if (dxz2 > r2) continue;

                for (int y = minY; y <= maxY; y++)
                {
                    float dy = (y + 0.5f) - ly;
                    if (dxz2 + dy * dy > r2) continue;

                    mut.set(x, y, z);
                    BlockState state = world.getBlockState(mut);
                    if (state.isAir()) continue;

                    // The block the light sits INSIDE (an emitting torch /
                    // glowstone / lantern / lava / ...) must not cast a shadow:
                    // the bulb is inside it, so its own silhouette would
                    // otherwise trap the light in its own depth map. Scoped to
                    // the host cell (floor of the light position) so emitters
                    // ELSEWHERE in range still cast shadows normally. Same idea
                    // as the INVISIBLE ModelBlock skip just below.
                    if (x == hostX && y == hostY && z == hostZ && state.getLuminance() > 0) continue;

                    // BlockRenderType.INVISIBLE means the block draws nothing
                    // through the vanilla render path - ModelBlock, barrier,
                    // light_block, structure_void. ModelBlock in particular
                    // hosts the IRLight itself and would otherwise bake a
                    // phantom full-cube around the bulb (its outline falls
                    // back to VoxelShapes.fullCube because Block has no
                    // override and collision is empty), trapping the light
                    // inside its own shadow map.
                    if (state.getRenderType() == BlockRenderType.INVISIBLE) continue;

                    // Cutout blocks (doors with glass, iron bars, trapdoors,
                    // ladders, leaves) are baked from their textured BakedModel
                    // via vanilla's alpha-test cutout shader so transparent
                    // texture pixels let light through. Shape is unused for
                    // these - geometry comes from the model, not the AABB.
                    boolean cutout;
                    try
                    {
                        RenderLayer layer = RenderLayers.getBlockLayer(state);
                        cutout = layer == RenderLayer.getCutout() || layer == RenderLayer.getCutoutMipped();
                    }
                    catch (Throwable t)
                    {
                        cutout = false;
                    }
                    if (cutout)
                    {
                        out.add(new BlockShadowEntry(mut.toImmutable(), null, true));
                        continue;
                    }

                    VoxelShape shape;
                    try
                    {
                        shape = state.getCullingShape(world, mut);
                        if (shape == null || shape.isEmpty())
                        {
                            shape = state.getCollisionShape(world, mut);
                        }
                        if (shape == null || shape.isEmpty())
                        {
                            shape = state.getOutlineShape(world, mut);
                        }
                    }
                    catch (Throwable t)
                    {
                        continue;
                    }
                    if (shape == null || shape.isEmpty()) continue;

                    out.add(new BlockShadowEntry(mut.toImmutable(), shape, false));
                }
            }
        }

        return out;
    }
}
