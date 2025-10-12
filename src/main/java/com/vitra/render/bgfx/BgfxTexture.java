package com.vitra.render.bgfx;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Simplified BGFX texture implementation that uses BGFX native functionality directly
 * Replaces the complex VitraGpuTexture wrapper class
 */
public class BgfxTexture extends GpuTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxTexture");

    private final short bgfxHandle;
    private final int bgfxFormat;
    private final String textureName;  // Store name locally since parent field is private
    private final int textureWidth;    // Store width locally since parent field is private
    private final int textureHeight;   // Store height locally since parent field is private
    private boolean closed = false;

    /**
     * Create a 2D texture using BGFX native functionality.
     * BGFX handles all validation and creation internally.
     */
    public BgfxTexture(String name, int usage, TextureFormat minecraftFormat, int width, int height, int depth, int mipLevels) {
        super(usage, name != null ? name : "unnamed", minecraftFormat, width, height, depth, mipLevels);
        this.textureName = name != null ? name : "unnamed";
        this.textureWidth = width;
        this.textureHeight = height;
        this.bgfxFormat = convertTextureFormat(minecraftFormat);

        // Create the texture using BGFX - it handles all validation
        this.bgfxHandle = BgfxOperations.createTexture2D(
            width, height, mipLevels > 1, 1, bgfxFormat, 0
        );

        LOGGER.debug("Created 2D texture: {} (handle: {}, {}x{}, format: {})",
            name, bgfxHandle, width, height, bgfxFormat);
    }

    /**
     * Create a texture from existing BGFX handle (for internal use)
     */
    public BgfxTexture(String name, short bgfxHandle, int width, int height, int format) {
        super(0, name, null, width, height, 1, 1);
        this.textureName = name;
        this.textureWidth = width;
        this.textureHeight = height;
        this.bgfxHandle = bgfxHandle;
        this.bgfxFormat = format;
    }

    private int convertTextureFormat(TextureFormat minecraftFormat) {
        if (minecraftFormat == null) {
            return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
        }

        // Convert Minecraft texture formats to BGFX formats
        String formatName = minecraftFormat.toString();
        switch (formatName) {
            case "RGBA8":
                return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
            case "RGB8":
                return BGFX.BGFX_TEXTURE_FORMAT_RGB8;
            case "R8":
                return BGFX.BGFX_TEXTURE_FORMAT_R8;
            case "R16":
                return BGFX.BGFX_TEXTURE_FORMAT_R16;
            case "RG8":
                return BGFX.BGFX_TEXTURE_FORMAT_RG8;
            case "DEPTH16":
                return BGFX.BGFX_TEXTURE_FORMAT_D16;
            case "DEPTH24":
            case "D24":
                return BGFX.BGFX_TEXTURE_FORMAT_D24;
            case "DEPTH32":
            case "DEPTH32F":
            case "D32":
                return BGFX.BGFX_TEXTURE_FORMAT_D32F;
            default:
                LOGGER.warn("Unknown texture format '{}', defaulting to RGBA8", formatName);
                return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
        }
    }

    public short getBgfxHandle() {
        return bgfxHandle;
    }

    public int getBgfxFormat() {
        return bgfxFormat;
    }

    /**
     * Update texture data using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public boolean updateData(int mipLevel, int x, int y, int width, int height, ByteBuffer data) {
        return BgfxOperations.updateTexture2D(bgfxHandle, mipLevel, x, y, width, height, data);
    }

    /**
     * Read texture data using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public boolean readData(ByteBuffer dataBuffer, int mipLevel) {
        return BgfxOperations.readTexture(bgfxHandle, dataBuffer, mipLevel);
    }

    /**
     * Blit from another texture using BGFX native functionality.
     * BGFX handles all validation internally.
     */
    public boolean blitFrom(int dstX, int dstY, BgfxTexture srcTexture, int srcX, int srcY, int width, int height) {
        return BgfxOperations.blitTexture(bgfxHandle, dstX, dstY, srcTexture.getBgfxHandle(), srcX, srcY, width, height);
    }

    public void close() {
        if (!closed && bgfxHandle != 0) {
            LOGGER.debug("Destroying texture: {} (handle: {})", textureName, bgfxHandle);
            BgfxOperations.destroyResource(bgfxHandle, "texture");
            closed = true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public String getTextureName() {
        return textureName;
    }

    /**
     * Get texture width at a specific mip level (for API compatibility)
     */
    public int getWidthAtMip(int mipLevel) {
        return Math.max(1, textureWidth >> mipLevel);
    }

    /**
     * Get texture height at a specific mip level (for API compatibility)
     */
    public int getHeightAtMip(int mipLevel) {
        return Math.max(1, textureHeight >> mipLevel);
    }

    @Override
    public String toString() {
        return String.format("BgfxTexture{name='%s', handle=%d, size=%dx%d, format=%s, closed=%s}",
            textureName, bgfxHandle, textureWidth, textureHeight, bgfxFormat, closed);
    }
}