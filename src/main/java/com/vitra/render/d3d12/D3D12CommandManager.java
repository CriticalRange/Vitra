package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DirectX 12 Command Manager inspired by VulkanMod's command buffer management
 * Provides command list and allocator management with frame-based recycling
 */
public class D3D12CommandManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12CommandManager");

    // Command list types
    public static final int COMMAND_LIST_TYPE_DIRECT = 0;
    public static final int COMMAND_LIST_TYPE_BUNDLE = 1;
    public static final int COMMAND_LIST_TYPE_COMPUTE = 2;
    public static final int COMMAND_LIST_TYPE_COPY = 3;
    public static final int COMMAND_LIST_TYPE_VIDEO_DECODE = 4;
    public static final int COMMAND_LIST_TYPE_VIDEO_PROCESS = 5;
    public static final int COMMAND_LIST_TYPE_VIDEO_ENCODE = 6;

    // Command execution states
    public static final int COMMAND_STATE_INITIALIZED = 0;
    public static final int COMMAND_STATE_RECORDING = 1;
    public static final int COMMAND_STATE_CLOSED = 2;
    public static final int COMMAND_STATE_EXECUTING = 3;
    public static final int COMMAND_STATE_PENDING = 4;

    // Frame configuration
    private static final int FRAME_COUNT = 3;
    private static final int COMMAND_ALLOCATORS_PER_TYPE = 4;
    private static final int MAX_COMMAND_LISTS_PER_FRAME = 64;

    /**
     * Command allocator wrapper
     */
    public static class CommandAllocator {
        private final long handle;
        private final int commandListType;
        private final int frameIndex;
        private volatile boolean inUse;
        private long resetTime;
        private final String debugName;

        public CommandAllocator(long handle, int commandListType, int frameIndex, String debugName) {
            this.handle = handle;
            this.commandListType = commandListType;
            this.frameIndex = frameIndex;
            this.inUse = false;
            this.resetTime = 0;
            this.debugName = debugName;
        }

        public long getHandle() { return handle; }
        public int getCommandListType() { return commandListType; }
        public int getFrameIndex() { return frameIndex; }
        public boolean isInUse() { return inUse; }
        public long getResetTime() { return resetTime; }
        public String getDebugName() { return debugName; }

        public void markInUse() {
            this.inUse = true;
        }

        public void markAvailable() {
            this.inUse = false;
            this.resetTime = System.nanoTime();
        }
    }

    /**
     * Command list wrapper
     */
    public static class CommandList {
        private final long handle;
        private final int commandListType;
        private CommandAllocator allocator; // Not final - allocator can be reassigned when command list is reset/reused
        private volatile int state;
        private final long creationTime;
        private final String debugName;
        private final AtomicInteger resourceBarriersCount = new AtomicInteger(0);
        private final AtomicInteger drawCallsCount = new AtomicInteger(0);
        private final AtomicInteger dispatchCallsCount = new AtomicInteger(0);

        public CommandList(long handle, int commandListType, CommandAllocator allocator, String debugName) {
            this.handle = handle;
            this.commandListType = commandListType;
            this.allocator = allocator;
            this.state = COMMAND_STATE_INITIALIZED;
            this.creationTime = System.nanoTime();
            this.debugName = debugName;
        }

        public long getHandle() { return handle; }
        public int getCommandListType() { return commandListType; }
        public CommandAllocator getAllocator() { return allocator; }
        public int getState() { return state; }
        public long getCreationTime() { return creationTime; }
        public String getDebugName() { return debugName; }
        public int getResourceBarriersCount() { return resourceBarriersCount.get(); }
        public int getDrawCallsCount() { return drawCallsCount.get(); }
        public int getDispatchCallsCount() { return dispatchCallsCount.get(); }

        public void setState(int newState) {
            this.state = newState;
        }

        public void incrementResourceBarriers() {
            resourceBarriersCount.incrementAndGet();
        }

        public void incrementDrawCalls() {
            drawCallsCount.incrementAndGet();
        }

        public void incrementDispatchCalls() {
            dispatchCallsCount.incrementAndGet();
        }
    }

    // Command queue handles
    private long graphicsQueue = 0;
    private long computeQueue = 0;
    private long copyQueue = 0;

    // Command allocators (per frame, per type)
    private final CommandAllocator[][] commandAllocators;
    private volatile int currentFrameIndex = 0;

    // Command list pools
    private final ConcurrentLinkedQueue<CommandList> directCommandListPool;
    private final ConcurrentLinkedQueue<CommandList> computeCommandListPool;
    private final ConcurrentLinkedQueue<CommandList> copyCommandListPool;

    // Statistics
    private final AtomicLong totalCommandListsCreated = new AtomicLong(0);
    private final AtomicLong totalCommandListsReused = new AtomicLong(0);
    private final AtomicLong totalResourceBarriers = new AtomicLong(0);
    private final AtomicLong totalDrawCalls = new AtomicLong(0);
    private final AtomicLong totalDispatchCalls = new AtomicLong(0);

    public D3D12CommandManager() {
        // Initialize command allocator arrays
        this.commandAllocators = new CommandAllocator[FRAME_COUNT][COMMAND_ALLOCATORS_PER_TYPE];

        // Initialize command list pools
        this.directCommandListPool = new ConcurrentLinkedQueue<>();
        this.computeCommandListPool = new ConcurrentLinkedQueue<>();
        this.copyCommandListPool = new ConcurrentLinkedQueue<>();

        initializeCommandQueues();
        initializeCommandAllocators();

        LOGGER.info("D3D12 Command Manager initialized with {} frames", FRAME_COUNT);
    }

    /**
     * Initialize command queues
     */
    private void initializeCommandQueues() {
        LOGGER.info("Initializing D3D12 command queues");

        // Create graphics queue
        graphicsQueue = VitraD3D12Native.createCommandQueue(COMMAND_LIST_TYPE_DIRECT, "Graphics Queue");
        if (graphicsQueue == 0) {
            throw new RuntimeException("Failed to create graphics command queue");
        }

        // Create compute queue
        computeQueue = VitraD3D12Native.createCommandQueue(COMMAND_LIST_TYPE_COMPUTE, "Compute Queue");
        if (computeQueue == 0) {
            LOGGER.warn("Failed to create compute command queue, using graphics queue");
            computeQueue = graphicsQueue;
        }

        // Create copy queue
        copyQueue = VitraD3D12Native.createCommandQueue(COMMAND_LIST_TYPE_COPY, "Copy Queue");
        if (copyQueue == 0) {
            LOGGER.warn("Failed to create copy queue, using graphics queue");
            copyQueue = graphicsQueue;
        }

        LOGGER.info("Command queues created: Graphics=0x{}, Compute=0x{}, Copy=0x{}",
            Long.toHexString(graphicsQueue), Long.toHexString(computeQueue), Long.toHexString(copyQueue));
    }

    /**
     * Initialize command allocators
     */
    private void initializeCommandAllocators() {
        LOGGER.info("Initializing D3D12 command allocators");

        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            for (int type = 0; type < 3; type++) { // Direct, Compute, Copy
                for (int i = 0; i < COMMAND_ALLOCATORS_PER_TYPE; i++) {
                    int commandListType = getCommandListTypeForIndex(type);
                    String debugName = String.format("Allocator[%d][%d][%d] - %s", frame, type, i, getCommandListTypeName(commandListType));

                    long handle = VitraD3D12Native.createCommandAllocator(commandListType, debugName);
                    if (handle == 0) {
                        LOGGER.error("Failed to create command allocator: {}", debugName);
                        continue;
                    }

                    CommandAllocator allocator = new CommandAllocator(handle, commandListType, frame, debugName);
                    commandAllocators[frame][type * COMMAND_ALLOCATORS_PER_TYPE + i] = allocator;
                }
            }
        }

        LOGGER.info("Command allocators initialized: {} total", FRAME_COUNT * 3 * COMMAND_ALLOCATORS_PER_TYPE);
    }

    /**
     * Get command list type for array index
     */
    private int getCommandListTypeForIndex(int index) {
        switch (index) {
            case 0: return COMMAND_LIST_TYPE_DIRECT;
            case 1: return COMMAND_LIST_TYPE_COMPUTE;
            case 2: return COMMAND_LIST_TYPE_COPY;
            default: return COMMAND_LIST_TYPE_DIRECT;
        }
    }

    /**
     * Get command list type name for logging
     */
    private static String getCommandListTypeName(int commandListType) {
        switch (commandListType) {
            case COMMAND_LIST_TYPE_DIRECT: return "Direct";
            case COMMAND_LIST_TYPE_BUNDLE: return "Bundle";
            case COMMAND_LIST_TYPE_COMPUTE: return "Compute";
            case COMMAND_LIST_TYPE_COPY: return "Copy";
            case COMMAND_LIST_TYPE_VIDEO_DECODE: return "VideoDecode";
            case COMMAND_LIST_TYPE_VIDEO_PROCESS: return "VideoProcess";
            case COMMAND_LIST_TYPE_VIDEO_ENCODE: return "VideoEncode";
            default: return "Unknown(" + commandListType + ")";
        }
    }

    /**
     * Get available command allocator
     */
    private CommandAllocator getAvailableCommandAllocator(int commandListType) {
        int typeIndex = getTypeIndexForCommandListType(commandListType);
        int frameIndex = currentFrameIndex;

        // Try to find an available allocator for current frame
        for (int i = 0; i < COMMAND_ALLOCATORS_PER_TYPE; i++) {
            CommandAllocator allocator = commandAllocators[frameIndex][typeIndex * COMMAND_ALLOCATORS_PER_TYPE + i];
            if (allocator != null && !allocator.isInUse()) {
                allocator.markInUse();
                return allocator;
            }
        }

        // No available allocator found
        LOGGER.error("No available command allocator for type {} in frame {}", getCommandListTypeName(commandListType), frameIndex);
        return null;
    }

    /**
     * Get type index for command list type
     */
    private int getTypeIndexForCommandListType(int commandListType) {
        switch (commandListType) {
            case COMMAND_LIST_TYPE_DIRECT: return 0;
            case COMMAND_LIST_TYPE_COMPUTE: return 1;
            case COMMAND_LIST_TYPE_COPY: return 2;
            default: return 0;
        }
    }

    /**
     * Get command queue handle
     */
    private long getCommandQueue(int commandListType) {
        switch (commandListType) {
            case COMMAND_LIST_TYPE_DIRECT:
            case COMMAND_LIST_TYPE_BUNDLE:
                return graphicsQueue;
            case COMMAND_LIST_TYPE_COMPUTE:
                return computeQueue;
            case COMMAND_LIST_TYPE_COPY:
                return copyQueue;
            default:
                return graphicsQueue;
        }
    }

    /**
     * Begin new frame
     */
    public void beginFrame() {
        currentFrameIndex = (currentFrameIndex + 1) % FRAME_COUNT;
        LOGGER.trace("Beginning frame: {}", currentFrameIndex);

        // Mark all command allocators for current frame as available
        int frameIndex = currentFrameIndex;
        for (int type = 0; type < 3; type++) {
            for (int i = 0; i < COMMAND_ALLOCATORS_PER_TYPE; i++) {
                CommandAllocator allocator = commandAllocators[frameIndex][type * COMMAND_ALLOCATORS_PER_TYPE + i];
                if (allocator != null) {
                    allocator.markAvailable();
                }
            }
        }
    }

    /**
     * Create new command list
     */
    public CommandList createCommandList(int commandListType, String debugName) {
        CommandAllocator allocator = getAvailableCommandAllocator(commandListType);
        if (allocator == null) {
            return null;
        }

        // Try to reuse from pool
        CommandList reused = getFromPool(commandListType);
        if (reused != null) {
            // Reset and reuse the command list
            VitraD3D12Native.resetCommandList(reused.getHandle(), allocator.getHandle());
            reused.allocator = allocator;
            reused.setState(COMMAND_STATE_INITIALIZED);
            reused.resourceBarriersCount.set(0);
            reused.drawCallsCount.set(0);
            reused.dispatchCallsCount.set(0);

            totalCommandListsReused.incrementAndGet();
            LOGGER.debug("Reused command list: {}", reused.getDebugName());
            return reused;
        }

        // Create new command list
        long handle = VitraD3D12Native.createCommandList(commandListType, allocator.getHandle(), debugName);
        if (handle == 0) {
            LOGGER.error("Failed to create command list: {}", debugName);
            return null;
        }

        CommandList commandList = new CommandList(handle, commandListType, allocator, debugName);
        commandList.setState(COMMAND_STATE_INITIALIZED);

        totalCommandListsCreated.incrementAndGet();
        LOGGER.debug("Created new command list: {}", debugName);

        return commandList;
    }

    /**
     * Get command list from pool
     */
    private CommandList getFromPool(int commandListType) {
        switch (commandListType) {
            case COMMAND_LIST_TYPE_DIRECT:
                return directCommandListPool.poll();
            case COMMAND_LIST_TYPE_COMPUTE:
                return computeCommandListPool.poll();
            case COMMAND_LIST_TYPE_COPY:
                return copyCommandListPool.poll();
            default:
                return null;
        }
    }

    /**
     * Return command list to pool
     */
    private void returnToPool(CommandList commandList) {
        switch (commandList.getCommandListType()) {
            case COMMAND_LIST_TYPE_DIRECT:
                directCommandListPool.offer(commandList);
                break;
            case COMMAND_LIST_TYPE_COMPUTE:
                computeCommandListPool.offer(commandList);
                break;
            case COMMAND_LIST_TYPE_COPY:
                copyCommandListPool.offer(commandList);
                break;
        }
    }

    /**
     * Execute command list
     */
    public boolean executeCommandList(CommandList commandList) {
        if (commandList == null || commandList.getState() != COMMAND_STATE_CLOSED) {
            LOGGER.warn("Cannot execute command list: invalid state");
            return false;
        }

        long queueHandle = getCommandQueue(commandList.getCommandListType());
        boolean success = VitraD3D12Native.executeCommandList(queueHandle, commandList.getHandle());

        if (success) {
            commandList.setState(COMMAND_STATE_EXECUTING);

            // Update statistics
            totalResourceBarriers.addAndGet(commandList.getResourceBarriersCount());
            totalDrawCalls.addAndGet(commandList.getDrawCallsCount());
            totalDispatchCalls.addAndGet(commandList.getDispatchCallsCount());

            LOGGER.debug("Executed command list: {} on {} queue", commandList.getDebugName(), getCommandListTypeName(commandList.getCommandListType()));
        } else {
            LOGGER.error("Failed to execute command list: {}", commandList.getDebugName());
        }

        return success;
    }

    /**
     * Execute multiple command lists
     */
    public boolean executeCommandLists(CommandList[] commandLists) {
        if (commandLists == null || commandLists.length == 0) {
            return true;
        }

        // Verify all command lists are closed
        for (CommandList commandList : commandLists) {
            if (commandList == null || commandList.getState() != COMMAND_STATE_CLOSED) {
                LOGGER.warn("Cannot execute command list: invalid state");
                return false;
            }
        }

        // Use first command list's type to determine queue
        int commandListType = commandLists[0].getCommandListType();
        long queueHandle = getCommandQueue(commandListType);

        long[] handles = new long[commandLists.length];
        for (int i = 0; i < commandLists.length; i++) {
            handles[i] = commandLists[i].getHandle();
            commandLists[i].setState(COMMAND_STATE_EXECUTING);

            // Update statistics
            totalResourceBarriers.addAndGet(commandLists[i].getResourceBarriersCount());
            totalDrawCalls.addAndGet(commandLists[i].getDrawCallsCount());
            totalDispatchCalls.addAndGet(commandLists[i].getDispatchCallsCount());
        }

        boolean success = VitraD3D12Native.executeCommandLists(queueHandle, handles);

        if (success) {
            LOGGER.debug("Executed {} command lists on {} queue", commandLists.length, getCommandListTypeName(commandListType));
        } else {
            LOGGER.error("Failed to execute {} command lists", commandLists.length);
        }

        return success;
    }

    /**
     * Return command list to pool after execution completes
     */
    public void returnCommandList(CommandList commandList) {
        if (commandList == null) {
            return;
        }

        // Return to pool if valid
        if (commandList.getHandle() != 0) {
            returnToPool(commandList);
            LOGGER.trace("Returned command list to pool: {}", commandList.getDebugName());
        }
    }

    /**
     * Get current frame index
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex;
    }

    /**
     * Get command queue handle
     */
    public long getGraphicsQueue() {
        return graphicsQueue;
    }

    public long getComputeQueue() {
        return computeQueue;
    }

    public long getCopyQueue() {
        return copyQueue;
    }

    /**
     * Wait for GPU to complete all work
     */
    public void waitForGpu() {
        LOGGER.debug("Waiting for GPU completion");

        // Wait on all queues
        VitraD3D12Native.waitForGpuCommands();
    }

    /**
     * Get statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("D3D12 Command Manager Statistics:\n");
        stats.append("  Current Frame: ").append(currentFrameIndex).append("\n");
        stats.append("  Total Command Lists Created: ").append(totalCommandListsCreated.get()).append("\n");
        stats.append("  Total Command Lists Reused: ").append(totalCommandListsReused.get()).append("\n");
        stats.append("  Total Resource Barriers: ").append(totalResourceBarriers.get()).append("\n");
        stats.append("  Total Draw Calls: ").append(totalDrawCalls.get()).append("\n");
        stats.append("  Total Dispatch Calls: ").append(totalDispatchCalls.get()).append("\n");

        stats.append("\n--- Command List Pool Sizes ---\n");
        stats.append("  Direct Pool: ").append(directCommandListPool.size()).append("\n");
        stats.append("  Compute Pool: ").append(computeCommandListPool.size()).append("\n");
        stats.append("  Copy Pool: ").append(copyCommandListPool.size()).append("\n");

        stats.append("\n--- Command Queues ---\n");
        stats.append("  Graphics Queue: 0x").append(Long.toHexString(graphicsQueue)).append("\n");
        stats.append("  Compute Queue: 0x").append(Long.toHexString(computeQueue)).append("\n");
        stats.append("  Copy Queue: 0x").append(Long.toHexString(copyQueue)).append("\n");

        return stats.toString();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        LOGGER.info("Cleaning up D3D12 Command Manager");

        // Clear pools
        directCommandListPool.clear();
        computeCommandListPool.clear();
        copyCommandListPool.clear();

        // Release command allocators
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            for (int type = 0; type < 3; type++) {
                for (int i = 0; i < COMMAND_ALLOCATORS_PER_TYPE; i++) {
                    CommandAllocator allocator = commandAllocators[frame][type * COMMAND_ALLOCATORS_PER_TYPE + i];
                    if (allocator != null && allocator.getHandle() != 0) {
                        VitraD3D12Native.releaseManagedResource(allocator.getHandle());
                    }
                }
            }
        }

        // Release command queues
        if (graphicsQueue != 0) {
            VitraD3D12Native.releaseManagedResource(graphicsQueue);
        }
        if (computeQueue != 0 && computeQueue != graphicsQueue) {
            VitraD3D12Native.releaseManagedResource(computeQueue);
        }
        if (copyQueue != 0 && copyQueue != graphicsQueue && copyQueue != computeQueue) {
            VitraD3D12Native.releaseManagedResource(copyQueue);
        }

        LOGGER.info("D3D12 Command Manager cleanup completed");
    }
}