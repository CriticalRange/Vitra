package com.vitra.render.chunk.frustum;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector3f;

/**
 * DirectX Frustum Culling Implementation
 * Provides efficient frustum culling for chunk rendering
 */
public class VFrustum {

    // Frustum planes: left, right, top, bottom, near, far
    private final Vector4f[] frustumPlanes = new Vector4f[6];
    private double camX, camY, camZ;

    public VFrustum() {
        for (int i = 0; i < 6; i++) {
            frustumPlanes[i] = new Vector4f();
        }
    }

    /**
     * Calculate frustum planes from view-projection matrix
     * Uses DirectX coordinate system (left-handed)
     */
    public void calculateFrustum(Matrix4f modelView, Matrix4f projection) {
        // Combine matrices to get view-projection matrix
        Matrix4f viewProjection = new Matrix4f(projection).mul(modelView);

        // Extract frustum planes from the view-projection matrix
        // Left plane: row4 + row1
        frustumPlanes[0].set(
            viewProjection.m30() + viewProjection.m00(),
            viewProjection.m31() + viewProjection.m01(),
            viewProjection.m32() + viewProjection.m02(),
            viewProjection.m33() + viewProjection.m03()
        );

        // Right plane: row4 - row1
        frustumPlanes[1].set(
            viewProjection.m30() - viewProjection.m00(),
            viewProjection.m31() - viewProjection.m01(),
            viewProjection.m32() - viewProjection.m02(),
            viewProjection.m33() - viewProjection.m03()
        );

        // Bottom plane: row4 + row2
        frustumPlanes[2].set(
            viewProjection.m30() + viewProjection.m10(),
            viewProjection.m31() + viewProjection.m11(),
            viewProjection.m32() + viewProjection.m12(),
            viewProjection.m33() + viewProjection.m13()
        );

        // Top plane: row4 - row2
        frustumPlanes[3].set(
            viewProjection.m30() - viewProjection.m10(),
            viewProjection.m31() - viewProjection.m11(),
            viewProjection.m32() - viewProjection.m12(),
            viewProjection.m33() - viewProjection.m13()
        );

        // Near plane: row3
        frustumPlanes[4].set(
            viewProjection.m20(),
            viewProjection.m21(),
            viewProjection.m22(),
            viewProjection.m23()
        );

        // Far plane: row4 - row3
        frustumPlanes[5].set(
            viewProjection.m30() - viewProjection.m20(),
            viewProjection.m31() - viewProjection.m21(),
            viewProjection.m32() - viewProjection.m22(),
            viewProjection.m33() - viewProjection.m23()
        );

        // Normalize all planes
        for (int i = 0; i < 6; i++) {
            normalizePlane(frustumPlanes[i]);
        }
    }

    /**
     * Normalize a plane equation
     */
    private void normalizePlane(Vector4f plane) {
        float length = (float) Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
        if (length > 0.0f) {
            plane.x /= length;
            plane.y /= length;
            plane.z /= length;
            plane.w /= length;
        }
    }

    /**
     * Set camera offset for world space calculations
     */
    public void setCamOffset(double camX, double camY, double camZ) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    /**
     * Check if a point is inside the frustum
     */
    public boolean isPointInFrustum(float x, float y, float z) {
        for (int i = 0; i < 6; i++) {
            Vector4f plane = frustumPlanes[i];
            if (plane.x * x + plane.y * y + plane.z * z + plane.w <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a bounding box is inside the frustum
     */
    public boolean isBoxInFrustum(float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
        // Check all 8 corners of the bounding box
        float[][] corners = {
            {minX, minY, minZ}, {maxX, minY, minZ},
            {minX, maxY, minZ}, {maxX, maxY, minZ},
            {minX, minY, maxZ}, {maxX, minY, maxZ},
            {minX, maxY, maxZ}, {maxX, maxY, maxZ}
        };

        for (int i = 0; i < 6; i++) {
            Vector4f plane = frustumPlanes[i];
            boolean inside = false;

            // Check if any corner is inside this plane
            for (float[] corner : corners) {
                if (plane.x * corner[0] + plane.y * corner[1] + plane.z * corner[2] + plane.w > 0) {
                    inside = true;
                    break;
                }
            }

            if (!inside) {
                return false; // Box is completely outside this plane
            }
        }

        return true; // Box is inside or intersects all planes
    }

    /**
     * Check if a chunk section is inside the frustum
     */
    public boolean isChunkSectionInFrustum(int chunkX, int chunkY, int chunkZ) {
        // Chunk sections are 16x16x16 blocks
        float minX = (float)(chunkX * 16 - camX);
        float maxX = minX + 16;
        float minY = (float)(chunkY * 16 - camY);
        float maxY = minY + 16;
        float minZ = (float)(chunkZ * 16 - camZ);
        float maxZ = minZ + 16;

        return isBoxInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }
}