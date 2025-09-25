package com.vitra.render.bgfx;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX implementation of GpuTexture for Minecraft's texture system
 */
public class BgfxGpuTexture extends GpuTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGpuTexture");

    private final short bgfxHandle;
    final int width;
    final int height;
    private final int depth;
    private final int mipLevels;
    private final TextureFormat format;
    private boolean disposed = false;

    public BgfxGpuTexture(String name, int i, TextureFormat format, int width, int height, int depth, int mipLevels) {
        super(i, name, format, width, height, depth, mipLevels);
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.format = format;

        LOGGER.info("Creating BGFX texture '{}': {}x{}x{}, format: {}, mips: {}",
                    name, width, height, depth, format, mipLevels);

        // Map TextureFormat to BGFX format
        int bgfxFormat = mapTextureFormatToBgfx(format);

        // Create BGFX texture based on dimensions
        if (depth > 1) {
            // 3D texture
            this.bgfxHandle = BGFX.bgfx_create_texture_3d(
                (short) width, (short) height, (short) depth,
                false, // hasMips
                bgfxFormat,
                BGFX.BGFX_TEXTURE_NONE, // flags
                null // memory
            );
        } else if (height > 1) {
            // 2D texture
            this.bgfxHandle = BGFX.bgfx_create_texture_2d(
                (short) width, (short) height,
                false, // hasMips
                1, // numLayers
                bgfxFormat,
                BGFX.BGFX_TEXTURE_NONE, // flags
                null // memory
            );
        } else {
            // 1D texture (treat as 2D with height=1)
            this.bgfxHandle = BGFX.bgfx_create_texture_2d(
                (short) width, (short) 1,
                false, // hasMips
                1, // numLayers
                bgfxFormat,
                BGFX.BGFX_TEXTURE_NONE, // flags
                null // memory
            );
        }

        LOGGER.info("Created BGFX texture handle: {}", bgfxHandle);
    }

    private int mapTextureFormatToBgfx(TextureFormat format) {
        // Map Minecraft texture formats to BGFX formats
        // This is a simplified mapping - may need refinement
        switch (format.toString()) {
            case "RGBA8":
                return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
            case "RGB8":
                return BGFX.BGFX_TEXTURE_FORMAT_RGB8;
            case "R8":
                return BGFX.BGFX_TEXTURE_FORMAT_R8;
            case "RG8":
                return BGFX.BGFX_TEXTURE_FORMAT_RG8;
            case "DEPTH16":
                return BGFX.BGFX_TEXTURE_FORMAT_D16;
            case "DEPTH24":
                return BGFX.BGFX_TEXTURE_FORMAT_D24;
            case "DEPTH32":
                return BGFX.BGFX_TEXTURE_FORMAT_D32F;
            case "DEPTH32F":
                return BGFX.BGFX_TEXTURE_FORMAT_D32F;
            default:
                LOGGER.warn("Unknown texture format '{}', defaulting to RGBA8", format);
                return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
        }
    }

    public short getBgfxHandle() {
        return bgfxHandle;
    }

    public boolean isClosed() {
        return disposed;
    }

    @Override
    public void close() {
        if (!disposed && bgfxHandle != BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.debug("Disposing BGFX texture handle: {}", bgfxHandle);
            BGFX.bgfx_destroy_texture(bgfxHandle);
            disposed = true;
        }
    }
}