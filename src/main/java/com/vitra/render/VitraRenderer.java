package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.core.VitraCore;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX DirectX 12 renderer for Vitra
 * BGFX handles all rendering operations directly
 */
public class VitraRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraRenderer.class);

    private boolean initialized = false;
    private long windowHandle = 0L;
    private VitraConfig config = null;
    private VitraCore core = null;

    public void initialize() {
        initialize(RendererType.DIRECTX11);
    }

    public void initialize(RendererType rendererType) {
        if (initialized) {
            LOGGER.warn("VitraRenderer already initialized");
            return;
        }

        LOGGER.info("Preparing Vitra BGFX DirectX 12 renderer (deferred initialization)");

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException("DirectX 12 is not supported on this platform. Vitra requires Windows 10+.");
            }

            initialized = true;
            LOGGER.info("Vitra renderer prepared, BGFX will initialize when window handle is available");

        } catch (Exception e) {
            LOGGER.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed", e);
        }
    }

    public void setConfig(VitraConfig config) {
        this.config = config;
    }

    public void setCore(VitraCore core) {
        this.core = core;
    }

    public boolean initializeWithWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        LOGGER.info("BGFX window handle set: 0x{}", Long.toHexString(windowHandle));

        // Actually initialize BGFX with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug and verbose mode from config, default to false if config not set
                boolean debugMode = (config != null) ? config.isDebugMode() : false;
                boolean verboseMode = (config != null) ? config.isVerboseLogging() : false;

                LOGGER.info("Initializing BGFX with debug={}, verbose={}", debugMode, verboseMode);
                if (verboseMode) {
                    LOGGER.warn("╔════════════════════════════════════════════════════════════╗");
                    LOGGER.warn("║  VERBOSE LOGGING ENABLED - BGFX will log EVERYTHING       ║");
                    LOGGER.warn("║  This may impact performance and generate huge log files  ║");
                    LOGGER.warn("║  Disable in config/vitra.properties after debugging       ║");
                    LOGGER.warn("╚════════════════════════════════════════════════════════════╝");
                }

                boolean success = com.vitra.render.bgfx.Util.initialize(windowHandle, 1920, 1080, debugMode, verboseMode);
                if (success) {
                    LOGGER.info("BGFX DirectX 12 initialized successfully (debug={}, verbose={})", debugMode, verboseMode);

                    // Load shaders after BGFX initialization
                    if (core != null) {
                        LOGGER.info("Loading BGFX shaders...");
                        core.loadShaders();
                    } else {
                        LOGGER.warn("VitraCore not set, skipping shader loading");
                    }

                    return true;
                } else {
                    LOGGER.error("BGFX DirectX 12 initialization failed");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Exception during BGFX initialization", e);
                return false;
            }
        }
        return false;
    }

    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra BGFX renderer...");
        initialized = false;
        windowHandle = 0L;
        LOGGER.info("Vitra renderer shutdown complete");
    }

    public boolean isInitialized() {
        return initialized && windowHandle != 0L;
    }

    public void beginFrame() {
        // BGFX frame operations handled elsewhere
    }

    public void endFrame() {
        // DirectX debug overlay is handled by BGFX internally via bgfx_set_debug()
        // No manual recording needed
    }

    public void resize(int width, int height) {
        if (initialized) {
            BGFX.bgfx_reset(width, height, BGFX.BGFX_RESET_VSYNC, BGFX.BGFX_TEXTURE_FORMAT_COUNT);
            // Update the view rectangle to match the new window size
            BGFX.bgfx_set_view_rect(0, 0, 0, width, height);
            LOGGER.info("BGFX view resized to {}x{}", width, height);
        }
    }

    public void clear(float r, float g, float b, float a) {
        // BGFX clear operations handled by render passes
    }
}
