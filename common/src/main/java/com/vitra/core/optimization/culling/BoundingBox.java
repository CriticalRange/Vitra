package com.vitra.core.optimization.culling;

/**
 * Axis-aligned bounding box for spatial calculations
 */
public class BoundingBox {
    public final float minX, minY, minZ;
    public final float maxX, maxY, maxZ;

    public BoundingBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /**
     * Get the center point of the bounding box
     */
    public Vector3 getCenter() {
        return new Vector3(
            (minX + maxX) * 0.5f,
            (minY + maxY) * 0.5f,
            (minZ + maxZ) * 0.5f
        );
    }

    /**
     * Get the size (extents) of the bounding box
     */
    public Vector3 getSize() {
        return new Vector3(
            maxX - minX,
            maxY - minY,
            maxZ - minZ
        );
    }

    /**
     * Get the closest point on the bounding box to a plane
     */
    public Vector3 getClosestPoint(Frustum.Plane plane) {
        Vector3 normal = plane.getNormal();
        return new Vector3(
            normal.x > 0 ? maxX : minX,
            normal.y > 0 ? maxY : minY,
            normal.z > 0 ? maxZ : minZ
        );
    }

    /**
     * Test if this bounding box intersects with another
     */
    public boolean intersects(BoundingBox other) {
        return !(maxX < other.minX || minX > other.maxX ||
                maxY < other.minY || minY > other.maxY ||
                maxZ < other.minZ || minZ > other.maxZ);
    }

    /**
     * Test if this bounding box contains a point
     */
    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX &&
               y >= minY && y <= maxY &&
               z >= minZ && z <= maxZ;
    }

    /**
     * Test if this bounding box contains another bounding box
     */
    public boolean contains(BoundingBox other) {
        return minX <= other.minX && maxX >= other.maxX &&
               minY <= other.minY && maxY >= other.maxY &&
               minZ <= other.minZ && maxZ >= other.maxZ;
    }

    @Override
    public String toString() {
        return String.format("BoundingBox[min=(%.2f,%.2f,%.2f), max=(%.2f,%.2f,%.2f)]",
                           minX, minY, minZ, maxX, maxY, maxZ);
    }
}