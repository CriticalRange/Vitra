package com.vitra.render.bgfx;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import java.util.Collection;
import java.util.function.Supplier;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX implementation of VitraRenderPass for render operations
 */
public class VitraRenderPass implements RenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraRenderPass");

    private final String name;
    private final GpuTextureView colorView;
    private final GpuTextureView depthView;
    private final OptionalInt clearColor;
    private final OptionalDouble clearDepth;
    private short currentProgram = (short)0;
    private BgfxBuffer currentVertexBufferObj = null;
    private BgfxBuffer currentIndexBufferObj = null;
    private int currentVertexCount = 0;
    private int currentIndexCount = 0;
    private byte currentVertexSlot = 0;
    private static short defaultProgram = (short)0;

    public VitraRenderPass(String name, GpuTextureView colorView, GpuTextureView depthView, OptionalInt clearColor, OptionalDouble clearDepth) {
        this.name = name;
        this.colorView = colorView;
        this.depthView = depthView;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;

        // Initialize default shader program if needed
        initializeDefaultProgram();
        this.currentProgram = defaultProgram;
    }

    public VitraRenderPass(Supplier<String> nameSupplier, GpuTextureView colorView, OptionalInt clearColor, GpuTextureView depthView, OptionalDouble clearDepth) {
        this(nameSupplier != null ? nameSupplier.get() : "unnamed", colorView, depthView, clearColor, clearDepth);
    }

    private static synchronized void initializeDefaultProgram() {
        if (defaultProgram == (short)0) {
            try {
                defaultProgram = Util.loadProgram("basic");
                if (defaultProgram != (short)0) {
                } else {
                    LOGGER.error("Failed to create default BGFX shader program will use invalid handle");
                    defaultProgram = (short)0;
                }
            } catch (Exception e) {
                LOGGER.error("Exception during shader program creation: {}", e.getMessage());
                LOGGER.error("Will continue with invalid shader handle");
                defaultProgram = (short)0;
            }
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> name) {
        // BGFX debug group implementation
    }

    @Override
    public void popDebugGroup() {
        // BGFX debug group implementation
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        // For now, use a default shader program
        // In a full implementation, this would compile and bind the appropriate shaders
        // currentProgram = loadShaderProgram(pipeline);
    }

    // ISSUE FIX 3: Cache uniform handles to prevent memory leaks
    private final java.util.Map<String, Short> cachedUniformHandles = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void bindSampler(String name, GpuTextureView textureView) {

        if (textureView != null && textureView.texture() instanceof BgfxTexture bgfxTexture) {
            // Get the texture handle
            short textureHandle = bgfxTexture.getBgfxHandle();

            // ISSUE FIX 3: Reuse existing uniform handle or create new one
            short samplerUniform = cachedUniformHandles.computeIfAbsent(name, uniformName -> {
                short handle = BGFX.bgfx_create_uniform(uniformName, BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
                return handle;
            });

            if (samplerUniform != (short)0) {
                // Bind texture to sampler use next available texture slot
                byte textureSlot = 0; // Start with slot 0, increment as needed
                BGFX.bgfx_set_texture(textureSlot, samplerUniform, textureHandle, BGFX.BGFX_SAMPLER_NONE);

            } else {
                LOGGER.warn("Failed to create uniform handle for sampler '{}'", name);
            }
        } else {
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (buffer instanceof BgfxBuffer bgfxBuffer) {

            // ISSUE FIX 3: Reuse existing uniform handle or create new one
            int uniformType = determineUniformType(name, bgfxBuffer.size());
            short uniformHandle = cachedUniformHandles.computeIfAbsent(name, uniformName -> {
                short handle = BGFX.bgfx_create_uniform(uniformName, uniformType, 1);
                return handle;
            });

            if (uniformHandle != (short)0) {
                // Note: BGFX uniforms are set per-draw call, not per buffer binding
                // The actual uniform data will be provided during draw call submission
                // For now, we just create the uniform handle and store the buffer reference
            } else {
                LOGGER.warn("Failed to create uniform handle for '{}'", name);
            }
        } else {
        }
    }

    @Override
    public void setUniform(String name, GpuBufferSlice bufferSlice) {
        if (bufferSlice.buffer() instanceof BgfxBuffer bgfxBuffer) {

            // ISSUE FIX 3: Reuse existing uniform handle or create new one
            int uniformType = determineUniformTypeBySize(name, bufferSlice.length());
            short uniformHandle = cachedUniformHandles.computeIfAbsent(name, uniformName -> {
                short handle = BGFX.bgfx_create_uniform(uniformName, uniformType, 1);
                return handle;
            });

            if (uniformHandle != (short)0) {
                // Note: BGFX uniforms are set per-draw call, not per buffer binding
                // The actual uniform slice data will be provided during draw call submission
                // For now, we just create the uniform handle and store the buffer slice reference
            } else {
                LOGGER.warn("Failed to create uniform handle for buffer slice '{}'", name);
            }
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        BGFX.bgfx_set_view_scissor(0, (short)x, (short)y, (short)width, (short)height);
    }

    @Override
    public void disableScissor() {
        // Reset scissor to full view
        BGFX.bgfx_set_view_scissor(0, (short)0, (short)0, (short)0, (short)0);
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        if (buffer instanceof BgfxBuffer bgfxBuffer) {
            currentVertexBufferObj = bgfxBuffer;
            currentVertexCount = estimateVertexCount(bgfxBuffer);
            currentVertexSlot = (byte)slot;
            // Don't set the buffer here - it will be set right before submit in drawIndexed/draw
        } else {
            LOGGER.warn("Expected BgfxBuffer, got: {}", buffer.getClass());
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        if (buffer instanceof BgfxBuffer bgfxBuffer) {
            currentIndexBufferObj = bgfxBuffer;
            boolean is32bit = indexType == VertexFormat.IndexType.INT;
            currentIndexCount = estimateIndexCount(bgfxBuffer, is32bit);
            // Don't set the buffer here - it will be set right before submit in drawIndexed
        } else {
            LOGGER.warn("Expected BgfxBuffer, got: {}", buffer.getClass());
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex) {

        // CRITICAL FIX: Parameter mapping issue detection
        // Minecraft's GL VitraRenderPass calls have different parameter meaning:
        // Expected BGFX: (indexCount, instanceCount, firstIndex, baseVertex)
        // Actual MC GL:  (0, 0, actualIndexCount, baseVertex)
        int actualIndexCount = indexCount;
        if (indexCount == 0 && firstIndex > 0) {
            actualIndexCount = firstIndex;
        } else if (indexCount == 0 && instanceCount > 0) {
            actualIndexCount = instanceCount;
        }

        // Clear the view first if needed
        if (clearColor.isPresent() || clearDepth.isPresent()) {
            int clearFlags = 0;
            int color = 0xFF000000; // Black in ABGR format for normal rendering
            float depth = 1.0f;

            if (clearColor.isPresent()) {
                clearFlags |= BGFX.BGFX_CLEAR_COLOR;
                color = clearColor.getAsInt();
            }
            if (clearDepth.isPresent()) {
                clearFlags |= BGFX.BGFX_CLEAR_DEPTH;
                depth = (float)clearDepth.getAsDouble();
            }

            BGFX.bgfx_set_view_clear(0, clearFlags, color, depth, (byte)0);
        }

        // Only submit if we have valid geometry to draw using corrected index count
        if (actualIndexCount > 0 && currentVertexBufferObj != null && currentIndexBufferObj != null) {
            // Set vertex buffer RIGHT BEFORE submit (BGFX requires this)
            short vbHandle = currentVertexBufferObj.getBgfxHandle();
            if (currentVertexBufferObj.getType() == BgfxBuffer.BufferType.DYNAMIC_VERTEX_BUFFER ||
                currentVertexBufferObj.getType() == BgfxBuffer.BufferType.UNIFORM_BUFFER) {
                BGFX.bgfx_set_dynamic_vertex_buffer(currentVertexSlot, vbHandle, 0, currentVertexCount);
            } else {
                BGFX.bgfx_set_vertex_buffer(currentVertexSlot, vbHandle, 0, currentVertexCount);
            }

            // Set index buffer RIGHT BEFORE submit (BGFX requires this)
            short ibHandle = currentIndexBufferObj.getBgfxHandle();
            if (currentIndexBufferObj.getType() == BgfxBuffer.BufferType.DYNAMIC_INDEX_BUFFER) {
                BGFX.bgfx_set_dynamic_index_buffer(ibHandle, 0, currentIndexCount);
            } else {
                BGFX.bgfx_set_index_buffer(ibHandle, 0, currentIndexCount);
            }

            // Set render state for this draw call
            long state = 0
                | BGFX.BGFX_STATE_WRITE_RGB
                | BGFX.BGFX_STATE_WRITE_A
                | BGFX.BGFX_STATE_WRITE_Z
                | BGFX.BGFX_STATE_DEPTH_TEST_LESS
                | BGFX.BGFX_STATE_MSAA;
            BGFX.bgfx_set_state(state, 0);

            // Submit the indexed draw call
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        } else if (actualIndexCount == 0) {
            LOGGER.warn("SKIPPING DRAW actualIndexCount=0 (no geometry to render)");
        } else if (currentIndexBufferObj == null) {
            LOGGER.warn("SKIPPING DRAW invalid index buffer handle");
        } else if (currentVertexBufferObj == null) {
            LOGGER.warn("SKIPPING DRAW invalid vertex buffer handle");
        }
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer, VertexFormat.IndexType indexType, Collection<String> uniformNames, T uniformData) {
        // BGFX multiple indexed draw implementation
    }

    @Override
    public void draw(int vertexCount, int firstVertex) {

        // Clear the view first if needed
        if (clearColor.isPresent() || clearDepth.isPresent()) {
            int clearFlags = 0;
            int color = 0xFF000000; // Black in ABGR format for normal rendering
            float depth = 1.0f;

            if (clearColor.isPresent()) {
                clearFlags |= BGFX.BGFX_CLEAR_COLOR;
                color = clearColor.getAsInt();
            }
            if (clearDepth.isPresent()) {
                clearFlags |= BGFX.BGFX_CLEAR_DEPTH;
                depth = (float)clearDepth.getAsDouble();
            }

            BGFX.bgfx_set_view_clear(0, clearFlags, color, depth, (byte)0);
        }

        // Only submit if we have valid geometry to draw
        if (vertexCount > 0 && currentVertexBufferObj != null) {
            // Set vertex buffer RIGHT BEFORE submit (BGFX requires this)
            short vbHandle = currentVertexBufferObj.getBgfxHandle();
            if (currentVertexBufferObj.getType() == BgfxBuffer.BufferType.DYNAMIC_VERTEX_BUFFER ||
                currentVertexBufferObj.getType() == BgfxBuffer.BufferType.UNIFORM_BUFFER) {
                BGFX.bgfx_set_dynamic_vertex_buffer(currentVertexSlot, vbHandle, 0, currentVertexCount);
            } else {
                BGFX.bgfx_set_vertex_buffer(currentVertexSlot, vbHandle, 0, currentVertexCount);
            }

            // Set render state for this draw call
            long state = 0
                | BGFX.BGFX_STATE_WRITE_RGB
                | BGFX.BGFX_STATE_WRITE_A
                | BGFX.BGFX_STATE_WRITE_Z
                | BGFX.BGFX_STATE_DEPTH_TEST_LESS
                | BGFX.BGFX_STATE_MSAA;
            BGFX.bgfx_set_state(state, 0);

            // Submit the non-indexed draw call
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        } else if (vertexCount == 0) {
            LOGGER.warn("SKIPPING DRAW vertexCount=0 (no geometry to render)");
        } else if (currentVertexBufferObj == null) {
            LOGGER.warn("SKIPPING DRAW invalid vertex buffer handle");
        }
    }

    @Override
    public void close() {
        // ISSUE FIX 3: Clean up cached uniform handles to prevent memory leaks
        for (java.util.Map.Entry<String, Short> entry : cachedUniformHandles.entrySet()) {
            try {
                if (entry.getValue() != (short)0) {
                    BGFX.bgfx_destroy_uniform(entry.getValue());
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy uniform '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        cachedUniformHandles.clear();
    }

    /**
     * Determine BGFX uniform type based on uniform name and buffer size
     */
    private int determineUniformType(String name, long bufferSize) {
        // Matrix uniforms (common in 3D graphics)
        if (name.toLowerCase().contains("matrix") || name.toLowerCase().contains("mvp") ||
            name.toLowerCase().contains("modelview") || name.toLowerCase().contains("projection")) {
            return BGFX.BGFX_UNIFORM_TYPE_MAT4; // 4x4 matrix
        }

        // Based on buffer size (in bytes)
        return determineUniformTypeBySize(name, bufferSize);
    }

    /**
     * Determine BGFX uniform type based on buffer size
     */
    private int determineUniformTypeBySize(String name, long sizeInBytes) {

        if (sizeInBytes >= 64) { // 4x4 matrix (16 floats * 4 bytes = 64)
            return BGFX.BGFX_UNIFORM_TYPE_MAT4;
        } else if (sizeInBytes >= 48) { // 3x4 matrix
            return BGFX.BGFX_UNIFORM_TYPE_MAT3;
        } else if (sizeInBytes >= 16) { // vec4 (4 floats * 4 bytes = 16)
            return BGFX.BGFX_UNIFORM_TYPE_VEC4;
        } else if (sizeInBytes >= 12) { // vec3 (3 floats * 4 bytes = 12)
            return BGFX.BGFX_UNIFORM_TYPE_VEC4; // BGFX pads vec3 to vec4
        } else if (sizeInBytes >= 8) { // vec2 (2 floats * 4 bytes = 8)
            return BGFX.BGFX_UNIFORM_TYPE_VEC4; // BGFX pads vec2 to vec4
        } else { // Default to vec4 for smaller sizes
            return BGFX.BGFX_UNIFORM_TYPE_VEC4;
        }
    }

    // Static methods for texture management (called from VitraRenderer)
    private static BgfxTexture lastRenderedColorTexture = null;

    public static void clearLastRenderedTexture() {
        lastRenderedColorTexture = null;
    }

    public static BgfxTexture getLastRenderedColorTexture() {
        return lastRenderedColorTexture;
    }

    public static void setLastRenderedColorTexture(BgfxTexture texture) {
        lastRenderedColorTexture = texture;
    }

    /**
     * Estimate vertex count from buffer size
     */
    private int estimateVertexCount(BgfxBuffer buffer) {
        // Based on our vertex layout: position(3) + texcoord(2) + color(4) = 9 floats = 36 bytes
        int vertexStride = 36; // bytes per vertex
        return (int)(buffer.size() / vertexStride);
    }

    /**
     * Estimate index count from buffer size
     */
    private int estimateIndexCount(BgfxBuffer buffer, boolean is32bit) {
        int indexSize = is32bit ? 4 : 2; // bytes per index
        return (int)(buffer.size() / indexSize);
    }
}