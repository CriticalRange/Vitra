package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Thin BGFX debug helper that toggles debug flags and renders an on-screen stats overlay.
 * <p>
 * All calls route through LWJGL's {@link BGFX} binding; no native helpers are used.
 */
public final class DebugOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DebugOverlay.class);
    private static final Locale LOCALE = Locale.ROOT;

    public static final int FLAG_NONE = 0;
    public static final int FLAG_TEXT = BGFX.BGFX_DEBUG_TEXT;
    public static final int FLAG_STATS = BGFX.BGFX_DEBUG_STATS;
    public static final int FLAG_PROFILER = BGFX.BGFX_DEBUG_PROFILER;
    public static final int FLAG_WIREFRAME = BGFX.BGFX_DEBUG_WIREFRAME;

    public static final int DEFAULT_FLAGS = FLAG_TEXT | FLAG_STATS | FLAG_PROFILER;

    private static volatile int activeFlags = FLAG_NONE;
    private static volatile int pendingFlags = FLAG_NONE;
    private static volatile boolean overlayEnabled = false;

    private static long lastFrameNano = 0L;
    private static long frameCounter = 0L;
    private static float smoothedFps = 0f;

    private DebugOverlay() {
    }

    /**
     * Enable the overlay with {@link #DEFAULT_FLAGS}.
     */
    public static void enable() {
        enable(DEFAULT_FLAGS);
    }

    /**
     * Enable the overlay with explicit debug flags.
     */
    public static void enable(int flags) {
        setFlags(flags);
    }

    /**
     * Disable all BGFX debug output and clear the overlay text buffer.
     */
    public static void disable() {
        setFlags(FLAG_NONE);
        if (Util.isInitialized()) {
            safeClearText();
        }
    }

    /**
     * Update the active debug flags.
     */
    public static void setFlags(int flags) {
        pendingFlags = flags;
        activeFlags = flags;
        overlayEnabled = flags != FLAG_NONE;

        if (overlayEnabled && (flags & FLAG_TEXT) != 0) {
            resetCounters();
        }

        if (!pushFlags()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Queued BGFX debug flags 0x{} (renderer not initialised yet)",
                    Integer.toHexString(flags));
            }
            return;
        }

        if ((flags & FLAG_TEXT) != 0) {
            safeClearText();
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("BGFX debug flags set to 0x{}", Integer.toHexString(flags));
        }
    }

    /**
     * Convenience helper for OR-ing a debug feature bit.
     */
    public static void enableFeature(int feature) {
        setFlags(activeFlags | feature);
    }

    /**
     * Convenience helper for clearing a debug feature bit.
     */
    public static void disableFeature(int feature) {
        setFlags(activeFlags & ~feature);
    }

    public static int getFlags() {
        return activeFlags;
    }

    public static boolean isEnabled() {
        return overlayEnabled && activeFlags != FLAG_NONE;
    }

    /**
     * Apply any queued flags once BGFX is initialised.
     * Useful when the overlay was toggled before bgfx_init() completed.
     */
    public static void reapply() {
        if (pendingFlags == activeFlags && pendingFlags == FLAG_NONE) {
            return;
        }

        if (pushFlags() && (activeFlags & FLAG_TEXT) != 0) {
            safeClearText();
        }
    }

    /**
     * Call once per frame to refresh the debug overlay with frame counters and BGFX stats.
     */
    public static void recordFrame() {
        if (!isEnabled() || (activeFlags & FLAG_TEXT) == 0) {
            return;
        }

        if (!Util.isInitialized()) {
            return;
        }

        long now = System.nanoTime();
        if (lastFrameNano != 0L) {
            float deltaSeconds = (now - lastFrameNano) / 1_000_000_000f;
            if (deltaSeconds > 0f) {
                float instantaneous = 1f / deltaSeconds;
                smoothedFps = smoothedFps == 0f
                    ? instantaneous
                    : (instantaneous * 0.25f) + (smoothedFps * 0.75f);
            }
        }

        lastFrameNano = now;
        frameCounter++;

        safeClearText();

        BGFX.bgfx_dbg_text_printf(0, 0, 0x1f, "Vitra Debug Overlay");
        BGFX.bgfx_dbg_text_printf(0, 2, 0x0f,
            String.format(LOCALE, "Frame: %,d", frameCounter));

        if (smoothedFps > 0f) {
            BGFX.bgfx_dbg_text_printf(0, 3, 0x0f,
                String.format(LOCALE, "FPS  : %.1f", smoothedFps));
        }

        BGFXStats stats = BGFX.bgfx_get_stats();
        if (stats != null) {
            double cpuMs = computeMilliseconds(stats.cpuTimeBegin(), stats.cpuTimeEnd(), stats.cpuTimerFreq());
            double gpuMs = computeMilliseconds(stats.gpuTimeBegin(), stats.gpuTimeEnd(), stats.gpuTimerFreq());

            if (!Double.isNaN(cpuMs)) {
                BGFX.bgfx_dbg_text_printf(0, 5, 0x0f,
                    String.format(LOCALE, "CPU frame: %.2f ms", cpuMs));
            }

            if (!Double.isNaN(gpuMs)) {
                BGFX.bgfx_dbg_text_printf(0, 6, 0x0f,
                    String.format(LOCALE, "GPU frame: %.2f ms", gpuMs));
            }

            BGFX.bgfx_dbg_text_printf(0, 8, 0x07,
                String.format(LOCALE, "Draw calls: %,d", stats.numDraw()));
        }
    }

    /**
     * Print an arbitrary line of debug text without clearing the buffer.
     */
    public static void print(int x, int y, int attr, CharSequence text) {
        if (!Util.isInitialized()) {
            return;
        }

        BGFX.bgfx_dbg_text_printf(x, y, attr, text);
    }

    /**
     * Reset frame counters (called automatically when enabling the overlay).
     */
    public static void resetCounters() {
        lastFrameNano = 0L;
        frameCounter = 0L;
        smoothedFps = 0f;
    }

    private static boolean pushFlags() {
        if (!Util.isInitialized()) {
            return false;
        }

        try {
            BGFX.bgfx_set_debug(pendingFlags);
            activeFlags = pendingFlags;
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply BGFX debug flags", t);
            return false;
        }
    }

    private static void safeClearText() {
        try {
            BGFX.bgfx_dbg_text_clear((byte) 0, false);
        } catch (Throwable t) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failed to clear BGFX debug text buffer: {}", t.getMessage());
            }
        }
    }

    private static double computeMilliseconds(long begin, long end, long freq) {
        long delta = end - begin;
        if (freq <= 0L || delta <= 0L) {
            return Double.NaN;
        }
        return (delta / (double) freq) * 1000.0;
    }
}
