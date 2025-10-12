package com.vitra.render.bgfx;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplified BGFX texture view implementation that uses BGFX native functionality directly
 * Replaces the complex VitraGpuTextureView wrapper class
 */
public class BgfxTextureView extends GpuTextureView {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxTextureView");

    private final short bgfxHandle;
    private final BgfxTexture sourceTexture;  // Store reference locally since parent field is private
    private final int viewBaseLevel;           // Store baseLevel locally since parent field is private
    private final int viewLevelCount;          // Store levelCount locally since parent field is private
    private boolean closed = false;

    /**
     * Create a texture view with default parameters (full texture, all mipmap levels)
     */
    public BgfxTextureView(BgfxTexture sourceTexture) {
        this(sourceTexture, 0, sourceTexture.getMipLevels());
    }

    /**
     * Create a texture view with specific mipmap level range
     */
    public BgfxTextureView(BgfxTexture sourceTexture, int baseLevel, int levelCount) {
        super(sourceTexture, baseLevel, levelCount);
        this.sourceTexture = sourceTexture;
        this.viewBaseLevel = baseLevel;
        this.viewLevelCount = levelCount;

        // Create the texture view using BGFX native functionality
        this.bgfxHandle = BgfxOperations.createTextureView(
            sourceTexture.getBgfxHandle(),
            sourceTexture.getBgfxFormat(),
            baseLevel,
            levelCount,
            0, // firstLayer
            1  // numLayers
        );

        if (this.bgfxHandle == 0) {
            throw new RuntimeException("Failed to create texture view: " + sourceTexture.getTextureName());
        }

        LOGGER.debug("Created texture view: {} (handle: {}, baseLevel: {}, levelCount: {})",
            sourceTexture.getTextureName(), bgfxHandle, baseLevel, levelCount);
    }

    public short getBgfxHandle() {
        return bgfxHandle;
    }

    public void close() {
        if (!closed && bgfxHandle != 0) {
            LOGGER.debug("Destroying texture view: {} (handle: {})", sourceTexture.getTextureName(), bgfxHandle);
            BgfxOperations.destroyResource(bgfxHandle, "texture_view");
            closed = true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return String.format("BgfxTextureView{handle=%d, source='%s', baseLevel=%d, levelCount=%d, closed=%s}",
            bgfxHandle, sourceTexture.getTextureName(), viewBaseLevel, viewLevelCount, closed);
    }
}