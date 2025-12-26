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
    
    // Reference to the underlying texture (for dynamic handle lookup)
    private final VitraGpuTexture vitraTexture;
    private boolean closed = false;
    
    public VitraGpuTextureView(VitraGpuTexture texture, int baseMip, int mipCount) {
        super(texture, baseMip, mipCount);
        this.vitraTexture = texture;
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        if (!closed) {
            // Note: We don't destroy the texture, just the view
            closed = true;
        }
    }
    
    /**
     * Get the native D3D11 texture handle.
     * This dynamically fetches from the underlying texture because
     * the native handle may not be set until writeToTexture is called.
     */
    public long getNativeHandle() {
        if (vitraTexture != null) {
            return vitraTexture.getNativeHandle();
        }
        return 0;
    }
}
