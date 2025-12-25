package com.vitra.render.bridge;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * DirectX 11 implementation of GpuTexture.
 * Wraps a D3D11 Texture2D resource.
 */
public class VitraGpuTexture extends GpuTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GpuTexture");
    
    // Native D3D11 texture handle
    private long nativeHandle = 0;
    private boolean closed = false;
    
    public VitraGpuTexture(int usage, String name, TextureFormat format,
                           int width, int height, int depth, int mipLevels) {
        super(usage, name, format, width, height, depth, mipLevels);
    }
    
    /**
     * Create native D3D11 texture with initial data.
     */
    public void upload(ByteBuffer data) {
        if (closed) return;
        
        // Convert ByteBuffer to byte array for JNI
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        // Map TextureFormat to D3D11 format
        int dxgiFormat = mapTextureFormat(getFormat());
        
        // Create texture through JNI
        nativeHandle = VitraD3D11Renderer.createTextureFromData(bytes, getWidth(0), getHeight(0), dxgiFormat);
        
        if (nativeHandle != 0) {
            LOGGER.debug("Created native texture '{}': handle=0x{}", getLabel(), Long.toHexString(nativeHandle));
        } else {
            LOGGER.error("Failed to create native texture '{}'", getLabel());
        }
    }
    
    /**
     * Update texture mip level data.
     */
    public void uploadMipLevel(int level, ByteBuffer data) {
        if (closed || nativeHandle == 0) return;
        
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        int mipWidth = getWidth(level);
        int mipHeight = getHeight(level);
        
        VitraD3D11Renderer.updateTextureMipLevel(nativeHandle, bytes, mipWidth, mipHeight, level);
    }
    
    private int mapTextureFormat(TextureFormat format) {
        // Map Minecraft texture format to DXGI format
        return switch (format) {
            case RGBA8 -> 28; // DXGI_FORMAT_R8G8B8A8_UNORM
            case RED8 -> 61; // DXGI_FORMAT_R8_UNORM
            case RED8I -> 62; // DXGI_FORMAT_R8_SINT
            case DEPTH32 -> 40; // DXGI_FORMAT_D32_FLOAT
        };
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
            LOGGER.debug("Closed texture '{}'", getLabel());
        }
    }
    
    public long getNativeHandle() {
        return nativeHandle;
    }
    
    public void setNativeHandle(long handle) {
        this.nativeHandle = handle;
    }
}
