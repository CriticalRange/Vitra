package com.vitra.render.texture;

/**
 * Renderer-agnostic texture interface for Vitra rendering backends.
 * Provides common texture operations that work across DirectX 11, DirectX 12, and future renderers.
 */
public interface IVitraTexture {
    /**
     * Get the OpenGL-compatible texture ID (for compatibility with Minecraft's texture system)
     */
    int getId();

    /**
     * Get the native backend handle (DirectX texture handle, etc.)
     */
    long getNativeHandle();

    /**
     * Get texture width in pixels
     */
    int getWidth();

    /**
     * Get texture height in pixels
     */
    int getHeight();

    /**
     * Get texture format (backend-specific format code)
     */
    int getFormat();

    /**
     * Get number of mip levels
     */
    int getMipLevels();

    /**
     * Bind this texture for rendering
     */
    void bind();

    /**
     * Unbind this texture
     */
    void unbind();

    /**
     * Upload pixel data to the texture
     * @param pixels Raw pixel data
     * @param width Width of the data
     * @param height Height of the data
     * @param format Pixel format
     */
    void upload(byte[] pixels, int width, int height, int format);

    /**
     * Upload a sub-region of pixel data to the texture
     * @param x X offset in pixels
     * @param y Y offset in pixels
     * @param width Width of the region
     * @param height Height of the region
     * @param pixels Raw pixel data
     * @param format Pixel format
     */
    void uploadSubImage(int x, int y, int width, int height, byte[] pixels, int format);

    /**
     * Set a texture parameter (filtering, wrapping, etc.)
     * @param paramName Parameter name (GL-compatible constant)
     * @param value Parameter value
     */
    void setParameter(int paramName, int value);

    /**
     * Generate mipmaps for this texture
     */
    void generateMipmaps();

    /**
     * Destroy the texture and free resources
     */
    void destroy();

    /**
     * Check if the texture needs to be recreated based on new parameters
     * @param mipLevels New mip level count
     * @param width New width
     * @param height New height
     * @return true if recreation is needed
     */
    boolean needsRecreation(int mipLevels, int width, int height);

    /**
     * Check if this texture has a valid native handle
     */
    boolean isValid();
}
