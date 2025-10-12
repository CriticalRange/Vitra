package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Simplified BGFX buffer implementation that uses BGFX native functionality directly
 * Replaces the complex VitraGpuBuffer wrapper class
 */
public class BgfxBuffer extends GpuBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxBuffer");

    private final short bgfxHandle;
    private final BufferType type;
    private final String name;
    private boolean closed = false;

    // CPU-side buffer for UNIFORM_BUFFER emulation (BGFX doesn't have OpenGL-style UBOs)
    private final ByteBuffer cpuBuffer;

    // Store actual buffer size since parent class may modify it
    private final int actualSize;

    public enum BufferType {
        VERTEX_BUFFER("vertex_buffer"),
        INDEX_BUFFER("index_buffer"),
        DYNAMIC_VERTEX_BUFFER("dynamic_vertex_buffer"),
        DYNAMIC_INDEX_BUFFER("dynamic_index_buffer"),
        UNIFORM_BUFFER("uniform_buffer");

        private final String resourceType;

        BufferType(String resourceType) {
            this.resourceType = resourceType;
        }

        public String getResourceType() {
            return resourceType;
        }
    }

    /**
     * Create a dynamic vertex buffer.
     * BGFX handles all validation and creation internally.
     */
    public BgfxBuffer(String name, int numVertices, BGFXVertexLayout layout, int flags) {
        super(numVertices * calculateVertexSize(layout), GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE);
        this.name = name;
        this.type = BufferType.DYNAMIC_VERTEX_BUFFER;
        this.actualSize = numVertices * calculateVertexSize(layout);
        this.bgfxHandle = BgfxOperations.createDynamicVertexBuffer(numVertices, layout, flags);
        this.cpuBuffer = null;

        LOGGER.debug("Created dynamic vertex buffer: {} (handle: {}, size: {})", name, bgfxHandle, actualSize);
    }

    /**
     * Create a dynamic index buffer.
     * BGFX handles all validation and creation internally.
     */
    public BgfxBuffer(String name, int numIndices, int flags) {
        super(numIndices * 4, GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE);
        this.name = name;
        this.type = BufferType.DYNAMIC_INDEX_BUFFER;
        this.actualSize = numIndices * 4;
        this.bgfxHandle = BgfxOperations.createDynamicIndexBuffer(numIndices, flags);
        this.cpuBuffer = null;

        LOGGER.debug("Created dynamic index buffer: {} (handle: {}, size: {})", name, bgfxHandle, actualSize);
    }

    /**
     * Create a buffer based on usage flags (for compatibility with GpuDevice interface).
     * BGFX handles all validation and creation internally.
     */
    public BgfxBuffer(String name, int size, int usage, BufferType typeMarker) {
        super(size, usage);
        this.name = name;
        this.type = typeMarker != null ? typeMarker : mapUsageToBufferType(usage);
        this.actualSize = size;  // Store the ACTUAL size we want, not what parent class might modify

        LOGGER.info("BgfxBuffer constructor: name={}, size={}, usage=0x{}, typeMarker={}, detected type={}",
            name, size, Integer.toHexString(usage), typeMarker, this.type);

        if (this.type == BufferType.DYNAMIC_VERTEX_BUFFER) {
            BGFXVertexLayout layout = BgfxOperations.getPositionTexColorLayout();
            int numVertices = size / calculateVertexSize(layout);
            this.bgfxHandle = BgfxOperations.createDynamicVertexBuffer(numVertices, layout, 0);
            this.cpuBuffer = null;
        } else if (this.type == BufferType.DYNAMIC_INDEX_BUFFER) {
            int numIndices = size / 4; // Assuming 32-bit indices
            this.bgfxHandle = BgfxOperations.createDynamicIndexBuffer(numIndices, 0);
            this.cpuBuffer = null;
        } else if (this.type == BufferType.UNIFORM_BUFFER) {
            // BGFX doesn't have OpenGL-style UBOs (Uniform Buffer Objects)
            // Emulate as CPU-side buffer - uniform values will be extracted and set per-draw
            this.bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
            this.cpuBuffer = ByteBuffer.allocateDirect(size);
            LOGGER.info("Created CPU-emulated uniform buffer: {} (size: {}, cpuBuffer capacity: {})",
                name, size, cpuBuffer.capacity());
        } else {
            LOGGER.warn("Unsupported buffer type: {} for buffer: {}", type, name);
            this.bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
            this.cpuBuffer = null;
        }

        LOGGER.info("Buffer created: {} (handle: {}, type: {}, actualSize: {}, parent size(): {})",
            name, bgfxHandle, type, actualSize, super.size());
    }

    private BufferType mapUsageToBufferType(int usage) {
        if ((usage & USAGE_INDEX) != 0) {
            return BufferType.DYNAMIC_INDEX_BUFFER;
        } else if ((usage & USAGE_VERTEX) != 0) {
            return BufferType.DYNAMIC_VERTEX_BUFFER;
        } else if ((usage & USAGE_UNIFORM) != 0) {
            return BufferType.UNIFORM_BUFFER;
        } else {
            return BufferType.DYNAMIC_VERTEX_BUFFER;
        }
    }

    private static int calculateVertexSize(BGFXVertexLayout layout) {
        // Estimate vertex size based on common layouts
        // Position (3 floats) + TexCoord (2 floats) + Color (4 bytes) = 24 bytes
        return 24;
    }

    public short getBgfxHandle() {
        return bgfxHandle;
    }

    public BufferType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    /**
     * Override size() to return the actual buffer size we allocated,
     * not what the parent GpuBuffer class might have modified.
     */
    @Override
    public int size() {
        return actualSize;
    }

    /**
     * Override slice() to use actualSize instead of parent's corrupted size field.
     * The parent's slice() method directly accesses the private size field, bypassing size().
     */
    @Override
    public GpuBufferSlice slice(int offset, int length) {
        // Validate using our actualSize, not parent's corrupted size
        if (offset < 0 || length < 0 || offset + length > actualSize) {
            throw new IllegalArgumentException(
                String.format("Offset of %d and length %d would put new slice outside buffer's range (of 0,%d)",
                    offset, length, actualSize));
        }
        return new GpuBufferSlice(this, offset, length);
    }

    /**
     * Override slice() with no args to create a slice of the entire buffer.
     */
    @Override
    public GpuBufferSlice slice() {
        return new GpuBufferSlice(this, 0, actualSize);
    }

    /**
     * Update buffer data using BGFX native functionality.
     * BGFX handles all validation internally.
     * For UNIFORM_BUFFER, data is stored in CPU-side buffer.
     */
    public boolean updateData(int offset, ByteBuffer data) {
        if (type == BufferType.UNIFORM_BUFFER && cpuBuffer != null) {
            // CPU-side update for uniform buffer emulation
            synchronized (cpuBuffer) {
                cpuBuffer.position(offset);
                cpuBuffer.put(data);
                cpuBuffer.position(0);
            }
            return true;
        }

        return BgfxOperations.updateDynamicBuffer(bgfxHandle, offset, data,
            type == BufferType.DYNAMIC_VERTEX_BUFFER || type == BufferType.VERTEX_BUFFER);
    }

    /**
     * Map buffer for CPU access.
     * BGFX doesn't support direct memory mapping - we emulate it with a CPU-side buffer.
     * On unmap, the data is copied to the BGFX buffer using bgfx_update_dynamic_*_buffer.
     * For UNIFORM_BUFFER, returns the CPU-side buffer directly.
     */
    public MappedView map(boolean read, boolean write) {
        if (type == BufferType.UNIFORM_BUFFER && cpuBuffer != null) {
            // For uniform buffers, return a view of the CPU-side buffer
            return new MappedView() {
                private boolean unmapped = false;

                @Override
                public ByteBuffer data() {
                    synchronized (cpuBuffer) {
                        cpuBuffer.clear();
                        return cpuBuffer;
                    }
                }

                @Override
                public void close() {
                    if (!unmapped) {
                        // No upload needed - data is already in CPU buffer
                        unmapped = true;
                    }
                }
            };
        }

        // BGFX doesn't have direct memory mapping like OpenGL
        // Return a CPU-side buffer that will be uploaded on unmap
        ByteBuffer mappedBuffer = ByteBuffer.allocateDirect((int)size());

        return new MappedView() {
            private boolean unmapped = false;

            @Override
            public ByteBuffer data() {
                return mappedBuffer;
            }

            @Override
            public void close() {
                if (!unmapped) {
                    mappedBuffer.flip();
                    updateData(0, mappedBuffer);
                    unmapped = true;
                }
            }
        };
    }

    public void close() {
        if (!closed) {
            if (type == BufferType.UNIFORM_BUFFER) {
                // CPU-side buffer, no BGFX resource to destroy
                LOGGER.debug("Closing CPU-emulated uniform buffer: {}", name);
            } else if (bgfxHandle != 0 && bgfxHandle != BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.debug("Destroying buffer: {} (handle: {}, type: {})", name, bgfxHandle, type);
                BgfxOperations.destroyResource(bgfxHandle, type.getResourceType());
            }
            closed = true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return String.format("BgfxBuffer{name='%s', handle=%d, type=%s, size=%d, closed=%s}",
            name, bgfxHandle, type, size(), closed);
    }
}