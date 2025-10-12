package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simplified BGFX debug utilities using BGFX native functionality directly
 * Replaces the complex DebugTools wrapper class
 */
public class BgfxDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxDebug");

    public static final int DEBUG_NONE = DebugOverlay.FLAG_NONE;
    public static final int DEBUG_TEXT = DebugOverlay.FLAG_TEXT;
    public static final int DEBUG_STATS = DebugOverlay.FLAG_STATS;
    public static final int DEBUG_PROFILER = DebugOverlay.FLAG_PROFILER;
    public static final int DEBUG_WIREFRAME = DebugOverlay.FLAG_WIREFRAME;

    public static final int DEBUG_BASIC = DEBUG_TEXT;
    public static final int DEBUG_PERFORMANCE = DEBUG_TEXT | DEBUG_STATS;
    public static final int DEBUG_FULL = DebugOverlay.DEFAULT_FLAGS;
    public static final int DEBUG_DEVELOPER = DEBUG_TEXT | DEBUG_STATS | DEBUG_PROFILER | DEBUG_WIREFRAME;

    /**
     * Set debug mode using BGFX's native functionality
     */
    public static void setDebugMode(int flags) {
        if (flags == DEBUG_NONE) {
            DebugOverlay.disable();
        } else {
            DebugOverlay.enable(flags);
        }
    }

    /**
     * Get current debug flags
     */
    public static int getCurrentDebugFlags() {
        return DebugOverlay.getFlags();
    }

    /**
     * Enable specific debug feature
     */
    public static void enableDebugFeature(int feature) {
        DebugOverlay.enableFeature(feature);
    }

    /**
     * Disable specific debug feature
     */
    public static void disableDebugFeature(int feature) {
        DebugOverlay.disableFeature(feature);
    }

    /**
     * Enable wireframe rendering
     */
    public static void enableWireframe() {
        DebugOverlay.enableFeature(DEBUG_WIREFRAME);
        LOGGER.info("BGFX wireframe mode enabled");
    }

    /**
     * Disable wireframe rendering
     */
    public static void disableWireframe() {
        DebugOverlay.disableFeature(DEBUG_WIREFRAME);
        LOGGER.info("BGFX wireframe mode disabled");
    }

    /**
     * Enable performance statistics
     */
    public static void enableStats() {
        DebugOverlay.enableFeature(DEBUG_STATS);
        LOGGER.info("BGFX performance statistics enabled");
    }

    /**
     * Disable performance statistics
     */
    public static void disableStats() {
        DebugOverlay.disableFeature(DEBUG_STATS);
        LOGGER.info("BGFX performance statistics disabled");
    }

    /**
     * Enable text overlay
     */
    public static void enableTextOverlay() {
        DebugOverlay.enableFeature(DEBUG_TEXT);
        LOGGER.info("BGFX text overlay enabled");
    }

    /**
     * Disable text overlay
     */
    public static void disableTextOverlay() {
        DebugOverlay.disableFeature(DEBUG_TEXT);
        LOGGER.info("BGFX text overlay disabled");
    }

    /**
     * Add debug text on screen using BGFX native functionality
     */
    public static void addDebugText(int x, int y, String text) {
        DebugOverlay.print(x, y, 0x0F, text);
    }

    /**
     * Set debug marker for performance profiling using BGFX native functionality
     */
    public static void setDebugMarker(String name) {
        if (!Util.isInitialized()) {
            return;
        }

        try {
            BGFX.bgfx_set_marker(name);
        } catch (Exception e) {
            LOGGER.warn("Failed to set debug marker: {}", e.getMessage());
        }
    }

    /**
     * Check if wireframe is enabled
     */
    public static boolean isWireframeEnabled() {
        return (DebugOverlay.getFlags() & DEBUG_WIREFRAME) != 0;
    }

    /**
     * Check if stats are enabled
     */
    public static boolean isStatsEnabled() {
        return (DebugOverlay.getFlags() & DEBUG_STATS) != 0;
    }

    /**
     * Check if text overlay is enabled
     */
    public static boolean isTextOverlayEnabled() {
        return (DebugOverlay.getFlags() & DEBUG_TEXT) != 0;
    }

    /**
     * Log current debug state
     */
    public static void logDebugState() {
        StringBuilder sb = new StringBuilder("BGFX Debug State: ");
        int flags = DebugOverlay.getFlags();

        if (flags == DEBUG_NONE) {
            sb.append("NONE");
        } else {
            if ((flags & DEBUG_TEXT) != 0) sb.append("TEXT ");
            if ((flags & DEBUG_STATS) != 0) sb.append("STATS ");
            if ((flags & DEBUG_PROFILER) != 0) sb.append("PROFILER ");
            if ((flags & DEBUG_WIREFRAME) != 0) sb.append("WIREFRAME ");
        }

        sb.append("(0x").append(Integer.toHexString(flags)).append(")");
        LOGGER.info(sb.toString());
    }
}
