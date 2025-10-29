package com.vitra.render.util;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Math utility functions for vertex transformation
 * Based on VulkanMod's approach but adapted for DirectX
 */
public class MathUtil {

    /**
     * Transforms X coordinate by matrix
     */
    public static float transformX(Matrix4f matrix, float x, float y, float z) {
        return matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
    }

    /**
     * Transforms Y coordinate by matrix
     */
    public static float transformY(Matrix4f matrix, float x, float y, float z) {
        return matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
    }

    /**
     * Transforms Z coordinate by matrix
     */
    public static float transformZ(Matrix4f matrix, float x, float y, float z) {
        return matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
    }

    /**
     * Packs transformed normal for vertex data
     */
    public static int packTransformedNorm(Matrix3f matrix, boolean trustedNormals, int x, int y, int z) {
        if (trustedNormals) {
            // Use the normal directly if trusted
            return packNormal(x, y, z);
        } else {
            // Transform the normal by the matrix
            float nx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z;
            float ny = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z;
            float nz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z;

            // Normalize and pack
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) {
                nx /= length;
                ny /= length;
                nz /= length;
            }

            return packNormal(nx, ny, nz);
        }
    }

    /**
     * Packs normal values into a single integer
     */
    public static int packNormal(float x, float y, float z) {
        int ix = (int) (x * 127.0f) & 0xFF;
        int iy = (int) (y * 127.0f) & 0xFF;
        int iz = (int) (z * 127.0f) & 0xFF;
        return (iz << 16) | (iy << 8) | ix;
    }

    /**
     * Packs normal values from integer components
     */
    public static int packNormal(int x, int y, int z) {
        return (z << 16) | (y << 8) | x;
    }
}