package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DirectX 12 Buffer Manager inspired by VulkanMod's buffer system
 * Provides comprehensive buffer management with upload optimization
 */
public class D3D12BufferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12BufferManager");

    // Buffer usage types
    public static final int USAGE_DEFAULT = 0;
    public static final int USAGE_IMMUTABLE = 1;
    public static final int USAGE_DYNAMIC = 2;
    public static final int USAGE_STAGING = 3;

    // Buffer bind flags (DirectX 12 uses resource flags, these are for compatibility)
    public static final int BIND_VERTEX_BUFFER = 0x1;
    public static final int BIND_INDEX_BUFFER = 0x2;
    public static final int BIND_CONSTANT_BUFFER = 0x4;
    public static final int BIND_SHADER_RESOURCE = 0x8;
    public static final int BIND_STREAM_OUTPUT = 0x10;
    public static final int BIND_UNORDERED_ACCESS = 0x20;
    public static final int BIND_RENDER_TARGET = 0x40;
    public static final int BIND_DEPTH_STENCIL = 0x80;
    public static final int BIND_ARGUMENTS = 0x100;

    // CPU access flags
    public static final int CPU_ACCESS_NONE = 0x0;
    public static final int CPU_ACCESS_WRITE = 0x4;
    public static final int CPU_ACCESS_READ = 0x8;

    // Buffer types
    public static final int BUFFER_TYPE_VERTEX = 0;
    public static final int BUFFER_TYPE_INDEX = 1;
    public static final int BUFFER_TYPE_CONSTANT = 2;
    public static final int BUFFER_TYPE_STRUCTURED = 3;
    public static final int BUFFER_TYPE_RAW = 4;
    public static final int BUFFER_TYPE_UPLOAD = 5;

    /**
     * Buffer description
     */
    public static class BufferDesc {
        public final int size;
        public final int usage;
        public final long bindFlags;
        public final int cpuAccessFlags;
        public final int bufferType;
        public final int elementSize;
        public final String debugName;

        public BufferDesc(int size, int usage, long bindFlags, int cpuAccessFlags,
                        int bufferType, int elementSize, String debugName) {
            this.size = size;
            this.usage = usage;
            this.bindFlags = bindFlags;
            this.cpuAccessFlags = cpuAccessFlags;
            this.bufferType = bufferType;
            this.elementSize = elementSize;
            this.debugName = debugName;
        }

        @Override
        public String toString() {
            return String.format("BufferDesc[size=%d, usage=%d, bindFlags=%x, type=%d, name=%s]",
                size, usage, bindFlags, bufferType, debugName);
        }
    }

    /**
     * Buffer wrapper
     */
    public static class D3D12Buffer {
        private final long handle;
        private final BufferDesc desc;
        private final D3D12MemoryManager memoryManager;
        private final long creationTime;
        private volatile boolean mapped = false;
        private volatile long mapPointer = 0;

        public D3D12Buffer(long handle, BufferDesc desc, D3D12MemoryManager memoryManager) {
            this.handle = handle;
            this.desc = desc;
            this.memoryManager = memoryManager;
            this.creationTime = System.nanoTime();
        }

        public long getHandle() { return handle; }
        public BufferDesc getDesc() { return desc; }
        public long getSize() { return desc.size; }
        public int getUsage() { return desc.usage; }
        public long getBindFlags() { return desc.bindFlags; }
        public int getBufferType() { return desc.bufferType; }
        public int getElementSize() { return desc.elementSize; }
        public String getDebugName() { return desc.debugName; }
        public long getCreationTime() { return creationTime; }
        public boolean isMapped() { return mapped; }
        public long getMapPointer() { return mapPointer; }
        public D3D12MemoryManager getMemoryManager() { return memoryManager; }

        public void setMapped(boolean mapped, long mapPointer) {
            this.mapped = mapped;
            this.mapPointer = mapPointer;
        }

        @Override
        public String toString() {
            return String.format("D3D12Buffer[handle=0x%x, %s]", handle, desc);
        }
    }

    // Buffer storage
    private final Map<Long, D3D12Buffer> buffers;
    private final Map<String, D3D12Buffer> namedBuffers;
    private final D3D12MemoryManager memoryManager;

    // Upload management
    private final Map<Integer, ByteBuffer> uploadBuffers;
    private final AtomicInteger uploadBufferCounter;

    // Statistics
    private final AtomicLong totalBuffersCreated = new AtomicLong(0);
    private final AtomicLong totalMemoryAllocated = new AtomicLong(0);
    private final AtomicLong totalUploads = new AtomicLong(0);
    private final AtomicLong totalUploadBytes = new AtomicLong(0);

    public D3D12BufferManager(D3D12MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        this.buffers = new ConcurrentHashMap<>();
        this.namedBuffers = new ConcurrentHashMap<>();
        this.uploadBuffers = new HashMap<>();
        this.uploadBufferCounter = new AtomicInteger(0);

        LOGGER.info("D3D12 Buffer Manager initialized");
    }

    /**
     * Create vertex buffer
     */
    public D3D12Buffer createVertexBuffer(int size, int elementSize, int usage, String debugName) {
        return createBuffer(size, usage, BIND_VERTEX_BUFFER, CPU_ACCESS_NONE, BUFFER_TYPE_VERTEX, elementSize, debugName);
    }

    /**
     * Create index buffer
     */
    public D3D12Buffer createIndexBuffer(int size, int elementSize, int usage, String debugName) {
        return createBuffer(size, usage, BIND_INDEX_BUFFER, CPU_ACCESS_NONE, BUFFER_TYPE_INDEX, elementSize, debugName);
    }

    /**
     * Create constant buffer
     */
    public D3D12Buffer createConstantBuffer(int size, int usage, String debugName) {
        // Constant buffers must be 256-byte aligned
        int alignedSize = (size + 255) & ~255;
        return createBuffer(alignedSize, usage, BIND_CONSTANT_BUFFER, CPU_ACCESS_WRITE, BUFFER_TYPE_CONSTANT, 256, debugName);
    }

    /**
     * Create structured buffer
     */
    public D3D12Buffer createStructuredBuffer(int elementCount, int elementSize, int usage, String debugName) {
        int size = elementCount * elementSize;
        long bindFlags = BIND_SHADER_RESOURCE | BIND_UNORDERED_ACCESS;
        return createBuffer(size, usage, bindFlags, CPU_ACCESS_NONE, BUFFER_TYPE_STRUCTURED, elementSize, debugName);
    }

    /**
     * Create upload buffer
     */
    public D3D12Buffer createUploadBuffer(int size, String debugName) {
        return createBuffer(size, USAGE_DYNAMIC, 0L, CPU_ACCESS_WRITE, BUFFER_TYPE_UPLOAD, 1, debugName);
    }

    /**
     * Create buffer from description
     */
    public D3D12Buffer createBuffer(int size, int usage, long bindFlags, int cpuAccessFlags,
                                   int bufferType, int elementSize, String debugName) {
        BufferDesc desc = new BufferDesc(size, usage, bindFlags, cpuAccessFlags, bufferType, elementSize, debugName);
        return createBuffer(desc);
    }

    /**
     * Create buffer from description
     */
    public D3D12Buffer createBuffer(BufferDesc desc) {
        if (desc.size <= 0) {
            LOGGER.warn("Cannot create buffer with non-positive size: {}", desc.size);
            return null;
        }

        LOGGER.debug("Creating buffer: {}", desc);

        long startTime = System.nanoTime();

        // Determine heap type and allocation flags based on usage
        int heapType = getHeapTypeForUsage(desc.usage, desc.cpuAccessFlags);
        int allocationFlags = getAllocationFlagsForUsage(desc.usage);

        // Create buffer through memory manager
        long handle = memoryManager.createBuffer(null, desc.size, desc.elementSize, heapType, allocationFlags);
        if (handle == 0) {
            LOGGER.error("Failed to create buffer: {}", desc);
            return null;
        }

        D3D12Buffer buffer = new D3D12Buffer(handle, desc, memoryManager);

        // Track buffer
        buffers.put(handle, buffer);
        if (desc.debugName != null) {
            namedBuffers.put(desc.debugName, buffer);
        }

        // Update statistics
        totalBuffersCreated.incrementAndGet();
        totalMemoryAllocated.addAndGet(desc.size);

        long creationTime = System.nanoTime() - startTime;
        LOGGER.debug("Created buffer: {}, handle=0x{}, time={}ms",
            desc.debugName, Long.toHexString(handle), creationTime / 1_000_000);

        return buffer;
    }

    /**
     * Get buffer by handle
     */
    public D3D12Buffer getBuffer(long handle) {
        return buffers.get(handle);
    }

    /**
     * Get buffer by name
     */
    public D3D12Buffer getBuffer(String name) {
        return namedBuffers.get(name);
    }

    /**
     * Upload data to buffer (for static buffers)
     */
    public boolean uploadData(D3D12Buffer buffer, byte[] data) {
        if (buffer == null || data == null || buffer.getDesc().size < data.length) {
            return false;
        }

        LOGGER.trace("Uploading {} bytes to buffer: {}", data.length, buffer.getDebugName());

        try {
            // Create temporary upload buffer
            D3D12Buffer uploadBuffer = createUploadBuffer(data.length, "Upload_" + buffer.getDebugName());
            if (uploadBuffer == null) {
                return false;
            }

            // Map upload buffer and copy data
            if (!mapBuffer(uploadBuffer)) {
                releaseBuffer(uploadBuffer);
                return false;
            }

            long uploadPtr = uploadBuffer.getMapPointer();
            if (uploadPtr != 0) {
                // Copy data to upload buffer (this would need native implementation)
                copyDataToPtr(data, uploadPtr);
            }

            unmapBuffer(uploadBuffer);

            // Copy from upload buffer to destination buffer
            boolean success = copyBuffer(uploadBuffer, buffer, data.length);

            // Release upload buffer
            releaseBuffer(uploadBuffer);

            // Update statistics
            totalUploads.incrementAndGet();
            totalUploadBytes.addAndGet(data.length);

            return success;

        } catch (Exception e) {
            LOGGER.error("Failed to upload data to buffer: {}", buffer.getDebugName(), e);
            return false;
        }
    }

    /**
     * Upload data to buffer with offset
     */
    public boolean uploadData(D3D12Buffer buffer, int offset, byte[] data) {
        if (buffer == null || data == null || offset < 0 || offset + data.length > buffer.getDesc().size) {
            return false;
        }

        LOGGER.trace("Uploading {} bytes to buffer: {} at offset {}", data.length, buffer.getDebugName(), offset);

        try {
            // Create temporary upload buffer
            D3D12Buffer uploadBuffer = createUploadBuffer(data.length, "Upload_" + buffer.getDebugName());
            if (uploadBuffer == null) {
                return false;
            }

            // Map upload buffer and copy data
            if (!mapBuffer(uploadBuffer)) {
                releaseBuffer(uploadBuffer);
                return false;
            }

            long uploadPtr = uploadBuffer.getMapPointer();
            if (uploadPtr != 0) {
                copyDataToPtr(data, uploadPtr);
            }

            unmapBuffer(uploadBuffer);

            // Copy from upload buffer to destination buffer at offset
            boolean success = copyBufferWithOffset(uploadBuffer, buffer, offset, data.length);

            // Release upload buffer
            releaseBuffer(uploadBuffer);

            // Update statistics
            totalUploads.incrementAndGet();
            totalUploadBytes.addAndGet(data.length);

            return success;

        } catch (Exception e) {
            LOGGER.error("Failed to upload data to buffer: {} at offset {}", buffer.getDebugName(), offset, e);
            return false;
        }
    }

    /**
     * Map buffer for CPU access
     */
    public boolean mapBuffer(D3D12Buffer buffer) {
        if (buffer == null || buffer.isMapped()) {
            return false;
        }

        try {
            long mapPointer = VitraD3D12Native.mapBuffer(buffer.getHandle());
            if (mapPointer != 0) {
                buffer.setMapped(true, mapPointer);
                LOGGER.trace("Mapped buffer: {}", buffer.getDebugName());
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to map buffer: {}", buffer.getDebugName(), e);
        }

        return false;
    }

    /**
     * Unmap buffer
     */
    public void unmapBuffer(D3D12Buffer buffer) {
        if (buffer == null || !buffer.isMapped()) {
            return;
        }

        try {
            VitraD3D12Native.unmapBuffer(buffer.getHandle());
            buffer.setMapped(false, 0);
            LOGGER.trace("Unmapped buffer: {}", buffer.getDebugName());
        } catch (Exception e) {
            LOGGER.error("Failed to unmap buffer: {}", buffer.getDebugName(), e);
        }
    }

    /**
     * Copy data between buffers
     */
    private boolean copyBuffer(D3D12Buffer src, D3D12Buffer dst, int size) {
        return copyBufferWithOffset(src, dst, 0, size);
    }

    /**
     * Copy data between buffers with offset
     */
    private boolean copyBufferWithOffset(D3D12Buffer src, D3D12Buffer dst, int dstOffset, int size) {
        if (src == null || dst == null || size <= 0) {
            return false;
        }

        return VitraD3D12Native.copyBuffer(src.getHandle(), dst.getHandle(), dstOffset, size);
    }

    /**
     * Copy data to native pointer
     */
    private void copyDataToPtr(byte[] data, long ptr) {
        // This would need native implementation
        // For now, it's a placeholder
    }

    /**
     * Release buffer
     */
    public void releaseBuffer(D3D12Buffer buffer) {
        if (buffer == null) {
            return;
        }

        // Unmap if currently mapped
        if (buffer.isMapped()) {
            unmapBuffer(buffer);
        }

        // Remove from tracking
        buffers.remove(buffer.getHandle());
        if (buffer.getDebugName() != null) {
            namedBuffers.remove(buffer.getDebugName());
        }

        // Release through memory manager
        memoryManager.releaseResource(buffer.getHandle());

        // Update statistics
        totalMemoryAllocated.addAndGet(-buffer.getSize());

        LOGGER.debug("Released buffer: {}", buffer.getDebugName());
    }

    /**
     * Release buffer by name
     */
    public void releaseBuffer(String name) {
        D3D12Buffer buffer = namedBuffers.remove(name);
        if (buffer != null) {
            releaseBuffer(buffer);
        }
    }

    /**
     * Get heap type for usage
     */
    private int getHeapTypeForUsage(int usage, int cpuAccess) {
        switch (usage) {
            case USAGE_DEFAULT:
                return D3D12MemoryManager.HEAP_TYPE_DEFAULT;
            case USAGE_DYNAMIC:
                return D3D12MemoryManager.HEAP_TYPE_UPLOAD;
            case USAGE_STAGING:
                return D3D12MemoryManager.HEAP_TYPE_READBACK;
            case USAGE_IMMUTABLE:
                return D3D12MemoryManager.HEAP_TYPE_DEFAULT;
            default:
                return D3D12MemoryManager.HEAP_TYPE_DEFAULT;
        }
    }

    /**
     * Get allocation flags for usage
     */
    private int getAllocationFlagsForUsage(int usage) {
        int flags = D3D12MemoryManager.ALLOC_FLAG_NONE;

        if (usage == USAGE_DYNAMIC || usage == USAGE_STAGING) {
            flags |= D3D12MemoryManager.ALLOC_FLAG_MAPPED;
        }

        return flags;
    }

    /**
     * Get statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Buffer Manager Statistics:\n");
        stats.append("  Total Buffers Created: ").append(totalBuffersCreated.get()).append("\n");
        stats.append("  Active Buffers: ").append(buffers.size()).append("\n");
        stats.append("  Named Buffers: ").append(namedBuffers.size()).append("\n");
        stats.append("  Total Memory Allocated: ").append(totalMemoryAllocated.get() / 1024).append(" KB\n");
        stats.append("  Total Uploads: ").append(totalUploads.get()).append("\n");
        stats.append("  Total Upload Bytes: ").append(totalUploadBytes.get() / 1024).append(" KB\n");

        // Count buffer types
        Map<Integer, AtomicInteger> bufferTypes = new HashMap<>();
        for (D3D12Buffer buffer : buffers.values()) {
            bufferTypes.computeIfAbsent(buffer.getBufferType(), k -> new AtomicInteger(0)).incrementAndGet();
        }

        stats.append("\n--- Buffer Types ---\n");
        for (Map.Entry<Integer, AtomicInteger> entry : bufferTypes.entrySet()) {
            String typeName = getBufferTypeName(entry.getKey());
            stats.append("  ").append(typeName).append(": ").append(entry.getValue().get()).append("\n");
        }

        return stats.toString();
    }

    /**
     * Get buffer type name for logging
     */
    private static String getBufferTypeName(int bufferType) {
        switch (bufferType) {
            case BUFFER_TYPE_VERTEX: return "Vertex";
            case BUFFER_TYPE_INDEX: return "Index";
            case BUFFER_TYPE_CONSTANT: return "Constant";
            case BUFFER_TYPE_STRUCTURED: return "Structured";
            case BUFFER_TYPE_RAW: return "Raw";
            case BUFFER_TYPE_UPLOAD: return "Upload";
            default: return "Unknown(" + bufferType + ")";
        }
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        LOGGER.info("Cleaning up D3D12 Buffer Manager");

        // Release all buffers
        for (D3D12Buffer buffer : new ArrayList<>(buffers.values())) {
            releaseBuffer(buffer);
        }

        // Clear tracking
        buffers.clear();
        namedBuffers.clear();
        uploadBuffers.clear();

        LOGGER.info("D3D12 Buffer Manager cleanup completed");
    }
}