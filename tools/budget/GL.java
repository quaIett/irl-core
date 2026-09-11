package org.lwjgl.opengl;

/** Only the capability read used by the budget's NVX query path. */
public final class GL
{
    public static final class Capabilities
    {
        public final boolean GL_NVX_gpu_memory_info = true;
    }

    private static final Capabilities CAPS = new Capabilities();
    public static Capabilities getCapabilities() { return CAPS; }
}
