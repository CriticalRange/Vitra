package com.vitra.render.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * DirectX 11 implementation of GpuBuffer.
 * Wraps D3D11 vertex, index, and constant buffers.
 */
public class VitraGpuBuffer extends GpuBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GpuBuffer");
    private static int bufferLogCount = 0;
    
    private final String name;
    private final boolean isConstantBuffer;
    
    // Native D3D11 buffer handle
    private long nativeHandle = 0;
    private boolean closed = false;
    
    public VitraGpuBuffer(String name, int usage, long size) {
        super(usage, size);
        this.name = name;
        // Determine buffer type based on usage flags
        this.isConstantBuffer = (usage & GpuBuffer.USAGE_UNIFORM) != 0;
    }
    
    /**
     * Check if this is an index buffer based on usage flags.
     */
    private boolean isIndexBuffer() {
        return (usage() & GpuBuffer.USAGE_INDEX) != 0;
    }
    
    /**
     * Upload data to the buffer.
     */
    public void upload(ByteBuffer data) {
        if (closed) return;
        
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        if (nativeHandle == 0) {
            // Create new buffer based on type
            if (isConstantBuffer) {
                // Create constant buffer for uniforms
                nativeHandle = VitraD3D11Renderer.createConstantBuffer(bytes.length);
                if (nativeHandle != 0) {
                    // Initialize with data
                    VitraD3D11Renderer.updateConstantBuffer(nativeHandle, bytes);
                    LOGGER.debug("Created constant buffer '{}': handle=0x{}, size={}", 
                        name, Long.toHexString(nativeHandle), bytes.length);
                } else {
                    LOGGER.error("Failed to create constant buffer '{}'", name);
                }
            } else if (isIndexBuffer()) {
                // Create index buffer
                // Format: DXGI_FORMAT_R16_UINT (57) for 16-bit indices, DXGI_FORMAT_R32_UINT (42) for 32-bit
                int format = bytes.length > 65536 * 2 ? 42 : 57; // Use 32-bit for large buffers
                nativeHandle = VitraD3D11Renderer.createIndexBuffer(bytes, bytes.length, format);
                if (bufferLogCount < 10) {
                    LOGGER.info("[IB {}] Created index buffer '{}': handle=0x{}, size={}", 
                        bufferLogCount, name, Long.toHexString(nativeHandle), bytes.length);
                    bufferLogCount++;
                }
            } else {
                // Create vertex buffer
                nativeHandle = VitraD3D11Renderer.createVertexBuffer(bytes, bytes.length, 32); // Default stride
                if (bufferLogCount < 10) {
                    LOGGER.info("[VB {}] Created vertex buffer '{}': handle=0x{}, size={}", 
                        bufferLogCount, name, Long.toHexString(nativeHandle), bytes.length);
                    bufferLogCount++;
                }
            }
        } else {
            // Update existing buffer
            if (isConstantBuffer) {
                VitraD3D11Renderer.updateConstantBuffer(nativeHandle, bytes);
            } else {
                // For vertex/index buffers, we may need to recreate or use different update method
                LOGGER.debug("Cannot dynamically update buffer '{}' - recreate instead", name);
            }
        }
    }
    
    /**
     * Create the buffer without initial data (lazy creation).
     * Useful for creating constant buffers that will be updated later.
     */
    public void ensureCreated() {
        if (nativeHandle == 0 && isConstantBuffer) {
            int size = (int) Math.min(size(), Integer.MAX_VALUE);
            nativeHandle = VitraD3D11Renderer.createConstantBuffer(size);
            if (nativeHandle != 0) {
                LOGGER.debug("Created empty constant buffer '{}': handle=0x{}, size={}", 
                    name, Long.toHexString(nativeHandle), size);
            } else {
                LOGGER.error("Failed to create empty constant buffer '{}'", name);
            }
        }
    }
    
    public boolean isConstantBuffer() {
        return isConstantBuffer;
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        if (!closed && nativeHandle != 0) {
            VitraD3D11Renderer.destroyResource(nativeHandle);
            nativeHandle = 0;
            closed = true;
            LOGGER.debug("Closed buffer '{}' (type={})", 
                name, isConstantBuffer ? "constant" : "vertex");
        }
    }
    
    public String getName() {
        return name;
    }
    
    public long getNativeHandle() {
        return nativeHandle;
    }
}
