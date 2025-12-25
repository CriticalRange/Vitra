package com.vitra.render.bridge;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 implementation of GpuTextureView.
 * Wraps a D3D11 ShaderResourceView (SRV).
 */
public class VitraGpuTextureView extends GpuTextureView {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GpuTextureView");
    
    // Native D3D11 SRV handle
    private long nativeHandle = 0;
    private boolean closed = false;
    
    public VitraGpuTextureView(VitraGpuTexture texture, int baseMip, int mipCount) {
        super(texture, baseMip, mipCount);
        
        // TODO: Create SRV through JNI
        // For now, use the texture's native handle directly
        this.nativeHandle = texture.getNativeHandle();
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        if (!closed) {
            // Note: We don't destroy the texture, just the view
            // TODO: Destroy SRV if we created a separate one
            nativeHandle = 0;
            closed = true;
        }
    }
    
    public long getNativeHandle() {
        return nativeHandle;
    }
}
