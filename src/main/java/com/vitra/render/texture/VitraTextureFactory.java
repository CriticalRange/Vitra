package com.vitra.render.texture;

import com.vitra.VitraMod;
import com.vitra.config.RendererType;
import com.vitra.core.VitraCore;
import com.vitra.render.D3D11Texture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Factory for creating renderer-appropriate textures.
 * Automatically selects D3D11 or DirectX 12 texture implementation based on active renderer.
 */
public class VitraTextureFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraTextureFactory.class);

    // Texture ID management
    private static int ID_COUNTER = 1;
    private static final Int2ObjectOpenHashMap<IVitraTexture> textureMap = new Int2ObjectOpenHashMap<>();
    private static int boundTextureId = 0;
    private static IVitraTexture boundTexture;

    /**
     * Get the currently active renderer type
     */
    private static RendererType getRendererType() {
        VitraCore core = VitraMod.getCore();
        if (core != null && core.getRenderer() != null) {
            return core.getRenderer().getRendererType();
        }
        // Default to D3D11 if renderer not initialized yet
        return RendererType.DIRECTX11;
    }

    /**
     * Generate a new texture ID and create appropriate texture instance
     */
    public static int genTextureId() {
        int id = ID_COUNTER++;
        IVitraTexture texture = createTexture(id);
        textureMap.put(id, texture);
        return id;
    }

    /**
     * Create a texture instance for the active renderer
     */
    private static IVitraTexture createTexture(int id) {
        RendererType rendererType = getRendererType();

        switch (rendererType) {
            case DIRECTX12:
            case DIRECTX12_ULTIMATE:
                return new D3D12Texture(id);

            case DIRECTX11:
            default:
                // For D3D11, we still use the existing D3D11Texture system
                // which manages its own texture map
                // This is a temporary bridge until D3D11Texture is fully refactored
                return new D3D11TextureWrapper(id);
        }
    }

    /**
     * Get texture by ID
     */
    public static IVitraTexture getTexture(int id) {
        return textureMap.get(id);
    }

    /**
     * Bind a texture by ID
     */
    public static void bindTexture(int id) {
        // Handle negative IDs (unbind)
        if (id < 0) {
            boundTextureId = 0;
            boundTexture = null;
            return;
        }

        boundTextureId = id;

        if (id == 0) {
            boundTexture = null;
            return;
        }

        IVitraTexture texture = textureMap.get(id);
        if (texture != null) {
            boundTexture = texture;
            texture.bind();
        } else {
            LOGGER.warn("Attempted to bind non-existent texture ID: {}", id);
        }
    }

    /**
     * Get currently bound texture
     */
    public static IVitraTexture getBoundTexture() {
        return boundTexture;
    }

    /**
     * Get currently bound texture ID
     */
    public static int getBoundTextureId() {
        return boundTextureId;
    }

    /**
     * Set pixel store parameters (for texture upload)
     * These parameters affect how texture data is unpacked during upload
     */
    public static void pixelStorei(int pname, int param) {
        // For now, delegate to D3D11Texture which manages these static parameters
        // TODO: Make this renderer-agnostic by storing parameters here
        D3D11Texture.pixelStorei(pname, param);
    }

    /**
     * Upload texture data (OpenGL-style texImage2D)
     * Creates or updates a texture with new pixel data
     */
    public static void texImage2D(int target, int level, int internalFormat, int width, int height,
                                   int border, int format, int type, @Nullable ByteBuffer pixels) {
        // For now, delegate to D3D11Texture for compatibility
        // TODO: Make this renderer-agnostic by using IVitraTexture interface
        D3D11Texture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    /**
     * Upload texture subregion data (OpenGL-style texSubImage2D)
     * Updates a portion of an existing texture
     */
    public static void texSubImage2D(int target, int level, int xOffset, int yOffset,
                                      int width, int height, int format, int type, long pixels) {
        // For now, delegate to D3D11Texture for compatibility
        // TODO: Make this renderer-agnostic by using IVitraTexture interface
        D3D11Texture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * Wrapper class that bridges to existing D3D11Texture system
     * This is temporary until D3D11Texture is fully refactored to implement IVitraTexture
     */
    private static class D3D11TextureWrapper implements IVitraTexture {
        private final int id;

        public D3D11TextureWrapper(int id) {
            this.id = id;
            // Ensure D3D11Texture has this ID in its map
            D3D11Texture.getTexture(id);
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public long getNativeHandle() {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null ? tex.getNativeHandle() : 0;
        }

        @Override
        public int getWidth() {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null ? tex.getWidth() : 0;
        }

        @Override
        public int getHeight() {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null ? tex.getHeight() : 0;
        }

        @Override
        public int getFormat() {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null ? tex.getFormat() : 0;
        }

        @Override
        public int getMipLevels() {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null ? tex.getMipLevels() : 0;
        }

        @Override
        public void bind() {
            D3D11Texture.bindTexture(id);
        }

        @Override
        public void unbind() {
            D3D11Texture.bindTexture(0);
        }

        @Override
        public void upload(byte[] pixels, int width, int height, int format) {
            // Delegate to D3D11Texture static methods
            // Convert byte array to ByteBuffer
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(pixels.length);
            buffer.put(pixels);
            buffer.flip();
            D3D11Texture.texImage2D(0x0DE1, 0, format, width, height, 0, format, 0x1401, buffer);
        }

        @Override
        public void uploadSubImage(int x, int y, int width, int height, byte[] pixels, int format) {
            // Convert byte array to native memory address
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(pixels.length);
            buffer.put(pixels);
            buffer.flip();
            long address = org.lwjgl.system.MemoryUtil.memAddress(buffer);
            D3D11Texture.texSubImage2D(0x0DE1, 0, x, y, width, height, format, 0x1401, address);
        }

        @Override
        public void setParameter(int paramName, int value) {
            D3D11Texture.setTextureParameter(id, paramName, value);
        }

        @Override
        public void generateMipmaps() {
            // TODO: Implement generateMipmaps for D3D11Texture
            // For now, this is a no-op as D3D11Texture doesn't have this method yet
            LOGGER.debug("generateMipmaps called for D3D11 texture ID={} (not yet implemented)", id);
        }

        @Override
        public void destroy() {
            D3D11Texture.deleteTexture(id);
        }

        @Override
        public boolean needsRecreation(int mipLevels, int width, int height) {
            D3D11Texture tex = D3D11Texture.getTexture(id);
            return tex != null && tex.needsRecreation(mipLevels, width, height);
        }

        @Override
        public boolean isValid() {
            return getNativeHandle() != 0;
        }
    }
}
