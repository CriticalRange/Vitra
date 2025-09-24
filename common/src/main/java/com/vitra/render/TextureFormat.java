package com.vitra.render;

/**
 * Supported texture formats for the rendering system
 */
public enum TextureFormat {
    // Basic color formats
    RGB8(3, false, false),
    RGBA8(4, true, false),

    // High precision formats
    RGB16F(6, false, true),
    RGBA16F(8, true, true),
    RGB32F(12, false, true),
    RGBA32F(16, true, true),

    // Depth formats
    DEPTH16(2, false, false),
    DEPTH24(3, false, false),
    DEPTH32F(4, false, true),

    // Compressed formats
    DXT1(0, false, false),    // Block compressed
    DXT3(0, true, false),     // Block compressed
    DXT5(0, true, false),     // Block compressed

    // Single channel formats
    R8(1, false, false),
    R16F(2, false, true),
    R32F(4, false, true);

    private final int bytesPerPixel;
    private final boolean hasAlpha;
    private final boolean isFloat;

    TextureFormat(int bytesPerPixel, boolean hasAlpha, boolean isFloat) {
        this.bytesPerPixel = bytesPerPixel;
        this.hasAlpha = hasAlpha;
        this.isFloat = isFloat;
    }

    public int getBytesPerPixel() {
        return bytesPerPixel;
    }

    public boolean hasAlpha() {
        return hasAlpha;
    }

    public boolean isFloat() {
        return isFloat;
    }

    public boolean isCompressed() {
        return bytesPerPixel == 0; // Compressed formats have variable size
    }

    public boolean isDepthFormat() {
        return this == DEPTH16 || this == DEPTH24 || this == DEPTH32F;
    }

    /**
     * Get the default texture format for general use
     */
    public static TextureFormat getDefault() {
        return RGBA8;
    }

    /**
     * Get the best format for HDR rendering
     */
    public static TextureFormat getHDR() {
        return RGBA16F;
    }

    /**
     * Get the best format for depth buffers
     */
    public static TextureFormat getDepth() {
        return DEPTH24;
    }
}