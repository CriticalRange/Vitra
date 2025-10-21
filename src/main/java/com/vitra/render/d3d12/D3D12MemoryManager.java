package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX 12 Memory Manager inspired by VulkanMod's VMA integration
 * Provides frame-based resource cleanup and intelligent memory allocation
 */
public class D3D12MemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12MemoryManager");

    // Frame configuration matching D3D12 triple buffering
    private static final int FRAME_COUNT = 3;

    // D3D12 Heap Types
    public static final int HEAP_TYPE_DEFAULT = 1;    // GPU-only memory
    public static final int HEAP_TYPE_UPLOAD = 2;     // CPU-to-GPU upload
    public static final int HEAP_TYPE_READBACK = 3;   // GPU-to-CPU readback

    // D3D12MA Allocation Flags
    public static final int ALLOC_FLAG_NONE = 0x0;
    public static final int ALLOC_FLAG_MAPPED = 0x1;
    public static final int ALLOC_FLAG_COMMITTED = 0x2;
    public static final int ALLOC_FLAG_STRATEGY_MASK = 0x7 << 4;
    public static final int ALLOC_FLAG_STRATEGY_DEFAULT = 0x0 << 4;
    public static final int ALLOC_FLAG_STRATEGY_MIN_MEMORY = 0x1 << 4;
    public static final int ALLOC_FLAG_STRATEGY_MIN_TIME = 0x2 << 4;
    public static final int ALLOC_FLAG_STRATEGY_MIN_OFFSET = 0x3 << 4;

    /**
     * Memory allocation information
     */
    public static class AllocationInfo {
        public final long handle;
        public final long size;
        public final long offset;
        public final int heapType;
        public final int allocationFlags;
        public final long creationFrame;
        public final String debugName;

        public AllocationInfo(long handle, long size, long offset, int heapType,
                           int allocationFlags, long currentFrame, String debugName) {
            this.handle = handle;
            this.size = size;
            this.offset = offset;
            this.heapType = heapType;
            this.allocationFlags = allocationFlags;
            this.creationFrame = currentFrame;
            this.debugName = debugName;
        }

        public boolean isMapped() {
            return (allocationFlags & ALLOC_FLAG_MAPPED) != 0;
        }

        public boolean isCommitted() {
            return (allocationFlags & ALLOC_FLAG_COMMITTED) != 0;
        }
    }

    /**
     * Per-frame resource tracking
     */
    private static class FrameResources {
        final List<Long> resourcesToCleanup = new ArrayList<>();
        final List<Long> temporaryResources = new ArrayList<>();
        long memoryAllocated = 0;
        long memoryReleased = 0;
        int cleanupCount = 0;

        void addResource(long handle) {
            resourcesToCleanup.add(handle);
        }

        void addTemporaryResource(long handle) {
            temporaryResources.add(handle);
        }

        void cleanup() {
            for (Long handle : resourcesToCleanup) {
                VitraD3D12Native.releaseManagedResource(handle);
            }
            for (Long handle : temporaryResources) {
                VitraD3D12Native.releaseManagedResource(handle);
            }
            resourcesToCleanup.clear();
            temporaryResources.clear();
            cleanupCount++;
        }
    }

    // Frame-based resource management
    private final FrameResources[] frameResources = new FrameResources[FRAME_COUNT];
    private int currentFrameIndex = 0;
    private long currentFrameNumber = 0;

    // Active allocations tracking
    private final Map<Long, AllocationInfo> activeAllocations = new ConcurrentHashMap<>();
    private final Map<String, Long> namedAllocations = new HashMap<>();

    // Memory usage statistics
    private long totalMemoryAllocated = 0;
    private long totalMemoryReleased = 0;
    private final Map<Integer, Long> heapMemoryUsage = new HashMap<>();

    // D3D12MA support
    private boolean d3d12maSupported = false;
    private boolean memoryBudgetingEnabled = false;

    public D3D12MemoryManager() {
        // Initialize frame resources
        for (int i = 0; i < FRAME_COUNT; i++) {
            frameResources[i] = new FrameResources();
        }

        // Initialize heap memory tracking
        heapMemoryUsage.put(HEAP_TYPE_DEFAULT, 0L);
        heapMemoryUsage.put(HEAP_TYPE_UPLOAD, 0L);
        heapMemoryUsage.put(HEAP_TYPE_READBACK, 0L);

        // Check for D3D12MA support
        d3d12maSupported = VitraD3D12Native.isD3D12MASupported();
        if (d3d12maSupported) {
            LOGGER.info("D3D12MA (Memory Allocator) available");
        } else {
            LOGGER.warn("D3D12MA not available, using fallback allocation");
        }
    }

    /**
     * Begin new frame for frame-based cleanup
     */
    public void beginFrame() {
        currentFrameIndex = (currentFrameIndex + 1) % FRAME_COUNT;
        currentFrameNumber++;

        // Clean up resources from frame N-2 (safe to delete)
        int cleanupFrameIndex = (currentFrameIndex + 1) % FRAME_COUNT;
        FrameResources cleanupFrame = frameResources[cleanupFrameIndex];

        if (!cleanupFrame.resourcesToCleanup.isEmpty() || !cleanupFrame.temporaryResources.isEmpty()) {
            LOGGER.debug("Cleaning up {} resources from frame {}",
                cleanupFrame.resourcesToCleanup.size() + cleanupFrame.temporaryResources.size(),
                currentFrameNumber - FRAME_COUNT + 1);

            cleanupFrame.cleanup();

            // Update statistics
            totalMemoryReleased += cleanupFrame.memoryReleased;
        }

        LOGGER.trace("Frame {}: Beginning memory management (index: {})",
            currentFrameNumber, currentFrameIndex);
    }

    /**
     * Create managed buffer
     */
    public long createBuffer(byte[] data, int size, int stride, int heapType, int allocationFlags) {
        if (data == null && size > 0) {
            LOGGER.warn("Creating buffer with null data but non-zero size: {}", size);
            data = new byte[size];
        }

        long handle = VitraD3D12Native.createManagedBuffer(data, size, stride, heapType, allocationFlags);
        if (handle != 0) {
            // Track allocation
            AllocationInfo info = new AllocationInfo(handle, size, 0, heapType, allocationFlags,
                                                currentFrameNumber, null);
            activeAllocations.put(handle, info);

            // Update statistics
            totalMemoryAllocated += size;
            heapMemoryUsage.merge(heapType, (long) size, Long::sum);

            LOGGER.debug("Created buffer: handle={}, size={}, heapType={}, frame={}",
                Long.toHexString(handle), size, heapTypeName(heapType), currentFrameNumber);
        } else {
            LOGGER.error("Failed to create buffer: size={}, heapType={}", size, heapTypeName(heapType));
        }

        return handle;
    }

    /**
     * Create managed texture
     */
    public long createTexture(byte[] data, int width, int height, int format, int heapType, int allocationFlags) {
        long handle = VitraD3D12Native.createManagedTexture(data, width, height, format, heapType, allocationFlags);
        if (handle != 0) {
            // Estimate texture size (assuming 4 bytes per pixel for now)
            long estimatedSize = (long) width * height * 4;

            // Track allocation
            AllocationInfo info = new AllocationInfo(handle, estimatedSize, 0, heapType, allocationFlags,
                                                currentFrameNumber, null);
            activeAllocations.put(handle, info);

            // Update statistics
            totalMemoryAllocated += estimatedSize;
            heapMemoryUsage.merge(heapType, estimatedSize, Long::sum);

            LOGGER.debug("Created texture: handle={}, {}x{}, format={}, heapType={}, frame={}",
                Long.toHexString(handle), width, height, format, heapTypeName(heapType), currentFrameNumber);
        } else {
            LOGGER.error("Failed to create texture: {}x{}, format={}, heapType={}",
                width, height, format, heapTypeName(heapType));
        }

        return handle;
    }

    /**
     * Create upload buffer for temporary data transfer
     */
    public long createUploadBuffer(int size) {
        long handle = VitraD3D12Native.createManagedUploadBuffer(size);
        if (handle != 0) {
            // Mark as temporary (will be cleaned up this frame)
            frameResources[currentFrameIndex].addTemporaryResource(handle);

            LOGGER.debug("Created upload buffer: handle={}, size={}, frame={}",
                Long.toHexString(handle), size, currentFrameNumber);
        } else {
            LOGGER.error("Failed to create upload buffer: size={}", size);
        }

        return handle;
    }

    /**
     * Release managed resource
     */
    public void releaseResource(long handle) {
        if (handle == 0) return;

        AllocationInfo info = activeAllocations.remove(handle);
        if (info != null) {
            // Update statistics
            long size = info.size;
            totalMemoryReleased += size;
            heapMemoryUsage.merge(info.heapType, -size, Long::sum);

            // Remove from named allocations
            if (info.debugName != null) {
                namedAllocations.remove(info.debugName);
            }

            LOGGER.debug("Releasing resource: handle={}, size={}, heapType={}, age={} frames",
                Long.toHexString(handle), size, heapTypeName(info.heapType),
                currentFrameNumber - info.creationFrame);
        }

        // Schedule for cleanup in N-1 frames (safe GPU timeline)
        int cleanupFrameIndex = (currentFrameIndex + 1) % FRAME_COUNT;
        frameResources[cleanupFrameIndex].addResource(handle);
    }

    /**
     * Release resource with name
     */
    public void releaseNamedResource(String name) {
        Long handle = namedAllocations.get(name);
        if (handle != null) {
            releaseResource(handle);
        } else {
            LOGGER.warn("Named resource not found: {}", name);
        }
    }

    /**
     * Enable/disable memory budgeting
     */
    public boolean enableMemoryBudgeting(boolean enable) {
        if (d3d12maSupported) {
            memoryBudgetingEnabled = VitraD3D12Native.enableMemoryBudgeting(enable);
            LOGGER.info("Memory budgeting {} (D3D12MA: {})",
                memoryBudgetingEnabled ? "enabled" : "disabled", d3d12maSupported);
            return memoryBudgetingEnabled;
        } else {
            LOGGER.warn("Cannot enable memory budgeting - D3D12MA not supported");
            return false;
        }
    }

    /**
     * Check if memory allocation fits budget
     */
    public boolean checkMemoryBudget(long requiredSize, int heapType) {
        if (!memoryBudgetingEnabled || !d3d12maSupported) {
            return true; // No budgeting if D3D12MA not available
        }

        return VitraD3D12Native.checkMemoryBudget(requiredSize, heapType);
    }

    /**
     * Begin defragmentation process
     */
    public boolean beginDefragmentation() {
        if (!d3d12maSupported) {
            LOGGER.warn("Cannot defragment - D3D12MA not supported");
            return false;
        }

        return VitraD3D12Native.beginDefragmentation();
    }

    /**
     * Validate allocation
     */
    public boolean validateAllocation(long handle) {
        if (!d3d12maSupported) {
            return activeAllocations.containsKey(handle);
        }

        return VitraD3D12Native.validateAllocation(handle);
    }

    /**
     * Get allocation information
     */
    public AllocationInfo getAllocationInfo(long handle) {
        return activeAllocations.get(handle);
    }

    /**
     * Set resource debug name
     */
    public void setResourceDebugName(long handle, String name) {
        if (handle == 0 || name == null) return;

        VitraD3D12Native.setResourceDebugName(handle, name);

        AllocationInfo info = activeAllocations.get(handle);
        if (info != null) {
            // Update stored info (create new AllocationInfo with name)
            activeAllocations.put(handle, new AllocationInfo(
                info.handle, info.size, info.offset, info.heapType,
                info.allocationFlags, info.creationFrame, name
            ));
            namedAllocations.put(name, handle);
        }
    }

    /**
     * Get memory statistics
     */
    public String getMemoryStatistics() {
        if (d3d12maSupported) {
            return VitraD3D12Native.getMemoryStatistics();
        }

        // Fallback statistics
        StringBuilder stats = new StringBuilder();
        stats.append("=== D3D12 Memory Manager Statistics (Fallback Mode) ===\n");
        stats.append("Total Allocated: ").append(totalMemoryAllocated / (1024 * 1024)).append(" MB\n");
        stats.append("Total Released: ").append(totalMemoryReleased / (1024 * 1024)).append(" MB\n");
        stats.append("Currently Allocated: ").append((totalMemoryAllocated - totalMemoryReleased) / (1024 * 1024)).append(" MB\n");
        stats.append("Active Allocations: ").append(activeAllocations.size()).append("\n");
        stats.append("Named Allocations: ").append(namedAllocations.size()).append("\n");
        stats.append("Current Frame: ").append(currentFrameNumber).append("\n");

        stats.append("\n--- Heap Usage ---\n");
        for (Map.Entry<Integer, Long> entry : heapMemoryUsage.entrySet()) {
            long usage = entry.getValue();
            if (usage > 0) {
                stats.append(heapTypeName(entry.getKey())).append(": ")
                     .append(usage / (1024 * 1024)).append(" MB\n");
            }
        }

        stats.append("\n--- Frame Resources ---\n");
        for (int i = 0; i < FRAME_COUNT; i++) {
            FrameResources frame = frameResources[i];
            int pendingCleanup = frame.resourcesToCleanup.size();
            int temporaryResources = frame.temporaryResources.size();
            if (pendingCleanup > 0 || temporaryResources > 0) {
                stats.append("Frame ").append(i).append(": ")
                     .append(pendingCleanup).append(" pending cleanup, ")
                     .append(temporaryResources).append(" temporary\n");
            }
        }

        return stats.toString();
    }

    /**
     * Get pool statistics
     */
    public String getPoolStatistics(int poolType) {
        if (d3d12maSupported) {
            return VitraD3D12Native.getPoolStatistics(poolType);
        }

        return "Pool statistics not available - D3D12MA not supported";
    }

    /**
     * Dump memory state to JSON for debugging
     */
    public void dumpMemoryToJson() {
        if (d3d12maSupported) {
            VitraD3D12Native.dumpMemoryToJson();
        } else {
            LOGGER.warn("Cannot dump memory to JSON - D3D12MA not supported");
        }
    }

    /**
     * Get memory manager statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Memory Manager:\n");
        stats.append("D3D12MA Support: ").append(d3d12maSupported).append("\n");
        stats.append("Memory Budgeting: ").append(memoryBudgetingEnabled).append("\n");
        stats.append("Active Allocations: ").append(activeAllocations.size()).append("\n");
        stats.append("Current Frame: ").append(currentFrameNumber).append("\n");
        stats.append("Total Memory Allocated: ").append(totalMemoryAllocated / (1024 * 1024)).append(" MB\n");

        return stats.toString();
    }

    /**
     * Get heap type name
     */
    private static String heapTypeName(int heapType) {
        switch (heapType) {
            case HEAP_TYPE_DEFAULT: return "Default";
            case HEAP_TYPE_UPLOAD: return "Upload";
            case HEAP_TYPE_READBACK: return "Readback";
            default: return "Unknown(" + heapType + ")";
        }
    }

    /**
     * Get current frame number
     */
    public long getCurrentFrameNumber() {
        return currentFrameNumber;
    }

    /**
     * Get current frame index
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex;
    }

    /**
     * Check if D3D12MA is supported
     */
    public boolean isD3D12MASupported() {
        return d3d12maSupported;
    }

    /**
     * Check if memory budgeting is enabled
     */
    public boolean isMemoryBudgetingEnabled() {
        return memoryBudgetingEnabled;
    }

    /**
     * Get total memory usage by heap type
     */
    public long getHeapMemoryUsage(int heapType) {
        return heapMemoryUsage.getOrDefault(heapType, 0L);
    }

    /**
     * Get active allocation count
     */
    public int getActiveAllocationCount() {
        return activeAllocations.size();
    }
}