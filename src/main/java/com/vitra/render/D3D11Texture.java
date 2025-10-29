package com.vitra.render;

import com.vitra.render.jni.VitraD3D11Renderer;
import com.vitra.render.texture.IVitraTexture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D3D11 texture wrapper that manages OpenGL-style texture IDs
 * mapped to native D3D11 texture handles.
 *
 * This class provides an OpenGL-compatible API that internally
 * uses D3D11 textures via JNI and implements IVitraTexture interface.
 */
public class D3D11Texture implements IVitraTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11Texture.class);

    // D3D11 format constants (DXGI_FORMAT)
    public static final int FORMAT_RGBA8_UNORM = 28;  // DXGI_FORMAT_R8G8B8A8_UNORM
    public static final int FORMAT_R8_UNORM = 61;     // DXGI_FORMAT_R8_UNORM
    public static final int FORMAT_BGRA8_UNORM = 87;  // DXGI_FORMAT_B8G8R8A8_UNORM
    public static final int FORMAT_DEPTH24_STENCIL8 = 45; // DXGI_FORMAT_D24_UNORM_S8_UINT

    // Texture ID management
    private static int ID_COUNTER = 1;
    private static final Int2ObjectOpenHashMap<D3D11Texture> textureMap = new Int2ObjectOpenHashMap<>();
    private static int boundTextureId = 0;
    private static D3D11Texture boundTexture;
    private static int activeTextureUnit = 0;

    // OpenGL pixel unpack parameters (CRITICAL for font textures)
    private static int unpackRowLength = 0;
    private static int unpackSkipRows = 0;
    private static int unpackSkipPixels = 0;
    private static int unpackAlignment = 4;

    // Instance fields
    public final int id;
    private long nativeHandle; // D3D11 texture handle from JNI
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
        D3D11Texture texture = new D3D11Texture(id);
        textureMap.put(id, texture);

        // DEBUG: Log first 20 texture ID generations
        if (id <= 20) {
            LOGGER.info("[TEXTURE_ID_GEN] Generated texture ID={}, Total IDs generated={}", id, ID_COUNTER - 1);
        }

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
            // Unbind texture from D3D11 (pass 0, not negative value)
            VitraD3D11Renderer.bindTexture(0);
            return;
        }

        boundTextureId = id;

        if (id == 0) {
            boundTexture = null;
            // Unbind texture from D3D11
            VitraD3D11Renderer.bindTexture(0);
            return;
        }

        boundTexture = textureMap.get(id);
        if (boundTexture == null) {
            LOGGER.warn("[TEXTURE_BIND] Attempted to bind non-existent texture ID: {} - creating placeholder", id);
            // Create placeholder texture entry (VulkanMod pattern)
            // This allows late initialization of textures
            D3D11Texture texture = new D3D11Texture(id);
            textureMap.put(id, texture);
            boundTexture = texture;
            return; // Don't bind yet - texture not created, will bind in texImage2D
        }

        // CRITICAL: Only bind to D3D11 if texture has been allocated (has native handle)
        // The actual binding happens in texImage2D after texture creation
        if (boundTexture.nativeHandle != 0) {
            VitraD3D11Renderer.bindTexture(id);
            LOGGER.info("[TEXTURE_BIND] Bound texture ID={} (handle={})", id, boundTexture.nativeHandle);
        } else {
            // Texture exists in Java map but not created yet on native side
            // Will be bound in texImage2D after creation
            LOGGER.info("[TEXTURE_BIND] Texture ID={} exists but not created yet - will bind after creation", id);
        }
    }

    /**
     * Get the currently bound texture
     */
    public static D3D11Texture getBoundTexture() {
        return boundTexture;
    }

    /**
     * Get the texture ID bound to a specific slot (for GlStateManagerMixin compatibility)
     */
    public static int getBoundTexture(int slot) {
        // D3D11Texture uses a single active texture slot model
        // Return the currently bound texture ID if it matches the active slot
        return (slot == activeTextureUnit) ? boundTextureId : 0;
    }

    /**
     * Get the currently active texture slot (for GlStateManagerMixin compatibility)
     */
    public static int getActiveTextureSlot() {
        return activeTextureUnit;
    }

    /**
     * Get a texture by ID
     */
    public static D3D11Texture getTexture(int id) {
        return textureMap.get(id);
    }

    /**
     * Set unpack row length (for GlStateManagerMixin compatibility)
     */
    public static void setUnpackRowLength(int rowLength) {
        unpackRowLength = rowLength;
    }

    /**
     * Set unpack skip rows (for GlStateManagerMixin compatibility)
     */
    public static void setUnpackSkipRows(int skipRows) {
        unpackSkipRows = skipRows;
    }

    /**
     * Set unpack skip pixels (for GlStateManagerMixin compatibility)
     */
    public static void setUnpackSkipPixels(int skipPixels) {
        unpackSkipPixels = skipPixels;
    }

    /**
     * Delete a texture
     */
    public static void deleteTexture(int id) {
        D3D11Texture texture = textureMap.remove(id);
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
        VitraD3D11Renderer.setActiveTextureUnit(activeTextureUnit);
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
     * Create a D3D11 texture with specified parameters
     */
    public static void createTexture(int id, int width, int height, int mipLevels, int dxFormat) {
        D3D11Texture texture = textureMap.get(id);
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
        D3D11Texture texture = textureMap.get(id);
        if (texture == null || texture.nativeHandle == 0) {
            return;
        }

        // Map OpenGL texture parameters to D3D11 sampler states
        switch (pname) {
            case GL30.GL_TEXTURE_MAX_LEVEL:
                // D3D11 handles this via shader resource view
                LOGGER.debug("Texture {}: Set max level to {}", id, param);
                break;
            case GL30.GL_TEXTURE_MIN_LOD:
            case GL30.GL_TEXTURE_MAX_LOD:
            case GL30.GL_TEXTURE_LOD_BIAS:
                // D3D11 sampler state parameters
                VitraD3D11Renderer.setTextureParameter(texture.nativeHandle, pname, param);
                break;
            case GL11.GL_TEXTURE_MIN_FILTER:
            case GL11.GL_TEXTURE_MAG_FILTER:
            case GL11.GL_TEXTURE_WRAP_S:
            case GL11.GL_TEXTURE_WRAP_T:
                // Sampler state parameters
                VitraD3D11Renderer.setTextureParameter(texture.nativeHandle, pname, param);
                break;
            default:
                LOGGER.debug("Unhandled texture parameter: {}", pname);
        }
    }

    private D3D11Texture(int id) {
        this.id = id;
        this.nativeHandle = 0;
        this.width = 0;
        this.height = 0;
        this.mipLevels = 1;
        // Default to RGBA - format will be determined by convertGlFormat
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

        // Create D3D11 texture via JNI
        boolean success = VitraD3D11Renderer.createTextureFromId(id, width, height, dxFormat);

        if (success) {
            this.nativeHandle = id;
            // Logging disabled for performance
        } else {
            LOGGER.error("Failed to create D3D11 texture: ID={}", id);
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
     * Release the native D3D11 texture
     */
    public void release() {
        if (this.nativeHandle != 0) {
            VitraD3D11Renderer.releaseTexture(this.nativeHandle);
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
    private static int texImage2DCount = 0;

    public static void texImage2D(int target, int level, int internalFormat, int width, int height,
                                   int border, int format, int type, @org.jetbrains.annotations.Nullable java.nio.ByteBuffer pixels) {
        if (width == 0 || height == 0) {
            return;
        }

        D3D11Texture texture = boundTexture;
        if (texture == null) {
            LOGGER.warn("texImage2D called with no bound texture");
            return;
        }

        // DEBUG: Log first 20 texImage2D calls
        if (texImage2DCount < 20) {
            LOGGER.info("[TEX_IMAGE_2D {}] ID={}, size={}x{}, pixels={}",
                texImage2DCount++, texture.id, width, height, pixels != null ? "YES" : "NULL");
        }

        // DEBUG: Log if internalFormat looks like a DirectX format instead of GL format
        if (internalFormat == FORMAT_RGBA8_UNORM || internalFormat == FORMAT_BGRA8_UNORM) {
            LOGGER.warn("[TEXTURE_FORMAT_BUG] texImage2D called with DirectX format 0x{} instead of GL format! " +
                "This is likely a bug - GL formats should be 0x1908 (GL_RGBA), not 0x{} (DXGI_FORMAT)",
                Integer.toHexString(internalFormat), Integer.toHexString(internalFormat));
            // Convert back to GL format for compatibility
            internalFormat = 0x1908; // GL_RGBA
        }

        // Update texture parameters and allocate if needed
        texture.updateParams(level, width, height, internalFormat, type);
        texture.allocateIfNeeded();

        // CRITICAL: Bind texture to current texture unit for upload (VulkanMod pattern)
        // This ensures newly created textures are bound immediately after creation
        if (texture.nativeHandle != 0) {
            VitraD3D11Renderer.bindTexture(texture.id);
            LOGGER.info("[TEXTURE_BIND_AFTER_CREATE] Bound texture ID={} after creation", texture.id);
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

        D3D11Texture texture = boundTexture;
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
            // Create D3D11 texture via JNI
            if (this.nativeHandle != 0) {
                release();
            }

            // Use createTextureFromId which exists in native code
            boolean success = VitraD3D11Renderer.createTextureFromId(id, width, height, format);

            if (success) {
                // Store the ID as the handle (DirectX manages the actual texture)
                this.nativeHandle = id;
            } else {
                LOGGER.error("Failed to allocate D3D11 texture: ID={}, size={}x{}, format={}", id, width, height, format);
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

        // CRITICAL: VulkanMod pattern - convert RGBA pixel data to BGRA if needed
        java.nio.ByteBuffer convertedPixels = pixels;
        boolean needsFree = false;

        if (format == 0x1908 && this.format == FORMAT_BGRA8_UNORM) { // GL_RGBA format but BGRA texture
            LOGGER.info("[RGBA_TO_BGRA] Converting RGBA pixel data to BGRA for texture ID={}, size={}x{}", id, width, height);
            convertedPixels = RGBAtoBGRA(pixels);
            needsFree = true;
            LOGGER.info("[RGBA_TO_BGRA] Conversion complete for texture ID={}", id);
        } else {
            LOGGER.info("[TEXTURE_UPLOAD] No conversion needed for texture ID={}, format=0x{}, textureFormat={}",
                id, Integer.toHexString(format), this.format);
        }

        // CRITICAL: Calculate row pitch using unpack parameters
        // This ensures DirectX gets the correct stride for the pixel data
        int formatSize = 4; // TODO: handle other formats
        int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
        int rowPitch = rowLength * formatSize;

        // Upload to D3D11 texture via JNI using texSubImage2D
        // Convert ByteBuffer to memory address for JNI call
        long pixelPtr = convertedPixels != null ? org.lwjgl.system.MemoryUtil.memAddress(convertedPixels) : 0;

        // Pass rowPitch to native code via a new method that accepts it
        VitraD3D11Renderer.texSubImage2DWithPitch(0x0DE1, level, xOffset, yOffset, width, height, format, 0x1401, pixelPtr, rowPitch);

        // Free converted buffer if we allocated it
        if (needsFree && convertedPixels != null) {
            org.lwjgl.system.MemoryUtil.memFree(convertedPixels);
        }
    }

    public static int convertGlFormat(int internalFormat, int type) {
        // Convert OpenGL format to DirectX format
        // Based on common OpenGL formats used by Minecraft
        // Following VulkanMod's GlUtil.vulkanFormat pattern

        // CRITICAL FIX: Use RGBA format instead of BGRA!
        // Shaders expect RGBA order, not BGRA. We convert pixels from RGBA to RGBA (no swap needed)
        switch (internalFormat) {
            case 0x1908: // GL_RGBA
            case 0x8058: // GL_RGBA8
                // Use RGBA format - matches shader expectations
                return FORMAT_RGBA8_UNORM;

            case 0x1903: // GL_RED (single channel red - VulkanMod pattern)
            case 0x1909: // GL_LUMINANCE (legacy single channel)
            case 0x8049: // GL_R8 (red channel 8-bit)
                return FORMAT_R8_UNORM;

            case 0x8227: // GL_RG (two channel red-green - VulkanMod pattern)
            case 0x822B: // GL_RG8 (8-bit red-green)
                // Note: DirectX 11 has R8G8_UNORM (format 49) for two-channel
                // For now, fall back to RGBA (will be fixed when we add more formats)
                LOGGER.debug("GL_RG format requested - using RGBA fallback (TODO: add DXGI_FORMAT_R8G8_UNORM)");
                return FORMAT_RGBA8_UNORM;

            case 0x80E1: // GL_BGRA
                // BGRA input needs to stay as BGRA
                return FORMAT_BGRA8_UNORM;

            case 0x8367: // GL_UNSIGNED_INT_8_8_8_8_REV (special packed format - VulkanMod pattern)
                // This is a special reverse byte order format
                return FORMAT_RGBA8_UNORM; // Use RGBA, not BGRA

            // Depth formats (VulkanMod pattern)
            case 0x1902: // GL_DEPTH_COMPONENT
            case 0x81A5: // GL_DEPTH_COMPONENT16
            case 0x81A6: // GL_DEPTH_COMPONENT24
            case 0x81A7: // GL_DEPTH_COMPONENT32
            case 0x8CAC: // GL_DEPTH_COMPONENT32F
                return FORMAT_DEPTH24_STENCIL8;

            default:
                LOGGER.warn("Unknown GL format: 0x{}, defaulting to RGBA8", Integer.toHexString(internalFormat));
                return FORMAT_RGBA8_UNORM;
        }
    }

    /**
     * Convert RGBA buffer to BGRA buffer (swap R and B channels)
     * Based on VulkanMod's GlUtil.BGRAtoRGBA_buffer pattern
     */
    private static java.nio.ByteBuffer RGBAtoBGRA(java.nio.ByteBuffer rgba) {
        int size = rgba.remaining();
        java.nio.ByteBuffer bgra = org.lwjgl.system.MemoryUtil.memAlloc(size);

        // Read and write sequentially for correct byte order
        rgba.rewind();
        while (rgba.hasRemaining()) {
            byte r = rgba.get();
            byte g = rgba.get();
            byte b = rgba.get();
            byte a = rgba.get();

            // Write as BGRA (swap R and B)
            bgra.put(b);
            bgra.put(g);
            bgra.put(r);
            bgra.put(a);
        }

        bgra.flip(); // Prepare for reading
        rgba.rewind(); // Reset source buffer
        return bgra;
    }

    /**
     * Convert RGBA to BGRA in-place by swapping R and B channels (byte array version)
     * More efficient than allocating a new buffer
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

    // IVitraTexture interface implementation methods

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void bind() {
        bindTexture(id);
    }

    @Override
    public void unbind() {
        bindTexture(0);
    }

    @Override
    public void upload(byte[] pixels, int width, int height, int format) {
        // Update texture parameters and allocate if needed
        updateParams(0, width, height, format, 0x1401); // GL_UNSIGNED_BYTE
        allocateIfNeeded();
        VitraD3D11Renderer.uploadTextureData(id, pixels, width, height, format);
    }

    @Override
    public void uploadSubImage(int x, int y, int width, int height, byte[] pixels, int format) {
        if (pixels != null && nativeHandle != 0) {
            VitraD3D11Renderer.updateTextureSubRegion(nativeHandle, pixels, x, y, width, height, format);
        }
    }

    @Override
    public void setParameter(int paramName, int value) {
        setTextureParameter(id, paramName, value);
    }

    @Override
    public void generateMipmaps() {
        // TODO: Implement mipmap generation for D3D11 textures
        LOGGER.debug("generateMipmaps not yet implemented for D3D11 textures");
    }

    @Override
    public void destroy() {
        release();
    }

    @Override
    public boolean isValid() {
        return nativeHandle != 0;
    }
}
