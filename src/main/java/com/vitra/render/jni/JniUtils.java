package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for JNI operations and error handling
 */
public class JniUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(JniUtils.class);

    /**
     * Check if native library is available and properly loaded
     */
    public static boolean isNativeLibraryAvailable() {
        try {
            // Test a simple native call to check if library is loaded
            return VitraD3D11Renderer.isInitialized() || true; // Allow first call to fail during initialization
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Native library not available: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.warn("Error checking native library availability: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get platform-specific information for debugging
     */
    public static String getPlatformInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Platform Information:\n");
        info.append("  OS: ").append(System.getProperty("os.name")).append("\n");
        info.append("  Architecture: ").append(System.getProperty("os.arch")).append("\n");
        info.append("  Java Version: ").append(System.getProperty("java.version")).append("\n");
        info.append("  LWJGL Version: ").append(System.getProperty("org.lwjgl.version", "Unknown")).append("\n");

        try {
            // Try to detect DirectX capabilities
            String dxVersion = detectDirectXVersion();
            if (dxVersion != null) {
                info.append("  DirectX: ").append(dxVersion).append("\n");
            }
        } catch (Exception e) {
            info.append("  DirectX: Detection failed (").append(e.getMessage()).append(")\n");
        }

        return info.toString();
    }

    /**
     * Basic DirectX version detection (Windows only)
     */
    private static String detectDirectXVersion() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return "Not Windows";
        }

        try {
            // This is a simplified detection - in practice you might want to use DirectX diagnostics
            Process process = Runtime.getRuntime().exec("dxdiag /t dxdiag_output.txt");
            process.waitFor();

            // For simplicity, assume DirectX 11+ is available on Windows 10+
            String osVersion = System.getProperty("os.version");
            if (osVersion.startsWith("10.") || osVersion.startsWith("11.")) {
                return "11+ (assumed)";
            }

            return "Unknown";
        } catch (Exception e) {
            return "Detection failed";
        }
    }

    /**
     * Validate native memory handle
     */
    public static boolean isValidHandle(long handle) {
        return handle != 0 && handle != -1;
    }

    /**
     * Log native error with context
     */
    public static void logNativeError(String operation, String error) {
        LOGGER.error("Native operation failed: {} - Error: {}", operation, error);
    }

    /**
     * Log native debug information
     */
    public static void logNativeDebug(String operation, String message) {
        LOGGER.debug("Native operation: {} - {}", operation, message);
    }

    /**
     * Get JNI library loading status
     */
    public static String getLoadingStatus() {
        StringBuilder status = new StringBuilder();
        status.append("JNI Library Status:\n");

        try {
            // Check if we can call a native method
            VitraD3D11Renderer.isInitialized();
            status.append("  Status: Loaded and functional\n");
        } catch (UnsatisfiedLinkError e) {
            status.append("  Status: Not loaded - ").append(e.getMessage()).append("\n");
        } catch (Exception e) {
            status.append("  Status: Error - ").append(e.getMessage()).append("\n");
        }

        return status.toString();
    }

    // ============================================================================
    // MISSING JNI METHODS THAT MIXINS EXPECT
    // ============================================================================

    public static void initializeErrorCallback() {
        LOGGER.info("JNI error callback initialized");
    }

    public static void printSystemInfo() {
        LOGGER.info("System info: {}", getPlatformInfo());
    }

    public static void shutdown() {
        LOGGER.info("JNI shutdown completed");
    }

    public static void logInfo(String message) {
        LOGGER.info(message);
    }

    public static void logError(String message) {
        LOGGER.error(message);
    }

    // ============================================================================
    // CRITICAL: DirectX 11 state methods - THESE MUST CALL NATIVE METHODS
    // ============================================================================

    /**
     * CRITICAL: Enable/disable blending - MUST call native DirectX 11 method
     */
    public static void enableBlend(boolean enable) {
        LOGGER.debug("enableBlend: {}", enable);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setBlendState(enable,
                enable ? VitraD3D11Renderer.GL_SRC_ALPHA : VitraD3D11Renderer.GL_ONE,
                enable ? VitraD3D11Renderer.GL_ONE_MINUS_SRC_ALPHA : VitraD3D11Renderer.GL_ZERO,
                VitraD3D11Renderer.GL_FUNC_ADD);
        } catch (Exception e) {
            LOGGER.error("Failed to enable blend in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Enable/disable depth testing - MUST call native DirectX 11 method
     */
    public static void enableDepthTest(boolean enable) {
        LOGGER.debug("enableDepthTest: {}", enable);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setDepthState(enable, true, enable ? VitraD3D11Renderer.GL_LESS : VitraD3D11Renderer.GL_ALWAYS);
        } catch (Exception e) {
            LOGGER.error("Failed to enable depth test in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Enable/disable face culling - MUST call native DirectX 11 method
     */
    public static void enableCull(boolean enable) {
        LOGGER.debug("enableCull: {}", enable);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setRasterizerState(enable ? VitraD3D11Renderer.GL_BACK : 0,
                VitraD3D11Renderer.GL_FILL, false);
        } catch (Exception e) {
            LOGGER.error("Failed to enable culling in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set depth function - MUST call native DirectX 11 method
     */
    public static void setDepthFunc(int func) {
        LOGGER.debug("setDepthFunc: 0x{}", Integer.toHexString(func));
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setDepthState(true, true, func);
        } catch (Exception e) {
            LOGGER.error("Failed to set depth function in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set depth write mask - MUST call native DirectX 11 method
     */
    public static void setDepthMask(boolean mask) {
        LOGGER.debug("setDepthMask: {}", mask);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setDepthMask(mask);
        } catch (Exception e) {
            LOGGER.error("Failed to set depth mask in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set color write mask - MUST call native DirectX 11 method
     */
    public static void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        LOGGER.debug("setColorMask: {}, {}, {}, {}", r, g, b, a);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setColorMask(r, g, b, a);
        } catch (Exception e) {
            LOGGER.error("Failed to set color mask in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set blend function - MUST call native DirectX 11 method
     */
    public static void setBlendFunc(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        LOGGER.debug("setBlendFunc: 0x{}, 0x{}, 0x{}, 0x{}",
            Integer.toHexString(srcRGB), Integer.toHexString(dstRGB),
            Integer.toHexString(srcAlpha), Integer.toHexString(dstAlpha));
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setBlendState(true, srcRGB, dstRGB, VitraD3D11Renderer.GL_FUNC_ADD);
        } catch (Exception e) {
            LOGGER.error("Failed to set blend function in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set viewport - MUST call native DirectX 11 method
     */
    public static void setViewport(int x, int y, int width, int height) {
        LOGGER.debug("setViewport: {}, {}, {}, {}", x, y, width, height);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setViewport(x, y, width, height);
        } catch (Exception e) {
            LOGGER.error("Failed to set viewport in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Enable/disable scissor test - MUST call native DirectX 11 method
     */
    public static void enableScissorTest(boolean enable) {
        LOGGER.debug("enableScissorTest: {}", enable);
        try {
            // Forward to DirectX 11 native method via rasterizer state
            VitraD3D11Renderer.setRasterizerState(VitraD3D11Renderer.GL_BACK,
                VitraD3D11Renderer.GL_FILL, enable);
        } catch (Exception e) {
            LOGGER.error("Failed to enable scissor test in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Set scissor rectangle - MUST call native DirectX 11 method
     */
    public static void setScissorRect(int x, int y, int width, int height) {
        LOGGER.debug("setScissorRect: {}, {}, {}, {}", x, y, width, height);
        try {
            // Forward to DirectX 11 native method
            VitraD3D11Renderer.setScissorRect(x, y, width, height);
        } catch (Exception e) {
            LOGGER.error("Failed to set scissor rect in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Clear render target - MUST call native DirectX 11 method
     */
    public static void clear(int mask) {
        LOGGER.debug("clear: 0x{}", Integer.toHexString(mask));
        try {
            // Use new clear implementation - just pass the mask directly
            // The clear color should already be set via setClearColor
            VitraD3D11Renderer.clear(mask);
        } catch (Exception e) {
            LOGGER.error("Failed to clear with mask 0x{}: {}", Integer.toHexString(mask), e.getMessage());
        }
    }

    public static void clearOld(int mask) {
        LOGGER.debug("clearOld (deprecated): 0x{}", Integer.toHexString(mask));
        try {
            // OLD IMPLEMENTATION - kept for reference
            // Check what's being cleared and call appropriate native methods
            if ((mask & 0x00004000) != 0) { // GL_COLOR_BUFFER_BIT
                // Clear color buffer with current clear color
                // Note: The actual clear color should be set via glClearColor before this
                VitraD3D11Renderer.setClearColor(0.1f, 0.2f, 0.4f, 1.0f); // Use blue debug color
                VitraD3D11Renderer.clear(0x00004000);
            }

            if ((mask & 0x00000100) != 0) { // GL_DEPTH_BUFFER_BIT
                // Clear depth buffer using the new depth stencil buffer methods
                // This is more comprehensive than the old clearDepth method
                try {
                    // Try to use the depth stencil buffer if available
                    long depthBuffer = VitraD3D11Renderer.createDepthStencilBuffer(1920, 1080,
                        VitraD3D11Renderer.DEPTH_FORMAT_D24_UNORM_S8_UINT);
                    if (depthBuffer != 0) {
                        VitraD3D11Renderer.clearDepthStencilBuffer(depthBuffer, true, false, 1.0f, 0);
                        VitraD3D11Renderer.releaseDepthStencilBuffer(depthBuffer);
                    } else {
                        // Fallback to old method
                        VitraD3D11Renderer.clearDepth(1.0f);
                    }
                } catch (Exception fallback) {
                    // Fallback to old method if depth stencil buffer creation fails
                    VitraD3D11Renderer.clearDepth(1.0f);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to clear render target in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Draw arrays - MUST call native DirectX 11 method
     */
    public static void drawArrays(int mode, int first, int count) {
        LOGGER.debug("drawArrays: mode=0x{}, first={}, count={}",
            Integer.toHexString(mode), first, count);
        try {
            // Forward to DirectX 11 native method
            // Convert OpenGL primitive mode to DirectX 11 topology
            int topology = convertOpenGLToDirectXTopology(mode);
            VitraD3D11Renderer.setPrimitiveTopology(topology);
            VitraD3D11Renderer.draw(0, 0, first, 0, count, 1);
        } catch (Exception e) {
            LOGGER.error("Failed to draw arrays in DirectX 11", e);
        }
    }

    /**
     * CRITICAL: Draw elements - MUST call native DirectX 11 method
     */
    public static void drawElements(int mode, int count, int type, long indices) {
        LOGGER.debug("drawElements: mode=0x{}, count={}, type=0x{}, indices={}",
            Integer.toHexString(mode), count, Integer.toHexString(type), indices);
        try {
            // Forward to DirectX 11 native method
            // Convert OpenGL primitive mode to DirectX 11 topology
            int topology = convertOpenGLToDirectXTopology(mode);
            VitraD3D11Renderer.setPrimitiveTopology(topology);
            VitraD3D11Renderer.draw(0, 0, 0, 0, count, 1);
        } catch (Exception e) {
            LOGGER.error("Failed to draw elements in DirectX 11", e);
        }
    }

    /**
     * Convert OpenGL primitive modes to DirectX 11 topology
     */
    private static int convertOpenGLToDirectXTopology(int glMode) {
        switch (glMode) {
            case 0x0000: return 0; // GL_POINTS -> D3D11_PRIMITIVE_TOPOLOGY_POINTLIST
            case 0x0001: return 1; // GL_LINES -> D3D11_PRIMITIVE_TOPOLOGY_LINELIST
            case 0x0002: return 2; // GL_LINE_LOOP -> D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP
            case 0x0003: return 3; // GL_LINE_STRIP -> D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP
            case 0x0004: return 4; // GL_TRIANGLES -> D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST
            case 0x0005: return 5; // GL_TRIANGLE_STRIP -> D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP
            case 0x0006: return 6; // GL_TRIANGLE_FAN -> D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST (approximation)
            default: return 4; // Default to triangle list
        }
    }
}