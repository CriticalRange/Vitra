package com.vitra.render;

import com.vitra.render.jni.VitraNativeRenderer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 texture wrapper that manages OpenGL-style texture IDs
 * mapped to native DirectX 11 texture handles.
 *
 * This class provides an OpenGL-compatible API that internally
 * uses DirectX 11 textures via JNI.
 */
public class Dx11Texture {
    private static final Logger LOGGER = LoggerFactory.getLogger(Dx11Texture.class);

    // DirectX 11 format constants (DXGI_FORMAT)
    public static final int FORMAT_RGBA8_UNORM = 28;  // DXGI_FORMAT_R8G8B8A8_UNORM
    public static final int FORMAT_R8_UNORM = 61;     // DXGI_FORMAT_R8_UNORM
    public static final int FORMAT_BGRA8_UNORM = 87;  // DXGI_FORMAT_B8G8R8A8_UNORM
    public static final int FORMAT_DEPTH24_STENCIL8 = 45; // DXGI_FORMAT_D24_UNORM_S8_UINT

    // Texture ID management
    private static int ID_COUNTER = 1;
    private static final Int2ObjectOpenHashMap<Dx11Texture> textureMap = new Int2ObjectOpenHashMap<>();
    private static int boundTextureId = 0;
    private static Dx11Texture boundTexture;
    private static int activeTextureUnit = 0;

    // OpenGL pixel unpack parameters (CRITICAL for font textures)
    private static int unpackRowLength = 0;
    private static int unpackSkipRows = 0;
    private static int unpackSkipPixels = 0;
    private static int unpackAlignment = 4;

    // Instance fields
    public final int id;
    private long nativeHandle; // DirectX 11 texture handle from JNI
    private int width;
    private int height;
    private int mipLevels;
    private int format; // DirectX format

    private boolean needsRecreation = false;

    /**
     * Generate a new texture ID
     */
    public static int genTextureId() {
        int id = ID_COUNTER++;
        Dx11Texture texture = new Dx11Texture(id);
        textureMap.put(id, texture);
        // Logging disabled for performance
        return id;
    }

    /**
     * Bind a texture by ID
     * Following VulkanMod's pattern: only bind to GPU if texture has native handle
     */
    public static void bindTexture(int id) {
        // CRITICAL FIX: Handle negative texture IDs (common during Minecraft initialization)
        // Negative IDs like -1 indicate "no texture" - treat as unbind
        if (id < 0) {
            boundTextureId = 0;
            boundTexture = null;
            // Unbind texture from DirectX 11 (pass 0, not negative value)
            VitraNativeRenderer.bindTexture(0);
            // Logging disabled for performance
            return;
        }

        boundTextureId = id;

        if (id == 0) {
            boundTexture = null;
            // Unbind texture from DirectX 11
            VitraNativeRenderer.bindTexture(0);
            return;
        }

        boundTexture = textureMap.get(id);
        if (boundTexture == null) {
            LOGGER.warn("Attempted to bind non-existent texture ID: {} - creating placeholder", id);
            // Create placeholder texture entry (VulkanMod pattern)
            // This allows late initialization of textures
            Dx11Texture texture = new Dx11Texture(id);
            textureMap.put(id, texture);
            boundTexture = texture;
            return; // Don't bind to GPU yet - no native handle
        }

        // CRITICAL: Only bind to DirectX 11 if texture has been allocated (has native handle)
        // VulkanMod pattern: defer GPU binding until texture is fully created
        if (boundTexture.nativeHandle != 0) {
            // Bind to DirectX 11 via JNI using texture ID (not handle)
            // The native code looks up the texture in g_shaderResourceViews using the ID
            VitraNativeRenderer.bindTexture(id);
            // Logging disabled for performance
        }
        // If nativeHandle is 0, texture not allocated yet - safe no-op (VulkanMod pattern)
    }

    /**
     * Get the currently bound texture
     */
    public static Dx11Texture getBoundTexture() {
        return boundTexture;
    }

    /**
     * Get a texture by ID
     */
    public static Dx11Texture getTexture(int id) {
        return textureMap.get(id);
    }

    /**
     * Delete a texture
     */
    public static void deleteTexture(int id) {
        Dx11Texture texture = textureMap.remove(id);
        if (texture != null) {
            texture.release();
            // Logging disabled for performance
        }
    }

    /**
     * Set the active texture unit
     */
    public static void activeTexture(int textureUnit) {
        activeTextureUnit = textureUnit - GL30.GL_TEXTURE0;
        VitraNativeRenderer.setActiveTextureUnit(activeTextureUnit);
    }

