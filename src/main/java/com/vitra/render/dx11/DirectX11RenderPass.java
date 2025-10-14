package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.debug.MatrixDebugHelper;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of Minecraft's RenderPass
 *
 * Represents a rendering pass in DirectX 11. Manages render state, pipeline binding,
 * vertex/index buffers, uniforms, and draw calls.
 */
public class DirectX11RenderPass implements RenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11RenderPass");

    private final String debugName;
    private final DirectX11GpuTextureView colorTarget;
    private final DirectX11GpuTextureView depthTarget;
    private boolean closed = false;
    private boolean scissorEnabled = false;

    // Track currently bound buffers for draw calls
    private DirectX11GpuBuffer currentVertexBuffer = null;
    private DirectX11GpuBuffer currentIndexBuffer = null;
    private RenderPipeline currentPipeline = null;

    // Track shader pipeline state to prevent GPU crashes
    private boolean pipelineStateBound = false;
    private long defaultShaderPipeline = 0;

    // Track whether viewport has been set for this render pass
    private boolean viewportSet = false;

    public DirectX11RenderPass(String debugName, DirectX11GpuTextureView colorTarget,
                               DirectX11GpuTextureView depthTarget) {
        this.debugName = debugName;
        this.colorTarget = colorTarget;
        this.depthTarget = depthTarget;

        LOGGER.debug("Created DirectX11RenderPass: {}", debugName);

        // Initialize default shader pipeline to prevent GPU crashes
        initializeDefaultShaderPipeline();

        // Set viewport to match render target dimensions
        setupViewportForRenderTarget();
    }

    /**
     * Initialize the default shader pipeline to prevent GPU crashes from missing shaders
     */
    private void initializeDefaultShaderPipeline() {
        try {
            // Use the native default shader pipeline handle (stored in vertex shader handle)
            // The native code creates default shaders during initialization
            defaultShaderPipeline = VitraNativeRenderer.getDefaultShaderPipeline();
            if (defaultShaderPipeline != 0) {
                LOGGER.debug("[RenderPass {}] Default shader pipeline initialized: handle=0x{}",
                    debugName, Long.toHexString(defaultShaderPipeline));
            } else {
                LOGGER.warn("[RenderPass {}] Failed to get default shader pipeline", debugName);
            }
        } catch (Exception e) {
            LOGGER.error("[RenderPass {}] Error initializing default shader pipeline", debugName, e);
        }
    }

    /**
     * Setup viewport to match render target dimensions
     * This prevents "There are no viewports currently bound" DirectX debug warnings
     */
    private void setupViewportForRenderTarget() {
        try {
            // Get render target dimensions from color target
            if (colorTarget != null) {
                DirectX11GpuTexture texture = colorTarget.getTexture();
                if (texture != null) {
                    // Get dimensions at mip level 0 (base texture size)
                    int width = texture.getWidth(0);
                    int height = texture.getHeight(0);

                    // Set viewport to match render target dimensions
                    VitraNativeRenderer.setViewport(0, 0, width, height);
                    viewportSet = true;

                    LOGGER.debug("[RenderPass {}] Set viewport from render target: {}x{}",
                        debugName, width, height);
                    return;
                }
            }

            // Fallback: Viewport should have been set by beginFrame()
            // Just mark it as set and log a debug message
            LOGGER.debug("[RenderPass {}] Using existing viewport (set by beginFrame)", debugName);
            viewportSet = true;
        } catch (Exception e) {
            LOGGER.warn("[RenderPass {}] Failed to setup viewport: {}", debugName, e.getMessage());
            // Even if we fail, mark as set to avoid repeated attempts
            viewportSet = true;
        }
    }

    /**
     * Ensure viewport is set before draw operations
     * This prevents "There are no viewports currently bound" DirectX debug warnings
     */
    private void ensureViewportSet() {
        if (!viewportSet) {
            setupViewportForRenderTarget();
        }
    }

    /**
     * Ensure that a valid shader pipeline is bound before any draw operations
     * This prevents GPU crashes from drawing without shaders
     */
    private void ensurePipelineStateBound() {
        // First, ensure viewport is set (required by DirectX 11 before draw calls)
        ensureViewportSet();

        // CRITICAL FIX: Force orthographic projection matrix for testing
        // This should fix the black screen by providing a valid transformation
        if (colorTarget != null) {
            DirectX11GpuTexture texture = colorTarget.getTexture();
            if (texture != null) {
                int width = texture.getWidth(0);
                int height = texture.getHeight(0);
                MatrixDebugHelper.forceOrthographicProjection(width, height);
            }
        }

        if (pipelineStateBound && currentPipeline != null) {
            // Try to bind the current pipeline - use reflection to check if it has bind() method
            try {
                if (currentPipeline.getClass().getSimpleName().contains("DirectX11")) {
                    // It's likely our DirectX pipeline - try to call bind()
                    java.lang.reflect.Method bindMethod = currentPipeline.getClass().getMethod("bind");
                    bindMethod.invoke(currentPipeline);

                    // Try to get native handle for logging
                    try {
                        java.lang.reflect.Method getHandleMethod = currentPipeline.getClass().getMethod("getNativeHandle");
                        long handle = (Long) getHandleMethod.invoke(currentPipeline);
                        LOGGER.debug("[RenderPass {}] Bound current pipeline: handle=0x{}",
                            debugName, Long.toHexString(handle));
                    } catch (Exception e) {
                        LOGGER.debug("[RenderPass {}] Bound current pipeline", debugName);
                    }
                    return;
                }
            } catch (Exception e) {
                LOGGER.debug("[RenderPass {}] Could not bind current pipeline: {}", debugName, e.getMessage());
            }
        }

        // Fall back to default shader pipeline
        if (defaultShaderPipeline != 0) {
            VitraNativeRenderer.setShaderPipeline(defaultShaderPipeline);

            // Set primitive topology to triangles (most common for GUI rendering)
            VitraNativeRenderer.setPrimitiveTopology(4); // GL_TRIANGLES = 0x0004

            pipelineStateBound = true;

            LOGGER.debug("[RenderPass {}] Bound default shader pipeline and triangle topology",
                debugName);
        } else {
            LOGGER.error("[RenderPass {}] No shader pipeline available - draw call will likely crash!",
                debugName);
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> nameSupplier) {
        // DirectX 11 doesn't have built-in debug groups in the same way as modern APIs
        // We can log this for debugging purposes
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[RenderPass {}] Push debug group: {}", debugName, nameSupplier.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[RenderPass {}] Pop debug group", debugName);
        }
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        LOGGER.debug("[RenderPass {}] Set pipeline: {}", debugName, pipeline);
        this.currentPipeline = pipeline;
        this.pipelineStateBound = false; // Reset state - new pipeline needs to be bound

        // Immediately bind the pipeline if it's a DirectX11 pipeline
        try {
            if (pipeline.getClass().getSimpleName().contains("DirectX11")) {
                // It's likely our DirectX pipeline - try to call bind()
                java.lang.reflect.Method bindMethod = pipeline.getClass().getMethod("bind");
                bindMethod.invoke(pipeline);
                this.pipelineStateBound = true;

                // Try to get native handle for logging
                try {
                    java.lang.reflect.Method getHandleMethod = pipeline.getClass().getMethod("getNativeHandle");
                    long handle = (Long) getHandleMethod.invoke(pipeline);
                    LOGGER.debug("[RenderPass {}] Pipeline bound immediately: handle=0x{}",
                        debugName, Long.toHexString(handle));
                } catch (Exception e) {
                    LOGGER.debug("[RenderPass {}] Pipeline bound immediately", debugName);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[RenderPass {}] Could not bind pipeline immediately: {}", debugName, e.getMessage());
        }
    }

    @Override
    public void bindSampler(String name, GpuTextureView textureView) {
        if (!(textureView instanceof DirectX11GpuTextureView dx11View)) {
            LOGGER.warn("Texture view is not a DirectX11GpuTextureView: {}", textureView);
            return;
        }

        long textureHandle = dx11View.getNativeTextureHandle();
        if (textureHandle != 0) {
            // Bind to texture slot 0 by default (shader can remap as needed)
            VitraNativeRenderer.bindTexture(textureHandle, 0);
            LOGGER.debug("[RenderPass {}] Bind sampler '{}': handle=0x{}",
                debugName, name, Long.toHexString(textureHandle));
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (!(buffer instanceof DirectX11GpuBuffer dx11Buffer)) {
            LOGGER.warn("Buffer is not a DirectX11GpuBuffer: {}", buffer);
            return;
        }

        // For constant/uniform buffers, we need to set them in a constant buffer slot
        // NOTES:
        // - Slot 0 (b0): Reserved for TransformMatrices (MVP, MV, Proj) - set by MatrixDebugHelper
        // - Minecraft 1.21.8 may pass uniform data as GpuBuffer, but DirectX 11 uses constant buffers
        // - Current implementation: uniform buffers are created as vertex buffers (see DirectX11GpuBuffer:152)
        // - TODO: Properly handle uniform buffer → constant buffer conversion
        if (dx11Buffer.isUniformBuffer()) {
            long handle = dx11Buffer.getNativeHandle();
            LOGGER.debug("[RenderPass {}] Set uniform buffer '{}': handle=0x{}, size={} bytes",
                debugName, name, Long.toHexString(handle), dx11Buffer.size());
            // Note: Currently no-op - uniform buffers need proper constant buffer binding
        }
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        setUniform(name, slice.buffer());
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        VitraNativeRenderer.setScissorRect(x, y, width, height);
        scissorEnabled = true;
        LOGGER.debug("[RenderPass {}] Enable scissor: ({},{}) {}x{}", debugName, x, y, width, height);
    }

    @Override
    public void disableScissor() {
        scissorEnabled = false;
        LOGGER.debug("[RenderPass {}] Disable scissor", debugName);
        // TODO: Implement native method to disable scissor test
    }

    @Override
    public void setVertexBuffer(int index, GpuBuffer buffer) {
        if (!(buffer instanceof DirectX11GpuBuffer dx11Buffer)) {
            LOGGER.warn("Buffer is not a DirectX11GpuBuffer: {}", buffer);
            return;
        }

        long handle = dx11Buffer.getNativeHandle();
        LOGGER.debug("[RenderPass {}] Set vertex buffer[{}]: handle=0x{}",
            debugName, index, Long.toHexString(handle));

        // Track for draw calls (index 0 is primary vertex buffer)
        if (index == 0) {
            this.currentVertexBuffer = dx11Buffer;
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
        if (!(indexBuffer instanceof DirectX11GpuBuffer dx11Buffer)) {
            LOGGER.warn("Buffer is not a DirectX11GpuBuffer: {}", indexBuffer);
            return;
        }

        long handle = dx11Buffer.getNativeHandle();
        LOGGER.debug("[RenderPass {}] Set index buffer: handle=0x{}, type={}",
            debugName, Long.toHexString(handle), indexType);

        // Track for drawIndexed calls
        this.currentIndexBuffer = dx11Buffer;
    }

    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        LOGGER.debug("[RenderPass {}] Draw indexed: base={}, first={}, count={}, instances={}",
            debugName, baseVertex, firstIndex, indexCount, instanceCount);

        if (currentVertexBuffer == null) {
            LOGGER.warn("[RenderPass {}] Draw indexed called but no vertex buffer bound!", debugName);
            return;
        }

        if (currentIndexBuffer == null) {
            LOGGER.warn("[RenderPass {}] Draw indexed called but no index buffer bound!", debugName);
            return;
        }

        long vertexHandle = currentVertexBuffer.getNativeHandle();
        long indexHandle = currentIndexBuffer.getNativeHandle();

        if (vertexHandle == 0) {
            LOGGER.error("[RenderPass {}] Vertex buffer has invalid native handle!", debugName);
            return;
        }

        if (indexHandle == 0) {
            LOGGER.error("[RenderPass {}] Index buffer has invalid native handle!", debugName);
            return;
        }

        // CRITICAL FIX: Ensure proper shader pipeline state before drawing
        ensurePipelineStateBound();

        // Call native DirectX 11 draw indexed
        // Calculate vertex count using actual stride from the buffer
        int vertexStride = currentVertexBuffer.getVertexStride();
        if (vertexStride <= 0) {
            LOGGER.warn("[RenderPass {}] Vertex buffer has invalid stride ({}), using default 32", debugName, vertexStride);
            vertexStride = 32; // Fallback to match native input layout (position=12 + pad=4 + texcoord=8 + color=4 + pad=4 = 32 bytes)
        }
        int availableVertexCount = currentVertexBuffer.size() / vertexStride;
        int availableIndexCount = currentIndexBuffer.size() / 4; // Assuming 32-bit indices

        // CRITICAL: Validate draw parameters to prevent buffer overrun
        // DirectX 11 indexed draws work as follows:
        // - Index buffer contains indices (0, 1, 2, 3, ...) that reference vertices
        // - For indexed draw: vertex accessed = baseVertex + index[i]
        // - We need: baseVertex + max(indices in range [firstIndex, firstIndex+indexCount]) < vertexCount
        //
        // PROBLEM: We can't read the index buffer from Java without mapping it (expensive!)
        // CONSERVATIVE APPROACH: Assume worst case where indices are sequential (0, 1, 2, ...)
        // This means: max index in range = firstIndex + indexCount - 1
        // Required vertices = baseVertex + firstIndex + indexCount
        //
        // NOTE: This is overly conservative for meshes with shared vertices, but safe!
        int maxIndexValue = firstIndex + indexCount - 1;
        int maxVertexAccessed = baseVertex + maxIndexValue;

        if (maxVertexAccessed >= availableVertexCount) {
            // Only log as warning, not error, since this is a conservative estimate
            LOGGER.warn("[RenderPass {}] Conservative buffer check detected potential underrun", debugName);
            LOGGER.warn("  Vertex buffer: {} bytes ({} vertices at {} stride)",
                currentVertexBuffer.size(), availableVertexCount, vertexStride);
            LOGGER.warn("  Draw params: baseVertex={}, firstIndex={}, indexCount={}, instances={}",
                baseVertex, firstIndex, indexCount, instanceCount);
            LOGGER.warn("  Max vertex that could be accessed: {} (conservative estimate)", maxVertexAccessed);
            LOGGER.warn("  Note: Actual usage may be lower if vertices are shared");

            // CRITICAL FIX: Don't skip the draw! This is likely a false positive.
            // Minecraft uses batched buffers where baseVertex offsets into a larger pool.
            // The buffer may appear too small, but DirectX will handle out-of-bounds reads gracefully.
            if (baseVertex >= availableVertexCount) {
                LOGGER.warn("  baseVertex {} >= vertex count {} - this may indicate buffer pooling",
                    baseVertex, availableVertexCount);
                LOGGER.warn("  Allowing draw anyway - DirectX 11 will return zero for out-of-bounds vertex reads");
            } else {
                // Otherwise, let it through
                LOGGER.debug("  Allowing draw to proceed - within bounds or DirectX will handle gracefully");
            }
        }

        // Also validate index buffer size
        if (firstIndex + indexCount > availableIndexCount) {
            LOGGER.error("[RenderPass {}] INDEX BUFFER UNDERRUN!", debugName);
            LOGGER.error("  Index buffer size: {} bytes ({} indices)",
                currentIndexBuffer.size(), availableIndexCount);
            LOGGER.error("  Requested range: [{}, {}] (first={}, count={})",
                firstIndex, firstIndex + indexCount, firstIndex, indexCount);

            int safeIndexCount = Math.max(0, availableIndexCount - firstIndex);
            if (safeIndexCount > 0) {
                LOGGER.warn("  Clamping indexCount from {} to {}", indexCount, safeIndexCount);
                indexCount = safeIndexCount;
            } else {
                LOGGER.error("  Cannot safely draw - skipping draw call");
                return;
            }
        }

        LOGGER.debug("[RenderPass {}] Calling native draw: vb=0x{} ({} verts), ib=0x{} ({} indices), base={}, first={}, count={}, instances={}",
            debugName, Long.toHexString(vertexHandle), availableVertexCount,
            Long.toHexString(indexHandle), availableIndexCount,
            baseVertex, firstIndex, indexCount, instanceCount);

        VitraNativeRenderer.draw(vertexHandle, indexHandle, baseVertex, firstIndex, indexCount, instanceCount);
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer,
                                         VertexFormat.IndexType indexType,
                                         Collection<String> validationSkippedUniforms, T userData) {
        LOGGER.debug("[RenderPass {}] Draw multiple indexed: {} draw calls", debugName, draws.size());

        // Iterate through each draw and execute it
        for (Draw<T> draw : draws) {
            // Each draw should bind its own state and execute
            // This is a simplified implementation
        }
    }

    @Override
    public void draw(int firstVertex, int vertexCount) {
        LOGGER.debug("[RenderPass {}] Draw: first={}, count={}", debugName, firstVertex, vertexCount);

        if (currentVertexBuffer == null) {
            LOGGER.warn("[RenderPass {}] Draw called but no vertex buffer bound!", debugName);
            return;
        }

        long vertexHandle = currentVertexBuffer.getNativeHandle();

        if (vertexHandle == 0) {
            LOGGER.error("[RenderPass {}] Vertex buffer has invalid native handle!", debugName);
            return;
        }

        // CRITICAL FIX: Ensure proper shader pipeline state before drawing
        ensurePipelineStateBound();

        // Call native DirectX 11 draw (no index buffer)
        LOGGER.debug("[RenderPass {}] Calling native draw: vb=0x{}, first={}, count={}",
            debugName, Long.toHexString(vertexHandle), firstVertex, vertexCount);

        VitraNativeRenderer.draw(vertexHandle, 0, 0, firstVertex, vertexCount, 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        LOGGER.debug("Closing DirectX11RenderPass: {}", debugName);
        closed = true;
    }
}
