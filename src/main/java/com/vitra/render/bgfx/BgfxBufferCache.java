package com.vitra.render.bgfx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central cache for BGFX buffer handles created from Minecraft's BufferBuilder/MeshData.
 *
 * This is NOT a mixin, so it can provide public static access without violating Mixin framework rules.
 * BufferBuilderMixin populates these caches, and MultiBufferSourceMixin reads from them.
 */
public class BgfxBufferCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(BgfxBufferCache.class);

    // Cache: MeshData hash -> BGFX vertex buffer handle
    private static final Map<Integer, Short> vertexBufferCache = new ConcurrentHashMap<>();

    // Cache: MeshData hash -> BGFX index buffer handle
    private static final Map<Integer, Short> indexBufferCache = new ConcurrentHashMap<>();

    // Cache: BufferBuilder hash -> MeshData (to connect BufferBuilder to MeshData in endBatch)
    private static final Map<Integer, MeshData> bufferBuilderToMeshData = new ConcurrentHashMap<>();

    /**
     * Get cached vertex buffer handle for MeshData.
     */
    public static short getVertexBufferHandle(MeshData meshData) {
        return vertexBufferCache.getOrDefault(System.identityHashCode(meshData), (short) -1);
    }

    /**
     * Get cached index buffer handle for MeshData.
     */
    public static short getIndexBufferHandle(MeshData meshData) {
        return indexBufferCache.getOrDefault(System.identityHashCode(meshData), (short) -1);
    }

    /**
     * Get MeshData associated with a BufferBuilder.
     * This is used in MultiBufferSourceMixin to connect BufferBuilder to its MeshData.
     */
    public static MeshData getMeshData(BufferBuilder bufferBuilder) {
        return bufferBuilderToMeshData.get(System.identityHashCode(bufferBuilder));
    }

    /**
     * Store vertex buffer handle for MeshData.
     * Called by BufferBuilderMixin when creating BGFX buffers.
     */
    public static void putVertexBufferHandle(MeshData meshData, short handle) {
        vertexBufferCache.put(System.identityHashCode(meshData), handle);
    }

    /**
     * Store index buffer handle for MeshData.
     * Called by BufferBuilderMixin when creating BGFX buffers.
     */
    public static void putIndexBufferHandle(MeshData meshData, short handle) {
        indexBufferCache.put(System.identityHashCode(meshData), handle);
    }

    /**
     * Store BufferBuilder -> MeshData association.
     * Called by BufferBuilderMixin when MeshData is built.
     */
    public static void putMeshData(BufferBuilder bufferBuilder, MeshData meshData) {
        bufferBuilderToMeshData.put(System.identityHashCode(bufferBuilder), meshData);
    }

    /**
     * Cleanup cached buffer handles.
     * Call this when shutting down or when buffers are no longer needed.
     */
    public static void cleanup() {
        LOGGER.info("Cleaning up {} cached vertex buffers and {} index buffers",
            vertexBufferCache.size(), indexBufferCache.size());

        // Destroy all cached vertex buffers
        vertexBufferCache.values().forEach(handle -> {
            if (Util.isValidHandle(handle)) {
                Util.destroy(handle, Util.RESOURCE_VERTEX_BUFFER);
            }
        });
        vertexBufferCache.clear();

        // Destroy all cached index buffers
        indexBufferCache.values().forEach(handle -> {
            if (Util.isValidHandle(handle)) {
                Util.destroy(handle, Util.RESOURCE_INDEX_BUFFER);
            }
        });
        indexBufferCache.clear();

        // Clear BufferBuilder -> MeshData associations
        bufferBuilderToMeshData.clear();

        LOGGER.info("BufferBuilder cache cleanup complete");
    }

    private BgfxBufferCache() {
    }
}