    /**
     * Set pixel store parameters (OpenGL-style)
     * CRITICAL for font texture uploads
     */
    public static void pixelStorei(int pname, int param) {
        switch (pname) {
            case 0x0CF2: // GL_UNPACK_ROW_LENGTH
                unpackRowLength = param;
                break;
            case 0x0CF3: // GL_UNPACK_SKIP_ROWS
                unpackSkipRows = param;
                break;
            case 0x0CF4: // GL_UNPACK_SKIP_PIXELS
                unpackSkipPixels = param;
                break;
            case 0x0CF5: // GL_UNPACK_ALIGNMENT
                unpackAlignment = param;
                break;
        }
    }

    /**
     * Create a DirectX 11 texture with specified parameters
     */
    public static void createTexture(int id, int width, int height, int mipLevels, int dxFormat) {
        Dx11Texture texture = textureMap.get(id);
        if (texture == null) {
            LOGGER.warn("Attempted to create texture for non-existent ID: {}", id);
            return;
        }

        texture.create(width, height, mipLevels, dxFormat);
    }

    /**
     * Set texture parameter (OpenGL-style)
     */
    public static void setTextureParameter(int id, int pname, int param) {
        Dx11Texture texture = textureMap.get(id);
        if (texture == null || texture.nativeHandle == 0) {
            return;
        }

        // Map OpenGL texture parameters to DirectX 11 sampler states
        switch (pname) {
            case GL30.GL_TEXTURE_MAX_LEVEL:
                // DirectX 11 handles this via shader resource view
                LOGGER.debug("Texture {}: Set max level to {}", id, param);
                break;
            case GL30.GL_TEXTURE_MIN_LOD:
            case GL30.GL_TEXTURE_MAX_LOD:
            case GL30.GL_TEXTURE_LOD_BIAS:
                // DirectX 11 sampler state parameters
                VitraNativeRenderer.setTextureParameter(texture.nativeHandle, pname, param);
                break;
            case GL11.GL_TEXTURE_MIN_FILTER:
            case GL11.GL_TEXTURE_MAG_FILTER:
            case GL11.GL_TEXTURE_WRAP_S:
            case GL11.GL_TEXTURE_WRAP_T:
                // Sampler state parameters
                VitraNativeRenderer.setTextureParameter(texture.nativeHandle, pname, param);
                break;
            default:
                LOGGER.debug("Unhandled texture parameter: {}", pname);
        }
    }

    private Dx11Texture(int id) {
        this.id = id;
        this.nativeHandle = 0;
        this.width = 0;
        this.height = 0;
        this.mipLevels = 1;
        this.format = FORMAT_RGBA8_UNORM;
    }

    private void create(int width, int height, int mipLevels, int dxFormat) {
        // Release old texture if it exists
        if (this.nativeHandle != 0) {
            release();
        }

        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = dxFormat;

        // Create DirectX 11 texture via JNI
        boolean success = VitraNativeRenderer.createTextureFromId(id, width, height, dxFormat);

        if (success) {
            this.nativeHandle = id;
            // Logging disabled for performance
        } else {
            LOGGER.error("Failed to create DirectX 11 texture: ID={}", id);
        }
    }

    /**
     * Check if texture needs recreation based on new parameters
     */
    public boolean needsRecreation(int mipLevels, int width, int height) {
        return this.nativeHandle == 0
            || this.width != width
            || this.height != height
            || this.mipLevels != mipLevels;
    }

    /**
     * Release the native DirectX 11 texture
     */
    public void release() {
        if (this.nativeHandle != 0) {
            VitraNativeRenderer.releaseTexture(this.nativeHandle);
            // Logging disabled for performance
            this.nativeHandle = 0;
        }
    }

