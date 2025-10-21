package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DirectX 12 Descriptor Heap Manager inspired by VulkanMod's descriptor management
 * Provides comprehensive descriptor heap allocation and management
 */
public class D3D12DescriptorHeapManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12DescriptorHeapManager");

    // Descriptor heap types
    public static final int HEAP_TYPE_CBV_SRV_UAV = 0;
    public static final int HEAP_TYPE_SAMPLER = 1;
    public static final int HEAP_TYPE_RTV = 2;
    public static final int HEAP_TYPE_DSV = 3;

    // Descriptor heap flags
    public static final int HEAP_FLAG_NONE = 0x0;
    public static final int HEAP_FLAG_SHADER_VISIBLE = 0x1;

    // Default heap sizes
    private static final int DEFAULT_CBV_SRV_UAV_HEAP_SIZE = 1024;
    private static final int DEFAULT_SAMPLER_HEAP_SIZE = 256;
    private static final int DEFAULT_RTV_HEAP_SIZE = 256;
    private static final int DEFAULT_DSV_HEAP_SIZE = 256;

    /**
     * Descriptor heap information
     */
    public static class DescriptorHeap {
        private final long handle;
        private final int heapType;
        private final int numDescriptors;
        private final int flags;
        private final long cpuStartHandle;
        private final long gpuStartHandle;
        private final int descriptorIncrementSize;
        private final String debugName;

        public DescriptorHeap(long handle, int heapType, int numDescriptors, int flags,
                          long cpuStartHandle, long gpuStartHandle, int descriptorIncrementSize,
                          String debugName) {
            this.handle = handle;
            this.heapType = heapType;
            this.numDescriptors = numDescriptors;
            this.flags = flags;
            this.cpuStartHandle = cpuStartHandle;
            this.gpuStartHandle = gpuStartHandle;
            this.descriptorIncrementSize = descriptorIncrementSize;
            this.debugName = debugName;
        }

        public long getHandle() { return handle; }
        public int getHeapType() { return heapType; }
        public int getNumDescriptors() { return numDescriptors; }
        public int getFlags() { return flags; }
        public long getCpuStartHandle() { return cpuStartHandle; }
        public long getGpuStartHandle() { return gpuStartHandle; }
        public int getDescriptorIncrementSize() { return descriptorIncrementSize; }
        public String getDebugName() { return debugName; }

        public boolean isShaderVisible() {
            return (flags & HEAP_FLAG_SHADER_VISIBLE) != 0;
        }
    }

    /**
     * Descriptor allocation
     */
    public static class DescriptorAllocation {
        private final DescriptorHeap heap;
        private final int index;
        private final int count;
        private final long cpuHandle;
        private final long gpuHandle;
        private final String debugName;

        public DescriptorAllocation(DescriptorHeap heap, int index, int count, String debugName) {
            this.heap = heap;
            this.index = index;
            this.count = count;
            this.debugName = debugName;

            // Calculate handles
            this.cpuHandle = heap.getCpuStartHandle() + (long) index * heap.getDescriptorIncrementSize();
            this.gpuHandle = heap.getGpuStartHandle() + (long) index * heap.getDescriptorIncrementSize();
        }

        public DescriptorHeap getHeap() { return heap; }
        public int getIndex() { return index; }
        public int getCount() { return count; }
        public long getCpuHandle() { return cpuHandle; }
        public long getGpuHandle() { return gpuHandle; }
        public String getDebugName() { return debugName; }

        public long getCpuHandleAtOffset(int offset) {
            if (offset < 0 || offset >= count) {
                throw new IllegalArgumentException("Invalid offset: " + offset);
            }
            return cpuHandle + (long) offset * heap.getDescriptorIncrementSize();
        }

        public long getGpuHandleAtOffset(int offset) {
            if (offset < 0 || offset >= count) {
                throw new IllegalArgumentException("Invalid offset: " + offset);
            }
            return gpuHandle + (long) offset * heap.getDescriptorIncrementSize();
        }
    }

    /**
     * Free block in a heap
     */
    private static class FreeBlock {
        final int index;
        final int count;

        FreeBlock(int index, int count) {
            this.index = index;
            this.count = count;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            FreeBlock freeBlock = (FreeBlock) obj;
            return index == freeBlock.index && count == freeBlock.count;
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, count);
        }
    }

    // Descriptor heaps
    private final Map<String, DescriptorHeap> namedHeaps;
    private final List<DescriptorHeap> heapsByType[];

    // Free blocks tracking
    private final Map<DescriptorHeap, PriorityQueue<FreeBlock>> freeBlocks;

    // Statistics
    private final AtomicLong totalAllocations = new AtomicLong(0);
    private final AtomicLong totalDeallocations = new AtomicLong(0);
    private final AtomicInteger activeAllocations = new AtomicInteger(0);
    private final AtomicInteger totalDescriptorsAllocated = new AtomicInteger(0);

    public D3D12DescriptorHeapManager() {
        this.namedHeaps = new HashMap<>();
        this.heapsByType = new ArrayList[4];
        for (int i = 0; i < 4; i++) {
            heapsByType[i] = new ArrayList<>();
        }
        this.freeBlocks = new HashMap<>();

        initializeDefaultHeaps();
    }

    /**
     * Initialize default descriptor heaps
     */
    private void initializeDefaultHeaps() {
        LOGGER.info("Initializing default D3D12 descriptor heaps");

        // Create CBV/SRV/UAV heap (shader visible)
        createHeap("DefaultCBV_SRV_UAV", HEAP_TYPE_CBV_SRV_UAV,
                  DEFAULT_CBV_SRV_UAV_HEAP_SIZE, HEAP_FLAG_SHADER_VISIBLE);

        // Create sampler heap (shader visible)
        createHeap("DefaultSampler", HEAP_TYPE_SAMPLER,
                  DEFAULT_SAMPLER_HEAP_SIZE, HEAP_FLAG_SHADER_VISIBLE);

        // Create RTV heap (non-shader visible)
        createHeap("DefaultRTV", HEAP_TYPE_RTV,
                  DEFAULT_RTV_HEAP_SIZE, HEAP_FLAG_NONE);

        // Create DSV heap (non-shader visible)
        createHeap("DefaultDSV", HEAP_TYPE_DSV,
                  DEFAULT_DSV_HEAP_SIZE, HEAP_FLAG_NONE);

        LOGGER.info("Default descriptor heaps initialized");
    }

    /**
     * Create descriptor heap
     */
    public DescriptorHeap createHeap(String name, int heapType, int numDescriptors, int flags) {
        LOGGER.info("Creating descriptor heap: {} ({})", name, getHeapTypeName(heapType));

        long heapHandle = VitraD3D12Native.createDescriptorHeap(heapType, numDescriptors, flags, name);
        if (heapHandle == 0) {
            LOGGER.error("Failed to create descriptor heap: {}", name);
            return null;
        }

        // Get heap information
        long cpuStartHandle = VitraD3D12Native.getDescriptorHeapCPUStart(heapHandle);
        long gpuStartHandle = VitraD3D12Native.getDescriptorHeapGPUStart(heapHandle);
        int descriptorIncrementSize = VitraD3D12Native.getDescriptorIncrementSize(heapType);

        DescriptorHeap heap = new DescriptorHeap(heapHandle, heapType, numDescriptors, flags,
                                             cpuStartHandle, gpuStartHandle, descriptorIncrementSize, name);

        // Track heap
        namedHeaps.put(name, heap);
        heapsByType[heapType].add(heap);

        // Initialize free blocks
        PriorityQueue<FreeBlock> blocks = new PriorityQueue<>(Comparator.comparingInt(a -> a.index));
        blocks.add(new FreeBlock(0, numDescriptors));
        freeBlocks.put(heap, blocks);

        LOGGER.info("Created descriptor heap: {}, handle=0x{}, descriptors={}, increment={} bytes",
            name, Long.toHexString(heapHandle), numDescriptors, descriptorIncrementSize);

        return heap;
    }

    /**
     * Get descriptor heap by name
     */
    public DescriptorHeap getHeap(String name) {
        return namedHeaps.get(name);
    }

    /**
     * Allocate descriptors from heap
     */
    public DescriptorAllocation allocate(DescriptorHeap heap, int count, String debugName) {
        return allocate(heap, count, debugName, false);
    }

    /**
     * Allocate descriptors from heap with optional alignment
     */
    public DescriptorAllocation allocate(DescriptorHeap heap, int count, String debugName, boolean requireAligned) {
        if (heap == null || count <= 0) {
            return null;
        }

        PriorityQueue<FreeBlock> blocks = freeBlocks.get(heap);
        if (blocks == null) {
            LOGGER.error("No free blocks tracking for heap: {}", heap.getDebugName());
            return null;
        }

        // Find suitable free block
        FreeBlock bestBlock = null;
        List<FreeBlock> checkedBlocks = new ArrayList<>();

        while (!blocks.isEmpty()) {
            FreeBlock block = blocks.poll();
            if (block == null) break;

            if (block.count >= count) {
                bestBlock = block;
                break;
            }

            checkedBlocks.add(block);
        }

        // Return checked blocks that were too small
        blocks.addAll(checkedBlocks);

        if (bestBlock == null) {
            LOGGER.error("Failed to allocate {} descriptors from heap: {}", count, heap.getDebugName());
            return null;
        }

        // Create allocation
        DescriptorAllocation allocation = new DescriptorAllocation(heap, bestBlock.index, count, debugName);

        // Update free blocks
        if (bestBlock.count == count) {
            // Exact fit - remove block
        } else {
            // Split block - add remaining space back
            FreeBlock remaining = new FreeBlock(bestBlock.index + count, bestBlock.count - count);
            blocks.offer(remaining);
        }

        // Update statistics
        totalAllocations.incrementAndGet();
        activeAllocations.incrementAndGet();
        totalDescriptorsAllocated.addAndGet(count);

        LOGGER.debug("Allocated {} descriptors from {} at index {}: {}",
            count, heap.getDebugName(), bestBlock.index, debugName);

        return allocation;
    }

    /**
     * Allocate descriptors from default heap
     */
    public DescriptorAllocation allocateFromDefaultHeap(int heapType, int count, String debugName) {
        List<DescriptorHeap> heaps = heapsByType[heapType];
        if (heaps.isEmpty()) {
            LOGGER.error("No heaps available for type: {}", getHeapTypeName(heapType));
            return null;
        }

        // Try to allocate from first available heap
        for (DescriptorHeap heap : heaps) {
            DescriptorAllocation allocation = allocate(heap, count, debugName);
            if (allocation != null) {
                return allocation;
            }
        }

        LOGGER.error("Failed to allocate {} descriptors from any {} heap", count, getHeapTypeName(heapType));
        return null;
    }

    /**
     * Deallocate descriptors
     */
    public void deallocate(DescriptorAllocation allocation) {
        if (allocation == null) {
            return;
        }

        DescriptorHeap heap = allocation.getHeap();
        PriorityQueue<FreeBlock> blocks = freeBlocks.get(heap);

        if (blocks != null) {
            // Add freed block back to free list
            FreeBlock freeBlock = new FreeBlock(allocation.getIndex(), allocation.getCount());
            blocks.offer(freeBlock);

            // Merge with adjacent free blocks (coalescing)
            coalesceFreeBlocks(blocks);

            LOGGER.debug("Deallocated {} descriptors from {} at index {}: {}",
                allocation.getCount(), heap.getDebugName(), allocation.getIndex(), allocation.getDebugName());
        }

        // Update statistics
        totalDeallocations.incrementAndGet();
        activeAllocations.decrementAndGet();
        totalDescriptorsAllocated.addAndGet(-allocation.getCount());
    }

    /**
     * Coalesce adjacent free blocks to reduce fragmentation
     */
    private void coalesceFreeBlocks(PriorityQueue<FreeBlock> blocks) {
        if (blocks.size() < 2) {
            return;
        }

        // Extract all blocks and sort by index
        List<FreeBlock> allBlocks = new ArrayList<>(blocks);
        Collections.sort(allBlocks, Comparator.comparingInt(a -> a.index));

        // Merge adjacent blocks
        List<FreeBlock> coalesced = new ArrayList<>();
        FreeBlock current = allBlocks.get(0);

        for (int i = 1; i < allBlocks.size(); i++) {
            FreeBlock next = allBlocks.get(i);
            if (current.index + current.count == next.index) {
                // Adjacent blocks - merge them
                current = new FreeBlock(current.index, current.count + next.count);
            } else {
                coalesced.add(current);
                current = next;
            }
        }
        coalesced.add(current);

        // Update blocks queue
        blocks.clear();
        blocks.addAll(coalesced);
    }

    /**
     * Create copy of descriptors between heaps
     */
    public boolean copyDescriptors(DescriptorAllocation source, DescriptorAllocation dest, int count) {
        if (source == null || dest == null || count <= 0) {
            return false;
        }

        if (source.getCount() < count || dest.getCount() < count) {
            LOGGER.error("Invalid copy operation: source={}, dest={}, requested={}",
                source.getCount(), dest.getCount(), count);
            return false;
        }

        boolean success = VitraD3D12Native.copyDescriptors(
            source.getCpuHandle(), dest.getCpuHandle(), count,
            source.getHeap().getDescriptorIncrementSize(), dest.getHeap().getDescriptorIncrementSize()
        );

        if (success) {
            LOGGER.debug("Copied {} descriptors: {} -> {}", count, source.getDebugName(), dest.getDebugName());
        } else {
            LOGGER.error("Failed to copy descriptors: {} -> {}", source.getDebugName(), dest.getDebugName());
        }

        return success;
    }

    /**
     * Get heap type name for logging
     */
    private static String getHeapTypeName(int heapType) {
        switch (heapType) {
            case HEAP_TYPE_CBV_SRV_UAV: return "CBV_SRV_UAV";
            case HEAP_TYPE_SAMPLER: return "SAMPLER";
            case HEAP_TYPE_RTV: return "RTV";
            case HEAP_TYPE_DSV: return "DSV";
            default: return "Unknown(" + heapType + ")";
        }
    }

    /**
     * Get statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Descriptor Heap Manager Statistics:\n");
        stats.append("  Total Allocations: ").append(totalAllocations.get()).append("\n");
        stats.append("  Total Deallocations: ").append(totalDeallocations.get()).append("\n");
        stats.append("  Active Allocations: ").append(activeAllocations.get()).append("\n");
        stats.append("  Total Descriptors Allocated: ").append(totalDescriptorsAllocated.get()).append("\n");

        stats.append("\n--- Descriptor Heaps ---\n");
        for (Map.Entry<String, DescriptorHeap> entry : namedHeaps.entrySet()) {
            DescriptorHeap heap = entry.getValue();
            PriorityQueue<FreeBlock> blocks = freeBlocks.get(heap);
            int freeDescriptors = 0;
            if (blocks != null) {
                freeDescriptors = blocks.stream().mapToInt(b -> b.count).sum();
            }

            stats.append("  ").append(entry.getKey()).append(": ")
                 .append(getHeapTypeName(heap.getHeapType()))
                 .append(", total=").append(heap.getNumDescriptors())
                 .append(", free=").append(freeDescriptors)
                 .append(", used=").append(heap.getNumDescriptors() - freeDescriptors)
                 .append(", shader_visible=").append(heap.isShaderVisible())
                 .append("\n");
        }

        return stats.toString();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        LOGGER.info("Cleaning up D3D12 Descriptor Heap Manager");

        // Release all descriptor heaps
        for (DescriptorHeap heap : namedHeaps.values()) {
            if (heap.getHandle() != 0) {
                VitraD3D12Native.releaseManagedResource(heap.getHandle());
            }
        }

        // Clear all tracking
        namedHeaps.clear();
        for (int i = 0; i < 4; i++) {
            heapsByType[i].clear();
        }
        freeBlocks.clear();

        LOGGER.info("D3D12 Descriptor Heap Manager cleanup completed");
    }
}