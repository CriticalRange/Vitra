package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.render.jni.VitraD3D12Renderer;
import com.vitra.render.jni.VitraD3D12Native;
import com.vitra.render.jni.D3D12ShaderManager;
import com.vitra.render.jni.D3D12BufferManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 12 Ultimate renderer implementation that wraps VitraD3D12JNI
 * Implements IVitraRenderer interface for compatibility with the renderer system
 */
public class DirectX12Renderer extends AbstractRenderer {
    // DirectX 12 components
    private D3D12ShaderManager shaderManager;
    private D3D12BufferManager bufferManager;

    public DirectX12Renderer() {
        super(DirectX12Renderer.class);
    }

    @Override
    public void initialize() {
        initialize(RendererType.DIRECTX12_ULTIMATE);
    }

    @Override
    public void initialize(RendererType rendererType) {
        if (initialized) {
            logger.warn("DirectX 12 renderer already initialized");
            return;
        }

        logger.info("Initializing DirectX 12 Ultimate renderer (deferred until window handle available)");

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException("DirectX 12 Ultimate is not supported on this platform. Requires Windows 10+ with compatible hardware.");
            }

            // Initialize DirectX 12 components
            shaderManager = new D3D12ShaderManager();
            bufferManager = new D3D12BufferManager();