    // Getters
    public long getNativeHandle() {
        return nativeHandle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getMipLevels() {
        return mipLevels;
    }

    public int getFormat() {
        return format;
    }

    /**
     * Upload texture data (OpenGL-style texImage2D)
     * Based on VulkanMod's VkGlTexture.texImage2D
     */
    public static void texImage2D(int target, int level, int internalFormat, int width, int height,
                                   int border, int format, int type, @org.jetbrains.annotations.Nullable java.nio.ByteBuffer pixels) {
        if (width == 0 || height == 0) {
            return;
        }

        Dx11Texture texture = boundTexture;
        if (texture == null) {
            LOGGER.warn("texImage2D called with no bound texture");
            return;
        }

        // Update texture parameters and allocate if needed
        texture.updateParams(level, width, height, internalFormat, type);
        texture.allocateIfNeeded();

        // CRITICAL: Bind texture to current texture unit for upload (VulkanMod pattern)
        // texSubImage2D needs the texture bound in native g_glState.boundTextures[]
        if (texture.nativeHandle != 0) {
            VitraNativeRenderer.bindTexture(texture.id);
        }

        // Upload texture data
        if (pixels != null) {
            texture.uploadSubImage(level, 0, 0, width, height, format, pixels);
        }
    }

    /**
     * Upload texture sub-region data (OpenGL-style texSubImage2D)
     * Based on VulkanMod's VkGlTexture.texSubImage2D
     */
    public static void texSubImage2D(int target, int level, int xOffset, int yOffset,
                                      int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0) {
            return;
        }

        Dx11Texture texture = boundTexture;
        if (texture == null) {
            LOGGER.warn("texSubImage2D called with no bound texture");
            return;
        }

        java.nio.ByteBuffer src;
        if (pixels != 0L) {
            // CRITICAL: Calculate proper buffer size using unpack parameters (VulkanMod pattern)
            // This is essential for font textures which use GL_UNPACK_ROW_LENGTH
            int formatSize = 4; // TODO: handle other formats (RGBA = 4 bytes)
            int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
            int offset = (unpackSkipRows * rowLength + unpackSkipPixels) * formatSize;
            int bufferSize = (rowLength * height - unpackSkipPixels) * formatSize;

            // Create ByteBuffer with proper offset and size
            src = org.lwjgl.system.MemoryUtil.memByteBuffer(pixels + offset, bufferSize);
        } else {
            src = null;
        }

        if (src != null) {
            texture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
        }
    }

    private void updateParams(int level, int width, int height, int internalFormat, int type) {
        if (level == 0) {
            int dxFormat = convertGlFormat(internalFormat, type);

            if (this.nativeHandle == 0 || this.width != width || this.height != height || this.format != dxFormat) {
                this.width = width;
                this.height = height;
                this.format = dxFormat;
                this.needsRecreation = true;
            }
        }
    }

    private void allocateIfNeeded() {
        if (needsRecreation) {
            // Create DirectX 11 texture via JNI
            if (this.nativeHandle != 0) {
                release();
            }

            // Use createTextureFromId which exists in native code
            boolean success = VitraNativeRenderer.createTextureFromId(id, width, height, format);

            if (success) {
                // Store the ID as the handle (DirectX manages the actual texture)
                this.nativeHandle = id;
                // Logging disabled for performance
            } else {
                LOGGER.error("Failed to allocate DirectX 11 texture: ID={}", id);
            }

            needsRecreation = false;
        }
    }

    private void uploadSubImage(int level, int xOffset, int yOffset, int width, int height,
                                 int format, java.nio.ByteBuffer pixels) {
        if (this.nativeHandle == 0) {
            LOGGER.warn("Attempted to upload to texture with no native handle: ID={}", id);
            return;
        }

        // CRITICAL: Calculate row pitch using unpack parameters
        // This ensures DirectX gets the correct stride for the pixel data
        int formatSize = 4; // TODO: handle other formats
        int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
        int rowPitch = rowLength * formatSize;

        // Upload to DirectX 11 texture via JNI using texSubImage2D
        // Convert ByteBuffer to memory address for JNI call
        long pixelPtr = pixels != null ? org.lwjgl.system.MemoryUtil.memAddress(pixels) : 0;

        // Pass rowPitch to native code via a new method that accepts it
        VitraNativeRenderer.texSubImage2DWithPitch(0x0DE1, level, xOffset, yOffset, width, height, format, 0x1401, pixelPtr, rowPitch);

        // Logging disabled for performance
    }

    private static int convertGlFormat(int internalFormat, int type) {
        // Convert OpenGL format to DirectX format
        // Based on common OpenGL formats used by Minecraft
        switch (internalFormat) {
            case 0x1908: // GL_RGBA
            case 0x8058: // GL_RGBA8
                return FORMAT_RGBA8_UNORM;
            case 0x1909: // GL_LUMINANCE
            case 0x8049: // GL_R8
                return FORMAT_R8_UNORM;
            case 0x80E1: // GL_BGRA
                return FORMAT_BGRA8_UNORM;
            default:
                LOGGER.warn("Unknown GL format: 0x{}, defaulting to RGBA8", Integer.toHexString(internalFormat));
                return FORMAT_RGBA8_UNORM;
        }
    }
}
