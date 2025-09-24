package com.vitra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for managing window handles and platform-specific data
 */
public class WindowUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowUtil.class);

    private static long minecraftWindowHandle = 0L;

    /**
     * Set the Minecraft window handle for use with BGFX
     */
    public static void setMinecraftWindowHandle(long handle) {
        minecraftWindowHandle = handle;
        LOGGER.info("Minecraft window handle stored: 0x{}", Long.toHexString(handle));
    }

    /**
     * Get the stored Minecraft window handle
     */
    public static long getMinecraftWindowHandle() {
        return minecraftWindowHandle;
    }

    /**
     * Check if we have a valid window handle
     */
    public static boolean hasValidWindowHandle() {
        return minecraftWindowHandle != 0L;
    }
}