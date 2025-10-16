package com.vitra;

import com.vitra.core.VitraCore;
import com.vitra.debug.VitraMixinVerifier;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.JniUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Vitra mod entry point - handles initialization of the optimization and rendering system
 */
public final class VitraMod {
    public static final String MOD_ID = "vitra";
    public static final String MOD_NAME = "Vitra";
    public static final String VERSION = "1.0.0";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static VitraCore core;
    private static boolean initialized = false;

    /**
     * Initialize the Vitra mod - called from platform-specific entry points
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("Vitra already initialized, skipping...");
            return;
        }

        LOGGER.info("Initializing {} v{}", MOD_NAME, VERSION);

        try {
            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 1: Initialize JNI utilities EARLY to catch ALL errors from the start
            // ═══════════════════════════════════════════════════════════════════════════
            LOGGER.info("Initializing JNI utilities...");
            JniUtils.initializeErrorCallback();
            JniUtils.printSystemInfo();
            LOGGER.info("JNI utilities initialized");

            // Initialize core optimization systems (includes renderer)
            core = new VitraCore();
            core.initialize();

            initialized = true;
            LOGGER.info("Vitra initialization complete");

            // Run @Overwrite mixin verification after successful initialization
            LOGGER.info("Running @Overwrite mixin verification...");
            VitraMixinVerifier.verifyAllOverwriteMixins();

            LOGGER.info("Vitra initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra", e);
            throw new RuntimeException("Vitra initialization failed", e);
        }
    }

    /**
     * Shutdown the Vitra mod - called on game exit
     */
    public static void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra...");

        if (core != null) {
            core.shutdown();
        }

        // Clean up JNI utilities
        LOGGER.info("Cleaning up JNI utilities...");
        JniUtils.shutdown();

        initialized = false;
        LOGGER.info("Vitra shutdown complete");
    }

    public static VitraCore getCore() {
        return core;
    }

    public static IVitraRenderer getRenderer() {
        return core != null ? core.getRenderer() : null;
    }

    public static VitraRenderer getDirectX11Renderer() {
        IVitraRenderer renderer = getRenderer();
        return renderer != null ? renderer.getDirectX11Renderer() : null;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if the renderer is currently in a rendering state
     */
    public static boolean isRendering() {
        if (!initialized || core == null) {
            return false;
        }

        IVitraRenderer renderer = core.getRenderer();
        return renderer != null && renderer.isInitialized();
    }

    /**
     * Get singleton instance
     */
    public static VitraMod getInstance() {
        return instance;
    }

    private static final VitraMod instance = new VitraMod();

    /**
     * Get the current status of @Overwrite mixin application
     */
    public static String getMixinStatus() {
        return VitraMixinVerifier.getDetailedStatus();
    }

    /**
     * Check if @Overwrite mixins are working
     */
    public static boolean areMixinsWorking() {
        return VitraMixinVerifier.testGLInterception() &&
               VitraMixinVerifier.testDirectX11Renderer();
    }
}
