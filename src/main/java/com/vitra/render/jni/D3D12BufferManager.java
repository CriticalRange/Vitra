package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DirectX 12 Ultimate buffer manager with support for ray tracing acceleration structures
 */
public class D3D12BufferManager implements IBufferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D12BufferManager.class);

    // Buffer type constants
    public static final int BUFFER_TYPE_VERTEX = 1;
    public static final int BUFFER_TYPE_INDEX = 2;
    public static final int BUFFER_TYPE_CONSTANT = 3;
    public static final int BUFFER_TYPE_STRUCTURED = 4;
    public static final int BUFFER_TYPE_RAYTRACING_TLAS = 5;
    public static final int BUFFER_TYPE_RAYTRACING_BLAS = 6;
    public static final int BUFFER_TYPE_VRS_MAP = 7;

    // Resource tracking
    private final Map<Long, BufferInfo> bufferRegistry = new ConcurrentHashMap<>();
    private final AtomicLong nextBufferId = new AtomicLong(1);

    // Statistics
    private long totalMemoryUsed = 0;
    private int bufferCount = 0;
    private int rayTracingBuffersCount = 0;

    // Native methods
    private static native long nativeCreateBuffer(int type, long size, boolean gpuOnly);
    private static native long nativeCreateBufferWithData(int type, byte[] data, boolean gpuOnly);
    private static native void nativeUpdateBuffer(long bufferHandle, byte[] data, long offset);
    private static native byte[] nativeReadBuffer(long bufferHandle, long offset, int size);
    private static native void nativeDestroyBuffer(long bufferHandle);
    private static native long nativeGetBufferGpuAddress(long bufferHandle);
    private static native void mapBuffer(long bufferHandle);
    private static native void unmapBuffer(long bufferHandle);

    // Ray tracing specific native methods
    private static native long nativeCreateBottomLevelAccelerationStructure(long vertexBuffer, long indexBuffer, int vertexCount, int indexCount);
    private static native long nativeCreateTopLevelAccelerationStructure(long[] blasHandles, long[] instanceTransforms);
    private static native void nativeUpdateAccelerationStructure(long tlasHandle, long[] blasHandles, long[] instanceTransforms);
    private static native boolean nativeIsAccelerationStructureReady(long asHandle);

    // Buffer creation methods
    public long createBuffer(int type, long size, boolean gpuOnly) {
        long bufferId = nextBufferId.getAndIncrement();
        long nativeHandle = nativeCreateBuffer(type, size, gpuOnly);

        if (nativeHandle != 0L) {
            BufferInfo info = new BufferInfo(bufferId, type, size, gpuOnly);
            bufferRegistry.put(nativeHandle, info);

            totalMemoryUsed += size;
            bufferCount++;

            if (type == BUFFER_TYPE_RAYTRACING_TLAS || type == BUFFER_TYPE_RAYTRACING_BLAS) {
                rayTracingBuffersCount++;
            }

            LOGGER.debug("Created buffer {} (type: {}, size: {}, gpuOnly: {})",
                bufferId, getBufferTypeName(type), size, gpuOnly);
        } else {
            LOGGER.error("Failed to create buffer of type {} with size {}", type, size);
        }

        return nativeHandle;
    }

    public long createVertexBuffer(long size, boolean gpuOnly) {
        return createBuffer(BUFFER_TYPE_VERTEX, size, gpuOnly);
    }

    public long createIndexBuffer(long size, boolean gpuOnly) {
        return createBuffer(BUFFER_TYPE_INDEX, size, gpuOnly);
    }

    public long createConstantBuffer(long size, boolean gpuOnly) {
        // Constant buffers must be 256-byte aligned in DirectX 12
        long alignedSize = (size + 255) & ~255;
        return createBuffer(BUFFER_TYPE_CONSTANT, alignedSize, gpuOnly);
    }

    public long createStructuredBuffer(long elementSize, long elementCount, boolean gpuOnly) {
        return createBuffer(BUFFER_TYPE_STRUCTURED, elementSize * elementCount, gpuOnly);
    }

    // Buffer creation with initial data
    public long createBufferWithData(int type, byte[] data, boolean gpuOnly) {
        long bufferId = nextBufferId.getAndIncrement();
        long nativeHandle = nativeCreateBufferWithData(type, data, gpuOnly);

        if (nativeHandle != 0L) {
            BufferInfo info = new BufferInfo(bufferId, type, data.length, gpuOnly);
            bufferRegistry.put(nativeHandle, info);

            totalMemoryUsed += data.length;
            bufferCount++;

            if (type == BUFFER_TYPE_RAYTRACING_TLAS || type == BUFFER_TYPE_RAYTRACING_BLAS) {
                rayTracingBuffersCount++;
            }

            LOGGER.debug("Created buffer {} with data (type: {}, size: {}, gpuOnly: {})",
                bufferId, getBufferTypeName(type), data.length, gpuOnly);
        } else {
            LOGGER.error("Failed to create buffer with data of type {} with size {}", type, data.length);
        }

        return nativeHandle;
    }

    // Ray tracing acceleration structure creation
    public long createBottomLevelAccelerationStructure(long vertexBuffer, long indexBuffer, int vertexCount, int indexCount) {
        long blasHandle = nativeCreateBottomLevelAccelerationStructure(vertexBuffer, indexBuffer, vertexCount, indexCount);

        if (blasHandle != 0L) {
            BufferInfo info = new BufferInfo(nextBufferId.getAndIncrement(), BUFFER_TYPE_RAYTRACING_BLAS, 0, true);
            bufferRegistry.put(blasHandle, info);
            rayTracingBuffersCount++;
            bufferCount++;

            LOGGER.debug("Created Bottom Level Acceleration Structure for {} vertices, {} indices", vertexCount, indexCount);
        } else {
            LOGGER.error("Failed to create Bottom Level Acceleration Structure");
        }

        return blasHandle;
    }

    public long createTopLevelAccelerationStructure(long[] blasHandles, long[] instanceTransforms) {
        if (blasHandles.length != instanceTransforms.length) {
            LOGGER.error("BLAS handles and instance transforms arrays must have the same length");
            return 0L;
        }

        long tlasHandle = nativeCreateTopLevelAccelerationStructure(blasHandles, instanceTransforms);

        if (tlasHandle != 0L) {
            BufferInfo info = new BufferInfo(nextBufferId.getAndIncrement(), BUFFER_TYPE_RAYTRACING_TLAS, 0, true);
            bufferRegistry.put(tlasHandle, info);
            rayTracingBuffersCount++;
            bufferCount++;

            LOGGER.debug("Created Top Level Acceleration Structure with {} instances", blasHandles.length);
        } else {
            LOGGER.error("Failed to create Top Level Acceleration Structure");
        }

        return tlasHandle;
    }

    // Variable Rate Shading
    public long createVRSMap(int width, int height) {
        // VRS maps are typically 1/8 or 1/16 resolution
        int vrsWidth = Math.max(1, width / 8);
        int vrsHeight = Math.max(1, height / 8);
        long size = vrsWidth * vrsHeight; // 1 byte per tile

        return createBuffer(BUFFER_TYPE_VRS_MAP, size, true);
    }

    // Buffer operations
    public boolean updateBuffer(long bufferHandle, byte[] data, long offset) {
        if (!bufferRegistry.containsKey(bufferHandle)) {
            LOGGER.error("Attempted to update unknown buffer handle: {}", bufferHandle);
            return false;
        }

        try {
            nativeUpdateBuffer(bufferHandle, data, offset);
            LOGGER.debug("Updated buffer {} with {} bytes at offset {}", bufferHandle, data.length, offset);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to update buffer {}", bufferHandle, e);
            return false;
        }
    }

    public byte[] readBuffer(long bufferHandle, long offset, int size) {
        if (!bufferRegistry.containsKey(bufferHandle)) {
            LOGGER.error("Attempted to read unknown buffer handle: {}", bufferHandle);
            return new byte[0];
        }

        try {
            return nativeReadBuffer(bufferHandle, offset, size);
        } catch (Exception e) {
            LOGGER.error("Failed to read buffer {}", bufferHandle, e);
            return new byte[0];
        }
    }

    // Resource management
    public void destroyBuffer(long bufferHandle) {
        BufferInfo info = bufferRegistry.remove(bufferHandle);
        if (info != null) {
            nativeDestroyBuffer(bufferHandle);

            totalMemoryUsed -= info.size;
            bufferCount--;

            if (info.type == BUFFER_TYPE_RAYTRACING_TLAS || info.type == BUFFER_TYPE_RAYTRACING_BLAS) {
                rayTracingBuffersCount--;
            }

            LOGGER.debug("Destroyed buffer {} (type: {}, size: {})",
                info.id, getBufferTypeName(info.type), info.size);
        } else {
            LOGGER.warn("Attempted to destroy unknown buffer handle: {}", bufferHandle);
        }
    }

    // Utility methods
    public long getBufferGpuAddress(long bufferHandle) {
        if (!bufferRegistry.containsKey(bufferHandle)) {
            LOGGER.error("Attempted to get GPU address for unknown buffer handle: {}", bufferHandle);
            return 0L;
        }

        return nativeGetBufferGpuAddress(bufferHandle);
    }

    public boolean isAccelerationStructureReady(long asHandle) {
        if (!bufferRegistry.containsKey(asHandle)) {
            return false;
        }

        BufferInfo info = bufferRegistry.get(asHandle);
        if (info.type != BUFFER_TYPE_RAYTRACING_TLAS && info.type != BUFFER_TYPE_RAYTRACING_BLAS) {
            return false;
        }

        return nativeIsAccelerationStructureReady(asHandle);
    }

    // Batch operations
    public void updateAccelerationStructure(long tlasHandle, long[] blasHandles, long[] instanceTransforms) {
        if (blasHandles.length != instanceTransforms.length) {
            LOGGER.error("BLAS handles and instance transforms arrays must have the same length");
            return;
        }

        if (!bufferRegistry.containsKey(tlasHandle)) {
            LOGGER.error("TLAS handle not found: {}", tlasHandle);
            return;
        }

        nativeUpdateAccelerationStructure(tlasHandle, blasHandles, instanceTransforms);
        LOGGER.debug("Updated acceleration structure with {} instances", blasHandles.length);
    }

    // Statistics and monitoring
    public String getBufferStats() {
        return String.format("Buffers - Count: %d, Memory: %.2f MB, RT Buffers: %d",
            bufferCount, totalMemoryUsed / (1024.0 * 1024.0), rayTracingBuffersCount);
    }

    public long getTotalMemoryUsed() {
        return totalMemoryUsed;
    }

    public int getBufferCount() {
        return bufferCount;
    }

    public int getRayTracingBufferCount() {
        return rayTracingBuffersCount;
    }

    // IBufferManager interface implementation

    @Override
    public void initialize() {
        LOGGER.info("Initializing DirectX 12 Ultimate buffer manager");
        // No specific initialization needed for DirectX 12 JNI
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down DirectX 12 Ultimate buffer manager");
        clearAll();
    }

    @Override
    public void clearAll() {
        LOGGER.info("Destroying all buffers...");

        for (Long bufferHandle : bufferRegistry.keySet()) {
            destroyBuffer(bufferHandle);
        }

        bufferRegistry.clear();
        totalMemoryUsed = 0;
        bufferCount = 0;
        rayTracingBuffersCount = 0;

        LOGGER.info("All buffers destroyed");
    }

    @Override
    public boolean isInitialized() {
        return true; // DirectX 12 buffer manager is always ready
    }

    @Override
    public String getRendererType() {
        return "DirectX 12 Ultimate";
    }

    // Helper methods
    private String getBufferTypeName(int type) {
        switch (type) {
            case BUFFER_TYPE_VERTEX: return "Vertex";
            case BUFFER_TYPE_INDEX: return "Index";
            case BUFFER_TYPE_CONSTANT: return "Constant";
            case BUFFER_TYPE_STRUCTURED: return "Structured";
            case BUFFER_TYPE_RAYTRACING_TLAS: return "RT-TLAS";
            case BUFFER_TYPE_RAYTRACING_BLAS: return "RT-BLAS";
            case BUFFER_TYPE_VRS_MAP: return "VRS-Map";
            default: return "Unknown";
        }
    }

    // Buffer info class
    private static class BufferInfo {
        final long id;
        final int type;
        final long size;
        final boolean gpuOnly;

        BufferInfo(long id, int type, long size, boolean gpuOnly) {
            this.id = id;
            this.type = type;
            this.size = size;
            this.gpuOnly = gpuOnly;
        }
    }
}