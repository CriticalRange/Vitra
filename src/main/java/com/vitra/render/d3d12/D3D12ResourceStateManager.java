package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX 12 Resource State Manager inspired by VulkanMod's barrier system
 * Provides comprehensive resource state tracking and barrier management
 */
public class D3D12ResourceStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12ResourceStateManager");

    // Resource states (from d3d12.h)
    public static final int RESOURCE_STATE_COMMON = D3D12TextureBuilder.D3D12_RESOURCE_STATE_COMMON;
    public static final int RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER = D3D12TextureBuilder.D3D12_RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER;
    public static final int RESOURCE_STATE_INDEX_BUFFER = D3D12TextureBuilder.D3D12_RESOURCE_STATE_INDEX_BUFFER;
    public static final int RESOURCE_STATE_RENDER_TARGET = D3D12TextureBuilder.D3D12_RESOURCE_STATE_RENDER_TARGET;
    public static final int RESOURCE_STATE_UNORDERED_ACCESS = D3D12TextureBuilder.D3D12_RESOURCE_STATE_UNORDERED_ACCESS;
    public static final int RESOURCE_STATE_DEPTH_WRITE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_DEPTH_WRITE;
    public static final int RESOURCE_STATE_DEPTH_READ = D3D12TextureBuilder.D3D12_RESOURCE_STATE_DEPTH_READ;
    public static final int RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
    public static final int RESOURCE_STATE_PIXEL_SHADER_RESOURCE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    public static final int RESOURCE_STATE_STREAM_OUT = D3D12TextureBuilder.D3D12_RESOURCE_STATE_STREAM_OUT;
    public static final int RESOURCE_STATE_INDIRECT_ARGUMENT = D3D12TextureBuilder.D3D12_RESOURCE_STATE_INDIRECT_ARGUMENT;
    public static final int RESOURCE_STATE_COPY_DEST = D3D12TextureBuilder.D3D12_RESOURCE_STATE_COPY_DEST;
    public static final int RESOURCE_STATE_COPY_SOURCE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_COPY_SOURCE;
    public static final int RESOURCE_STATE_RESOLVE_DEST = D3D12TextureBuilder.D3D12_RESOURCE_STATE_RESOLVE_DEST;
    public static final int RESOURCE_STATE_RESOLVE_SOURCE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_RESOLVE_SOURCE;
    public static final int RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE;
    public static final int RESOURCE_STATE_SHADING_RATE_SOURCE = D3D12TextureBuilder.D3D12_RESOURCE_STATE_SHADING_RATE_SOURCE;
    public static final int RESOURCE_STATE_PRESENT = D3D12TextureBuilder.D3D12_RESOURCE_STATE_PRESENT;
    public static final int RESOURCE_STATE_PREDICATION = D3D12TextureBuilder.D3D12_RESOURCE_STATE_PREDICATION;

    // Barrier types
    public static final int BARRIER_TYPE_TRANSITION = 0;
    public static final int BARRIER_TYPE_UAV = 1;
    public static final int BARRIER_TYPE_ALIASED = 2;

    // Barrier flags
    public static final int BARRIER_FLAG_NONE = 0x0;
    public static final int BARRIER_FLAG_BEGIN_ONLY = 0x1;
    public static final int BARRIER_FLAG_END_ONLY = 0x2;
    public static final int BARRIER_FLAG_SPLIT_FRONT = BARRIER_FLAG_BEGIN_ONLY;
    public static final int BARRIER_FLAG_SPLIT_BACK = BARRIER_FLAG_END_ONLY;

    /**
     * Resource state tracking entry
     */
    private static class ResourceStateEntry {
        final long resourceHandle;
        volatile int currentState;
        final String debugName;
        volatile long lastTransitionTime;

        ResourceStateEntry(long resourceHandle, int initialState, String debugName) {
            this.resourceHandle = resourceHandle;
            this.currentState = initialState;
            this.debugName = debugName;
            this.lastTransitionTime = System.nanoTime();
        }
    }

    /**
     * Resource barrier description
     */
    public static class ResourceBarrier {
        final int barrierType;
        final long resourceHandle;
        final int stateBefore;
        final int stateAfter;
        final int subresourceIndex;
        final String debugName;

        ResourceBarrier(int barrierType, long resourceHandle, int stateBefore, int stateAfter, int subresourceIndex, String debugName) {
            this.barrierType = barrierType;
            this.resourceHandle = resourceHandle;
            this.stateBefore = stateBefore;
            this.stateAfter = stateAfter;
            this.subresourceIndex = subresourceIndex;
            this.debugName = debugName;
        }

        @Override
        public String toString() {
            return String.format("ResourceBarrier[type=%d, resource=0x%x, %s -> %s, subresource=%d, name=%s]",
                barrierType, resourceHandle, stateName(stateBefore), stateName(stateAfter), subresourceIndex, debugName);
        }
    }

    // Resource state tracking
    private final Map<Long, ResourceStateEntry> resourceStates;
    private final Map<String, ResourceStateEntry> namedResources;

    // Pending barriers
    private final List<ResourceBarrier> pendingBarriers;
    private final List<ResourceBarrier> executedBarriers;

    // Statistics
    private int totalBarriersIssued = 0;
    private int totalTransitions = 0;
    private int redundantBarriersAvoided = 0;

    public D3D12ResourceStateManager() {
        this.resourceStates = new ConcurrentHashMap<>();
        this.namedResources = new ConcurrentHashMap<>();
        this.pendingBarriers = new ArrayList<>();
        this.executedBarriers = new ArrayList<>();

        LOGGER.info("D3D12 Resource State Manager initialized");
    }

    /**
     * Register resource with initial state
     */
    public void registerResource(long resourceHandle, int initialState, String debugName) {
        if (resourceHandle == 0) {
            LOGGER.warn("Cannot register resource with null handle");
            return;
        }

        ResourceStateEntry entry = new ResourceStateEntry(resourceHandle, initialState, debugName);
        resourceStates.put(resourceHandle, entry);
        if (debugName != null) {
            namedResources.put(debugName, entry);
        }

        LOGGER.debug("Registered resource: {} in state {}", debugName, stateName(initialState));
    }

    /**
     * Register resource with name
     */
    public void registerResource(String name, long resourceHandle, int initialState) {
        registerResource(resourceHandle, initialState, name);
    }

    /**
     * Get current resource state
     */
    public int getResourceState(long resourceHandle) {
        ResourceStateEntry entry = resourceStates.get(resourceHandle);
        return entry != null ? entry.currentState : RESOURCE_STATE_COMMON;
    }

    /**
     * Get current resource state by name
     */
    public int getResourceState(String name) {
        ResourceStateEntry entry = namedResources.get(name);
        return entry != null ? entry.currentState : RESOURCE_STATE_COMMON;
    }

    /**
     * Transition resource to new state
     */
    public boolean transitionResource(long resourceHandle, int newState, String debugName) {
        return transitionResource(resourceHandle, newState, debugName, 0xFFFFFFFF);
    }

    /**
     * Transition resource to new state with subresource mask
     */
    public boolean transitionResource(long resourceHandle, int newState, String debugName, int subresourceMask) {
        ResourceStateEntry entry = resourceStates.get(resourceHandle);
        if (entry == null) {
            LOGGER.warn("Resource not registered for state transition: handle=0x{}", Long.toHexString(resourceHandle));
            registerResource(resourceHandle, newState, debugName);
            return true;
        }

        int currentState = entry.currentState;
        if (currentState == newState) {
            // No transition needed
            redundantBarriersAvoided++;
            return true;
        }

        // Create transition barrier
        ResourceBarrier barrier = new ResourceBarrier(BARRIER_TYPE_TRANSITION, resourceHandle,
                                                 currentState, newState, 0, entry.debugName);
        pendingBarriers.add(barrier);

        // Update tracked state
        entry.currentState = newState;
        entry.lastTransitionTime = System.nanoTime();

        totalTransitions++;
        LOGGER.trace("Scheduled resource transition: {} (0x{}) {} -> {}",
            entry.debugName, Long.toHexString(resourceHandle),
            stateName(currentState), stateName(newState));

        return true;
    }

    /**
     * Transition named resource to new state
     */
    public boolean transitionResource(String name, int newState) {
        ResourceStateEntry entry = namedResources.get(name);
        if (entry != null) {
            return transitionResource(entry.resourceHandle, newState, name);
        }
        LOGGER.warn("Named resource not found for state transition: {}", name);
        return false;
    }

    /**
     * UAV barrier for all UAV resources
     */
    public void uavBarrier() {
        ResourceBarrier barrier = new ResourceBarrier(BARRIER_TYPE_UAV, 0L, 0, 0, 0, "Global_UAV");
        pendingBarriers.add(barrier);

        LOGGER.trace("Scheduled global UAV barrier");
    }

    /**
     * UAV barrier for specific resource
     */
    public void uavBarrier(long resourceHandle, String debugName) {
        ResourceBarrier barrier = new ResourceBarrier(BARRIER_TYPE_UAV, resourceHandle, 0, 0, 0, debugName);
        pendingBarriers.add(barrier);

        LOGGER.trace("Scheduled UAV barrier: {}", debugName);
    }

    /**
     * Aliasing barrier for resource reuse
     */
    public void aliasingBarrier(long beforeResource, long afterResource, String debugName) {
        // Create two-part aliasing barrier
        ResourceBarrier beginBarrier = new ResourceBarrier(BARRIER_TYPE_ALIASED, beforeResource, 0, 0, BARRIER_FLAG_SPLIT_FRONT, debugName + "_begin");
        ResourceBarrier endBarrier = new ResourceBarrier(BARRIER_TYPE_ALIASED, afterResource, 0, 0, BARRIER_FLAG_SPLIT_BACK, debugName + "_end");

        pendingBarriers.add(beginBarrier);
        pendingBarriers.add(endBarrier);

        LOGGER.trace("Scheduled aliasing barrier: {} between 0x{} and 0x{}",
            debugName, Long.toHexString(beforeResource), Long.toHexString(afterResource));
    }

    /**
     * Execute all pending barriers on command list
     */
    public boolean executePendingBarriers(long commandListHandle) {
        if (pendingBarriers.isEmpty()) {
            return true;
        }

        LOGGER.debug("Executing {} pending barriers on command list: 0x{}",
            pendingBarriers.size(), Long.toHexString(commandListHandle));

        // Create native barrier array
        long[] barrierHandles = new long[pendingBarriers.size()];
        int[] barrierTypes = new int[pendingBarriers.size()];
        long[] resourceHandles = new long[pendingBarriers.size()];
        int[] statesBefore = new int[pendingBarriers.size()];
        int[] statesAfter = new int[pendingBarriers.size()];
        int[] flags = new int[pendingBarriers.size()];

        for (int i = 0; i < pendingBarriers.size(); i++) {
            ResourceBarrier barrier = pendingBarriers.get(i);
            barrierHandles[i] = 0; // Would be native barrier handle
            barrierTypes[i] = barrier.barrierType;
            resourceHandles[i] = barrier.resourceHandle;
            statesBefore[i] = barrier.stateBefore;
            statesAfter[i] = barrier.stateAfter;
            flags[i] = 0; // Would be barrier flags
        }

        boolean success = VitraD3D12Native.executeBarriers(commandListHandle, barrierTypes,
                                                         resourceHandles, statesBefore, statesAfter,
                                                         flags, barrierHandles.length);

        if (success) {
            // Move barriers to executed list
            executedBarriers.addAll(pendingBarriers);
            totalBarriersIssued += pendingBarriers.size();
            pendingBarriers.clear();

            LOGGER.debug("Successfully executed {} barriers", executedBarriers.size());
        } else {
            LOGGER.error("Failed to execute barriers");
        }

        return success;
    }

    /**
     * Clear pending barriers
     */
    public void clearPendingBarriers() {
        int clearedCount = pendingBarriers.size();
        pendingBarriers.clear();
        LOGGER.debug("Cleared {} pending barriers", clearedCount);
    }

    /**
     * Unregister resource
     */
    public void unregisterResource(long resourceHandle) {
        ResourceStateEntry entry = resourceStates.remove(resourceHandle);
        if (entry != null && entry.debugName != null) {
            namedResources.remove(entry.debugName);
        }

        LOGGER.debug("Unregistered resource: {} (0x{})",
            entry != null ? entry.debugName : "Unknown", Long.toHexString(resourceHandle));
    }

    /**
     * Unregister resource by name
     */
    public void unregisterResource(String name) {
        ResourceStateEntry entry = namedResources.remove(name);
        if (entry != null) {
            resourceStates.remove(entry.resourceHandle);
        }

        LOGGER.debug("Unregistered named resource: {}", name);
    }

    /**
     * Get state name for logging
     */
    public static String stateName(int state) {
        switch (state) {
            case RESOURCE_STATE_COMMON: return "COMMON"; // Note: PRESENT (0x0) is synonymous with COMMON
            case RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER: return "VERTEX_AND_CONSTANT_BUFFER";
            case RESOURCE_STATE_INDEX_BUFFER: return "INDEX_BUFFER";
            case RESOURCE_STATE_RENDER_TARGET: return "RENDER_TARGET";
            case RESOURCE_STATE_UNORDERED_ACCESS: return "UNORDERED_ACCESS";
            case RESOURCE_STATE_DEPTH_WRITE: return "DEPTH_WRITE";
            case RESOURCE_STATE_DEPTH_READ: return "DEPTH_READ";
            case RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE: return "NON_PIXEL_SHADER_RESOURCE";
            case RESOURCE_STATE_PIXEL_SHADER_RESOURCE: return "PIXEL_SHADER_RESOURCE";
            case RESOURCE_STATE_STREAM_OUT: return "STREAM_OUT";
            case RESOURCE_STATE_INDIRECT_ARGUMENT: return "INDIRECT_ARGUMENT"; // Note: PREDICATION (0x200) has same value
            case RESOURCE_STATE_COPY_DEST: return "COPY_DEST";
            case RESOURCE_STATE_COPY_SOURCE: return "COPY_SOURCE";
            case RESOURCE_STATE_RESOLVE_DEST: return "RESOLVE_DEST";
            case RESOURCE_STATE_RESOLVE_SOURCE: return "RESOLVE_SOURCE";
            case RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE: return "RAYTRACING_ACCELERATION_STRUCTURE";
            case RESOURCE_STATE_SHADING_RATE_SOURCE: return "SHADING_RATE_SOURCE";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /**
     * Get barrier type name for logging
     */
    private static String barrierTypeName(int barrierType) {
        switch (barrierType) {
            case BARRIER_TYPE_TRANSITION: return "TRANSITION";
            case BARRIER_TYPE_UAV: return "UAV";
            case BARRIER_TYPE_ALIASED: return "ALIASED";
            default: return "UNKNOWN(" + barrierType + ")";
        }
    }

    /**
     * Get statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Resource State Manager Statistics:\n");
        stats.append("  Total Resources Tracked: ").append(resourceStates.size()).append("\n");
        stats.append("  Named Resources Tracked: ").append(namedResources.size()).append("\n");
        stats.append("  Pending Barriers: ").append(pendingBarriers.size()).append("\n");
        stats.append("  Total Barriers Issued: ").append(totalBarriersIssued).append("\n");
        stats.append("  Total Transitions: ").append(totalTransitions).append("\n");
        stats.append("  Redundant Barriers Avoided: ").append(redundantBarriersAvoided).append("\n");

        // Count resources by state
        Map<Integer, Integer> stateCounts = new HashMap<>();
        for (ResourceStateEntry entry : resourceStates.values()) {
            stateCounts.merge(entry.currentState, 1, Integer::sum);
        }

        stats.append("\n--- Resources by State ---\n");
        for (Map.Entry<Integer, Integer> entry : stateCounts.entrySet()) {
            String stateName = stateName(entry.getKey());
            stats.append("  ").append(stateName).append(": ").append(entry.getValue()).append("\n");
        }

        // Recent barriers
        stats.append("\n--- Recent Barriers (Last 10) ---\n");
        int start = Math.max(0, executedBarriers.size() - 10);
        for (int i = start; i < executedBarriers.size(); i++) {
            ResourceBarrier barrier = executedBarriers.get(i);
            stats.append("  ").append(barrier.toString()).append("\n");
        }

        return stats.toString();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        LOGGER.info("Cleaning up D3D12 Resource State Manager");

        // Clear all tracking
        resourceStates.clear();
        namedResources.clear();
        pendingBarriers.clear();
        executedBarriers.clear();

        // Reset statistics
        totalBarriersIssued = 0;
        totalTransitions = 0;
        redundantBarriersAvoided = 0;

        LOGGER.info("D3D12 Resource State Manager cleanup completed");
    }
}