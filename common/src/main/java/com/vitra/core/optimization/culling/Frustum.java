package com.vitra.core.optimization.culling;

/**
 * View frustum implementation for culling calculations
 * Represents the 6 planes of the camera's viewing frustum
 */
public class Frustum {
    // Frustum planes: left, right, top, bottom, near, far
    private final Plane[] planes = new Plane[6];

    public Frustum() {
        for (int i = 0; i < 6; i++) {
            planes[i] = new Plane();
        }
    }

    /**
     * Update the frustum planes from view and projection matrices
     * @param viewMatrix 4x4 view matrix (column-major)
     * @param projectionMatrix 4x4 projection matrix (column-major)
     */
    public void update(float[] viewMatrix, float[] projectionMatrix) {
        // Multiply view and projection matrices to get combined matrix
        float[] combined = multiplyMatrices(projectionMatrix, viewMatrix);

        // Extract frustum planes from combined matrix
        extractPlanes(combined);
    }

    /**
     * Test if a bounding box intersects with the frustum
     * @param bounds Bounding box to test
     * @return true if the box is inside or intersects the frustum
     */
    public boolean intersects(BoundingBox bounds) {
        // Test against all 6 planes
        for (Plane plane : planes) {
            if (plane.distanceTo(bounds.getClosestPoint(plane)) < 0) {
                return false; // Outside this plane, so outside frustum
            }
        }
        return true; // Inside all planes
    }

    /**
     * Test if a point is inside the frustum
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the point is inside the frustum
     */
    public boolean contains(float x, float y, float z) {
        for (Plane plane : planes) {
            if (plane.distanceTo(x, y, z) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Multiply two 4x4 matrices
     */
    private float[] multiplyMatrices(float[] a, float[] b) {
        float[] result = new float[16];

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                result[i * 4 + j] = 0;
                for (int k = 0; k < 4; k++) {
                    result[i * 4 + j] += a[i * 4 + k] * b[k * 4 + j];
                }
            }
        }

        return result;
    }

    /**
     * Extract frustum planes from the combined view-projection matrix
     */
    private void extractPlanes(float[] matrix) {
        // Left plane
        planes[0].set(
            matrix[3] + matrix[0],
            matrix[7] + matrix[4],
            matrix[11] + matrix[8],
            matrix[15] + matrix[12]
        );

        // Right plane
        planes[1].set(
            matrix[3] - matrix[0],
            matrix[7] - matrix[4],
            matrix[11] - matrix[8],
            matrix[15] - matrix[12]
        );

        // Bottom plane
        planes[2].set(
            matrix[3] + matrix[1],
            matrix[7] + matrix[5],
            matrix[11] + matrix[9],
            matrix[15] + matrix[13]
        );

        // Top plane
        planes[3].set(
            matrix[3] - matrix[1],
            matrix[7] - matrix[5],
            matrix[11] - matrix[9],
            matrix[15] - matrix[13]
        );

        // Near plane
        planes[4].set(
            matrix[3] + matrix[2],
            matrix[7] + matrix[6],
            matrix[11] + matrix[10],
            matrix[15] + matrix[14]
        );

        // Far plane
        planes[5].set(
            matrix[3] - matrix[2],
            matrix[7] - matrix[6],
            matrix[11] - matrix[10],
            matrix[15] - matrix[14]
        );

        // Normalize all planes
        for (Plane plane : planes) {
            plane.normalize();
        }
    }

    /**
     * Represents a plane in 3D space using the equation ax + by + cz + d = 0
     */
    public static class Plane {
        public float a, b, c, d; // Plane equation coefficients

        public Plane() {
            this(0, 0, 0, 0);
        }

        public Plane(float a, float b, float c, float d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        public void set(float a, float b, float c, float d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        /**
         * Normalize the plane equation
         */
        public void normalize() {
            float length = (float) Math.sqrt(a * a + b * b + c * c);
            if (length > 0) {
                a /= length;
                b /= length;
                c /= length;
                d /= length;
            }
        }

        /**
         * Calculate distance from this plane to a point
         * @param x Point X coordinate
         * @param y Point Y coordinate
         * @param z Point Z coordinate
         * @return Signed distance (positive = in front of plane)
         */
        public float distanceTo(float x, float y, float z) {
            return a * x + b * y + c * z + d;
        }

        /**
         * Calculate distance from this plane to a point
         */
        public float distanceTo(Vector3 point) {
            return distanceTo(point.x, point.y, point.z);
        }

        /**
         * Get the normal vector of this plane
         */
        public Vector3 getNormal() {
            return new Vector3(a, b, c);
        }
    }
}