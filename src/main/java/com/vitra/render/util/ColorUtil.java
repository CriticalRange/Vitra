package com.vitra.render.util;

/**
 * Color utility functions for vertex data
 * Based on VulkanMod's approach
 */
public class ColorUtil {

  
    /**
     * Packs RGBA components into a single integer
     */
    public static int pack(float r, float g, float b, float a) {
        int ir = (int) (r * 255.0f) & 0xFF;
        int ig = (int) (g * 255.0f) & 0xFF;
        int ib = (int) (b * 255.0f) & 0xFF;
        int ia = (int) (a * 255.0f) & 0xFF;
        return (ia << 24) | (ib << 16) | (ig << 8) | ir;
    }

    /**
     * Unpacks red component from packed color
     */
    public static float unpackR(int color) {
        return ((color >> 0) & 0xFF) / 255.0f;
    }

    /**
     * Unpacks green component from packed color
     */
    public static float unpackG(int color) {
        return ((color >> 8) & 0xFF) / 255.0f;
    }

    /**
     * Unpacks blue component from packed color
     */
    public static float unpackB(int color) {
        return ((color >> 16) & 0xFF) / 255.0f;
    }

    /**
     * Unpacks alpha component from packed color
     */
    public static float unpackA(int color) {
        return ((color >> 24) & 0xFF) / 255.0f;
    }

    /**
     * Multiplies RGB components of a packed color by a factor
     */
    public static int multiplyRGB(int color, float factor) {
        float r = unpackR(color) * factor;
        float g = unpackG(color) * factor;
        float b = unpackB(color) * factor;
        float a = unpackA(color);
        return pack(r, g, b, a);
    }

    /**
     * Multiplies all components of a packed color by respective factors
     */
    public static int multiply(int color, float rf, float gf, float bf, float af) {
        float r = unpackR(color) * rf;
        float g = unpackG(color) * gf;
        float b = unpackB(color) * bf;
        float a = unpackA(color) * af;
        return pack(r, g, b, a);
    }
}