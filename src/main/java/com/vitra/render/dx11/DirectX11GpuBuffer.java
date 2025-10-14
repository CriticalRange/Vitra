package com.vitra.render.dx11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * DirectX 11 implementation of Minecraft's GpuBuffer
 *
 * Wraps a native DirectX 11 ID3D11Buffer (vertex buffer, index buffer, or constant buffer).
 * Buffers are created with appropriate usage flags based on the Minecraft usage parameter.
 */
public class DirectX11GpuBuffer extends GpuBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11GpuBuffer");

    private long nativeHandle = 0;
    private boolean closed = false;
    private final String debugLabel;
    private final boolean isIndexBuffer;
    private final boolean isUniformBuffer;
    private int vertexStride = 0; // Track vertex stride for proper rendering

    public DirectX11GpuBuffer(int usage, int size, String debugLabel) {
        super(usage, size);
        this.debugLabel = debugLabel;
        this.isIndexBuffer = (usage & USAGE_INDEX) != 0;
        this.isUniformBuffer = (usage & USAGE_UNIFORM) != 0;

        // Defer native buffer creation until DirectX is initialized and data is provided
        // This prevents crashes when buffers are created before DirectX init completes
        LOGGER.debug("Created DirectX11GpuBuffer (deferred): {} (size={}, usage=0x{}, type={})",
            debugLabel, size, Integer.toHexString(usage), getBufferType());
    }

    public DirectX11GpuBuffer(int usage, ByteBuffer data, String debugLabel) {
        // IMPORTANT: We pass data.remaining() here, but may update size later if we expand the data
        super(usage, data.remaining());
        this.debugLabel = debugLabel;
        this.isIndexBuffer = (usage & USAGE_INDEX) != 0;
        this.isUniformBuffer = (usage & USAGE_UNIFORM) != 0;

        // Extract byte array from ByteBuffer
        byte[] bytes = new byte[data.remaining()];
        int originalPosition = data.position();
        data.get(bytes);
        data.position(originalPosition); // Restore position instead of rewind

        // CRITICAL: Check if this is a vertex buffer with potentially packed data
        // Minecraft may pass 24-byte vertices, but DirectX expects 32-byte with padding
        if (!isIndexBuffer && !isUniformBuffer && (usage & USAGE_VERTEX) != 0) {
            // Check if data size suggests 24-byte stride (tightly packed)
            int vertexCount = bytes.length / 24;
            if (bytes.length % 24 == 0 && vertexCount > 0) {
                LOGGER.warn("Detected tightly packed 24-byte vertices ({} vertices) - expanding to 32-byte stride for DirectX",
                    vertexCount);

                // Expand to 32-byte stride by adding padding
                byte[] expandedBytes = expandVertexData(bytes, vertexCount);

                // CRITICAL FIX: Update the parent GpuBuffer size to match expanded data
                // This ensures Minecraft's validation logic uses the correct buffer size
                updateSize(expandedBytes.length);

                createNativeBuffer(expandedBytes, expandedBytes.length);

                LOGGER.debug("Created DirectX11GpuBuffer with expanded data: {} (original={} bytes, expanded={} bytes, {} vertices, size={})",
                    debugLabel, bytes.length, expandedBytes.length, vertexCount, size());
                return;
            }
        }

        createNativeBuffer(bytes, bytes.length);

        LOGGER.debug("Created DirectX11GpuBuffer with data: {} (size={}, usage=0x{}, type={})",
            debugLabel, bytes.length, Integer.toHexString(usage), getBufferType());
    }

    /**
     * Update the parent GpuBuffer size field using reflection
     * This is necessary when we expand vertex data to maintain stride alignment
     */
    private void updateSize(int newSize) {
        try {
            // Access the private 'size' field in parent GpuBuffer class
            java.lang.reflect.Field sizeField = GpuBuffer.class.getDeclaredField("size");
            sizeField.setAccessible(true);
            sizeField.setInt(this, newSize);
            LOGGER.debug("Updated buffer size for {} to {} bytes", debugLabel, newSize);
        } catch (Exception e) {
            LOGGER.error("Failed to update buffer size for {} - Minecraft may reject draw calls!", debugLabel, e);
        }
    }

    /**
     * Expand tightly packed 24-byte vertices to 32-byte stride
     * Input format:  position(12) + texcoord(8) + color(4) = 24 bytes
     * Output format: position(12) + padding(4) + texcoord(8) + color(4) + padding(4) = 32 bytes
     */
    private byte[] expandVertexData(byte[] packedData, int vertexCount) {
        byte[] expanded = new byte[vertexCount * 32];

        for (int i = 0; i < vertexCount; i++) {
            int srcOffset = i * 24;
            int dstOffset = i * 32;

            // Copy position (12 bytes)
            System.arraycopy(packedData, srcOffset, expanded, dstOffset, 12);
            // Padding (4 bytes) - already zero

            // Copy texcoord (8 bytes)
            System.arraycopy(packedData, srcOffset + 12, expanded, dstOffset + 16, 8);

            // Copy color (4 bytes)
            System.arraycopy(packedData, srcOffset + 20, expanded, dstOffset + 24, 4);
            // Padding (4 bytes) - already zero
        }

        return expanded;
    }

    /**
     * Create the native DirectX 11 buffer
     */
    private void createNativeBuffer(byte[] data, int size) {
        if (closed) {
            throw new IllegalStateException("Buffer already closed: " + debugLabel);
        }

        // DirectX 11 doesn't allow zero-sized buffers - E_INVALIDARG (0x887a0005)
        if (size <= 0) {
            LOGGER.error("Cannot create buffer {} with size <= 0 (size={})", debugLabel, size);
            throw new IllegalArgumentException("DirectX 11 requires buffer size > 0, got: " + size + " for " + debugLabel);
        }

        // Validate data array if provided
        if (data != null && data.length < size) {
            LOGGER.error("Buffer data size mismatch for {}: data.length={}, requested size={}",
                debugLabel, data.length, size);
            throw new IllegalArgumentException("Data array too small for buffer size");
        }

        if (isIndexBuffer) {
            // Create index buffer - assume 32-bit indices by default
            int indexFormat = VitraNativeRenderer.INDEX_FORMAT_32_BIT;
            nativeHandle = VitraNativeRenderer.createIndexBuffer(data, size, indexFormat);
        } else if (isUniformBuffer) {
            // For uniform/constant buffers, we'll use a separate method later
            // For now, create as vertex buffer with default stride
            nativeHandle = VitraNativeRenderer.createVertexBuffer(data, size, 32);
            this.vertexStride = 32;
        } else {
            // Create vertex buffer - use default stride of 32 bytes to match native input layout
            // Format: position (12 bytes) + padding (4 bytes) + texcoord (8 bytes) + color (4 bytes) + padding (4 bytes) = 32 bytes
            // This matches the D3D11_INPUT_ELEMENT_DESC layout in vitra_d3d11.cpp:72-77
            nativeHandle = VitraNativeRenderer.createVertexBuffer(data, size, 32);
            // Store the stride for proper vertex count calculation
            this.vertexStride = 32;
        }

        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create native buffer: " + debugLabel);
        }

        LOGGER.debug("Created native DirectX 11 buffer: {} (handle=0x{})",
            debugLabel, Long.toHexString(nativeHandle));
    }

    /**
     * Set the vertex stride for this buffer (for vertex buffers only)
     */
    public void setVertexStride(int stride) {
        if (!isIndexBuffer && !isUniformBuffer) {
            this.vertexStride = stride;
            LOGGER.debug("Set vertex stride for {}: {} bytes", debugLabel, stride);
        }
    }

    /**
     * Get the vertex stride for this buffer
     */
    public int getVertexStride() {
        return vertexStride;
    }

    /**
     * Get the native DirectX 11 buffer handle
     * Creates the buffer on first access if it hasn't been created yet
     */
    public long getNativeHandle() {
        // Lazy initialization: create buffer on first access if not yet created
        if (nativeHandle == 0 && !closed) {
            // Check if buffer has valid size before creating
            if (size() <= 0) {
                LOGGER.error("Cannot create buffer {} - size is {} (must be > 0)", debugLabel, size());
                LOGGER.error("Stack trace:", new Exception("Buffer size validation"));
                return 0;
            }

            // Check if native renderer is initialized before creating buffer
            if (VitraNativeRenderer.isInitialized()) {
                LOGGER.debug("Lazy-creating native buffer for: {} (size={})", debugLabel, size());
                // Create empty buffer with zeroed data
                byte[] emptyData = new byte[size()];
                createNativeBuffer(emptyData, size());
            } else {
                LOGGER.warn("Cannot create native buffer {} - DirectX not initialized yet", debugLabel);
            }
        }
        return nativeHandle;
    }

    /**
     * Get the debug label
     */
    public String getDebugLabel() {
        return debugLabel;
    }

    /**
     * Check if this is an index buffer
     */
    public boolean isIndexBuffer() {
        return isIndexBuffer;
    }

    /**
     * Check if this is a uniform/constant buffer
     */
    public boolean isUniformBuffer() {
        return isUniformBuffer;
    }

    /**
     * Get a human-readable buffer type string
     */
    private String getBufferType() {
        if (isIndexBuffer) return "INDEX";
        if (isUniformBuffer) return "UNIFORM";
        if ((usage() & USAGE_VERTEX) != 0) return "VERTEX";
        return "GENERIC";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        if (nativeHandle != 0) {
            LOGGER.debug("Closing DirectX 11 buffer: {} (handle=0x{})",
                debugLabel, Long.toHexString(nativeHandle));

            try {
                VitraNativeRenderer.destroyResource(nativeHandle);
            } catch (Exception e) {
                LOGGER.error("Error destroying buffer: {}", debugLabel, e);
            }

            nativeHandle = 0;
        }

        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
