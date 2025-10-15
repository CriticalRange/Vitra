package com.vitra.render.vertex.format;

/**
 * Utility class for packing signed normalized integer values
 * Based on VulkanMod's approach
 */
public class I32_SNorm {

    /**
     * Packs normal vector components into a single 32-bit signed normalized integer
     */
    public static int packNormal(float x, float y, float z) {
        // Convert float [-1, 1] to signed normalized int [-127, 127]
        int ix = Math.round(x * 127.0f);
        int iy = Math.round(y * 127.0f);
        int iz = Math.round(z * 127.0f);

        // Clamp to valid range
        ix = Math.max(-127, Math.min(127, ix));
        iy = Math.max(-127, Math.min(127, iy));
        iz = Math.max(-127, Math.min(127, iz));

        // Pack into 32-bit integer (8 bits per component, with 8 bits unused)
        return (iz & 0xFF) << 16 | (iy & 0xFF) << 8 | (ix & 0xFF);
    }

    /**
     * Unpacks X component from packed normal
     */
    public static float unpackX(int packed) {
        int x = (packed & 0xFF);
        if (x >= 128) x -= 256; // Convert to signed
        return x / 127.0f;
    }

    /**
     * Unpacks Y component from packed normal
     */
    public static float unpackY(int packed) {
        int y = ((packed >> 8) & 0xFF);
        if (y >= 128) y -= 256; // Convert to signed
        return y / 127.0f;
    }

    /**
     * Unpacks Z component from packed normal
     */
    public static float unpackZ(int packed) {
        int z = ((packed >> 16) & 0xFF);
        if (z >= 128) z -= 256; // Convert to signed
        return z / 127.0f;
    }
}