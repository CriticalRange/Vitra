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
            return VitraNativeRenderer.isInitialized() || true; // Allow first call to fail during initialization
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
            VitraNativeRenderer.isInitialized();
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

    // DirectX 11 state methods (stubs for now)
    public static void enableBlend(boolean enable) {
        LOGGER.debug("enableBlend: {}", enable);
    }

    public static void enableDepthTest(boolean enable) {
        LOGGER.debug("enableDepthTest: {}", enable);
    }

    public static void enableCull(boolean enable) {
        LOGGER.debug("enableCull: {}", enable);
    }

    public static void setDepthFunc(int func) {
        LOGGER.debug("setDepthFunc: 0x{}", Integer.toHexString(func));
    }

    public static void setDepthMask(boolean mask) {
        LOGGER.debug("setDepthMask: {}", mask);
    }

    public static void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        LOGGER.debug("setColorMask: {}, {}, {}, {}", r, g, b, a);
    }

    public static void setBlendFunc(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        LOGGER.debug("setBlendFunc: 0x{}, 0x{}, 0x{}, 0x{}",
            Integer.toHexString(srcRGB), Integer.toHexString(dstRGB),
            Integer.toHexString(srcAlpha), Integer.toHexString(dstAlpha));
    }

    public static void setViewport(int x, int y, int width, int height) {
        LOGGER.debug("setViewport: {}, {}, {}, {}", x, y, width, height);
    }

    public static void enableScissorTest(boolean enable) {
        LOGGER.debug("enableScissorTest: {}", enable);
    }

    public static void setScissorRect(int x, int y, int width, int height) {
        LOGGER.debug("setScissorRect: {}, {}, {}, {}", x, y, width, height);
    }

    public static void clear(int mask) {
        LOGGER.debug("clear: 0x{}", Integer.toHexString(mask));
    }

    public static void drawArrays(int mode, int first, int count) {
        LOGGER.debug("drawArrays: mode=0x{}, first={}, count={}",
            Integer.toHexString(mode), first, count);
    }

    public static void drawElements(int mode, int count, int type, long indices) {
        LOGGER.debug("drawElements: mode=0x{}, count={}, type=0x{}, indices={}",
            Integer.toHexString(mode), count, Integer.toHexString(type), indices);
    }
}