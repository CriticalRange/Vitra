package com.vitra.core.optimization.mesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous mesh building system for improved performance
 * Builds chunk meshes on background threads to avoid blocking the main render thread
 */
public class AsyncMeshBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncMeshBuilder.class);

    private ExecutorService meshBuildExecutor;
    private final BlockingQueue<MeshBuildTask> buildQueue;
    private final BlockingQueue<MeshBuildResult> completedBuilds;
    private final AtomicInteger queueSize;
    private final AtomicInteger completedCount;

    private boolean initialized = false;
    private int workerThreadCount;

    public AsyncMeshBuilder() {
        this.buildQueue = new LinkedBlockingQueue<>();
        this.completedBuilds = new LinkedBlockingQueue<>();
        this.queueSize = new AtomicInteger(0);
        this.completedCount = new AtomicInteger(0);
        this.workerThreadCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /**
     * Initialize the async mesh builder
     */
    public void initialize() {
        if (initialized) {
            LOGGER.warn("AsyncMeshBuilder already initialized");
            return;
        }

        meshBuildExecutor = Executors.newFixedThreadPool(workerThreadCount,
            r -> {
                Thread t = new Thread(r, "Vitra-MeshBuilder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

        // Start worker threads
        for (int i = 0; i < workerThreadCount; i++) {
            meshBuildExecutor.submit(new MeshBuildWorker());
        }

        initialized = true;
        LOGGER.info("AsyncMeshBuilder initialized with {} worker threads", workerThreadCount);
    }

    /**
     * Shutdown the async mesh builder
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down AsyncMeshBuilder...");

        if (meshBuildExecutor != null) {
            meshBuildExecutor.shutdown();
            try {
                if (!meshBuildExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    meshBuildExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                meshBuildExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        buildQueue.clear();
        completedBuilds.clear();
        queueSize.set(0);
        initialized = false;

        LOGGER.info("AsyncMeshBuilder shutdown complete");
    }

    /**
     * Queue a mesh build task
     * @param chunkX Chunk X coordinate
     * @param chunkY Chunk Y coordinate
     * @param chunkZ Chunk Z coordinate
     * @param priority Build priority (higher = more urgent)
     * @param callback Callback to invoke when build is complete
     */
    public void queueMeshBuild(int chunkX, int chunkY, int chunkZ, int priority, MeshBuildCallback callback) {
        if (!initialized) {
            LOGGER.warn("Cannot queue mesh build - not initialized");
            return;
        }

        MeshBuildTask task = new MeshBuildTask(chunkX, chunkY, chunkZ, priority, callback);
        if (buildQueue.offer(task)) {
            queueSize.incrementAndGet();
        } else {
            LOGGER.warn("Mesh build queue is full, dropping task for chunk ({}, {}, {})", chunkX, chunkY, chunkZ);
        }
    }

    /**
     * Update the mesh builder - should be called each frame to process completed builds
     * @param deltaTime Time since last frame
     */
    public void update(float deltaTime) {
        if (!initialized) return;

        // Process completed builds
        MeshBuildResult result;
        while ((result = completedBuilds.poll()) != null) {
            if (result.callback != null) {
                try {
                    result.callback.onMeshBuildComplete(result);
                } catch (Exception e) {
                    LOGGER.error("Error in mesh build callback", e);
                }
            }
            completedCount.incrementAndGet();
        }
    }

    public int getQueueSize() {
        return queueSize.get();
    }

    public int getCompletedBuilds() {
        return completedCount.get();
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Worker thread for building meshes
     */
    private class MeshBuildWorker implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MeshBuildTask task = buildQueue.take();
                    queueSize.decrementAndGet();

                    // Build the mesh (placeholder implementation)
                    MeshBuildResult result = buildMesh(task);

                    completedBuilds.offer(result);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Error in mesh build worker", e);
                }
            }
        }

        private MeshBuildResult buildMesh(MeshBuildTask task) {
            // Placeholder mesh building logic
            // In a real implementation, this would:
            // 1. Get block data for the chunk
            // 2. Generate vertex and index data
            // 3. Apply optimizations (face culling, etc.)
            // 4. Return the built mesh data

            try {
                // Simulate mesh building time
                Thread.sleep(10 + (int)(Math.random() * 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return new MeshBuildResult(
                task.chunkX, task.chunkY, task.chunkZ,
                new float[0], // vertices
                new int[0],   // indices
                true,         // success
                task.callback
            );
        }
    }

    /**
     * Mesh build task data
     */
    public static class MeshBuildTask {
        public final int chunkX, chunkY, chunkZ;
        public final int priority;
        public final MeshBuildCallback callback;

        public MeshBuildTask(int chunkX, int chunkY, int chunkZ, int priority, MeshBuildCallback callback) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.priority = priority;
            this.callback = callback;
        }
    }

    /**
     * Mesh build result data
     */
    public static class MeshBuildResult {
        public final int chunkX, chunkY, chunkZ;
        public final float[] vertices;
        public final int[] indices;
        public final boolean success;
        public final MeshBuildCallback callback;

        public MeshBuildResult(int chunkX, int chunkY, int chunkZ, float[] vertices, int[] indices, boolean success, MeshBuildCallback callback) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.vertices = vertices;
            this.indices = indices;
            this.success = success;
            this.callback = callback;
        }
    }

    /**
     * Callback interface for mesh build completion
     */
    public interface MeshBuildCallback {
        void onMeshBuildComplete(MeshBuildResult result);
    }
}