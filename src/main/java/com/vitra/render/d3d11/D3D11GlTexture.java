package com.vitra.render.d3d11;

import com.vitra.render.jni.VitraD3D11Renderer;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * DirectX OpenGL Texture Wrapper
 *
 * Maps OpenGL texture IDs to DirectX texture handles.
 * Based on VulkanMod's VkGlTexture pattern.
 *
 * Architecture:
 * - OpenGL texture ID (int) → DirectX texture handle (long)
 * - Tracks active texture slot (GL_TEXTURE0-15)
 * - Manages texture binding to shader resource views
 * - Handles texture creation, upload, and deletion
 */
public class D3D11GlTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D11GlTexture");

    // Texture ID counter (starts at 1, 0 is reserved for "no texture")
    private static int ID_COUNTER = 1;

    // OpenGL texture ID → D3D11GlTexture mapping
    private static final Int2LongOpenHashMap textureHandles = new Int2LongOpenHashMap();

    // Currently bound texture ID (per slot)
    private static final int[] boundTextureIds = new int[16]; // Support 16 texture slots

    // Currently active texture slot (0-15, corresponding to GL_TEXTURE0-15)
    private static int activeTextureSlot = 0;

    // Unpack parameters (for glTexImage2D with non-zero unpack settings)
    private static int unpackRowLength = 0;
    private static int unpackSkipRows = 0;
    private static int unpackSkipPixels = 0;

    static {
        textureHandles.defaultReturnValue(0L); // Return 0 for unmapped IDs
    }

    /**
     * Generate a new OpenGL texture ID
     * Called from GlStateManagerM._glGenTextures()
     */
    public static int genTextureId() {
        int id = ID_COUNTER++;
        // Don't create DirectX texture yet - wait for glTexImage2D
        LOGGER.debug("Generated texture ID: {}", id);
        return id;
    }

    /**
     * Bind an OpenGL texture ID to the active texture slot
     * Called from GlStateManagerM._bindTexture()
     *
     * This is the CRITICAL method that fixes the yellow screen!
     */
    public static void bindTexture(int textureId) {
        if (textureId < 0) {
            LOGGER.warn("Attempted to bind negative texture ID: {}", textureId);
            return;
        }

        // Track bound texture for this slot
        boundTextureIds[activeTextureSlot] = textureId;

        if (textureId == 0) {
            // CRITICAL FIX: In DirectX, ignore glBindTexture(0) - keep last texture bound
            // OpenGL unbinds with glBindTexture(0), but DirectX draws fail with NULL textures
            // VulkanMod does the same - they ignore texture unbinding
            LOGGER.debug("Ignoring texture unbind for slot {} (keeping last texture bound)", activeTextureSlot);
            return;
        }

        // Get DirectX texture handle
        long d3d11Handle = textureHandles.get(textureId);

        if (d3d11Handle == 0L) {
            // Texture not yet created in DirectX - this is normal, glTexImage2D will create it
            LOGGER.debug("Texture ID {} not yet uploaded to DirectX (slot {})", textureId, activeTextureSlot);
            return;
        }

        // Bind DirectX texture to shader resource view slot
        VitraD3D11Renderer.bindTexture(activeTextureSlot, d3d11Handle);
        LOGGER.debug("Bound texture {} (D3D11 handle: 0x{}) to slot {}",
            textureId, Long.toHexString(d3d11Handle), activeTextureSlot);
    }

    /**
     * Set the active texture slot
     * Called from GlStateManagerM._activeTexture()
     */
    public static void activeTexture(int glTextureEnum) {
        // Convert GL_TEXTURE0 + n to slot index n
        activeTextureSlot = glTextureEnum - GL30.GL_TEXTURE0;

        if (activeTextureSlot < 0 || activeTextureSlot >= 16) {
            LOGGER.warn("Invalid texture slot: GL_TEXTURE{} (clamping to 0-15)", activeTextureSlot);
            activeTextureSlot = Math.max(0, Math.min(15, activeTextureSlot));
        }

        LOGGER.debug("Active texture slot set to: {}", activeTextureSlot);
    }

    /**
     * Get the currently active texture slot (0-15)
     */
    public static int getActiveTextureSlot() {
        return activeTextureSlot;
    }

    /**
     * Get the texture ID bound to a specific slot
     */
    public static int getBoundTexture(int slot) {
        if (slot < 0 || slot >= 16) return 0;
        return boundTextureIds[slot];
    }

    /**
     * Get the DirectX handle for an OpenGL texture ID
     */
    public static long getD3D11Handle(int textureId) {
        return textureHandles.get(textureId);
    }

    /**
     * Create or update a DirectX texture from pixel data
     * Called from GlStateManagerM after glTexImage2D
     *
     * @param textureId OpenGL texture ID
     * @param level Mipmap level (0 = base level)
     * @param internalFormat OpenGL internal format (e.g., GL_RGBA)
     * @param width Texture width
     * @param height Texture height
     * @param format OpenGL format (e.g., GL_RGBA)
     * @param type OpenGL type (e.g., GL_UNSIGNED_BYTE)
     * @param pixels Pixel data (can be null for allocation without upload)
     */
    public static void texImage2D(int textureId, int level, int internalFormat,
                                   int width, int height, int format, int type,
                                   ByteBuffer pixels) {
        if (textureId <= 0) {
            LOGGER.warn("Invalid texture ID for texImage2D: {}", textureId);
            return;
        }

        long existingHandle = textureHandles.get(textureId);

        if (level == 0) {
            // Base level - create new texture
            if (existingHandle != 0L) {
                // Delete old texture
                LOGGER.debug("Replacing existing texture {} (handle: 0x{})",
                    textureId, Long.toHexString(existingHandle));
                VitraD3D11Renderer.releaseTexture(existingHandle);
            }

            // Convert OpenGL format to DirectX format
            int dxgiFormat = convertToDXGIFormat(internalFormat, format, type);

            // Create DirectX texture
            long d3d11Handle;
            byte[] pixelData = null;  // Declare outside for checksum calculation

            if (pixels != null && pixels.remaining() > 0) {
                // Extract pixel data
                pixelData = new byte[pixels.remaining()];
                pixels.get(pixelData);
                pixels.rewind();

                // CRITICAL: Convert RGBA → BGRA if using BGRA format (VulkanMod pattern)
                // DirectX uses BGRA natively on Windows for optimal performance
                final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
                final int GL_RGBA = 0x1908;
                final int GL_RGB = 0x1907;

                if (dxgiFormat == DXGI_FORMAT_B8G8R8A8_UNORM &&
                    (format == GL_RGBA || format == GL_RGB)) {
                    // Swap R and B channels in-place
                    convertRGBAtoBGRAInPlace(pixelData);
                    LOGGER.debug("Converted RGBA→BGRA for texture {} ({}x{})",
                        textureId, width, height);
                }

                d3d11Handle = VitraD3D11Renderer.createTextureFromData(
                    pixelData, width, height, dxgiFormat
                );
            } else {
                // Create without initial data (will be uploaded later)
                d3d11Handle = VitraD3D11Renderer.createTextureFromData(
                    new byte[0], width, height, dxgiFormat
                );
            }

            if (d3d11Handle == 0L) {
                LOGGER.error("Failed to create DirectX texture for ID {}", textureId);
                return;
            }

            // Store mapping
            textureHandles.put(textureId, d3d11Handle);

            // Calculate simple checksum for verification (first 64 bytes or available)
            int checksum = 0;
            if (pixelData != null && pixelData.length > 0) {
                int bytesToCheck = Math.min(64, pixelData.length);
                for (int i = 0; i < bytesToCheck; i++) {
                    checksum = checksum * 31 + (pixelData[i] & 0xFF);
                }
            }

            LOGGER.info("Created DirectX texture: ID={}, size={}x{}, format=0x{}, handle=0x{}, checksum=0x{}",
                textureId, width, height, Integer.toHexString(dxgiFormat), Long.toHexString(d3d11Handle),
                Integer.toHexString(checksum));

            // Re-bind if this texture is currently bound
            if (boundTextureIds[activeTextureSlot] == textureId) {
                VitraD3D11Renderer.bindTexture(activeTextureSlot, d3d11Handle);
            }
        } else {
            // Mipmap level - update existing texture
            if (existingHandle == 0L) {
                LOGGER.warn("Cannot upload mipmap level {} for texture {} - base texture not created",
                    level, textureId);
                return;
            }

            if (pixels != null && pixels.remaining() > 0) {
                byte[] pixelData = new byte[pixels.remaining()];
                pixels.get(pixelData);
                pixels.rewind();

                boolean success = VitraD3D11Renderer.updateTextureMipLevel(
                    existingHandle, pixelData, width, height, level
                );

                if (success) {
                    LOGGER.debug("Updated texture {} mipmap level {}: {}x{}",
                        textureId, level, width, height);
                } else {
                    LOGGER.error("Failed to update texture {} mipmap level {}", textureId, level);
                }
            }
        }
    }

    /**
     * Update a subregion of an existing texture
     * Called from GlStateManagerM after glTexSubImage2D
     */
    public static void texSubImage2D(int textureId, int level, int xoffset, int yoffset,
                                      int width, int height, int format, int type,
                                      ByteBuffer pixels) {
        if (textureId <= 0) {
            LOGGER.warn("Invalid texture ID for texSubImage2D: {}", textureId);
            return;
        }

        long d3d11Handle = textureHandles.get(textureId);
        if (d3d11Handle == 0L) {
            LOGGER.warn("Cannot update texture {} - not created yet", textureId);
            return;
        }

        if (pixels == null || pixels.remaining() == 0) {
            LOGGER.warn("No pixel data provided for texSubImage2D");
            return;
        }

        // Extract pixel data
        byte[] pixelData = new byte[pixels.remaining()];
        pixels.get(pixelData);
        pixels.rewind();

        // CRITICAL: Convert RGBA → BGRA if needed (VulkanMod pattern)
        // Assume BGRA format for texSubImage2D (matches texImage2D behavior)
        final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
        final int GL_RGBA = 0x1908;
        final int GL_RGB = 0x1907;

        int dxgiFormat = DXGI_FORMAT_B8G8R8A8_UNORM;  // Default to BGRA

        if (format == GL_RGBA || format == GL_RGB) {
            // Convert RGBA → BGRA
            convertRGBAtoBGRAInPlace(pixelData);
            LOGGER.debug("Converted RGBA→BGRA for texSubImage2D texture {} subregion",
                textureId);
        }

        boolean success = VitraD3D11Renderer.updateTextureSubRegion(
            d3d11Handle, pixelData, xoffset, yoffset, width, height, dxgiFormat
        );

        if (success) {
            LOGGER.debug("Updated texture {} subregion: offset=({},{}), size={}x{}",
                textureId, xoffset, yoffset, width, height);
        } else {
            LOGGER.error("Failed to update texture {} subregion", textureId);
        }
    }

    /**
     * Delete a texture
     * Called from GlStateManagerM._glDeleteTextures()
     */
    public static void deleteTexture(int textureId) {
        if (textureId <= 0) return;

        long d3d11Handle = textureHandles.remove(textureId);

        if (d3d11Handle != 0L) {
            VitraD3D11Renderer.releaseTexture(d3d11Handle);
            LOGGER.debug("Deleted texture {} (D3D11 handle: 0x{})",
                textureId, Long.toHexString(d3d11Handle));
        }

        // Unbind from all slots
        for (int i = 0; i < boundTextureIds.length; i++) {
            if (boundTextureIds[i] == textureId) {
                boundTextureIds[i] = 0;
            }
        }
    }

    /**
     * Delete multiple textures
     */
    public static void deleteTextures(IntBuffer textureIds) {
        for (int i = textureIds.position(); i < textureIds.limit(); i++) {
            deleteTexture(textureIds.get(i));
        }
    }

    /**
     * Set unpack row length (for glPixelStorei)
     */
    public static void setUnpackRowLength(int rowLength) {
        unpackRowLength = rowLength;
    }

    /**
     * Set unpack skip rows (for glPixelStorei)
     */
    public static void setUnpackSkipRows(int skipRows) {
        unpackSkipRows = skipRows;
    }

    /**
     * Set unpack skip pixels (for glPixelStorei)
     */
    public static void setUnpackSkipPixels(int skipPixels) {
        unpackSkipPixels = skipPixels;
    }

    /**
     * Convert OpenGL texture format to DXGI_FORMAT
     *
     * CRITICAL FIX: Use BGRA format for optimal Windows/DirectX performance
     * Follows VulkanMod's pattern - pixel data will be converted RGBA→BGRA
     *
     * Common mappings:
     * GL_RGBA8 → DXGI_FORMAT_B8G8R8A8_UNORM (87) - BGRA, not RGBA!
     * GL_RGB8 → DXGI_FORMAT_B8G8R8A8_UNORM (87) - padded to BGRA
     * GL_RED → DXGI_FORMAT_R8_UNORM (61)
     * GL_DEPTH_COMPONENT → DXGI_FORMAT_D24_UNORM_S8_UINT (45)
     */
    private static int convertToDXGIFormat(int internalFormat, int format, int type) {
        // DXGI_FORMAT enum values from d3dcommon.h
        final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;  // BGRA - native to Windows
        final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;  // RGBA - for special cases
        final int DXGI_FORMAT_R8_UNORM = 61;
        final int DXGI_FORMAT_R16_UNORM = 56;
        final int DXGI_FORMAT_D24_UNORM_S8_UINT = 45;
        final int DXGI_FORMAT_R32_FLOAT = 41;

        // OpenGL format constants
        final int GL_RGBA = 0x1908;
        final int GL_RGB = 0x1907;
        final int GL_RED = 0x1903;
        final int GL_DEPTH_COMPONENT = 0x1902;
        final int GL_RGBA8 = 0x8058;
        final int GL_RGB8 = 0x8051;
        final int GL_BGRA = 0x80E1;
        final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;

        // CRITICAL: Check for special reverse byte order format first
        if (type == GL_UNSIGNED_INT_8_8_8_8_REV) {
            // Special packed format - use RGBA, not BGRA (VulkanMod pattern)
            return DXGI_FORMAT_R8G8B8A8_UNORM;
        }

        // Check internal format first
        switch (internalFormat) {
            case GL_RGBA8:
            case GL_RGBA:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // BGRA (VulkanMod pattern)
            case GL_RGB8:
            case GL_RGB:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // Pad to BGRA
            case GL_BGRA:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // Already BGRA
            case GL_RED:
                return DXGI_FORMAT_R8_UNORM;
            case GL_DEPTH_COMPONENT:
                return DXGI_FORMAT_D24_UNORM_S8_UINT;
        }

        // Fallback to checking format parameter
        switch (format) {
            case GL_RGBA:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // BGRA (VulkanMod pattern)
            case GL_RGB:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // Pad to BGRA
            case GL_BGRA:
                return DXGI_FORMAT_B8G8R8A8_UNORM;  // Already BGRA
            case GL_RED:
                return DXGI_FORMAT_R8_UNORM;
            case GL_DEPTH_COMPONENT:
                return DXGI_FORMAT_D24_UNORM_S8_UINT;
        }

        // Default to BGRA8
        LOGGER.warn("Unknown texture format: internalFormat=0x{}, format=0x{}, type=0x{} - defaulting to BGRA8",
            Integer.toHexString(internalFormat), Integer.toHexString(format), Integer.toHexString(type));
        return DXGI_FORMAT_B8G8R8A8_UNORM;
    }

    /**
     * Convert RGBA buffer to BGRA buffer (swap R and B channels)
     * Based on VulkanMod's GlUtil.BGRAtoRGBA_buffer pattern
     *
     * DEPRECATED: Use convertRGBAtoBGRAInPlace for better performance
     */
    private static ByteBuffer convertRGBAtoBGRA(ByteBuffer rgba) {
        if (rgba == null || !rgba.hasRemaining()) {
            return rgba;
        }

        int size = rgba.remaining();
        ByteBuffer bgra = org.lwjgl.system.MemoryUtil.memAlloc(size);

        // Save position and rewind to start
        int originalPosition = rgba.position();
        rgba.rewind();

        // Convert RGBA → BGRA by swapping R and B channels
        while (rgba.hasRemaining()) {
            byte r = rgba.get();
            byte g = rgba.get();
            byte b = rgba.get();
            byte a = rgba.get();

            // Write as BGRA
            bgra.put(b);
            bgra.put(g);
            bgra.put(r);
            bgra.put(a);
        }

        bgra.flip();  // Prepare for reading
        rgba.position(originalPosition);  // Restore original position

        return bgra;
    }

    /**
     * Convert RGBA to BGRA in-place by swapping R and B channels
     * This is more efficient than allocating a new buffer
     * Based on DirectXTex's format conversion pattern
     */
    private static void convertRGBAtoBGRAInPlace(byte[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return;
        }

        // Swap R and B channels in-place (assumes 4 bytes per pixel)
        for (int i = 0; i < pixels.length; i += 4) {
            byte temp = pixels[i];     // Save R
            pixels[i] = pixels[i + 2]; // R = B
            pixels[i + 2] = temp;      // B = R
            // G (i+1) and A (i+3) stay the same
        }
    }

    /**
     * Get texture statistics for debugging
     */
    public static String getStats() {
        int totalTextures = textureHandles.size();
        int boundCount = 0;
        for (int boundId : boundTextureIds) {
            if (boundId != 0) boundCount++;
        }

        return String.format("D3D11GlTexture Stats: %d textures, %d bound, active slot %d",
            totalTextures, boundCount, activeTextureSlot);
    }

    /**
     * Cleanup - called on shutdown
     */
    public static void cleanup() {
        LOGGER.info("Cleaning up D3D11GlTexture: {} textures", textureHandles.size());

        // Delete all textures
        textureHandles.int2LongEntrySet().forEach(entry -> {
            long handle = entry.getLongValue();
            if (handle != 0L) {
                VitraD3D11Renderer.releaseTexture(handle);
            }
        });

        textureHandles.clear();
        for (int i = 0; i < boundTextureIds.length; i++) {
            boundTextureIds[i] = 0;
        }
        activeTextureSlot = 0;
    }
}
