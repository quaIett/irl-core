package org.qualet.irl.light;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.qualet.irl.light.shadow.ShadowBaker;

import java.util.function.BooleanSupplier;

/**
 * The per-frame light orchestration run at renderWorld HEAD, before Iris
 * activates: collect this frame's lights, bake spotlight/point shadow maps, and
 * flush the light SSBO. Both IRLite and IRL-redactor drive this identically; the
 * per-mod {@code GameRendererLightMixin} is a thin delegate that supplies the
 * two mod-specific seams (the shaders-off check and the light collect source)
 * plus an optional dormant hook.
 *
 * <h2>Shaders-off gate</h2>
 * With no shaderpack active nothing consumes the SSBO or the shadow maps, so the
 * whole collect/bake/upload pipeline would be wasted work (the shadow bake being
 * the expensive part). The gate drops the render-path registrations that still
 * arrive each frame, releases the GPU-side state ONCE on the transition into the
 * dormant state, and stays dormant until a shaderpack returns. The gate is passed
 * as a {@link BooleanSupplier} rather than called here so the Iris dependency
 * stays out of the parts of the engine that do not need it.
 */
public final class FramePipeline
{
    /** Supplies this frame's lights (world blocks, live actors, replays, …). The
     *  signature matches both mods' collect entry points verbatim. */
    @FunctionalInterface
    public interface LightSource
    {
        void collect(ClientWorld world, Vec3d cameraPos, float tickDelta);
    }

    /** True while the light pipeline is parked because shaders are off; the
     *  GPU-side release (SSBO zero + shadow textures/caches) runs once, on
     *  the transition into that state. */
    private static boolean dormant;

    private FramePipeline()
    {}

    /**
     * Run one frame of the light pipeline.
     *
     * @param tickDelta       render partial tick
     * @param shadersDisabled true iff Iris shaders are positively off this frame
     * @param source          collects this frame's lights into the registry
     * @param onDormant        run once on the transition into the dormant state,
     *                         after the GPU-side release (may be a no-op)
     */
    public static void frame(float tickDelta, BooleanSupplier shadersDisabled, LightSource source, Runnable onDormant)
    {
        // Shaders off -> nothing consumes the SSBO or the shadow maps, so the
        // whole collect/bake/upload pipeline would be wasted work (the shadow
        // bake being the expensive part). Drop the render-path registrations
        // that still arrive each frame, release the GPU-side state once, and
        // stay dormant until a shaderpack returns.
        if (shadersDisabled.getAsBoolean())
        {
            LightRegistry.clear();

            if (!dormant)
            {
                dormant = true;
                LightBuffer.uploadEmpty();
                ShadowBaker.onShadersDisabled();
                onDormant.run();
            }

            return;
        }

        dormant = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cameraPos = camera != null ? camera.getPos() : Vec3d.ZERO;
        // Forward look vector for the shadow baker's behind-camera light cull.
        Vec3d cameraForward = camera != null ? Vec3d.fromPolar(camera.getPitch(), camera.getYaw()) : null;

        source.collect(world, cameraPos, tickDelta);

        // Bake spotlight shadow depth maps BEFORE the SSBO upload (sets each
        // spot's shadow tile index) and before Iris activates (vanilla entity
        // rendering writes into our depth FBO).
        if (world != null && camera != null)
        {
            mc.getEntityRenderDispatcher().configure(world, camera, mc.getCameraEntity());
        }
        ShadowBaker.bake(world, cameraPos, cameraForward, tickDelta);

        LightRegistry.flush();
    }
}
