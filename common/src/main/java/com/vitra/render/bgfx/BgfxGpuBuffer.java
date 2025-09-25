package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * BGFX-backed implementation of Minecraft's GpuBuffer class.
 * This provides a compatibility layer between Minecraft's buffer system and BGFX.
 */
public class BgfxGpuBuffer extends GpuBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGpuBuffer");

    private final short bgfxHandle;
    private final BufferType type;
    private final int actualSize;  // Store the intended size
    private boolean disposed = false;

    // Static default vertex layout for generic buffers
    private static BGFXVertexLayout defaultLayout = null;

    /**
     * Create a default vertex layout for generic buffer usage.
     * This layout includes position, normal, and texture coordinates.
     */
    private static BGFXVertexLayout getDefaultVertexLayout() {
        if (defaultLayout == null) {
            defaultLayout = BGFXVertexLayout.create();

            // Begin vertex layout definition
            BGFX.bgfx_vertex_layout_begin(defaultLayout, BGFX.BGFX_RENDERER_TYPE_DIRECT3D11);

            // Add position attribute (3 floats - x, y, z)
            BGFX.bgfx_vertex_layout_add(defaultLayout,
                BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);

            // Add normal attribute (3 floats - nx, ny, nz)
            BGFX.bgfx_vertex_layout_add(defaultLayout,
                BGFX.BGFX_ATTRIB_NORMAL, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);

            // Add texture coordinate attribute (2 floats - u, v)
            BGFX.bgfx_vertex_layout_add(defaultLayout,
                BGFX.BGFX_ATTRIB_TEXCOORD0, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);

            // Finalize the layout
            BGFX.bgfx_vertex_layout_end(defaultLayout);

            // Get the actual stride calculated by BGFX
            int actualStride = defaultLayout.stride();
            LOGGER.debug("Created default BGFX vertex layout - stride: {} bytes (pos: 3 floats, normal: 3 floats, texcoord: 2 floats)", actualStride);
        }

        return defaultLayout;
    }

    /**
     * Get the stride (size per vertex) of the default vertex layout
     */
    private static int getDefaultVertexStride() {
        BGFXVertexLayout layout = getDefaultVertexLayout();
        return layout.stride();
    }

    /**
     * Calculate the actual buffer size that BGFX will allocate
     */
    private static int calculateActualBufferSize(int requestedSize, BufferType type) {
        LOGGER.info("calculateActualBufferSize: requestedSize={}, type={}", requestedSize, type);

        // For uniform buffers, always preserve exact size
        if (type == BufferType.UNIFORM_BUFFER) {
            LOGGER.info("Preserving exact size for UNIFORM_BUFFER: {}", requestedSize);
            return requestedSize;
        }

        // For uniform buffer sizes (like projection matrices), preserve the exact requested size
        // These are typically 64, 128, 256 bytes and should not be modified
        if (requestedSize == 64 || requestedSize == 128 || requestedSize == 256 || requestedSize == 512) {
            LOGGER.info("Preserving exact size for uniform buffer: {}", requestedSize);
            return requestedSize;
        }

        switch (type) {
            case DYNAMIC_VERTEX_BUFFER, VERTEX_BUFFER -> {
                int vertexStride = getDefaultVertexStride();
                LOGGER.debug("calculateActualBufferSize: requestedSize={}, vertexStride={}", requestedSize, vertexStride);
                // For large buffers (likely vertex data), align to vertex boundary
                if (requestedSize >= 1024) {
                    int alignedSize = ((requestedSize + vertexStride - 1) / vertexStride) * vertexStride;
                    if (alignedSize < requestedSize) {
                        alignedSize = requestedSize; // Never make buffer smaller than requested
                    }
                    LOGGER.debug("calculateActualBufferSize result (large buffer): {}", alignedSize);
                    return alignedSize;
                } else {
                    // Small buffers - preserve exact size (likely uniform buffers)
                    LOGGER.debug("calculateActualBufferSize result (small buffer): {}", requestedSize);
                    return requestedSize;
                }
            }
            case DYNAMIC_INDEX_BUFFER, INDEX_BUFFER -> {
                int indexSize = 2; // 2 bytes per 16-bit index
                int alignedSize = ((requestedSize + indexSize - 1) / indexSize) * indexSize;
                if (alignedSize < requestedSize) {
                    alignedSize = requestedSize;
                }
                return alignedSize;
            }
            case INDIRECT_BUFFER -> {
                int drawCallSize = 20; // Conservative estimate for draw call data
                int alignedSize = ((requestedSize + drawCallSize - 1) / drawCallSize) * drawCallSize;
                if (alignedSize < requestedSize) {
                    alignedSize = requestedSize;
                }
                return alignedSize;
            }
            default -> {
                // Default: preserve exact size for unknown buffers
                LOGGER.debug("calculateActualBufferSize result (default): {}", requestedSize);
                return requestedSize;
            }
        }
    }

    public enum BufferType {
        VERTEX_BUFFER,
        INDEX_BUFFER,
        DYNAMIC_VERTEX_BUFFER,
        DYNAMIC_INDEX_BUFFER,
        INDIRECT_BUFFER,
        UNIFORM_BUFFER  // New type for uniform/constant buffers
    }

    /**
     * Create a BGFX GPU buffer with dynamic vertex buffer type (most common for Minecraft)
     */
    public BgfxGpuBuffer(int size) {
        this(size, BufferType.DYNAMIC_VERTEX_BUFFER);
    }

    /**
     * Create a BGFX GPU buffer with specified type
     */
    public BgfxGpuBuffer(int size, BufferType type) {
        // Calculate the correct size and store it
        super(calculateActualBufferSize(size, type), GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE);
        this.type = type;
        this.actualSize = calculateActualBufferSize(size, type);  // Store the intended size

        LOGGER.info("CONSTRUCTOR DEBUG: requestedSize={}, calculatedSize={}, superSize={}, type={}", size, actualSize, size(), type);

        // Create BGFX buffer based on type
        // Note: BGFX expects counts (vertices/indices/draw calls), not byte sizes
        switch (type) {
            case UNIFORM_BUFFER -> {
                // Uniform buffers: Create as dynamic vertex buffer but with minimal vertex layout
                // Use a simple layout with just raw bytes to avoid stride calculations
                LOGGER.info("Creating UNIFORM_BUFFER with actualSize: {}", actualSize);
                BGFXVertexLayout layout = BGFXVertexLayout.create();

                // Create a minimal layout with 1-byte elements to match the exact buffer size
                BGFX.bgfx_vertex_layout_begin(layout, BGFX.BGFX_RENDERER_TYPE_DIRECT3D11);
                BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_POSITION, 1, BGFX.BGFX_ATTRIB_TYPE_UINT8, false, false);
                BGFX.bgfx_vertex_layout_end(layout);

                int stride = layout.stride(); // Should be 1 byte
                int vertexCount = actualSize; // Each "vertex" is 1 byte, so count = actualSize

                LOGGER.info("UNIFORM_BUFFER: stride={}, vertexCount={}, actualSize={}", stride, vertexCount, actualSize);

                this.bgfxHandle = BGFX.bgfx_create_dynamic_vertex_buffer(vertexCount, layout, BGFX.BGFX_BUFFER_NONE);
                LOGGER.info("Created BGFX uniform buffer (as dynamic vertex buffer) - handle: {}, vertexCount: {}, stride: {}, actualSize: {}",
                           bgfxHandle, vertexCount, stride, actualSize);
            }
            case DYNAMIC_VERTEX_BUFFER -> {
                // Create a dynamic vertex buffer with proper vertex layout
                BGFXVertexLayout layout = getDefaultVertexLayout();
                int vertexStride = getDefaultVertexStride();
                int vertexCount = Math.max(1, (size() + vertexStride - 1) / vertexStride); // Round up

                this.bgfxHandle = BGFX.bgfx_create_dynamic_vertex_buffer(vertexCount, layout, BGFX.BGFX_BUFFER_NONE);
                LOGGER.debug("Created BGFX dynamic vertex buffer - handle: {}, vertexCount: {}, stride: {}, requestedSize: {}, actualSize: {}",
                           bgfxHandle, vertexCount, vertexStride, size, size());
            }
            case DYNAMIC_INDEX_BUFFER -> {
                // Index buffers expect index count - assume 2 bytes per index (16-bit indices)
                int indexSize = 2; // 2 bytes per 16-bit index
                int indexCount = Math.max(1, (size() + indexSize - 1) / indexSize); // Round up

                this.bgfxHandle = BGFX.bgfx_create_dynamic_index_buffer(indexCount, BGFX.BGFX_BUFFER_NONE);
                LOGGER.debug("Created BGFX dynamic index buffer - handle: {}, indexCount: {}, indexSize: {}, requestedSize: {}, actualSize: {}",
                           bgfxHandle, indexCount, indexSize, size, size());
            }
            case VERTEX_BUFFER -> {
                // Static vertex buffer - use dynamic for now
                BGFXVertexLayout layout = getDefaultVertexLayout();
                int vertexStride = getDefaultVertexStride();
                int vertexCount = Math.max(1, (size() + vertexStride - 1) / vertexStride); // Round up

                this.bgfxHandle = BGFX.bgfx_create_dynamic_vertex_buffer(vertexCount, layout, BGFX.BGFX_BUFFER_NONE);
                LOGGER.debug("Created BGFX static vertex buffer (as dynamic) - handle: {}, vertexCount: {}, stride: {}, size: {}",
                           bgfxHandle, vertexCount, vertexStride, size());
            }
            case INDEX_BUFFER -> {
                // Static index buffer - use dynamic for now
                int indexSize = 2; // 2 bytes per 16-bit index
                int indexCount = Math.max(1, (size() + indexSize - 1) / indexSize); // Round up

                this.bgfxHandle = BGFX.bgfx_create_dynamic_index_buffer(indexCount, BGFX.BGFX_BUFFER_NONE);
                LOGGER.debug("Created BGFX static index buffer (as dynamic) - handle: {}, indexCount: {}, indexSize: {}, size: {}",
                           bgfxHandle, indexCount, indexSize, size());
            }
            case INDIRECT_BUFFER -> {
                // Indirect buffers expect number of draw calls
                // Each draw call typically needs 16-20 bytes, we'll use 20 for safety
                int drawCallSize = 20; // Conservative estimate for draw call data
                int drawCallCount = Math.max(1, (size() + drawCallSize - 1) / drawCallSize); // Round up

                this.bgfxHandle = BGFX.bgfx_create_indirect_buffer(drawCallCount);
                LOGGER.debug("Created BGFX indirect buffer - handle: {}, drawCallCount: {}, drawCallSize: {}, size: {}",
                           bgfxHandle, drawCallCount, drawCallSize, size());
            }
            default -> {
                // Default to dynamic vertex buffer
                BGFXVertexLayout layout = getDefaultVertexLayout();
                int vertexStride = getDefaultVertexStride();
                int vertexCount = Math.max(1, (size() + vertexStride - 1) / vertexStride); // Round up

                this.bgfxHandle = BGFX.bgfx_create_dynamic_vertex_buffer(vertexCount, layout, BGFX.BGFX_BUFFER_NONE);
                LOGGER.debug("Created BGFX default dynamic vertex buffer - handle: {}, vertexCount: {}, stride: {}, size: {}",
                           bgfxHandle, vertexCount, vertexStride, size());
            }
        }

        if (bgfxHandle == BGFX.BGFX_INVALID_HANDLE) {
            throw new RuntimeException("Failed to create BGFX buffer of type: " + type);
        }
    }

    /**
     * Get the BGFX buffer handle for use with BGFX API calls
     */
    public short getBgfxHandle() {
        return bgfxHandle;
    }

    /**
     * Get the buffer type
     */
    public BufferType getType() {
        return type;
    }

    /**
     * Override size() to return the correct buffer size
     */
    @Override
    public int size() {
        return actualSize;
    }

    /**
     * Get the buffer size
     */
    public int getSize() {
        return size();
    }

    /**
     * Update buffer data (for dynamic buffers)
     */
    public void updateData(ByteBuffer data, int offset) {
        if (disposed) {
            throw new IllegalStateException("Buffer has been disposed");
        }

        // Convert ByteBuffer to BGFX memory
        var bgfxMemory = BGFX.bgfx_copy(data);

        switch (type) {
            case DYNAMIC_VERTEX_BUFFER, VERTEX_BUFFER -> {
                BGFX.bgfx_update_dynamic_vertex_buffer(bgfxHandle, offset, bgfxMemory);
                LOGGER.debug("Updated BGFX dynamic vertex buffer {} with {} bytes at offset {}", bgfxHandle, data.remaining(), offset);
            }
            case DYNAMIC_INDEX_BUFFER, INDEX_BUFFER -> {
                BGFX.bgfx_update_dynamic_index_buffer(bgfxHandle, offset, bgfxMemory);
                LOGGER.debug("Updated BGFX dynamic index buffer {} with {} bytes at offset {}", bgfxHandle, data.remaining(), offset);
            }
            default -> {
                LOGGER.warn("Cannot update buffer type: {}", type);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return disposed;
    }

    @Override
    public GpuBufferSlice slice(int offset, int length) {
        if (disposed) {
            throw new IllegalStateException("Buffer has been disposed");
        }

        // Validate parameters
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        if (offset + length > size()) {
            throw new IllegalArgumentException("Offset of " + offset + " and length " + length +
                " would put new slice outside buffer's range (of 0," + size() + ")");
        }

        LOGGER.debug("Creating BGFX buffer slice: offset={}, length={}, bufferSize={}", offset, length, size());
        return new GpuBufferSlice(this, offset, length);
    }

    @Override
    public GpuBufferSlice slice() {
        // Create a slice covering the entire buffer
        return slice(0, size());
    }

    @Override
    public void close() {
        if (!disposed) {
            // Destroy BGFX buffer
            switch (type) {
                case VERTEX_BUFFER -> {
                    // Note: static vertex buffers use different destroy call
                    BGFX.bgfx_destroy_vertex_buffer(bgfxHandle);
                }
                case INDEX_BUFFER -> {
                    BGFX.bgfx_destroy_index_buffer(bgfxHandle);
                }
                case UNIFORM_BUFFER, DYNAMIC_VERTEX_BUFFER -> {
                    BGFX.bgfx_destroy_dynamic_vertex_buffer(bgfxHandle);
                }
                case DYNAMIC_INDEX_BUFFER -> {
                    BGFX.bgfx_destroy_dynamic_index_buffer(bgfxHandle);
                }
                case INDIRECT_BUFFER -> {
                    BGFX.bgfx_destroy_indirect_buffer(bgfxHandle);
                }
            }
            disposed = true;
            LOGGER.debug("Destroyed BGFX buffer handle: {} type: {}", bgfxHandle, type);
        }
    }

    /**
     * Create a mapped view for BGFX buffer access
     */
    public GpuBuffer.MappedView createMappedView(int offset, int length) {
        if (disposed) {
            throw new IllegalStateException("Buffer has been disposed");
        }

        // Create a direct ByteBuffer for the mapped region
        ByteBuffer mappedBuffer = ByteBuffer.allocateDirect(length);

        return new BgfxMappedView(mappedBuffer, this, offset);
    }

    /**
     * BGFX implementation of MappedView
     */
    private static class BgfxMappedView implements GpuBuffer.MappedView {
        private final ByteBuffer buffer;
        private final BgfxGpuBuffer parentBuffer;
        private final int offset;
        private boolean closed = false;

        public BgfxMappedView(ByteBuffer buffer, BgfxGpuBuffer parentBuffer, int offset) {
            this.buffer = buffer;
            this.parentBuffer = parentBuffer;
            this.offset = offset;
        }

        @Override
        public ByteBuffer data() {
            if (closed) {
                throw new IllegalStateException("MappedView has been closed");
            }
            return buffer;
        }

        @Override
        public void close() {
            if (!closed) {
                // When closing, update the BGFX buffer with any changes made to the ByteBuffer
                if (buffer.position() > 0 || buffer.hasRemaining()) {
                    try {
                        buffer.flip(); // Prepare for reading
                        parentBuffer.updateData(buffer, offset);
                        LOGGER.debug("Updated BGFX buffer on MappedView close: {} bytes at offset {}", buffer.remaining(), offset);
                    } catch (Exception e) {
                        LOGGER.error("Failed to update BGFX buffer on MappedView close", e);
                    }
                }
                closed = true;
            }
        }
    }

    @Override
    public String toString() {
        return String.format("BgfxGpuBuffer{handle=%d, type=%s, size=%d, disposed=%s}",
                           bgfxHandle, type, size(), disposed);
    }
}