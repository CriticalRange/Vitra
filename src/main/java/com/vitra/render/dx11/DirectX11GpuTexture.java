package com.vitra.render.dx11;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 implementation of Minecraft's GpuTexture
 *
 * Wraps a native DirectX 11 ID3D11Texture2D and related resources.
 * The actual texture is created on-demand when texture data is uploaded.
 */
public class DirectX11GpuTexture extends GpuTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectX11GpuTexture");

    private long nativeHandle = 0;
    private boolean closed = false;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;

    // Track ACTUAL native texture dimensions (may differ from initial width/height due to atlas resizing)
    private int actualNativeWidth = 0;
    private int actualNativeHeight = 0;

    public DirectX11GpuTexture(int usage, String label, TextureFormat format,
                               int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.width = width;
        this.height = height;
        this.depthOrLayers = depthOrLayers;
        this.mipLevels = mipLevels;

        LOGGER.debug("Created DirectX11GpuTexture: {} ({}x{}x{}, {} mips, format={})",
            label, width, height, depthOrLayers, mipLevels, format);
    }

    /**
     * Create the native DirectX 11 texture with initial data
     * Uses the actual data size to determine texture dimensions
     */
    public void createNativeTexture(byte[] data, int actualWidth, int actualHeight) {
        if (closed) {
            throw new IllegalStateException("Texture already closed: " + getLabel());
        }

        // Validate that DirectX is initialized
        if (!VitraNativeRenderer.isInitialized()) {
            LOGGER.warn("Cannot create texture {} - DirectX not initialized yet", getLabel());
            return;
        }

        // ✅ CRITICAL FIX: Check if texture dimensions changed (Minecraft texture atlases can resize!)
        // If dimensions changed, we MUST recreate - DirectX 11 textures cannot be resized
        if (nativeHandle != 0) {
            if (actualNativeWidth == actualWidth && actualNativeHeight == actualHeight) {
                // Same dimensions - we can update in-place (much faster!)
                LOGGER.debug("Texture {} already exists with same dimensions ({}x{}), using update",
                    getLabel(), actualWidth, actualHeight);
                updateNativeTexture(data, actualWidth, actualHeight, 0);
                return;
            } else {
                // Dimensions changed - must recreate texture (DirectX 11 limitation)
                LOGGER.info("Texture {} dimensions changed: {}x{} -> {}x{}, recreating texture",
                    getLabel(), actualNativeWidth, actualNativeHeight, actualWidth, actualHeight);
                VitraNativeRenderer.destroyResource(nativeHandle);
                nativeHandle = 0;
                actualNativeWidth = 0;
                actualNativeHeight = 0;
                // Fall through to create new texture below
            }
        }

        // Validate data size
        if (data == null || data.length == 0) {
            LOGGER.warn("No texture data provided for {}, skipping texture creation", getLabel());
            return;
        }

        // Calculate expected size based on format and ACTUAL dimensions
        int bytesPerPixel = switch (getFormat().toString()) {
            case "R8" -> 1;
            case "RGB8", "SRGB8" -> 3;
            case "RGBA8", "SRGBA8" -> 4;
            case "DEPTH24_STENCIL8" -> 4;
            default -> 4;
        };

        int expectedSize = actualWidth * actualHeight * bytesPerPixel;
        if (data.length != expectedSize) {
            LOGGER.warn("Texture data size mismatch for {}: expected {} bytes ({}x{}x{}), got {} bytes",
                getLabel(), expectedSize, actualWidth, actualHeight, bytesPerPixel, data.length);

            // If data is smaller than expected, this is a critical error
            if (data.length < expectedSize) {
                LOGGER.error("Cannot create texture {} - data too small", getLabel());
                return;
            }
        }

        // Convert TextureFormat to native format constant
        int nativeFormat = convertTextureFormat(getFormat());

        // Create the native texture with ACTUAL dimensions from the image data
        nativeHandle = VitraNativeRenderer.createTexture(data, actualWidth, actualHeight, nativeFormat);

        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create native texture: " + getLabel());
        }

        // ✅ Track actual native texture dimensions for future dimension checks
        actualNativeWidth = actualWidth;
        actualNativeHeight = actualHeight;

        LOGGER.debug("Created native DirectX 11 texture: {} (handle=0x{}, dimensions={}x{})",
            getLabel(), Long.toHexString(nativeHandle), actualWidth, actualHeight);
    }

    /**
     * Update existing native DirectX 11 texture data using UpdateSubresource
     * This is MUCH more efficient than destroying and recreating the texture!
     */
    public void updateNativeTexture(byte[] data, int actualWidth, int actualHeight, int mipLevel) {
        if (closed) {
            throw new IllegalStateException("Texture already closed: " + getLabel());
        }

        if (nativeHandle == 0) {
            LOGGER.warn("Cannot update texture {} - no native handle exists yet", getLabel());
            return;
        }

        // Validate data
        if (data == null || data.length == 0) {
            LOGGER.warn("No texture data provided for update: {}", getLabel());
            return;
        }

        // Update the texture using native UpdateSubresource
        boolean success = VitraNativeRenderer.updateTexture(nativeHandle, data, actualWidth, actualHeight, mipLevel);

        if (!success) {
            LOGGER.error("Failed to update texture {}, recreating from scratch", getLabel());
            // If update fails (e.g., dimension mismatch), recreate the texture
            VitraNativeRenderer.destroyResource(nativeHandle);
            nativeHandle = 0;
            createNativeTexture(data, actualWidth, actualHeight);
        } else {
            LOGGER.debug("Successfully updated texture: {} ({}x{}, mip {})",
                getLabel(), actualWidth, actualHeight, mipLevel);
        }
    }

    /**
     * Get the native DirectX 11 texture handle
     */
    public long getNativeHandle() {
        return nativeHandle;
    }

    /**
     * Convert Minecraft TextureFormat to native DirectX 11 format constant
     */
    private int convertTextureFormat(TextureFormat format) {
        // Map to the constants defined in VitraNativeRenderer
        return switch (format.toString()) {
            case "R8" -> VitraNativeRenderer.TEXTURE_FORMAT_R8;
            case "RGB8", "SRGB8" -> VitraNativeRenderer.TEXTURE_FORMAT_RGB8;
            case "RGBA8", "SRGBA8" -> VitraNativeRenderer.TEXTURE_FORMAT_RGBA8;
            case "DEPTH24_STENCIL8" -> VitraNativeRenderer.TEXTURE_FORMAT_D24S8;
            default -> {
                LOGGER.warn("Unsupported texture format {}, defaulting to RGBA8", format);
                yield VitraNativeRenderer.TEXTURE_FORMAT_RGBA8;
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        if (nativeHandle != 0) {
            LOGGER.debug("Closing DirectX 11 texture: {} (handle=0x{})",
                getLabel(), Long.toHexString(nativeHandle));

            try {
                VitraNativeRenderer.destroyResource(nativeHandle);
            } catch (Exception e) {
                LOGGER.error("Error destroying texture: {}", getLabel(), e);
            }

            nativeHandle = 0;
            actualNativeWidth = 0;
            actualNativeHeight = 0;
        }

        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
