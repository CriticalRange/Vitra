package com.vitra.render.dx11;

import com.vitra.render.jni.VitraNativeRenderer;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * DirectX 11 OpenGL Texture Wrapper
 *
 * Maps OpenGL texture IDs to DirectX 11 texture handles.
 * Based on VulkanMod's VkGlTexture pattern.
 *
 * Architecture:
 * - OpenGL texture ID (int) → DirectX 11 texture handle (long)
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
            // Unbind texture (bind null)
            VitraNativeRenderer.bindTexture(activeTextureSlot, 0L);
            LOGGER.debug("Unbound texture from slot {}", activeTextureSlot);
            return;
        }

        // Get DirectX 11 texture handle
        long d3d11Handle = textureHandles.get(textureId);

        if (d3d11Handle == 0L) {
            // Texture not yet created in DirectX - this is normal, glTexImage2D will create it
            LOGGER.debug("Texture ID {} not yet uploaded to DirectX 11 (slot {})", textureId, activeTextureSlot);
            return;
        }

        // Bind DirectX 11 texture to shader resource view slot
        VitraNativeRenderer.bindTexture(activeTextureSlot, d3d11Handle);
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
     * Get the DirectX 11 handle for an OpenGL texture ID
     */
    public static long getD3D11Handle(int textureId) {
        return textureHandles.get(textureId);
    }

    /**
     * Create or update a DirectX 11 texture from pixel data
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
                VitraNativeRenderer.releaseTexture(existingHandle);
            }

            // Convert OpenGL format to DirectX 11 format
            int dxgiFormat = convertToDXGIFormat(internalFormat, format, type);

            // Create DirectX 11 texture
            long d3d11Handle;
            if (pixels != null && pixels.remaining() > 0) {
                // Create with initial data
                byte[] pixelData = new byte[pixels.remaining()];
                pixels.get(pixelData);
                pixels.rewind();

                d3d11Handle = VitraNativeRenderer.createTextureFromData(
                    pixelData, width, height, dxgiFormat
                );
            } else {
                // Create without initial data (will be uploaded later)
                d3d11Handle = VitraNativeRenderer.createTextureFromData(
                    new byte[0], width, height, dxgiFormat
                );
            }

            if (d3d11Handle == 0L) {
                LOGGER.error("Failed to create DirectX 11 texture for ID {}", textureId);
                return;
            }

            // Store mapping
            textureHandles.put(textureId, d3d11Handle);

            LOGGER.info("Created DirectX 11 texture: ID={}, size={}x{}, format=0x{}, handle=0x{}",
                textureId, width, height, Integer.toHexString(dxgiFormat), Long.toHexString(d3d11Handle));

            // Re-bind if this texture is currently bound
            if (boundTextureIds[activeTextureSlot] == textureId) {
                VitraNativeRenderer.bindTexture(activeTextureSlot, d3d11Handle);
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

                boolean success = VitraNativeRenderer.updateTextureMipLevel(
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

        // Use proper subregion update with D3D11_BOX
        byte[] pixelData = new byte[pixels.remaining()];
        pixels.get(pixelData);
        pixels.rewind();

        // Convert format to DXGI format (need to determine from existing texture)
        int dxgiFormat = 28; // DXGI_FORMAT_R8G8B8A8_UNORM (default)

        boolean success = VitraNativeRenderer.updateTextureSubRegion(
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
            VitraNativeRenderer.releaseTexture(d3d11Handle);
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
     * Common mappings:
     * GL_RGBA8 → DXGI_FORMAT_R8G8B8A8_UNORM (28)
     * GL_RGB8 → DXGI_FORMAT_R8G8B8A8_UNORM (28) - padded to RGBA
     * GL_RED → DXGI_FORMAT_R8_UNORM (61)
     * GL_DEPTH_COMPONENT → DXGI_FORMAT_D24_UNORM_S8_UINT (45)
     */
    private static int convertToDXGIFormat(int internalFormat, int format, int type) {
        // DXGI_FORMAT enum values from d3dcommon.h
        final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
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

        // Check internal format first
        switch (internalFormat) {
            case GL_RGBA8:
            case GL_RGBA:
                return DXGI_FORMAT_R8G8B8A8_UNORM;
            case GL_RGB8:
            case GL_RGB:
                return DXGI_FORMAT_R8G8B8A8_UNORM; // Pad to RGBA
            case GL_RED:
                return DXGI_FORMAT_R8_UNORM;
            case GL_DEPTH_COMPONENT:
                return DXGI_FORMAT_D24_UNORM_S8_UINT;
        }

        // Fallback to checking format parameter
        switch (format) {
            case GL_RGBA:
                return DXGI_FORMAT_R8G8B8A8_UNORM;
            case GL_RGB:
                return DXGI_FORMAT_R8G8B8A8_UNORM; // Pad to RGBA
            case GL_RED:
                return DXGI_FORMAT_R8_UNORM;
            case GL_DEPTH_COMPONENT:
                return DXGI_FORMAT_D24_UNORM_S8_UINT;
        }

        // Default to RGBA8
        LOGGER.warn("Unknown texture format: internalFormat=0x{}, format=0x{}, type=0x{} - defaulting to RGBA8",
            Integer.toHexString(internalFormat), Integer.toHexString(format), Integer.toHexString(type));
        return DXGI_FORMAT_R8G8B8A8_UNORM;
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
                VitraNativeRenderer.releaseTexture(handle);
            }
        });

        textureHandles.clear();
        for (int i = 0; i < boundTextureIds.length; i++) {
            boundTextureIds[i] = 0;
        }
        activeTextureSlot = 0;
    }
}