            initialized = true;
            logger.info("DirectX 12 renderer prepared, native DirectX 12 will initialize when window handle is available");

        } catch (Exception e) {
            logger.error("Failed to prepare DirectX 12 renderer", e);
            throw new RuntimeException("DirectX 12 renderer initialization failed", e);
        }
    }

    @Override
    public boolean initializeWithWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        logger.info("DirectX 12 window handle set: 0x{}", Long.toHexString(windowHandle));

        // Actually initialize native DirectX 12 with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug and verbose mode from config
                boolean debugMode = isDebugMode();
                boolean verboseMode = isVerboseMode();

                logger.info("Initializing native DirectX 12 Ultimate with debug={}, verbose={}", debugMode, verboseMode);

                // Convert GLFW window handle to Win32 HWND
                long nativeWindowHandle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(windowHandle);
                if (nativeWindowHandle == 0) {
                    logger.error("Failed to get Win32 HWND from GLFW window");
                    return false;
                }
                logger.debug("Converted GLFW handle 0x{} to Win32 HWND 0x{}",
                    Long.toHexString(windowHandle), Long.toHexString(nativeWindowHandle));

                // Initialize DirectX 12 with window handle and dimensions
                // TODO: Get actual window dimensions from Minecraft
                int width = 1920;  // Default width
                int height = 1080; // Default height
                boolean success = VitraD3D12Native.initializeDirectX12(nativeWindowHandle, width, height, debugMode);
                if (success) {
                    logger.info("Native DirectX 12 Ultimate initialized successfully (debug={})", debugMode);

                    // Initialize managers with modern DirectX 12 Ultimate support
                    if (shaderManager != null) {
                        shaderManager.initialize();
                        shaderManager.preloadMinecraftShaders();
                        logger.info("DirectX 12 Ultimate shader manager initialized: {}", shaderManager.getCacheStats());
                    }

                    if (bufferManager != null) {
                        bufferManager.initialize();
                        logger.info("DirectX 12 Ultimate buffer manager initialized: {}", bufferManager.getBufferStats());
                    }

                    // Load shaders after native initialization
                    if (core != null) {
                        logger.info("Loading custom shaders...");
                        core.loadShaders();
                    } else {
                        logger.warn("VitraCore not set, skipping custom shader loading");
                    }

                    return true;
                } else {
                    logger.error("Native DirectX 12 Ultimate initialization failed");
                    return false;
                }
            } catch (Exception e) {
                logger.error("Exception during native DirectX 12 Ultimate initialization", e);
                return false;
            }
        }
        return false;
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        logger.info("Shutting down DirectX 12 Ultimate renderer...");

        try {
            // Clear shader and buffer caches
            if (shaderManager != null) {
                shaderManager.shutdown();
                logger.info("DirectX 12 shader manager shutdown");
            }

            if (bufferManager != null) {
                bufferManager.shutdown();
                logger.info("DirectX 12 buffer manager shutdown");
            }

            // Shutdown native renderer
            VitraD3D12Native.shutdown();
            logger.info("Native DirectX 12 Ultimate shutdown completed");

        } catch (Exception e) {
            logger.error("Exception during shutdown", e);
        }

        initialized = false;
        windowHandle = 0L;
        logger.info("DirectX 12 renderer shutdown complete");
    }

    @Override
    public boolean isInitialized() {
        // For deferred initialization: return true if prepared
        // Full DirectX 12 initialization happens in initializeWithWindowHandle()
        return initialized;
    }

    /**
     * Check if DirectX 12 is fully initialized (has window handle and native context)
     */
    public boolean isFullyInitialized() {
        return initialized && windowHandle != 0L && VitraD3D12Renderer.isInitializedStatic();
    }

    @Override
    public RendererType getRendererType() {
        return RendererType.DIRECTX12_ULTIMATE;
    }

    @Override
    public void beginFrame() {
        if (isInitialized()) {
            VitraD3D12Native.beginFrame();
        }
    }

    @Override
    public void endFrame() {
        if (isInitialized()) {
            VitraD3D12Native.endFrame();
        }
    }

    @Override
    public void present() {
        if (isInitialized()) {
            VitraD3D12Native.present();
        }
    }

    @Override
    public void resize(int width, int height) {
        if (isInitialized()) {
            VitraD3D12Native.resize(width, height);
            logger.info("DirectX 12 view resized to {}x{}", width, height);
        }
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        if (isInitialized()) {
            VitraD3D12Native.clear(r, g, b, a);
        }
    }

    // DirectX 12 Ultimate features
    @Override
    public boolean isRayTracingSupported() {
        return VitraD3D12Native.isRaytracingSupported();
    }

    @Override
    public boolean isVariableRateShadingSupported() {
        return VitraD3D12Renderer.isVariableRateShadingSupportedStatic();
    }

    @Override
    public boolean isMeshShadersSupported() {
        return VitraD3D12Renderer.isMeshShadersSupportedStatic();
    }

    @Override
    public boolean isGpuDrivenRenderingSupported() {
        return true; // DirectX 12 supports GPU-driven rendering
    }

    // Performance metrics from DirectX 12
    @Override
    public float getGpuUtilization() {
        return VitraD3D12Renderer.getGpuUtilizationStatic();
    }

    @Override
    public long getFrameTime() {
        return VitraD3D12Renderer.getFrameTimeStatic();
    }

    @Override
    public int getDrawCallsPerFrame() {
        return VitraD3D12Renderer.getDrawCallsPerFrameStatic();
    }

    @Override
    public void resetPerformanceCounters() {
        VitraD3D12Renderer.resetPerformanceCountersStatic();
    }

    @Override
    public void setDebugMode(boolean enabled) {
        VitraD3D12Renderer.setDebugModeStatic(enabled);
    }

    @Override
    public void captureFrame(String filename) {
        VitraD3D12Renderer.captureFrameStatic(filename);
    }

    @Override
    public String getRendererStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX 12 Ultimate Renderer Stats ===\n");
        stats.append("Initialized: ").append(isInitialized()).append("\n");
        stats.append("Window Handle: 0x").append(Long.toHexString(windowHandle)).append("\n");
        stats.append("Debug Mode: ").append(config != null ? config.isDebugMode() : false).append("\n");
        stats.append("Verbose Logging: ").append(config != null ? config.isVerboseLogging() : false).append("\n");

        // Include DirectX 12 Ultimate features
        stats.append("\n--- DirectX 12 Ultimate Features ---\n");
        stats.append("Ray Tracing: ").append(VitraD3D12Native.isRaytracingSupported()).append("\n");
        stats.append("Variable Rate Shading: ").append(VitraD3D12Renderer.isVariableRateShadingSupportedStatic()).append("\n");
        stats.append("Mesh Shaders: ").append(VitraD3D12Renderer.isMeshShadersSupportedStatic()).append("\n");

        // Include DirectStorage features
        if (VitraD3D12Renderer.isDirectStorageSupported()) {
            stats.append("\n--- DirectStorage ---\n");
            stats.append(VitraD3D12Renderer.getDirectStorageStats());
        }

        // Include D3D12MA features
        if (VitraD3D12Renderer.isD3D12MASupported()) {
            stats.append("\n--- D3D12 Memory Allocator ---\n");
            stats.append(VitraD3D12Renderer.getD3D12MAStats());
        }

        // Include performance metrics
        stats.append("\n--- Performance Metrics ---\n");
        stats.append("GPU Utilization: ").append(String.format("%.1f%%", getGpuUtilization() * 100)).append("\n");
        stats.append("Frame Time: ").append(getFrameTime()).append(" ms\n");
        stats.append("Draw Calls: ").append(getDrawCallsPerFrame()).append("\n");

        // Include debug stats if available
        if (VitraD3D12Renderer.isInitializedStatic()) {
            stats.append("\n--- Debug System ---\n");
            stats.append(VitraD3D12Renderer.getDebugStatsStatic());
        }

        // Include component stats
        if (shaderManager != null) {
            stats.append("\n--- Shader Manager ---\n");
            stats.append(shaderManager.getCacheStats()).append("\n");
        }

        if (bufferManager != null) {
            stats.append("\n--- Buffer Manager ---\n");
            stats.append(bufferManager.getBufferStats()).append("\n");
        }

        return stats.toString();
    }

    // Feature access methods
    @Override
    public VitraRenderer getDirectX11Renderer() {
        return null; // This is the DirectX 12 renderer
    }

    @Override
    public com.vitra.render.jni.VitraD3D12Renderer getDirectX12Renderer() {
        return VitraD3D12Renderer.isInitializedStatic() ? null : null; // Static class, return null for now
    }

    @Override
    public Object getShaderManager() {
        return shaderManager;
    }

    @Override
    public Object getBufferManager() {
        return bufferManager;
    }

    @Override
    public long getNativeHandle() {
        // Return the native DirectX 12 device handle for debugging and verification
        if (isInitialized()) {
            return VitraD3D12Renderer.getNativeDeviceHandle();
        }
        return 0L;
    }

    // DirectX 12 specific methods for advanced features
    public boolean enableRayTracing(int quality) {
        return VitraD3D12Renderer.enableRayTracing(quality);
    }

    public void disableRayTracing() {
        VitraD3D12Renderer.disableRayTracing();
    }

    public boolean enableVariableRateShading(int tileSize) {
        return VitraD3D12Renderer.enableVariableRateShading(tileSize);
    }

    public void disableVariableRateShading() {
        VitraD3D12Renderer.disableVariableRateShading();
    }

    // DirectStorage feature methods
    public boolean isDirectStorageSupported() {
        return VitraD3D12Renderer.isDirectStorageSupported();
    }

    public boolean isHardwareDecompressionSupported() {
        return VitraD3D12Renderer.isHardwareDecompressionSupported();
    }

    public long openStorageFile(String filename) {
        return VitraD3D12Renderer.openStorageFile(filename);
    }

    public long enqueueReadRequest(long fileHandle, long offset, long size, long destination, long requestTag) {
        return VitraD3D12Renderer.enqueueReadRequest(fileHandle, offset, size, destination, requestTag);
    }

    public void processStorageQueue() {
        VitraD3D12Renderer.processStorageQueue();
    }

    public boolean isStorageQueueEmpty() {
        return VitraD3D12Renderer.isStorageQueueEmpty();
    }

    public void closeStorageFile(long fileHandle) {
        VitraD3D12Renderer.closeStorageFile(fileHandle);
    }

    public void setStoragePriority(boolean realtimePriority) {
        VitraD3D12Renderer.setStoragePriority(realtimePriority);
    }

    public String getDirectStorageStats() {
        return VitraD3D12Renderer.getDirectStorageStats();
    }

    public float getStorageUtilization() {
        return VitraD3D12Renderer.getStorageUtilization();
    }

    public long getStorageThroughput() {
        return VitraD3D12Renderer.getStorageThroughput();
    }

    // D3D12 Memory Allocator (D3D12MA) feature methods
    public boolean isD3D12MASupported() {
        return VitraD3D12Renderer.isD3D12MASupported();
    }

    public boolean enableMemoryBudgeting(boolean enable) {
        return VitraD3D12Renderer.enableMemoryBudgeting(enable);
    }

    public long createManagedBuffer(byte[] data, int size, int stride, int heapType, int allocationFlags) {
        return VitraD3D12Renderer.createManagedBuffer(data, size, stride, heapType, allocationFlags);
    }

    public long createManagedTexture(byte[] data, int width, int height, int format, int heapType, int allocationFlags) {
        return VitraD3D12Renderer.createManagedTexture(data, width, height, format, heapType, allocationFlags);
    }

    public long createManagedUploadBuffer(int size) {
        return VitraD3D12Renderer.createManagedUploadBuffer(size);
    }

    public void releaseManagedResource(long handle) {
        VitraD3D12Renderer.releaseManagedResource(handle);
    }

    public String getMemoryStatistics() {
        return VitraD3D12Renderer.getMemoryStatistics();
    }

    public String getPoolStatistics(int poolType) {
        return VitraD3D12Renderer.getPoolStatistics(poolType);
    }

    public boolean beginDefragmentation() {
        return VitraD3D12Renderer.beginDefragmentation();
    }

    public boolean validateAllocation(long handle) {
        return VitraD3D12Renderer.validateAllocation(handle);
    }

    public String getAllocationInfo(long handle) {
        return VitraD3D12Renderer.getAllocationInfo(handle);
    }

    public void setResourceDebugName(long handle, String name) {
        VitraD3D12Renderer.setResourceDebugName(handle, name);
    }

    public boolean checkMemoryBudget(long requiredSize, int heapType) {
        return VitraD3D12Renderer.checkMemoryBudget(requiredSize, heapType);
    }

    public void dumpMemoryToJson() {
        VitraD3D12Renderer.dumpMemoryToJson();
    }

    public String getD3D12MAStats() {
        return VitraD3D12Renderer.getD3D12MAStats();
    }

    public void processDebugMessages() {
        VitraD3D12Renderer.processDebugMessages();
    }

    // Additional methods needed by mixins (interface implementation)
    @Override
    public void drawMesh(Object vertexBuffer, Object indexBuffer, Object mode, Object format, int vertexCount) {
        if (isInitialized()) {
            // DirectX 12 implementation would use command lists
            logger.debug("DirectX 12 drawMesh called - would use command lists");
        }
    }

    @Override
    public void clearDepthBuffer() {
        if (isInitialized()) {
            VitraD3D12Renderer.clearDepthBufferStatic();
        }
    }

    @Override
    public void waitForGpuCommands() {
        if (isInitialized()) {
            VitraD3D12Renderer.waitForGpuCommandsStatic();
        }
    }
}