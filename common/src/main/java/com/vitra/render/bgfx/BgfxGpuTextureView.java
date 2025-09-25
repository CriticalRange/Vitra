package com.vitra.render.bgfx;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX implementation of GpuTextureView for Minecraft's texture system
 */
public class BgfxGpuTextureView extends GpuTextureView {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGpuTextureView");

    private final BgfxGpuTexture texture;
    private final int baseLevel;
    private final int levelCount;

    public BgfxGpuTextureView(BgfxGpuTexture texture, int baseLevel, int levelCount) {
        super(texture, baseLevel, levelCount);
        this.texture = texture;
        this.baseLevel = baseLevel;
        this.levelCount = levelCount;

        LOGGER.debug("Created BGFX texture view: base level {}, level count {}", baseLevel, levelCount);
    }

    public BgfxGpuTextureView(BgfxGpuTexture texture) {
        this(texture, 0, texture.getMipLevels());
    }

    public BgfxGpuTexture getTexture() {
        return texture;
    }

    public int getBaseLevel() {
        return baseLevel;
    }

    public int getLevelCount() {
        return levelCount;
    }

    public boolean isClosed() {
        return texture.isClosed();
    }

    @Override
    public void close() {
        LOGGER.debug("Closing BGFX texture view");
        // Texture views don't need explicit cleanup in BGFX
        // The underlying texture will handle its own cleanup
    }
}