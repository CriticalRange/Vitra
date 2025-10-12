package com.vitra.render.backend;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.bgfx.BGFX.*;

/**
 * Manages OpenGL texture IDs and their corresponding BGFX texture handles.
 * Based on VulkanMod's VkGlTexture architecture.
 */
public class BgfxGlTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxGlTexture");

    private static int ID_COUNTER = 1;
    private static final Int2ObjectOpenHashMap<BgfxGlTexture> textureMap = new Int2ObjectOpenHashMap<>();

    // Track currently bound texture (per OpenGL bind semantics)
    private static int boundTextureId = 0;
    private static BgfxGlTexture boundTexture = null;

    // Track active texture unit (GL_TEXTURE0, GL_TEXTURE1, etc.)
    private static int activeTexture = 0;

    // OpenGL pixel store parameters (for texture upload)
    private static int unpackRowLength = 0;
    private static int unpackSkipRows = 0;
    private static int unpackSkipPixels = 0;
    private static int unpackAlignment = 4;

    // Instance fields for this texture
    public final int glId;
    private short bgfxHandle = BGFX_INVALID_HANDLE;

    private int width = 0;
    private int height = 0;
    private int internalFormat = GL11.GL_RGBA;
    private int bgfxFormat = BGFX_TEXTURE_FORMAT_RGBA8;

    private boolean needsAllocation = false;
    private int maxLevel = 0;
    private int minFilter = GL11.GL_NEAREST;
    private int magFilter = GL11.GL_NEAREST;
    private int wrapS = GL30.GL_CLAMP_TO_EDGE;
    private int wrapT = GL30.GL_CLAMP_TO_EDGE;

    // ========== Static Methods (GL API Interception) ==========

    /**
     * Generate a new texture ID.
     * Called by GlStateManager._genTexture()
     */
    public static int genTextureId() {
        int id = ID_COUNTER++;
        BgfxGlTexture texture = new BgfxGlTexture(id);
        textureMap.put(id, texture);
        LOGGER.debug("Generated texture ID: {}", id);
        return id;
    }

    /**
     * Bind a texture by ID (makes it the current texture).
     * Called by GlStateManager._bindTexture()
     */
    public static void bindTexture(int id) {
        boundTextureId = id;

        if (id == 0) {
            boundTexture = null;
            LOGGER.trace("Unbound texture (ID 0)");
            return;
        }

        boundTexture = textureMap.get(id);
        if (boundTexture == null) {
            LOGGER.warn("Attempted to bind non-existent texture ID: {}", id);
        } else {
            LOGGER.trace("Bound texture ID: {} (BGFX handle: {})", id, boundTexture.bgfxHandle);
        }
    }

    /**
     * Delete a texture by ID.
     * Called by GlStateManager._deleteTexture()
     */
    public static void deleteTexture(int id) {
        BgfxGlTexture texture = textureMap.remove(id);
        if (texture != null) {
            texture.destroy();
            LOGGER.debug("Deleted texture ID: {}", id);
        }
    }

    /**
     * Set active texture unit (GL_TEXTURE0 + index).
     * Called by GlStateManager._activeTexture()
     */
    public static void activeTexture(int textureUnit) {
        activeTexture = textureUnit - GL30.GL_TEXTURE0;
        LOGGER.trace("Active texture unit: {}", activeTexture);
    }

    /**
     * Upload full texture image (allocates and uploads data).
     * Called by GlStateManager._texImage2D()
     */
    public static void texImage2D(int target, int level, int internalFormat,
                                   int width, int height, int border,
                                   int format, int type, IntBuffer pixels) {
        if (boundTexture == null) {
            LOGGER.warn("texImage2D called with no texture bound!");
            return;
        }

        if (width == 0 || height == 0) {
            LOGGER.trace("texImage2D with zero dimensions, skipping");
            return;
        }

        LOGGER.info("texImage2D: ID={}, level={}, format=0x{}, size={}x{}, pixels={}",
            boundTexture.glId, level, Integer.toHexString(internalFormat),
            width, height, pixels != null ? "present" : "null");

        // Update texture parameters
        boundTexture.updateParams(level, width, height, internalFormat, type);

        // Allocate BGFX texture if needed
        boundTexture.allocateIfNeeded();

        // Upload pixel data via texSubImage2D
        if (pixels != null) {
            // Convert IntBuffer to ByteBuffer
            ByteBuffer bytePixels = MemoryUtil.memByteBuffer(pixels);
            texSubImage2D(target, level, 0, 0, width, height, format, type, bytePixels);
        }
    }

    /**
     * Upload texture sub-region (updates existing texture).
     * Called by GlStateManager._texSubImage2D()
     */
    public static void texSubImage2D(int target, int level, int xOffset, int yOffset,
                                      int width, int height, int format, int type, long pixels) {
        if (boundTexture == null) {
            LOGGER.warn("texSubImage2D called with no texture bound!");
            return;
        }

        if (width == 0 || height == 0) {
            return;
        }

        ByteBuffer src = null;
        if (pixels != 0L) {
            // Calculate buffer size based on format
            int bytesPerPixel = getBytesPerPixel(format, type);
            int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
            int offset = (unpackSkipRows * rowLength + unpackSkipPixels) * bytesPerPixel;
            int size = rowLength * height * bytesPerPixel;

            src = MemoryUtil.memByteBuffer(pixels + offset, size);
        }

        if (src != null) {
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, type, src);
        }
    }

    /**
     * Upload texture sub-region (ByteBuffer variant).
     */
    public static void texSubImage2D(int target, int level, int xOffset, int yOffset,
                                      int width, int height, int format, int type, ByteBuffer pixels) {
        if (boundTexture == null) {
            LOGGER.warn("texSubImage2D called with no texture bound!");
            return;
        }

        if (width == 0 || height == 0 || pixels == null) {
            return;
        }

        boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * Set texture parameter (filtering, wrapping, etc.).
     * Called by GlStateManager._texParameter()
     */
    public static void texParameter(int target, int pname, int param) {
        if (boundTexture == null) {
            return;
        }

        switch (pname) {
            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.setMaxLevel(param);
            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.setMagFilter(param);
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.setMinFilter(param);
            case GL11.GL_TEXTURE_WRAP_S -> boundTexture.setWrapS(param);
            case GL11.GL_TEXTURE_WRAP_T -> boundTexture.setWrapT(param);
            default -> LOGGER.trace("Unhandled texParameter: pname=0x{}", Integer.toHexString(pname));
        }
    }

    /**
     * Get texture level parameter (width, height, format).
     * Called by GlStateManager._getTexLevelParameter()
     */
    public static int getTexLevelParameter(int target, int level, int pname) {
        if (boundTexture == null) {
            return 0;
        }

        return switch (pname) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> boundTexture.internalFormat;
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.height;
            default -> 0;
        };
    }

    /**
     * Set pixel store parameters (affects texture upload).
     * Called by GlStateManager._pixelStore()
     */
    public static void pixelStore(int pname, int param) {
        switch (pname) {
            case GL11.GL_UNPACK_ROW_LENGTH -> {
                unpackRowLength = param;
                LOGGER.trace("Set GL_UNPACK_ROW_LENGTH: {}", param);
            }
            case GL11.GL_UNPACK_SKIP_ROWS -> {
                unpackSkipRows = param;
                LOGGER.trace("Set GL_UNPACK_SKIP_ROWS: {}", param);
            }
            case GL11.GL_UNPACK_SKIP_PIXELS -> {
                unpackSkipPixels = param;
                LOGGER.trace("Set GL_UNPACK_SKIP_PIXELS: {}", param);
            }
            case GL11.GL_UNPACK_ALIGNMENT -> {
                unpackAlignment = param;
                LOGGER.trace("Set GL_UNPACK_ALIGNMENT: {}", param);
            }
        }
    }

    /**
     * Get currently bound texture.
     */
    public static BgfxGlTexture getBoundTexture() {
        return boundTexture;
    }

    // ========== Instance Methods ==========

    private BgfxGlTexture(int glId) {
        this.glId = glId;
    }

    /**
     * Update texture parameters (size, format).
     * Called when texImage2D is used to change texture properties.
     */
    private void updateParams(int level, int width, int height, int internalFormat, int type) {
        if (level > this.maxLevel) {
            this.maxLevel = level;
            this.needsAllocation = true;
        }

        if (level == 0) {
            int newBgfxFormat = glFormatToBgfx(internalFormat, type);

            if (this.width != width || this.height != height || this.bgfxFormat != newBgfxFormat) {
                this.width = width;
                this.height = height;
                this.internalFormat = internalFormat;
                this.bgfxFormat = newBgfxFormat;
                this.needsAllocation = true;

                LOGGER.debug("Texture {} params updated: {}x{}, format=0x{} -> BGFX format={}",
                    glId, width, height, Integer.toHexString(internalFormat), bgfxFormat);
            }
        }
    }

    /**
     * Allocate BGFX texture if parameters have changed.
     */
    private void allocateIfNeeded() {
        if (!needsAllocation) {
            return;
        }

        // Destroy old texture if exists
        if (bgfxHandle != BGFX_INVALID_HANDLE) {
            LOGGER.debug("Destroying old texture handle {} for GL ID {}", bgfxHandle, glId);
            bgfx_destroy_texture(bgfxHandle);
            bgfxHandle = BGFX_INVALID_HANDLE;
        }

        // Create new BGFX texture
        long flags = BGFX_TEXTURE_NONE | BGFX_SAMPLER_NONE;

        // Add filtering flags based on min/mag filter
        if (minFilter == GL11.GL_LINEAR || minFilter == GL11.GL_LINEAR_MIPMAP_LINEAR || minFilter == GL11.GL_LINEAR_MIPMAP_NEAREST) {
            flags |= BGFX_SAMPLER_MIN_ANISOTROPIC;
        }
        if (magFilter == GL11.GL_LINEAR) {
            flags |= BGFX_SAMPLER_MAG_ANISOTROPIC;
        }

        // Add wrapping flags
        if (wrapS == GL30.GL_CLAMP_TO_EDGE) {
            flags |= BGFX_SAMPLER_U_CLAMP;
        }
        if (wrapT == GL30.GL_CLAMP_TO_EDGE) {
            flags |= BGFX_SAMPLER_V_CLAMP;
        }

        bgfxHandle = bgfx_create_texture_2d(
            width, height,
            maxLevel > 0, // hasMips
            1, // numLayers
            bgfxFormat,
            flags,
            null // no initial data
        );

        if (bgfxHandle == BGFX_INVALID_HANDLE) {
            LOGGER.error("FAILED to create BGFX texture for GL ID {}: {}x{}, format={}",
                glId, width, height, bgfxFormat);
        } else {
            LOGGER.info("Created BGFX texture handle {} for GL ID {}: {}x{}, format={}",
                bgfxHandle, glId, width, height, bgfxFormat);
        }

        needsAllocation = false;
    }

    /**
     * Upload pixel data to BGFX texture.
     */
    private void uploadSubImage(int level, int xOffset, int yOffset,
                                 int width, int height, int format, int type, ByteBuffer pixels) {
        if (bgfxHandle == BGFX_INVALID_HANDLE) {
            LOGGER.warn("Cannot upload to invalid texture handle (GL ID {})", glId);
            return;
        }

        // Format conversion if needed (RGB -> RGBA, BGRA -> RGBA, etc.)
        ByteBuffer uploadBuffer = pixels;
        boolean needsFree = false;

        if (format == GL11.GL_RGB && bgfxFormat == BGFX_TEXTURE_FORMAT_RGBA8) {
            uploadBuffer = convertRGBtoRGBA(pixels, width, height);
            needsFree = true;
        } else if (format == GL30.GL_BGRA && bgfxFormat == BGFX_TEXTURE_FORMAT_RGBA8) {
            uploadBuffer = convertBGRAtoRGBA(pixels, width, height);
            needsFree = true;
        }

        // Create BGFX memory and copy pixel data
        BGFXMemory mem = bgfx_make_ref(uploadBuffer);

        // Upload to BGFX texture
        bgfx_update_texture_2d(
            bgfxHandle,
            (short) level,
            (short) 0, // layer
            (short) xOffset,
            (short) yOffset,
            (short) width,
            (short) height,
            mem,
            (short) BGFX_TEXTURE_NONE
        );

        LOGGER.info("Uploaded {}x{} pixels to texture {} (BGFX handle {}) at offset ({}, {}), level {}",
            width, height, glId, bgfxHandle, xOffset, yOffset, level);

        // Free converted buffer if we allocated one
        if (needsFree) {
            MemoryUtil.memFree(uploadBuffer);
        }
    }

    /**
     * Destroy BGFX texture handle.
     */
    private void destroy() {
        if (bgfxHandle != BGFX_INVALID_HANDLE) {
            bgfx_destroy_texture(bgfxHandle);
            bgfxHandle = BGFX_INVALID_HANDLE;
        }
    }

    // ========== Setters for Texture Parameters ==========

    private void setMaxLevel(int level) {
        if (this.maxLevel != level) {
            this.maxLevel = level;
            this.needsAllocation = true;
        }
    }

    private void setMinFilter(int filter) {
        this.minFilter = filter;
        // TODO: Update BGFX sampler flags
    }

    private void setMagFilter(int filter) {
        this.magFilter = filter;
        // TODO: Update BGFX sampler flags
    }

    private void setWrapS(int wrap) {
        this.wrapS = wrap;
        // TODO: Update BGFX sampler flags
    }

    private void setWrapT(int wrap) {
        this.wrapT = wrap;
        // TODO: Update BGFX sampler flags
    }

    // ========== Utility Methods ==========

    /**
     * Convert OpenGL format to BGFX format.
     */
    private static int glFormatToBgfx(int glInternalFormat, int glType) {
        // Handle common formats
        return switch (glInternalFormat) {
            case GL11.GL_RGBA, GL11.GL_RGBA8 -> BGFX_TEXTURE_FORMAT_RGBA8;
            case GL11.GL_RGB, GL11.GL_RGB8 -> BGFX_TEXTURE_FORMAT_RGBA8; // Convert RGB to RGBA
            case GL30.GL_BGRA -> BGFX_TEXTURE_FORMAT_RGBA8; // Convert BGRA to RGBA
            case GL11.GL_ALPHA -> BGFX_TEXTURE_FORMAT_R8;
            case GL30.GL_DEPTH_COMPONENT -> BGFX_TEXTURE_FORMAT_D24S8;
            default -> {
                LOGGER.warn("Unsupported GL format 0x{}, defaulting to RGBA8", Integer.toHexString(glInternalFormat));
                yield BGFX_TEXTURE_FORMAT_RGBA8;
            }
        };
    }

    /**
     * Calculate bytes per pixel based on format and type.
     */
    private static int getBytesPerPixel(int format, int type) {
        int components = switch (format) {
            case GL11.GL_RGBA, GL30.GL_BGRA -> 4;
            case GL11.GL_RGB -> 3;
            case GL30.GL_RG -> 2;
            case GL11.GL_RED, GL11.GL_ALPHA -> 1;
            default -> 4; // Default to 4
        };

        int bytesPerComponent = switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_UNSIGNED_SHORT -> 2;
            case GL11.GL_FLOAT -> 4;
            default -> 1;
        };

        return components * bytesPerComponent;
    }

    /**
     * Convert RGB to RGBA (add alpha channel).
     */
    private static ByteBuffer convertRGBtoRGBA(ByteBuffer rgb, int width, int height) {
        int pixelCount = width * height;
        ByteBuffer rgba = MemoryUtil.memAlloc(pixelCount * 4);

        for (int i = 0; i < pixelCount; i++) {
            rgba.put(rgb.get()); // R
            rgba.put(rgb.get()); // G
            rgba.put(rgb.get()); // B
            rgba.put((byte) 0xFF); // A = 255
        }

        rgba.flip();
        return rgba;
    }

    /**
     * Convert BGRA to RGBA (swap R and B channels).
     */
    private static ByteBuffer convertBGRAtoRGBA(ByteBuffer bgra, int width, int height) {
        int pixelCount = width * height;
        ByteBuffer rgba = MemoryUtil.memAlloc(pixelCount * 4);

        for (int i = 0; i < pixelCount; i++) {
            byte b = bgra.get();
            byte g = bgra.get();
            byte r = bgra.get();
            byte a = bgra.get();

            rgba.put(r);
            rgba.put(g);
            rgba.put(b);
            rgba.put(a);
        }

        rgba.flip();
        return rgba;
    }

    /**
     * Get BGFX texture handle for this texture.
     */
    public short getBgfxHandle() {
        return bgfxHandle;
    }
}
