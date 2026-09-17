package org.qualet.irl.light.shadow;

import java.util.Arrays;
import org.lwjgl.opengl.GL11;

/** Exercises the real budget class with fixed capacities and one query stub. */
public final class ShadowVramBudgetTest
{
    private static int checks;

    private static void check(boolean value, String name)
    {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    private static void capacities(int a, int b, int c)
    {
        int[] values = {a, b, c};
        System.arraycopy(values, 0, PointShadowPyramid.blocks, 0, 3);
        System.arraycopy(values, 0, PointShadowEvsm.blocks, 0, 3);
    }

    private static void caps(int a, int b, int c, String name)
    {
        int[] expected = {a, b, c};
        for (int t = 0; t < 3; t++)
            check(ShadowVramBudget.approvedPointBlocks(t) == expected[t], name + " tier " + t);
    }

    public static void main(String[] args)
    {
        capacities(2, 12, 16);
        ShadowVramBudget.updatePointCaps();
        check(GL11.queries == 0, "full physical pool does not query VRAM");
        caps(2, 12, 16, "full pool");

        capacities(2, 12, 12);
        ShadowVramBudget.updatePointCaps();
        check(GL11.queries == 1, "one incomplete tier still queries VRAM");
        caps(2, 12, 16, "last chunk approved with headroom");

        // Simulate texture deletion/preset reset after the full-pool fast path.
        capacities(0, 0, 0);
        GL11.freeKiB = 2560 * 1024;
        ShadowVramBudget.updatePointCaps();
        check(GL11.queries == 2, "resource reset resumes live budget query");
        caps(0, 0, 0, "no headroom denies all growth");

        ShadowBaker.owned[0] = 1;
        ShadowVramBudget.updatePointCaps();
        caps(1, 0, 0, "existing handout commitment remains backed");
        Arrays.fill(ShadowBaker.owned, 0);

        PointShadowPyramid.failed = true;
        PointShadowEvsm.failed = true;
        int before = GL11.queries;
        ShadowVramBudget.updatePointCaps();
        check(GL11.queries == before, "two inert filters need no growth query");
        caps(2, 12, 16, "inert filters preserve full pool fallback");

        PointShadowEvsm.failed = false;
        ShadowVramBudget.updatePointCaps();
        check(GL11.queries == before + 1, "one active unallocated filter still queries");
        caps(0, 0, 0, "active filter still constrains capacity");

        System.out.println("Shadow VRAM budget checks passed: " + checks);
    }
}

// CPU-only capacity providers; no rendering API is mocked here.
final class PointDepthAtlas
{
    static int getTileSize() { return 64; }
    static int tierBlockCount(int t) { return new int[]{2, 12, 16}[t]; }
}

final class ShadowBaker
{
    static final int[] owned = new int[3];
    static int ownedPointBlocks(int t) { return owned[t]; }
}

final class PointShadowPyramid
{
    static final int[] blocks = new int[3];
    static boolean failed;
    static boolean inert() { return failed; }
    static int allocatedBlocks(int t) { return blocks[t]; }
}

final class PointShadowEvsm
{
    static final int[] blocks = new int[3];
    static boolean failed;
    static boolean inert() { return failed; }
    static int allocatedBlocks(int t) { return blocks[t]; }
}
