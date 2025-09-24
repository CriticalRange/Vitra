package com.vitra;

import com.vitra.core.VitraCore;
import com.vitra.render.VitraRenderer;
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
    private static VitraRenderer renderer;
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
            // Initialize core optimization systems
            core = new VitraCore();
            core.initialize();

            // Initialize rendering system
            renderer = new VitraRenderer();
            renderer.initialize();

            initialized = true;
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

        if (renderer != null) {
            renderer.shutdown();
        }

        if (core != null) {
            core.shutdown();
        }

        initialized = false;
        LOGGER.info("Vitra shutdown complete");
    }

    public static VitraCore getCore() {
        return core;
    }

    public static VitraRenderer getRenderer() {
        return renderer;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
