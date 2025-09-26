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
        LOGGER.debug("*** CREATED BGFX RENDER PASS: '{}' with color view: {}, depth view: {}", name, colorView != null, depthView != null);
        LOGGER.debug("*** BGFX_INVALID_HANDLE constant value: {}", BGFX.BGFX_INVALID_HANDLE);

        // Initialize default shader program if needed
        initializeDefaultProgram();
        this.currentProgram = defaultProgram;
    }

    private static synchronized void initializeDefaultProgram() {
        if (defaultProgram == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.debug("Initializing default BGFX shader program");
            try {
                defaultProgram = BgfxShaderCompiler.createBasicProgram();
                if (defaultProgram != BGFX.BGFX_INVALID_HANDLE) {
                    LOGGER.debug("Default BGFX shader program created: handle {}", defaultProgram);
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
        LOGGER.debug("*** BINDING BGFX SAMPLER '{}' with texture view: {}", name, textureView);

        if (textureView != null && textureView.texture() instanceof BgfxGpuTexture bgfxTexture) {
            // Get the texture handle
            short textureHandle = bgfxTexture.getBgfxHandle();
            LOGGER.debug("*** Binding BGFX texture handle {} to sampler '{}'", textureHandle, name);

            // Create uniform handle for the sampler
            short samplerUniform = BGFX.bgfx_create_uniform(name, BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);

            if (samplerUniform != BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.debug("*** Created sampler uniform handle {} for '{}'", samplerUniform, name);

                // Bind texture to sampler - use next available texture slot
                byte textureSlot = 0; // Start with slot 0, increment as needed
                BGFX.bgfx_set_texture(textureSlot, samplerUniform, textureHandle, BGFX.BGFX_SAMPLER_NONE);

                LOGGER.debug("*** Successfully bound texture {} to sampler '{}' at slot {}", textureHandle, name, textureSlot);
            } else {
                LOGGER.warn("*** Failed to create uniform handle for sampler '{}'", name);
            }
        } else {
            LOGGER.debug("*** No BGFX texture to bind for sampler '{}' - textureView: {}, type: {}",
                       name, textureView, textureView != null ? textureView.texture().getClass() : "null");
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (buffer instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("*** SETTING BGFX UNIFORM '{}' with buffer handle: {}", name, bgfxBuffer.getBgfxHandle());

            // Determine uniform type based on buffer size and name
            int uniformType = determineUniformType(name, bgfxBuffer.getSize());
            short uniformHandle = BGFX.bgfx_create_uniform(name, uniformType, 1);

            if (uniformHandle != BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.debug("*** Created uniform handle {} for '{}' with type {}", uniformHandle, name, uniformType);

                // Note: BGFX uniforms are set per-draw call, not per buffer binding
                // The actual uniform data will be provided during draw call submission
                // For now, we just create the uniform handle and store the buffer reference
                LOGGER.debug("*** Prepared uniform handle for '{}' - uniform data will be set during draw call", name);
            } else {
                LOGGER.warn("*** Failed to create uniform handle for '{}'", name);
            }
        } else {
            LOGGER.debug("Expected BgfxGpuBuffer for uniform '{}', got: {}", name, buffer.getClass());
        }
    }

    @Override
    public void setUniform(String name, GpuBufferSlice bufferSlice) {
        if (bufferSlice.buffer() instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("*** SETTING BGFX UNIFORM SLICE '{}' with buffer slice: handle {}, offset: {}, length: {}",
                        name, bgfxBuffer.getBgfxHandle(), bufferSlice.offset(), bufferSlice.length());

            // Determine uniform type based on buffer slice length and name
            int uniformType = determineUniformTypeBySize(name, bufferSlice.length());
            short uniformHandle = BGFX.bgfx_create_uniform(name, uniformType, 1);

            if (uniformHandle != BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.debug("*** Created uniform handle {} for buffer slice '{}' (offset: {}, length: {}, type: {})",
                           uniformHandle, name, bufferSlice.offset(), bufferSlice.length(), uniformType);

                // Note: BGFX uniforms are set per-draw call, not per buffer binding
                // The actual uniform slice data will be provided during draw call submission
                // For now, we just create the uniform handle and store the buffer slice reference
                LOGGER.debug("*** Prepared uniform slice handle for '{}' - uniform data will be set during draw call", name);
            } else {
                LOGGER.warn("*** Failed to create uniform handle for buffer slice '{}'", name);
            }
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
            LOGGER.debug("*** SETTING VERTEX BUFFER at slot {}: handle {}", slot, bgfxBuffer.getBgfxHandle());
            currentVertexBuffer = bgfxBuffer.getBgfxHandle();

            // Look up appropriate shader program for this buffer
            short shaderProgram = BgfxMeshDataHandler.getShaderProgramForBuffer(currentVertexBuffer);
            if (shaderProgram != BGFX.BGFX_INVALID_HANDLE) {
                currentProgram = shaderProgram;
                LOGGER.debug("*** Updated shader program to {} based on vertex buffer {}", currentProgram, currentVertexBuffer);
            } else {
                // Use Position+UV0+Color shader (most common for game rendering) instead of default
                short gameRenderingShader = BgfxShaderCompiler.createPositionTexColorProgram();
                if (gameRenderingShader != BGFX.BGFX_INVALID_HANDLE) {
                    currentProgram = gameRenderingShader;
                    LOGGER.debug("*** Using Position+Tex+Color shader {} for vertex buffer {} (assumed game rendering)", currentProgram, currentVertexBuffer);
                } else {
                    LOGGER.warn("*** No shader program found for vertex buffer {}, using default {}", currentVertexBuffer, currentProgram);
                }
            }

            // Set vertex buffer for BGFX
            BGFX.bgfx_set_vertex_buffer((byte)slot, bgfxBuffer.getBgfxHandle(), 0, BGFX.BGFX_INVALID_HANDLE);
        } else {
            LOGGER.warn("Expected BgfxGpuBuffer, got: {}", buffer.getClass());
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        if (buffer instanceof BgfxGpuBuffer bgfxBuffer) {
            LOGGER.debug("*** SETTING INDEX BUFFER: handle {}, type: {}", bgfxBuffer.getBgfxHandle(), indexType);
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
        LOGGER.debug("*** BGFX DRAW CALL: drawIndexed: indexCount={}, instanceCount={}, firstIndex={}, baseVertex={}",
                    indexCount, instanceCount, firstIndex, baseVertex);
        LOGGER.debug("*** Current vertex buffer: {}, index buffer: {}, program: {}",
                   currentVertexBuffer, currentIndexBuffer, currentProgram);

        // CRITICAL FIX: Parameter mapping issue detection
        // Minecraft's GL RenderPass calls have different parameter meaning:
        // Expected BGFX: (indexCount, instanceCount, firstIndex, baseVertex)
        // Actual MC GL:  (0, 0, actualIndexCount, baseVertex)
        int actualIndexCount = indexCount;
        if (indexCount == 0 && firstIndex > 0) {
            actualIndexCount = firstIndex;
            LOGGER.debug("*** PARAMETER MAPPING FIX: indexCount was 0 but firstIndex={}, using firstIndex as actual indexCount", firstIndex);
        } else if (indexCount == 0 && instanceCount > 0) {
            actualIndexCount = instanceCount;
            LOGGER.debug("*** PARAMETER MAPPING FIX: indexCount was 0 but instanceCount={}, using instanceCount as actual indexCount", instanceCount);
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
        // Note: Handle 0 is VALID in BGFX! BGFX_INVALID_HANDLE is 65535 (UINT16_MAX)
        if (actualIndexCount > 0) {
            LOGGER.debug("*** SUBMITTING INDEXED DRAW: actualIndexCount={}, indexBuffer={}, vertexBuffer={}, program={}",
                       actualIndexCount, currentIndexBuffer, currentVertexBuffer, currentProgram);
            // Submit the indexed draw call
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        } else if (actualIndexCount == 0) {
            LOGGER.warn("*** SKIPPING DRAW - actualIndexCount=0 (no geometry to render)");
        } else if (currentIndexBuffer == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.warn("*** SKIPPING DRAW - actually invalid index buffer handle (currentIndexBuffer={}, BGFX_INVALID_HANDLE={})", currentIndexBuffer, BGFX.BGFX_INVALID_HANDLE);
        } else {
            LOGGER.debug("*** SUBMITTING DRAW anyway for debugging");
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        }
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer, VertexFormat.IndexType indexType, Collection<String> uniformNames, T uniformData) {
        // BGFX multiple indexed draw implementation
    }

    @Override
    public void draw(int vertexCount, int firstVertex) {
        LOGGER.debug("*** BGFX DRAW CALL: draw: vertexCount={}, firstVertex={}", vertexCount, firstVertex);
        LOGGER.debug("*** Current vertex buffer: {}, program: {}", currentVertexBuffer, currentProgram);

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
        // Note: Handle 0 is VALID in BGFX! BGFX_INVALID_HANDLE is 65535 (UINT16_MAX)
        if (vertexCount > 0) {
            LOGGER.debug("*** SUBMITTING NON-INDEXED DRAW: vertexCount={}, vertexBuffer={}, program={}",
                       vertexCount, currentVertexBuffer, currentProgram);
            // Submit the non-indexed draw call
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        } else if (vertexCount == 0) {
            LOGGER.warn("*** SKIPPING DRAW - vertexCount=0 (no geometry to render)");
        } else if (currentVertexBuffer == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.warn("*** SKIPPING DRAW - actually invalid vertex buffer handle (currentVertexBuffer={}, BGFX_INVALID_HANDLE={})", currentVertexBuffer, BGFX.BGFX_INVALID_HANDLE);
        } else {
            LOGGER.debug("*** SUBMITTING NON-INDEXED DRAW anyway for debugging");
            BGFX.bgfx_submit(0, currentProgram, 0, (byte)BGFX.BGFX_DISCARD_ALL);
        }
    }

    @Override
    public void close() {
        // BGFX render passes don't require explicit cleanup
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
        LOGGER.debug("Determining uniform type for '{}' with size {} bytes", name, sizeInBytes);

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
}