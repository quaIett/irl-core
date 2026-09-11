package org.lwjgl.opengl;

/** Counts the one driver query whose removal this regression checks. */
public final class GL11
{
    public static int queries;
    public static int freeKiB = 6144 * 1024;

    public static int glGetInteger(int parameter)
    {
        if (parameter != 0x9049) throw new AssertionError("Unexpected GL query: " + parameter);
        queries++;
        return freeKiB;
    }
}
