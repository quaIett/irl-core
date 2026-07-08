package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.joml.Matrix4f;

/**
 * {@link OccluderBatch} backed by the raw-GL depth path's per-pass triangle
 * accumulator — the batch kind for the MC 1.21.11 line, where the 1.21.5
 * RenderPipeline + 1.21.9 EntityRenderState rewrites removed the immediate
 * {@code VertexConsumerProvider} rasterization the old {@code ImmediateOccluderBatch}
 * rode. A {@link ShadowCasterSource#emitOccluder} downcasts this batch and calls
 * {@link #append(float[])} with the caster's REAL model silhouette as world-space
 * POSITION triangles (3 floats per vertex, e.g. from the per-mod capture queue);
 * {@link ShadowRenderer} flushes the whole pass with one raw {@code glDrawArrays}
 * through its position depth program in {@link ShadowRenderer#endPass}.
 *
 * <p>The accumulator IS rewindable, so run isolation (INVARIANT 4) is by REWINDING
 * to the start-of-this-caster {@link #mark} rather than draining: on a throw the
 * shared wrapper calls {@link #terminateRun}, truncating the buffer back to the
 * mark so a half-written caster's partial vertices are dropped before the next
 * caster appends. The raw-GL caster path never touches RenderSystem matrices, so
 * it is INVARIANT-1 exempt and {@code terminateRun}'s view/proj args are ignored
 * (the pass-wide flush uploads {@code currentView}/{@code currentProj} itself).
 */
public final class RawOccluderBatch implements OccluderBatch
{
    /** The current pass's shared accumulator, (re)bound by the shared wrapper
     *  before each {@code emitOccluder}. POSITION only: 3 floats per vertex. */
    private FloatArrayList accum;
    /** Start-of-this-caster write cursor, snapshotted by {@link #mark}. */
    private int markPos;

    /** (Re)bind to the current pass's accumulator. Package-visible: only the
     *  shared {@link ShadowRenderer} wrapper binds it. */
    void bind(FloatArrayList accum)
    {
        this.accum = accum;
    }

    /**
     * Append one caster's world-space POSITION triangles (3 floats per vertex,
     * triangle-list). The source produces these from its own model capture (the
     * 1.21.11 equivalent of the removed immediate entity draw). A null/empty array
     * is a no-op — a caster that captured nothing simply casts no shadow.
     */
    public void append(float[] tris)
    {
        if (accum != null && tris != null && tris.length > 0)
        {
            accum.addElements(accum.size(), tris);
        }
    }

    /** As {@link #append(float[])} for a sub-range of {@code tris}. */
    public void append(float[] tris, int offset, int length)
    {
        if (accum != null && tris != null && length > 0)
        {
            accum.addElements(accum.size(), tris, offset, length);
        }
    }

    @Override
    public long mark()
    {
        markPos = accum == null ? 0 : accum.size();
        return markPos;
    }

    @Override
    public void terminateRun(Matrix4f view, Matrix4f proj)
    {
        // Rewind to the whole-caster boundary snapshotted in mark(): the source
        // appends only whole casters, so the mark is always at a triangle boundary.
        if (accum != null && accum.size() > markPos)
        {
            accum.size(markPos);
        }
    }
}
