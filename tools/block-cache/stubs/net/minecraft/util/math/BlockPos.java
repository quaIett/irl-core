package net.minecraft.util.math;
public record BlockPos(int x, int y, int z) {
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
}
