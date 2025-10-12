package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages draw call submission for BGFX rendering.
 *
 * This class uses ONLY BGFX's native methods:
 * - bgfx_set_vertex_buffer() - Bind vertex buffer for draw
 * - bgfx_set_index_buffer() - Bind index buffer for draw
 * - bgfx_set_state() - Set render state for draw
 * - bgfx_set_texture() - Bind texture for draw
 * - bgfx_submit() - Submit draw call to GPU
 * - bgfx_set_transient_vertex_buffer() - Bind transient vertex data
 * - bgfx_set_transient_index_buffer() - Bind transient index data
 * - bgfx_discard() - Discard pending draw state
 *
 * NO custom draw call batching or custom implementations.
 * All draw operations are delegated to BGFX's native API.
 */
public class BgfxDrawCallManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxDrawCallManager");

    /**
     * Submit a draw call using vertex and index buffers.
     * Uses: bgfx_set_vertex_buffer(), bgfx_set_index_buffer(), bgfx_set_state(), bgfx_submit()
     *
     * This method ONLY calls BGFX's native APIs in the correct order.
     *
     * @param viewId View ID (render pass)
     * @param programHandle BGFX program handle
     * @param vertexBufferHandle BGFX vertex buffer handle
     * @param indexBufferHandle BGFX index buffer handle
     * @param state BGFX state flags (from BgfxStateTracker)
     * @param startVertex Starting vertex index
     * @param numVertices Number of vertices
     * @param startIndex Starting index
     * @param numIndices Number of indices
     */
    public void submitIndexed(int viewId, short programHandle, short vertexBufferHandle, short indexBufferHandle,
                               long state, int startVertex, int numVertices, int startIndex, int numIndices) {
        // Set render state using BGFX native method
        BGFX.bgfx_set_state(state, 0);

        // Bind vertex buffer using BGFX native method
        BGFX.bgfx_set_vertex_buffer(0, vertexBufferHandle, startVertex, numVertices);

        // Bind index buffer using BGFX native method
        BGFX.bgfx_set_index_buffer(indexBufferHandle, startIndex, numIndices);

        // Submit draw call using BGFX native method (viewId, program, depth, flags)
        BGFX.bgfx_submit(viewId, programHandle, 0, BGFX.BGFX_DISCARD_NONE);

        LOGGER.trace("bgfx_submit(view={}, program={}, verts={}, indices={})",
            viewId, programHandle, numVertices, numIndices);
    }

    /**
     * Submit a draw call using only vertex buffer (non-indexed).
     * Uses: bgfx_set_vertex_buffer(), bgfx_set_state(), bgfx_submit()
     *
     * @param viewId View ID (render pass)
     * @param programHandle BGFX program handle
     * @param vertexBufferHandle BGFX vertex buffer handle
     * @param state BGFX state flags (from BgfxStateTracker)
     * @param startVertex Starting vertex index
     * @param numVertices Number of vertices
     */
    public void submitNonIndexed(int viewId, short programHandle, short vertexBufferHandle,
                                  long state, int startVertex, int numVertices) {
        // Set render state using BGFX native method
        BGFX.bgfx_set_state(state, 0);

        // Bind vertex buffer using BGFX native method
        BGFX.bgfx_set_vertex_buffer(0, vertexBufferHandle, startVertex, numVertices);

        // Submit draw call using BGFX native method (viewId, program, depth, flags)
        BGFX.bgfx_submit(viewId, programHandle, 0, BGFX.BGFX_DISCARD_NONE);

        LOGGER.trace("bgfx_submit(view={}, program={}, verts={})", viewId, programHandle, numVertices);
    }

    /**
     * Submit a draw call using transient vertex and index data.
     * Uses: bgfx_set_transient_vertex_buffer(), bgfx_set_transient_index_buffer(), bgfx_set_state(), bgfx_submit()
     *
     * Transient buffers are single-frame data managed internally by BGFX.
     *
     * @param viewId View ID (render pass)
     * @param programHandle BGFX program handle
     * @param tvb Transient vertex buffer (from bgfx_alloc_transient_vertex_buffer)
     * @param tib Transient index buffer (from bgfx_alloc_transient_index_buffer)
     * @param state BGFX state flags (from BgfxStateTracker)
     */
    public void submitTransient(int viewId, short programHandle,
                                 org.lwjgl.bgfx.BGFXTransientVertexBuffer tvb,
                                 org.lwjgl.bgfx.BGFXTransientIndexBuffer tib,
                                 long state) {
        // Set render state using BGFX native method
        BGFX.bgfx_set_state(state, 0);

        // Bind transient vertex buffer using BGFX native method
        BGFX.bgfx_set_transient_vertex_buffer(0, tvb, 0, -1);

        // Bind transient index buffer using BGFX native method
        BGFX.bgfx_set_transient_index_buffer(tib, 0, -1);

        // Submit draw call using BGFX native method (viewId, program, depth, flags)
        BGFX.bgfx_submit(viewId, programHandle, 0, BGFX.BGFX_DISCARD_NONE);

        LOGGER.trace("bgfx_submit(view={}, program={}, transient)", viewId, programHandle);
    }

    /**
     * Discard pending draw state without submitting.
     * Uses: bgfx_discard()
     *
     * Use this to cancel a draw call setup if rendering is aborted.
     */
    public void discard() {
        BGFX.bgfx_discard(0);
        LOGGER.trace("bgfx_discard()");
    }

    /**
     * Set render state before draw call.
     * Uses: bgfx_set_state()
     *
     * Convenience method for setting state separately from submit.
     *
     * @param state BGFX state flags (from BgfxStateTracker.getCurrentState())
     * @param rgba RGBA value for blend factor (usually 0)
     */
    public void setState(long state, int rgba) {
        BGFX.bgfx_set_state(state, rgba);
        LOGGER.trace("bgfx_set_state(0x{}, rgba=0x{})", Long.toHexString(state), Integer.toHexString(rgba));
    }

    /**
     * Set scissor rectangle for draw call.
     * Uses: bgfx_set_scissor()
     *
     * @param x Scissor X position
     * @param y Scissor Y position
     * @param width Scissor width
     * @param height Scissor height
     * @return Scissor cache index for bgfx_set_scissor_cached()
     */
    public short setScissor(int x, int y, int width, int height) {
        short cache = BGFX.bgfx_set_scissor(x, y, width, height);
        LOGGER.trace("bgfx_set_scissor({}, {}, {}, {}) -> cache={}", x, y, width, height, cache);
        return cache;
    }
}
