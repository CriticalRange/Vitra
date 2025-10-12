package com.vitra.render.bgfx;

/**
 * Central access point for BGFX manager instances.
 *
 * This class is NOT a mixin, so it can provide public static access to managers
 * without violating Mixin framework rules (which forbid non-private static methods).
 *
 * These managers use ONLY BGFX native methods:
 * - BgfxTextureManager: texture creation and management
 * - BgfxShaderManager: shader program management
 */
public class BgfxManagers {
    private static final BgfxTextureManager textureManager = new BgfxTextureManager();
    private static final BgfxShaderManager shaderManager = new BgfxShaderManager();

    /**
     * Get the texture manager instance.
     * Uses BGFX native methods: bgfx_create_texture(), bgfx_destroy_texture()
     */
    public static BgfxTextureManager getTextureManager() {
        return textureManager;
    }

    /**
     * Get the shader manager instance.
     * Uses BGFX native methods: bgfx_create_program(), bgfx_destroy_program()
     */
    public static BgfxShaderManager getShaderManager() {
        return shaderManager;
    }

    // Private constructor to prevent instantiation
    private BgfxManagers() {
    }
}
