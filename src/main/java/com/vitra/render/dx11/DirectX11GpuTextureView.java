package com.vitra.render.dx11;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 implementation of Minecraft's GpuTextureView
 *
 * Wraps a DirectX 11 ID3D11ShaderResourceView that provides a view into a texture.
 * In DirectX 11, texture views allow different interpretations of texture data
 * (e.g., viewing specific mip levels or array slices).
 */
public class DirectX11GpuTextureView extends GpuTextureView {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11GpuTextureView");

    private final DirectX11GpuTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    private boolean closed = false;

    /**
     * Create a texture view for the entire texture
     */
    public DirectX11GpuTextureView(DirectX11GpuTexture texture) {
        super(texture, 0, texture.getMipLevels());
        this.texture = texture;
        this.baseMipLevel = 0;
        this.mipLevels = texture.getMipLevels();

        LOGGER.debug("Created DirectX11GpuTextureView: {} (all mip levels)",
            texture.getLabel());
    }

    /**
     * Create a texture view for a specific range of mip levels
     */
    public DirectX11GpuTextureView(DirectX11GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;

        LOGGER.debug("Created DirectX11GpuTextureView: {} (mips {}-{})",
            texture.getLabel(), baseMipLevel, baseMipLevel + mipLevels - 1);
    }

    /**
     * Get the underlying DirectX 11 texture
     */
    public DirectX11GpuTexture getTexture() {
        return texture;
    }

    /**
     * Get the base mip level for this view
     */
    public int getBaseMipLevel() {
        return baseMipLevel;
    }

    /**
     * Get the number of mip levels in this view
     */
    public int getMipLevelCount() {
        return mipLevels;
    }

    /**
     * Get the native DirectX 11 texture handle
     * (In DirectX 11, the ShaderResourceView is created on-demand from the texture)
     */
    public long getNativeTextureHandle() {
        return texture.getNativeHandle();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        // Note: We don't close the underlying texture here, as the texture view
        // is just a reference to the texture. The texture itself will be closed
        // when its refcount reaches zero.

        LOGGER.debug("Closing DirectX11GpuTextureView: {}", texture.getLabel());
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed || texture.isClosed();
    }
}
