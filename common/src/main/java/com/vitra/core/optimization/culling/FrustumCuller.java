package com.vitra.core.optimization.culling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Frustum culling implementation for chunks and entities
 * Determines what objects are visible within the camera's view frustum
 */
public class FrustumCuller {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrustumCuller.class);

    private Frustum frustum;
    private int culledChunkCount = 0;
    private int culledEntityCount = 0;
    private boolean initialized = false;

    /**
     * Initialize the frustum culler
     */
    public void initialize() {
        if (initialized) {
            LOGGER.warn("FrustumCuller already initialized");
            return;
        }

        frustum = new Frustum();
        initialized = true;
        LOGGER.debug("FrustumCuller initialized");
    }

    /**
     * Shutdown the frustum culler
     */
    public void shutdown() {
        if (!initialized) return;

        initialized = false;
        LOGGER.debug("FrustumCuller shut down");
    }

    /**
     * Update the frustum with new view and projection matrices
     * @param viewMatrix 4x4 view matrix (column-major)
     * @param projectionMatrix 4x4 projection matrix (column-major)
     */
    public void updateFrustum(float[] viewMatrix, float[] projectionMatrix) {
        if (!initialized) return;
        frustum.update(viewMatrix, projectionMatrix);
    }

    /**
     * Test if a chunk is visible within the current frustum
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param chunkY Chunk Y coordinate (height)
     * @param chunkSize Size of the chunk (typically 16)
     * @return true if the chunk is visible
     */
    public boolean isChunkVisible(int chunkX, int chunkZ, int chunkY, int chunkSize) {
        if (!initialized) return true;

        // Convert chunk coordinates to world space
        float worldX = chunkX * chunkSize;
        float worldY = chunkY * chunkSize;
        float worldZ = chunkZ * chunkSize;

        // Create bounding box for the chunk
        BoundingBox chunkBounds = new BoundingBox(
            worldX, worldY, worldZ,
            worldX + chunkSize, worldY + chunkSize, worldZ + chunkSize
        );

        boolean visible = frustum.intersects(chunkBounds);
        if (!visible) {
            culledChunkCount++;
        }

        return visible;
    }

    /**
     * Test if an entity is visible within the current frustum
     * @param entityX Entity X position
     * @param entityY Entity Y position
     * @param entityZ Entity Z position
     * @param boundingBoxSize Approximate size of entity bounding box
     * @return true if the entity is visible
     */
    public boolean isEntityVisible(float entityX, float entityY, float entityZ, float boundingBoxSize) {
        if (!initialized) return true;

        float halfSize = boundingBoxSize * 0.5f;
        BoundingBox entityBounds = new BoundingBox(
            entityX - halfSize, entityY - halfSize, entityZ - halfSize,
            entityX + halfSize, entityY + halfSize, entityZ + halfSize
        );

        boolean visible = frustum.intersects(entityBounds);
        if (!visible) {
            culledEntityCount++;
        }

        return visible;
    }

    /**
     * Cull a list of chunk positions, returning only visible ones
     * @param chunks List of chunk positions to test
     * @param chunkSize Size of each chunk
     * @return List of visible chunk positions
     */
    public List<ChunkPosition> cullChunks(List<ChunkPosition> chunks, int chunkSize) {
        if (!initialized || chunks.isEmpty()) return chunks;

        List<ChunkPosition> visibleChunks = new ArrayList<>();
        int originalCount = culledChunkCount;

        for (ChunkPosition chunk : chunks) {
            if (isChunkVisible(chunk.x, chunk.z, chunk.y, chunkSize)) {
                visibleChunks.add(chunk);
            }
        }

        int newlyCulled = culledChunkCount - originalCount;
        if (newlyCulled > 0) {
            LOGGER.debug("Culled {} chunks, {} visible", newlyCulled, visibleChunks.size());
        }

        return visibleChunks;
    }

    /**
     * Reset culling counters (typically called each frame)
     */
    public void resetCounters() {
        culledChunkCount = 0;
        culledEntityCount = 0;
    }

    public int getCulledChunkCount() {
        return culledChunkCount;
    }

    public int getCulledEntityCount() {
        return culledEntityCount;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Simple chunk position holder
     */
    public static class ChunkPosition {
        public final int x, y, z;

        public ChunkPosition(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChunkPosition)) return false;
            ChunkPosition other = (ChunkPosition) obj;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * x + y) + z;
        }

        @Override
        public String toString() {
            return String.format("ChunkPos[%d, %d, %d]", x, y, z);
        }
    }
}