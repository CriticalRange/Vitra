package com.vitra.core.optimization;

import com.vitra.core.config.VitraConfig;
import com.vitra.core.optimization.culling.FrustumCuller;
import com.vitra.core.optimization.lod.LODManager;
import com.vitra.core.optimization.memory.MemoryPool;
import com.vitra.core.optimization.mesh.AsyncMeshBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central manager for all performance optimizations in Vitra
 * Coordinates various optimization systems and manages their lifecycle
 */
public class OptimizationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationManager.class);

    private final VitraConfig config;

    // Optimization modules
    private FrustumCuller frustumCuller;
    private AsyncMeshBuilder meshBuilder;
    private LODManager lodManager;
    private MemoryPool memoryPool;

    private boolean initialized = false;

    public OptimizationManager(VitraConfig config) {
        this.config = config;
    }

    /**
     * Initialize all optimization systems
     */
    public void initialize() {
        if (initialized) {
            LOGGER.warn("OptimizationManager already initialized");
            return;
        }

        LOGGER.info("Initializing optimization systems...");

        try {
            // Initialize frustum culling
            if (config.isFrustumCulling()) {
                frustumCuller = new FrustumCuller();
                frustumCuller.initialize();
                LOGGER.info("Frustum culling enabled");
            }

            // Initialize async mesh building
            if (config.isAsyncMeshBuilding()) {
                meshBuilder = new AsyncMeshBuilder();
                meshBuilder.initialize();
                LOGGER.info("Async mesh building enabled");
            }

            // Initialize LOD system
            if (config.isLodEnabled()) {
                lodManager = new LODManager(config.getLodDistance());
                lodManager.initialize();
                LOGGER.info("LOD system enabled with distance: {}", config.getLodDistance());
            }

            // Initialize memory pooling
            if (config.isMemoryPooling()) {
                memoryPool = new MemoryPool(config.getMaxPoolSize());
                memoryPool.initialize();
                LOGGER.info("Memory pooling enabled with max size: {}", config.getMaxPoolSize());
            }

            initialized = true;
            LOGGER.info("Optimization systems initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize optimization systems", e);
            throw new RuntimeException("Optimization systems initialization failed", e);
        }
    }

    /**
     * Shutdown all optimization systems
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down optimization systems...");

        if (memoryPool != null) {
            memoryPool.shutdown();
        }

        if (lodManager != null) {
            lodManager.shutdown();
        }

        if (meshBuilder != null) {
            meshBuilder.shutdown();
        }

        if (frustumCuller != null) {
            frustumCuller.shutdown();
        }

        initialized = false;
        LOGGER.info("Optimization systems shutdown complete");
    }

    /**
     * Update optimization systems each frame
     * @param deltaTime Time since last frame in seconds
     */
    public void update(float deltaTime) {
        if (!initialized) return;

        if (lodManager != null) {
            lodManager.update(deltaTime);
        }

        if (meshBuilder != null) {
            meshBuilder.update(deltaTime);
        }
    }

    /**
     * Apply configuration changes at runtime
     */
    public void applyConfigChanges() {
        if (!initialized) return;

        LOGGER.info("Applying optimization configuration changes...");

        // Update LOD distance
        if (lodManager != null) {
            lodManager.setLODDistance(config.getLodDistance());
        }

        // Update memory pool size
        if (memoryPool != null) {
            memoryPool.setMaxPoolSize(config.getMaxPoolSize());
        }

        // Enable/disable systems based on config
        if (config.isFrustumCulling() && frustumCuller == null) {
            frustumCuller = new FrustumCuller();
            frustumCuller.initialize();
            LOGGER.info("Frustum culling enabled at runtime");
        } else if (!config.isFrustumCulling() && frustumCuller != null) {
            frustumCuller.shutdown();
            frustumCuller = null;
            LOGGER.info("Frustum culling disabled at runtime");
        }

        // Similar logic for other systems...

        LOGGER.info("Configuration changes applied");
    }

    // Getters for optimization modules
    public FrustumCuller getFrustumCuller() {
        return frustumCuller;
    }

    public AsyncMeshBuilder getMeshBuilder() {
        return meshBuilder;
    }

    public LODManager getLODManager() {
        return lodManager;
    }

    public MemoryPool getMemoryPool() {
        return memoryPool;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get optimization statistics for debugging
     */
    public OptimizationStats getStats() {
        OptimizationStats stats = new OptimizationStats();

        if (frustumCuller != null) {
            stats.culledChunks = frustumCuller.getCulledChunkCount();
            stats.culledEntities = frustumCuller.getCulledEntityCount();
        }

        if (meshBuilder != null) {
            stats.meshBuildQueueSize = meshBuilder.getQueueSize();
            stats.completedMeshBuilds = meshBuilder.getCompletedBuilds();
        }

        if (lodManager != null) {
            stats.activeHighLOD = lodManager.getHighLODCount();
            stats.activeMediumLOD = lodManager.getMediumLODCount();
            stats.activeLowLOD = lodManager.getLowLODCount();
        }

        if (memoryPool != null) {
            stats.pooledObjects = memoryPool.getPooledObjectCount();
            stats.poolUtilization = memoryPool.getUtilizationPercentage();
        }

        return stats;
    }

    /**
     * Statistics class for optimization data
     */
    public static class OptimizationStats {
        public int culledChunks = 0;
        public int culledEntities = 0;
        public int meshBuildQueueSize = 0;
        public int completedMeshBuilds = 0;
        public int activeHighLOD = 0;
        public int activeMediumLOD = 0;
        public int activeLowLOD = 0;
        public int pooledObjects = 0;
        public float poolUtilization = 0.0f;

        @Override
        public String toString() {
            return String.format(
                "OptimizationStats{culled: chunks=%d, entities=%d; mesh: queue=%d, completed=%d; " +
                "lod: high=%d, medium=%d, low=%d; pool: objects=%d, util=%.1f%%}",
                culledChunks, culledEntities, meshBuildQueueSize, completedMeshBuilds,
                activeHighLOD, activeMediumLOD, activeLowLOD, pooledObjects, poolUtilization * 100
            );
        }
    }
}