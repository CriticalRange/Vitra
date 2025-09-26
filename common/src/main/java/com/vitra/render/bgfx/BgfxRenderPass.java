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
 * BGFX implementation of RenderPass for render operations
 */
public class BgfxRenderPass implements RenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxRenderPass");

    private final String name;
    private final GpuTextureView colorView;
    private final GpuTextureView depthView;
    private final OptionalInt clearColor;
    private final OptionalDouble clearDepth;
    private short currentProgram = BGFX.BGFX_INVALID_HANDLE;
    private short currentVertexBuffer = BGFX.BGFX_INVALID_HANDLE;
    private short currentIndexBuffer = BGFX.BGFX_INVALID_HANDLE;
    private static short defaultProgram = BGFX.BGFX_INVALID_HANDLE;

    public BgfxRenderPass(String name, GpuTextureView colorView, GpuTextureView depthView, OptionalInt clearColor, OptionalDouble clearDepth) {
        this.name = name;
        this.colorView = colorView;
        this.depthView = depthView;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;

        // Initialize default shader program if needed
        initializeDefaultProgram();
        this.currentProgram = defaultProgram;
    }

    private static synchronized void initializeDefaultProgram() {
        if (defaultProgram == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.info("Initializing default BGFX shader program");
            try {
                defaultProgram = BgfxShaderCompiler.createBasicProgram();
                if (defaultProgram != BGFX.BGFX_INVALID_HANDLE) {
                    LOGGER.info("Default BGFX shader program created: handle {}", defaultProgram);
                } else {
                    LOGGER.error("Failed to create default BGFX shader program - will use invalid handle");
                    defaultProgram = BGFX.BGFX_INVALID_HANDLE;
                }
            } catch (Exception e) {
                LOGGER.error("Exception during shader program creation: {}", e.getMessage());
                LOGGER.error("Will continue with invalid shader handle");
                defaultProgram = BGFX.BGFX_INVALID_HANDLE;
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
        LOGGER.debug("Setting BGFX pipeline: {}", pipeline);
        // For now, use a default shader program
        // In a full implementation, this would compile and bind the appropriate shaders
        // currentProgram = loadShaderProgram(pipeline);
    }

    @Override
    public void bindSampler(String name, GpuTextureView textureView) {
        LOGGER.debug("Binding BGFX sampler '{}' with texture view: {}", name, textureView);

        if (textureView != null && textureView.texture() instanceof BgfxGpuTexture bgfxTexture) {
            // Get the texture handle
            short textureHandle = bgfxTexture.getBgfxHandle();
            LOGGER.debug("Binding BGFX texture handle {} to sampler slot 0", textureHandle);

            // Bind texture to sampler slot 0 (assuming first texture slot for now)
            BGFX.bgfx_set_texture(0, BGFX.bgfx_create_uniform(name, BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1), textureHandle, BGFX.BGFX_SAMPLER_NONE);
        } else {
            LOGGER.debug("No BGFX texture to bind for sampler '{}'", name);
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (buffer instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("Setting BGFX uniform '{}' with buffer handle: {}", name, bgfxBuffer.getBgfxHandle());

            // Create uniform and set buffer
            short uniform = BGFX.bgfx_create_uniform(name, BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
            // For uniform buffers, we need to update the buffer data first
            // This is simplified - in practice we'd need proper uniform buffer management
        } else {
            LOGGER.debug("Expected BgfxGpuBuffer for uniform '{}', got: {}", name, buffer.getClass());
        }
    }

    @Override
    public void setUniform(String name, GpuBufferSlice bufferSlice) {
        if (bufferSlice.buffer() instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("Setting BGFX uniform '{}' with buffer slice: handle {}, offset: {}, length: {}",
                        name, bgfxBuffer.getBgfxHandle(), bufferSlice.offset(), bufferSlice.length());
            // Similar to above - would need proper uniform buffer slice handling
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        LOGGER.debug("Enabling BGFX scissor: {}x{} at ({},{})", width, height, x, y);
        BGFX.bgfx_set_view_scissor(0, (short)x, (short)y, (short)width, (short)height);
    }

    @Override
    public void disableScissor() {
        LOGGER.debug("Disabling BGFX scissor");
        // Reset scissor to full view
        BGFX.bgfx_set_view_scissor(0, (short)0, (short)0, (short)0, (short)0);
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        if (buffer instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("Setting BGFX vertex buffer at slot {}: handle {}", slot, bgfxBuffer.getBgfxHandle());
            currentVertexBuffer = bgfxBuffer.getBgfxHandle();

            // Set vertex buffer for BGFX
            BGFX.bgfx_set_vertex_buffer((byte)slot, bgfxBuffer.getBgfxHandle(), 0, BGFX.BGFX_INVALID_HANDLE);
        } else {
            LOGGER.warn("Expected BgfxGpuBuffer, got: {}", buffer.getClass());
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        if (buffer instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("Setting BGFX index buffer: handle {}, type: {}", bgfxBuffer.getBgfxHandle(), indexType);
            currentIndexBuffer = bgfxBuffer.getBgfxHandle();

            // Set index buffer for BGFX
            boolean is32bit = indexType == VertexFormat.IndexType.INT;
            BGFX.bgfx_set_index_buffer(bgfxBuffer.getBgfxHandle(), 0, BGFX.BGFX_INVALID_HANDLE);
        } else {
            LOGGER.warn("Expected BgfxGpuBuffer, got: {}", buffer.getClass());
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex) {
        LOGGER.debug("BGFX drawIndexed: indexCount={}, instanceCount={}, firstIndex={}, baseVertex={}",
                    indexCount, instanceCount, firstIndex, baseVertex);

        // Clear the view first if needed
        if (clearColor.isPresent() || clearDepth.isPresent()) {
            int clearFlags = 0;
            int color = 0x303030ff; // Dark gray default
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

        // Submit the draw call
        BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        LOGGER.debug("BGFX draw call submitted to view 0 with program {}", currentProgram);
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer, VertexFormat.IndexType indexType, Collection<String> uniformNames, T uniformData) {
        // BGFX multiple indexed draw implementation
    }

    @Override
    public void draw(int vertexCount, int firstVertex) {
        LOGGER.debug("BGFX draw: vertexCount={}, firstVertex={}", vertexCount, firstVertex);

        // Clear the view first if needed
        if (clearColor.isPresent() || clearDepth.isPresent()) {
            int clearFlags = 0;
            int color = 0x303030ff; // Dark gray default
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

        // Submit the draw call
        BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        LOGGER.debug("BGFX non-indexed draw call submitted to view 0 with program {}", currentProgram);
    }

    @Override
    public void close() {
        // BGFX render passes don't require explicit cleanup
    }
}