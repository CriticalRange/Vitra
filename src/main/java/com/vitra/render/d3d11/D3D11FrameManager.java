package com.vitra.render.d3d11;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * D3D11 Frame Resource Manager - Triple Buffering for Dynamic Resources
 *
 * Based on VulkanMod's MemoryManager pattern.
 * Maintains 3 sets of per-frame resources to prevent GPU-CPU synchronization stalls.
 *
 * Why needed:
 * - GPU may still be rendering frame N while CPU prepares frame N+1
 * - Without per-frame resources, CPU overwrites data GPU is reading → corruption/flickering
 * - Triple buffering ensures CPU is always working on a frame the GPU isn't using
 *
 * Architecture:
 * - 3 frame resource sets (frames 0, 1, 2)
 * - Current frame index rotates each frame: (currentFrame + 1) % 3
 * - Each frame set contains: vertex buffers, index buffers, uniform buffers
 *
 * VulkanMod equivalent: MemoryManager.java maintains per-frame vertex buffer pools
 *
 * Usage:
 * 1. beginFrame() - advance to next frame set
 * 2. getCurrentFrame() - get current frame index for buffer allocation
 * 3. resetFrameResources() - called at frame start to reset allocators
 */
public class D3D11FrameManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11FrameManager.class);

    // Frame configuration
    private static final int FRAMES_IN_FLIGHT = 3;  // Triple buffering
    private static int currentFrame = 0;

    // Per-frame resource tracking
    private static final List<FrameResources>[] perFrameResources = new List[FRAMES_IN_FLIGHT];

    static {
        // Initialize per-frame resource lists
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            perFrameResources[i] = new ArrayList<>();
        }
        LOGGER.info("Initialized frame manager with {} frames in flight", FRAMES_IN_FLIGHT);
    }

    /**
     * Frame resource set - buffers allocated for a specific frame
     */
    public static class FrameResources {
        public long vertexBufferHandle = 0;
        public long indexBufferHandle = 0;
        public int capacity = 0;
        public boolean inUse = false;

        public FrameResources(int capacity) {
            this.capacity = capacity;
        }

        public void markInUse() {
            this.inUse = true;
        }

        public void markFree() {
            this.inUse = false;
        }

        public void cleanup() {
            if (vertexBufferHandle != 0) {
                com.vitra.render.jni.VitraD3D11Renderer.destroyResource(vertexBufferHandle);
                vertexBufferHandle = 0;
            }
            if (indexBufferHandle != 0) {
                com.vitra.render.jni.VitraD3D11Renderer.destroyResource(indexBufferHandle);
                indexBufferHandle = 0;
            }
        }
    }

    // ==================== FRAME LIFECYCLE ====================

    /**
     * Begin new frame - advance to next frame set
     *
     * VulkanMod pattern: MemoryManager.resetBuffers() called at frame start
     * Rotates to next frame index so we don't overwrite buffers GPU is reading
     */
    public static void beginFrame() {
        currentFrame = (currentFrame + 1) % FRAMES_IN_FLIGHT;
        LOGGER.trace("[FRAME_MANAGER] Advanced to frame {}", currentFrame);

        // Reset all resources for this frame (mark as free for reuse)
        resetCurrentFrameResources();
    }

    /**
     * Get current frame index (0-2)
     * Use this to allocate per-frame resources
     */
    public static int getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Get total number of frames in flight
     */
    public static int getFramesInFlight() {
        return FRAMES_IN_FLIGHT;
    }

    // ==================== RESOURCE MANAGEMENT ====================

    /**
     * Reset resources for current frame (mark as available for reuse)
     *
     * VulkanMod pattern: MemoryManager resets write offset for vertex buffer pool
     * We mark resources as free so they can be reused by new draws
     */
    private static void resetCurrentFrameResources() {
        List<FrameResources> frameResources = perFrameResources[currentFrame];
        for (FrameResources resources : frameResources) {
            resources.markFree();
        }
        LOGGER.trace("[FRAME_MANAGER] Reset {} resources for frame {}", frameResources.size(), currentFrame);
    }

    /**
     * Allocate frame resource set for immediate mode rendering
     *
     * VulkanMod pattern: MemoryManager.getVertexBuffer() gets buffer from pool
     * Returns existing free resource or creates new one if needed
     *
     * @param requiredCapacity Minimum capacity needed (in bytes)
     * @return FrameResources for current frame
     */
    public static FrameResources allocateFrameResources(int requiredCapacity) {
        List<FrameResources> frameResources = perFrameResources[currentFrame];

        // Try to find existing free resource with sufficient capacity
        for (FrameResources resources : frameResources) {
            if (!resources.inUse && resources.capacity >= requiredCapacity) {
                resources.markInUse();
                LOGGER.trace("[FRAME_MANAGER] Reused frame resource: frame={}, capacity={}",
                    currentFrame, resources.capacity);
                return resources;
            }
        }

        // No suitable resource found - create new one
        FrameResources newResources = new FrameResources(requiredCapacity);
        newResources.markInUse();
        frameResources.add(newResources);

        LOGGER.debug("[FRAME_MANAGER] Created new frame resource: frame={}, capacity={}, total={}",
            currentFrame, requiredCapacity, frameResources.size());

        return newResources;
    }

    /**
     * Free frame resource (mark as available for reuse in this frame)
     */
    public static void freeFrameResource(FrameResources resources) {
        if (resources != null) {
            resources.markFree();
            LOGGER.trace("[FRAME_MANAGER] Freed frame resource");
        }
    }

    // ==================== STATISTICS ====================

    /**
     * Get resource statistics for debugging
     */
    public static String getStats() {
        int totalResources = 0;
        int inUseResources = 0;

        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            totalResources += perFrameResources[i].size();
            for (FrameResources resources : perFrameResources[i]) {
                if (resources.inUse) inUseResources++;
            }
        }

        return String.format("Frame %d/%d, Resources: %d total (%d in use, %d free)",
            currentFrame, FRAMES_IN_FLIGHT, totalResources, inUseResources,
            totalResources - inUseResources);
    }

    /**
     * Get detailed per-frame statistics
     */
    public static String getDetailedStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Frame Manager Stats ===\n");
        sb.append("Current Frame: ").append(currentFrame).append("/").append(FRAMES_IN_FLIGHT).append("\n");

        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            List<FrameResources> frameResources = perFrameResources[i];
            int inUse = 0;
            long totalCapacity = 0;

            for (FrameResources resources : frameResources) {
                if (resources.inUse) inUse++;
                totalCapacity += resources.capacity;
            }

            sb.append(String.format("Frame %d: %d resources (%d in use), %d KB total capacity\n",
                i, frameResources.size(), inUse, totalCapacity / 1024));
        }

        return sb.toString();
    }

    // ==================== CLEANUP ====================

    /**
     * Cleanup all frame resources
     * Called during renderer shutdown
     */
    public static void cleanup() {
        LOGGER.info("Cleaning up frame manager...");

        int totalCleaned = 0;
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            List<FrameResources> frameResources = perFrameResources[i];
            for (FrameResources resources : frameResources) {
                resources.cleanup();
                totalCleaned++;
            }
            frameResources.clear();
        }

        currentFrame = 0;
        LOGGER.info("Frame manager cleanup complete: {} resources destroyed", totalCleaned);
    }

    /**
     * Wait for GPU to finish all frames
     * Called before cleanup to ensure no resources are in use
     */
    public static void waitForIdle() {
        LOGGER.debug("Waiting for GPU idle before cleanup...");
        com.vitra.render.jni.VitraD3D11Renderer.waitForIdle();
        LOGGER.debug("GPU idle");
    }
}
