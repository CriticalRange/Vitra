package com.vitra.render.bgfx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX debug utilities and error code decoder.
 *
 * NOTE: LWJGL BGFX callback system is complex and requires proper memory management.
 * For now, we rely on BGFX's built-in debug output to stderr via bgfx_set_debug().
 *
 * BGFX will output detailed error messages to stderr when:
 * 1. D3D11 debug layer is enabled (requires Windows Graphics Tools)
 * 2. BGFX_DEBUG flags are set via bgfx_set_debug()
 *
 * This approach is simpler and more reliable than custom callback implementations.
 */
public class BgfxDebugCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("BGFX-Debug");

    /**
     * Utility class - no instantiation needed
     */
    private BgfxDebugCallback() {}

    /**
     * Log debug configuration.
     */
    public static void logDebugConfig(boolean enableDebug, boolean verboseMode) {
        if (enableDebug || verboseMode) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  BGFX DEBUG OUTPUT CONFIGURATION                          ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ D3D11 Debug Layer: {}", enableDebug ? "ENABLED" : "DISABLED");
            LOGGER.info("║ Verbose Logging:   {}", verboseMode ? "ENABLED" : "DISABLED");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ BGFX errors will be output to stderr (console)            ║");
            LOGGER.info("║ Look for [BGFX] or DirectX error messages                 ║");
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");

            if (enableDebug) {
                LOGGER.warn("D3D11 debug layer enabled - requires Windows Graphics Tools!");
                LOGGER.warn("Install via: Settings -> Apps -> Optional Features -> Graphics Tools");
            }
        }
    }

    /**
     * Decode BGFX fatal error codes to human-readable messages.
     * These codes are from bgfx::Fatal::Enum in bgfx.h
     */
    public static String decodeFatalCode(int code) {
        switch (code) {
            case 0: return "BGFX_FATAL_DEBUG_CHECK: Debug check failed (assertion in BGFX code)";
            case 1: return "BGFX_FATAL_INVALID_SHADER: Shader compilation/creation failed";
            case 2: return "BGFX_FATAL_UNABLE_TO_INITIALIZE: BGFX init failed (DirectX runtime, driver, or window issue)";
            case 3: return "BGFX_FATAL_UNABLE_TO_CREATE_TEXTURE: Texture creation failed (format, size, or GPU memory)";
            case 4: return "BGFX_FATAL_DEVICE_LOST: GPU device lost (driver crash or TDR)";
            default: return "UNKNOWN_FATAL_CODE_" + code + " (check bgfx.h)";
        }
    }
}
