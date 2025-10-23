package com.vitra.render.jni;

import com.vitra.render.constants.RenderConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * DirectX 11 buffer manager that replaces BGFX buffer operations
 */
public class D3D11BufferManager extends AbstractBufferManager {
    private final Map<Long, BufferInfo> vertexBuffers = new ConcurrentHashMap<>();
    private final Map<Long, BufferInfo> indexBuffers = new ConcurrentHashMap<>();

    public D3D11BufferManager() {
        super(D3D11BufferManager.class);
    }

    private static class BufferInfo {
        long handle;
        int size;
        int stride;
        int type; // VERTEX or INDEX

        BufferInfo(long handle, int size, int stride, int type) {
            this.handle = handle;
            this.size = size;
            this.stride = stride;
            this.type = type;
        }
    }

    // Using centralized constants from RenderConstants

    /**
     * Create a vertex buffer
     */
    public long createVertexBuffer(ByteBuffer data, int stride) {
        if (data == null || !data.hasRemaining()) {
            logger.error("Invalid vertex buffer data");
            return 0;
        }

        byte[] dataArray = new byte[data.remaining()];
        data.get(dataArray);

        long handle = VitraD3D11Renderer.createVertexBuffer(dataArray, dataArray.length, stride);
        if (handle != 0) {
            vertexBuffers.put(handle, new BufferInfo(handle, dataArray.length, stride, RenderConstants.BufferType.VERTEX));
            logger.debug("Created vertex buffer: handle=0x{}, size={}, stride={}",
                Long.toHexString(handle), dataArray.length, stride);
        } else {
            logger.error("Failed to create vertex buffer");
        }

        return handle;
    }

    /**
     * Create an index buffer
     */
    public long createIndexBuffer(ByteBuffer data, boolean use32Bit) {
        if (data == null || !data.hasRemaining()) {
            logger.error("Invalid index buffer data");
            return 0;
        }

        byte[] dataArray = new byte[data.remaining()];
        data.get(dataArray);

        int format = use32Bit ? VitraD3D11Renderer.INDEX_FORMAT_32_BIT : VitraD3D11Renderer.INDEX_FORMAT_16_BIT;
        long handle = VitraD3D11Renderer.createIndexBuffer(dataArray, dataArray.length, format);
        if (handle != 0) {
            indexBuffers.put(handle, new BufferInfo(handle, dataArray.length, 0, RenderConstants.BufferType.INDEX));
            logger.debug("Created index buffer: handle=0x{}, size={}, format={}",
                Long.toHexString(handle), dataArray.length, use32Bit ? "32-bit" : "16-bit");
        } else {
            logger.error("Failed to create index buffer");
        }

        return handle;
    }

    /**
     * Destroy a buffer
     */
    public void destroyBuffer(long handle) {
        if (handle == 0) return;

        BufferInfo info = vertexBuffers.remove(handle);
        if (info == null) {
            info = indexBuffers.remove(handle);
        }

        if (info != null) {
            VitraD3D11Renderer.destroyResource(handle);
            logger.debug("Destroyed buffer: handle=0x{}, type={}",
                Long.toHexString(handle), info.type == RenderConstants.BufferType.VERTEX ? "vertex" : "index");
        }
    }

    /**
     * Update vertex buffer data (recreate for simplicity)
     */
    public long updateVertexBuffer(long oldHandle, ByteBuffer data, int stride) {
        if (oldHandle != 0) {
            destroyBuffer(oldHandle);
        }
        return createVertexBuffer(data, stride);
    }

    /**
     * Update index buffer data (recreate for simplicity)
     */
    public long updateIndexBuffer(long oldHandle, ByteBuffer data, boolean use32Bit) {
        if (oldHandle != 0) {
            destroyBuffer(oldHandle);
        }
        return createIndexBuffer(data, use32Bit);
    }

    /**
     * Get buffer information
     */
    public BufferInfo getBufferInfo(long handle) {
        BufferInfo info = vertexBuffers.get(handle);
        if (info == null) {
            info = indexBuffers.get(handle);
        }
        return info;
    }


    /**
     * Get buffer statistics
     */
    @Override
    public String getBufferStats() {
        return String.format("Vertex buffers: %d, Index buffers: %d",
            vertexBuffers.size(), indexBuffers.size());
    }

    /**
     * Check if a handle is valid
     */
    public boolean isValidHandle(long handle) {
        return vertexBuffers.containsKey(handle) || indexBuffers.containsKey(handle);
    }

    /**
     * Get total memory usage
     */
    public long getTotalMemoryUsage() {
        long total = 0;
        for (BufferInfo info : vertexBuffers.values()) {
            total += info.size;
        }
        for (BufferInfo info : indexBuffers.values()) {
            total += info.size;
        }
        return total;
    }

    @Override
    public void clearAll() {
        // Destroy all vertex buffers
        for (long handle : vertexBuffers.keySet()) {
            VitraD3D11Renderer.destroyResource(handle);
        }
        vertexBuffers.clear();

        // Destroy all index buffers
        for (long handle : indexBuffers.keySet()) {
            VitraD3D11Renderer.destroyResource(handle);
        }
        indexBuffers.clear();

        logger.info("Cleared all buffers");
    }

    @Override
    public String getRendererType() {
        return "DirectX 11";
    }
}