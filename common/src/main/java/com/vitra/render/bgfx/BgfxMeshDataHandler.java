package com.vitra.render.bgfx;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Handles MeshData from Minecraft's BufferBuilder and creates/updates BGFX GPU buffers
 * This is the critical bridge between Minecraft's mesh data and BGFX rendering
 */
public class BgfxMeshDataHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxMeshDataHandler");

    // Map to track MeshData -> BGFX buffer handles for reuse
    private static final ConcurrentMap<MeshData, BufferHandles> meshBufferMap = new ConcurrentHashMap<>();

    // Reverse map to track buffer handle -> MeshData for shader program lookup
    private static final ConcurrentMap<Short, MeshData> bufferToMeshMap = new ConcurrentHashMap<>();

    private static class BufferHandles {
        final short vertexBufferHandle;
        final short indexBufferHandle;
        final short shaderProgram;

        BufferHandles(short vertexBufferHandle, short indexBufferHandle, short shaderProgram) {
            this.vertexBufferHandle = vertexBufferHandle;
            this.indexBufferHandle = indexBufferHandle;
            this.shaderProgram = shaderProgram;
        }
    }

    /**
     * Handle MeshData from BufferBuilder - create/update BGFX buffers with the actual vertex/index data
     * This is called from BufferBuilderMixin when MeshData is created
     */
    public static void handleMeshData(MeshData meshData) {
        try {
            // Get the ByteBuffers from MeshData
            ByteBuffer vertexBuffer = meshData.vertexBuffer();
            ByteBuffer indexBuffer = meshData.indexBuffer();
            MeshData.DrawState drawState = meshData.drawState();

            LOGGER.debug("*** HANDLING MeshData: vertexCount={}, mode={}, format={}",
                       drawState.vertexCount(), drawState.mode(), drawState.format());
            LOGGER.debug("*** Vertex buffer: size={}, Index buffer: size={}",
                       vertexBuffer != null ? vertexBuffer.remaining() : 0,
                       indexBuffer != null ? indexBuffer.remaining() : 0);

            if (vertexBuffer != null && vertexBuffer.hasRemaining()) {
                // Create BGFX dynamic vertex buffer
                short vertexBufferHandle = createBgfxVertexBuffer(vertexBuffer, drawState.format());
                LOGGER.debug("*** Created BGFX vertex buffer: handle={}", vertexBufferHandle);

                // Create BGFX index buffer if we have indices
                short indexBufferHandle = BGFX.BGFX_INVALID_HANDLE;
                if (indexBuffer != null && indexBuffer.hasRemaining()) {
                    indexBufferHandle = createBgfxIndexBuffer(indexBuffer, drawState.mode());
                    LOGGER.debug("*** Created BGFX index buffer: handle={}", indexBufferHandle);
                }

                // Select appropriate shader program based on vertex format
                short shaderProgram = selectShaderProgram(drawState.format());
                LOGGER.debug("*** Selected shader program: {} for format: {}", shaderProgram, drawState.format());

                // Store the buffer handles for later use by RenderPass
                BufferHandles handles = new BufferHandles(vertexBufferHandle, indexBufferHandle, shaderProgram);
                meshBufferMap.put(meshData, handles);

                // Store reverse mappings for shader program lookup
                bufferToMeshMap.put(vertexBufferHandle, meshData);
                if (indexBufferHandle != BGFX.BGFX_INVALID_HANDLE) {
                    bufferToMeshMap.put(indexBufferHandle, meshData);
                }

                LOGGER.debug("*** SUCCESSFULLY processed MeshData - vertex buffer: {}, index buffer: {}",
                           vertexBufferHandle, indexBufferHandle);
            } else {
                LOGGER.warn("*** MeshData has no vertex data to upload");
            }

        } catch (Exception e) {
            LOGGER.error("*** ERROR processing MeshData", e);
        }
    }

    /**
     * Create BGFX dynamic vertex buffer from ByteBuffer data
     */
    private static short createBgfxVertexBuffer(ByteBuffer vertexData, VertexFormat format) {
        try {
            // Copy vertex data to BGFX memory
            BGFXMemory bgfxMemory = BGFX.bgfx_copy(vertexData);

            // Create dynamic vertex buffer
            short bufferHandle = BGFX.bgfx_create_dynamic_vertex_buffer_mem(
                bgfxMemory,
                createBgfxVertexLayout(format),
                BGFX.BGFX_BUFFER_NONE
            );

            if (bufferHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to create BGFX dynamic vertex buffer");
            } else {
                LOGGER.debug("Created BGFX dynamic vertex buffer: handle={}, size={} bytes",
                           bufferHandle, vertexData.remaining());
            }

            return bufferHandle;
        } catch (Exception e) {
            LOGGER.error("Error creating BGFX vertex buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create BGFX dynamic index buffer from ByteBuffer data
     */
    private static short createBgfxIndexBuffer(ByteBuffer indexData, VertexFormat.Mode mode) {
        try {
            // Copy index data to BGFX memory
            BGFXMemory bgfxMemory = BGFX.bgfx_copy(indexData);

            // Create dynamic index buffer
            int flags = BGFX.BGFX_BUFFER_NONE;
            // Check if we need 32-bit indices based on buffer size and vertex count estimation
            int indexBufferSize = indexData.remaining();
            boolean is32bit = false;

            // If buffer size is multiple of 4, it's likely 32-bit indices
            if (indexBufferSize % 4 == 0) {
                int indexCount = indexBufferSize / 4; // 4 bytes per 32-bit index
                is32bit = true;
                flags |= BGFX.BGFX_BUFFER_INDEX32;
                LOGGER.debug("*** Detected 32-bit indices: {} indices ({} bytes)", indexCount, indexBufferSize);
            } else if (indexBufferSize % 2 == 0) {
                int indexCount = indexBufferSize / 2; // 2 bytes per 16-bit index
                LOGGER.debug("*** Detected 16-bit indices: {} indices ({} bytes)", indexCount, indexBufferSize);
            } else {
                LOGGER.warn("*** Unusual index buffer size: {} bytes - assuming 16-bit indices", indexBufferSize);
            }

            short bufferHandle = BGFX.bgfx_create_dynamic_index_buffer_mem(bgfxMemory, flags);

            if (bufferHandle == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("Failed to create BGFX dynamic index buffer");
            } else {
                LOGGER.debug("Created BGFX dynamic index buffer: handle={}, size={} bytes, 32bit={}",
                           bufferHandle, indexData.remaining(), is32bit);
            }

            return bufferHandle;
        } catch (Exception e) {
            LOGGER.error("Error creating BGFX index buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create BGFX vertex layout from Minecraft's VertexFormat
     * Dynamically maps Minecraft vertex formats to BGFX layouts
     */
    private static BGFXVertexLayout createBgfxVertexLayout(VertexFormat format) {
        LOGGER.debug("*** Creating BGFX vertex layout for format: {}", format);

        BGFXVertexLayout layout = BGFXVertexLayout.create();
        BGFX.bgfx_vertex_layout_begin(layout, BGFX.BGFX_RENDERER_TYPE_DIRECT3D11);

        String formatStr = format.toString();

        // Always add position (required for all vertex formats)
        BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
        LOGGER.debug("Added POSITION (3 floats)");

        // Add texture coordinates if present
        if (formatStr.contains("UV0")) {
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_TEXCOORD0, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            LOGGER.debug("Added TEXCOORD0 (2 floats)");
        }

        // Add second texture coordinates if present (lightmap)
        if (formatStr.contains("UV2")) {
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_TEXCOORD1, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            LOGGER.debug("Added TEXCOORD1 (2 floats)");
        }

        // Add color if present
        if (formatStr.contains("Color")) {
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false);
            LOGGER.debug("Added COLOR0 (4 normalized bytes)");
        }

        // Add normal if present
        if (formatStr.contains("Normal")) {
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_NORMAL, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            LOGGER.debug("Added NORMAL (3 floats)");
        }

        BGFX.bgfx_vertex_layout_end(layout);

        LOGGER.debug("*** Created BGFX vertex layout for format: {}", format);
        return layout;
    }

    /**
     * Select appropriate shader program based on vertex format
     */
    private static short selectShaderProgram(VertexFormat format) {
        String formatStr = format.toString();

        LOGGER.debug("*** Selecting shader for format string: {}", formatStr);

        // Text rendering: Position + Color + UV0 + UV2 (lightmap)
        if (formatStr.contains("UV0") && formatStr.contains("Color") && formatStr.contains("UV2")) {
            LOGGER.debug("*** Using rendertype_text shader for text rendering");
            return BgfxShaderCompiler.createRenderTypeTextProgram();
        }

        // Position + Texture + Color (most common for blocks/entities)
        if (formatStr.contains("UV0") && formatStr.contains("Color")) {
            return BgfxShaderCompiler.createPositionTexColorProgram();
        }

        // Position + Color only (UI elements, debug rendering)
        if (formatStr.contains("Color") && !formatStr.contains("UV")) {
            return BgfxShaderCompiler.createPositionColorProgram();
        }

        // Position + Texture only (no color)
        if (formatStr.contains("UV0") && !formatStr.contains("Color")) {
            return BgfxShaderCompiler.createPositionTexProgram();
        }

        // Position + Normal (for lighting calculations)
        if (formatStr.contains("Normal")) {
            return BgfxShaderCompiler.createEntityProgram();
        }

        // Position + Lightmap (terrain with lightmap UV2)
        if (formatStr.contains("UV2")) {
            return BgfxShaderCompiler.createChunkProgram();
        }

        // Position only (minimal geometry like lines)
        return BgfxShaderCompiler.createPositionProgram();
    }

    /**
     * Get BGFX buffer handles for a MeshData object
     * Used by RenderPass to get the actual BGFX buffers for drawing
     */
    public static BufferHandles getBufferHandles(MeshData meshData) {
        return meshBufferMap.get(meshData);
    }

    /**
     * Check if MeshData has associated BGFX buffers
     */
    public static boolean hasBgfxBuffers(MeshData meshData) {
        return meshBufferMap.containsKey(meshData);
    }

    /**
     * Clean up BGFX buffers when MeshData is no longer needed
     */
    public static void cleanupMeshData(MeshData meshData) {
        BufferHandles handles = meshBufferMap.remove(meshData);
        if (handles != null) {
            // Clean up reverse mappings
            bufferToMeshMap.remove(handles.vertexBufferHandle);
            bufferToMeshMap.remove(handles.indexBufferHandle);

            if (handles.vertexBufferHandle != BGFX.BGFX_INVALID_HANDLE) {
                BGFX.bgfx_destroy_dynamic_vertex_buffer(handles.vertexBufferHandle);
                LOGGER.debug("Destroyed BGFX vertex buffer: {}", handles.vertexBufferHandle);
            }
            if (handles.indexBufferHandle != BGFX.BGFX_INVALID_HANDLE) {
                BGFX.bgfx_destroy_dynamic_index_buffer(handles.indexBufferHandle);
                LOGGER.debug("Destroyed BGFX index buffer: {}", handles.indexBufferHandle);
            }
        }
    }

    /**
     * Get vertex buffer handle for MeshData
     */
    public static short getVertexBufferHandle(MeshData meshData) {
        BufferHandles handles = meshBufferMap.get(meshData);
        return handles != null ? handles.vertexBufferHandle : BGFX.BGFX_INVALID_HANDLE;
    }

    /**
     * Get index buffer handle for MeshData
     */
    public static short getIndexBufferHandle(MeshData meshData) {
        BufferHandles handles = meshBufferMap.get(meshData);
        return handles != null ? handles.indexBufferHandle : BGFX.BGFX_INVALID_HANDLE;
    }

    /**
     * Get shader program handle for MeshData
     */
    public static short getShaderProgram(MeshData meshData) {
        BufferHandles handles = meshBufferMap.get(meshData);
        return handles != null ? handles.shaderProgram : BGFX.BGFX_INVALID_HANDLE;
    }

    /**
     * Get shader program handle for buffer handle (for use by RenderPass)
     */
    public static short getShaderProgramForBuffer(short bufferHandle) {
        MeshData meshData = bufferToMeshMap.get(bufferHandle);
        return meshData != null ? getShaderProgram(meshData) : BGFX.BGFX_INVALID_HANDLE;
    }

    /**
     * Draw all available BGFX buffers to the specified view
     * This is called from BgfxWindow.renderMinecraftContent() each frame
     */
    public static int drawAllBuffers(int viewId) {
        int drawCallCount = 0;

        LOGGER.info("*** FRAMEBUFFER TEST: drawAllBuffers called with {} mesh entries", meshBufferMap.size());

        try {
            // Iterate through all stored mesh data and submit draw calls
            for (java.util.Map.Entry<MeshData, BufferHandles> entry : meshBufferMap.entrySet()) {
                MeshData meshData = entry.getKey();
                BufferHandles handles = entry.getValue();

                if (handles.vertexBufferHandle != BGFX.BGFX_INVALID_HANDLE &&
                    handles.shaderProgram != BGFX.BGFX_INVALID_HANDLE) {

                    // CRITICAL FIX: Use same depth test as working triangle - ALWAYS pass depth test
                    // The issue is depth buffer conflict - geometry is depth-testing against itself
                    long renderState = BGFX.BGFX_STATE_WRITE_RGB | BGFX.BGFX_STATE_WRITE_A | BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
                    LOGGER.info("*** DEPTH FIX: Using DEPTH_TEST_ALWAYS like working triangle - state = 0x{}", Long.toHexString(renderState));
                    BGFX.bgfx_set_state(renderState, 0);

                    // CRITICAL: Set identity model matrix for Minecraft geometry
                    // Without model matrix, geometry may be transformed incorrectly
                    float[] identityMatrix = {
                        1.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                    };
                    BGFX.bgfx_set_transform(identityMatrix);
                    LOGGER.info("*** MODEL MATRIX FIX: Set identity model matrix for geometry at origin");

                    // Set vertex buffer
                    BGFX.bgfx_set_vertex_buffer(0, handles.vertexBufferHandle, 0, 0xffffffff);

                    // Set index buffer if available
                    if (handles.indexBufferHandle != BGFX.BGFX_INVALID_HANDLE) {
                        BGFX.bgfx_set_index_buffer(handles.indexBufferHandle, 0, 0xffffffff);
                    }

                    // Submit draw call with the appropriate shader program
                    LOGGER.info("*** FRAMEBUFFER TEST: Submitting draw call - vertex={}, index={}, shader={}, viewId={}",
                               handles.vertexBufferHandle, handles.indexBufferHandle, handles.shaderProgram, viewId);
                    BGFX.bgfx_submit(viewId, handles.shaderProgram, 0, (byte)0);

                    drawCallCount++;
                    LOGGER.debug("Submitted BGFX draw call: vertex={}, index={}, shader={}",
                               handles.vertexBufferHandle, handles.indexBufferHandle, handles.shaderProgram);
                }
            }

            if (drawCallCount > 0) {
                LOGGER.debug("Successfully submitted {} BGFX draw calls", drawCallCount);
            }

        } catch (Exception e) {
            LOGGER.error("Error drawing BGFX buffers", e);
        }

        return drawCallCount;
    }
}