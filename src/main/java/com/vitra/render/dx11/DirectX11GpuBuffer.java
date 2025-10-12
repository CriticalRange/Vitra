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
        super(usage, data.remaining());
        this.debugLabel = debugLabel;
        this.isIndexBuffer = (usage & USAGE_INDEX) != 0;
        this.isUniformBuffer = (usage & USAGE_UNIFORM) != 0;

        // Extract byte array from ByteBuffer
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        data.rewind();

        createNativeBuffer(bytes, bytes.length);

        LOGGER.debug("Created DirectX11GpuBuffer with data: {} (size={}, usage=0x{}, type={})",
            debugLabel, bytes.length, Integer.toHexString(usage), getBufferType());
    }

    /**
     * Create the native DirectX 11 buffer
     */
    private void createNativeBuffer(byte[] data, int size) {
        if (closed) {
            throw new IllegalStateException("Buffer already closed: " + debugLabel);
        }

        if (isIndexBuffer) {
            // Create index buffer - assume 32-bit indices by default
            int indexFormat = VitraNativeRenderer.INDEX_FORMAT_32_BIT;
            nativeHandle = VitraNativeRenderer.createIndexBuffer(data, size, indexFormat);
        } else if (isUniformBuffer) {
            // For uniform/constant buffers, we'll use a separate method later
            // For now, create as vertex buffer
            nativeHandle = VitraNativeRenderer.createVertexBuffer(data, size, 0);
        } else {
            // Create vertex buffer - stride is 0 for now (will be set later based on vertex format)
            nativeHandle = VitraNativeRenderer.createVertexBuffer(data, size, 0);
        }

        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create native buffer: " + debugLabel);
        }

        LOGGER.debug("Created native DirectX 11 buffer: {} (handle=0x{})",
            debugLabel, Long.toHexString(nativeHandle));
    }

    /**
     * Get the native DirectX 11 buffer handle
     * Creates the buffer on first access if it hasn't been created yet
     */
    public long getNativeHandle() {
        // Lazy initialization: create buffer on first access if not yet created
        if (nativeHandle == 0 && !closed) {
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
