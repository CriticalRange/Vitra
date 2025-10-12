package com.vitra.render.bgfx;

import com.mojang.blaze3d.buffers.GpuFence;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXCaps;
import org.lwjgl.bgfx.BGFXStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consolidated BGFX utility class that combines common operations
 * This replaces multiple small utility classes and provides a single point of access
 */
public class BgfxUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxUtils");

    /**
     * Create a BGFX fence using native functionality
     */
    public static GpuFence createFence() {
        return new BgfxFence();
    }

    /**
     * Create a texture view using native functionality
     */
    public static BgfxTextureView createTextureView(BgfxTexture texture) {
        return new BgfxTextureView(texture);
    }

    /**
     * Create a texture view with specific mipmap levels using native functionality
     */
    public static BgfxTextureView createTextureView(BgfxTexture texture, int baseLevel, int levelCount) {
        return new BgfxTextureView(texture, baseLevel, levelCount);
    }

    /**
     * Get BGFX renderer information
     */
    public static String getRendererInfo() {
        if (!Util.isInitialized()) {
            return "BGFX not initialized";
        }

        try {
            int rendererType = BGFX.bgfx_get_renderer_type();
            String rendererName = BGFX.bgfx_get_renderer_name(rendererType);
            return String.format("BGFX %s (Type: %d)", rendererName, rendererType);
        } catch (Exception e) {
            LOGGER.error("Failed to get BGFX renderer info", e);
            return "BGFX Unknown";
        }
    }

    /**
     * Get BGFX capabilities
     */
    public static BGFXCaps getCapabilities() {
        if (!Util.isInitialized()) {
            return null;
        }

        try {
            return BGFX.bgfx_get_caps();
        } catch (Exception e) {
            LOGGER.error("Failed to get BGFX capabilities", e);
            return null;
        }
    }

    /**
     * Check if a specific renderer type is supported
     */
    public static boolean isRendererSupported(int rendererType) {
        BGFXCaps caps = getCapabilities();
        if (caps == null) {
            return false;
        }

        try {
            // BGFXCaps.supported() is a long bitmap indicating supported renderers
            return (caps.supported() & (1L << rendererType)) != 0;
        } catch (Exception e) {
            LOGGER.error("Failed to check renderer support", e);
            return false;
        }
    }

    /**
     * Check if BGFX supports DirectX 11
     */
    public static boolean supportsDirectX11() {
        return isRendererSupported(BGFX.BGFX_RENDERER_TYPE_DIRECT3D11);
    }

    /**
     * Check if BGFX supports OpenGL
     */
    public static boolean supportsOpenGL() {
        return isRendererSupported(BGFX.BGFX_RENDERER_TYPE_OPENGL);
    }

    /**
     * Get memory usage statistics
     */
    public static String getMemoryStats() {
        if (!Util.isInitialized()) {
            return "BGFX not initialized";
        }

        try {
            BGFXStats stats = BGFX.bgfx_get_stats();
            if (stats != null) {
                return String.format(
                    "GPU Memory: %d/%d MB, Textures: %d, Buffers: %d",
                    stats.gpuMemoryUsed() / (1024 * 1024),
                    stats.gpuMemoryMax() / (1024 * 1024),
                    stats.numTextures(),
                    stats.numVertexBuffers() + stats.numIndexBuffers()
                );
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get memory stats", e);
        }

        return "Memory stats unavailable";
    }

    /**
     * Validate BGFX handle
     */
    public static boolean isValidHandle(short handle) {
        return Util.isValidHandle(handle);
    }

    /**
     * Check if BGFX is initialized
     */
    public static boolean isInitialized() {
        return Util.isInitialized();
    }

    /**
     * Get current BGFX renderer type
     */
    public static int getRendererType() {
        return Util.getRendererType();
    }

    /**
     * Log BGFX status information
     */
    public static void logStatus() {
        LOGGER.info("=== BGFX Status ===");
        LOGGER.info("Initialized: {}", isInitialized());
        LOGGER.info("Renderer: {}", getRendererInfo());
        LOGGER.info("Memory: {}", getMemoryStats());
        LOGGER.info("==================");
    }
}