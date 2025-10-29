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
 * D3D12 Ultimate renderer implementation that wraps VitraD3D12JNI
 * Implements IVitraRenderer interface for compatibility with the renderer system
 */
public class D3D12Renderer extends AbstractRenderer {
    // D3D12 components
    private D3D12ShaderManager shaderManager;
    private D3D12BufferManager bufferManager;

    // Uniform dirty flag for tracking when uniforms need to be re-uploaded
    private boolean uniformsDirty = false;

    public D3D12Renderer() {
        super(D3D12Renderer.class);
    }

    @Override
    public void initialize() {
        initialize(RendererType.DIRECTX12);
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
        return RendererType.DIRECTX12;
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
    public VitraRenderer getD3D11Renderer() {
        return null; // This is the D3D12 renderer
    }

    @Override
    public com.vitra.render.jni.VitraD3D12Renderer getD3D12Renderer() {
        return null; // TODO: Implement proper D3D12 renderer instance management
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

    // Mixin compatibility methods - wrappers for native D3D12 calls

    /**
     * Set viewport with default depth range (0.0 to 1.0)
     * Wrapper for DirectX 12 setViewport with default depth
     */
    public void setViewport(int x, int y, int width, int height) {
        if (isInitialized()) {
            VitraD3D12Native.setViewport(x, y, width, height, 0.0f, 1.0f);
        }
    }

    /**
     * Set rasterizer state with simplified parameters
     * Wrapper for DirectX 12 setRasterizerState
     *
     * @param cullMode 0=NONE, 1=FRONT, 2=BACK
     * @param fillMode 0=WIREFRAME, 1=SOLID
     * @param frontCCW true if front face is counter-clockwise
     */
    public void setRasterizerState(int cullMode, int fillMode, boolean frontCCW) {
        if (isInitialized()) {
            VitraD3D12Native.setRasterizerState(
                fillMode, cullMode, frontCCW,
                0, 0.0f, 0.0f, // depth bias parameters (disabled)
                true, false, false, // depth clip, multisample, AA lines
                0, false // forced sample count, conservative raster
            );
        }
    }

    /**
     * Set scissor rectangle for clipping
     * Wrapper for DirectX 12 setScissorRect
     */
    public void setScissorRect(int x, int y, int width, int height) {
        if (isInitialized()) {
            VitraD3D12Native.setScissorRect(x, y, width, height);
        }
    }

    /**
     * Set primitive topology
     * Wrapper for DirectX 12 setPrimitiveTopology
     *
     * @param glTopology OpenGL/DirectX topology constant (e.g., GL_TRIANGLES = 4 = D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST)
     */
    public void setPrimitiveTopology(int glTopology) {
        if (isInitialized()) {
            VitraD3D12Native.setPrimitiveTopology(glTopology);
        }
    }

    /**
     * Destroy/release a DirectX 12 resource
     * Wrapper for D3D12MA releaseManagedResource (deferred cleanup)
     */
    public void destroyResource(long handle) {
        if (isInitialized() && handle != 0L) {
            VitraD3D12Native.releaseManagedResource(handle);
        }
    }

    /**
     * Create graphics pipeline state (shader pipeline)
     * Wrapper for DirectX 12 createGraphicsPipelineState
     *
     * @param vsHandle Vertex shader handle
     * @param psHandle Pixel shader handle
     * @return Pipeline state object handle
     */
    public long createShaderPipeline(long vsHandle, long psHandle) {
        if (isInitialized()) {
            // TODO: Need to serialize pipeline description with shader handles
            logger.trace("DirectX 12 createShaderPipeline(vs={}, ps={}) - needs pipeline descriptor", vsHandle, psHandle);
            return 0L; // Placeholder
        }
        return 0L;
    }

    /**
     * Set active shader pipeline
     * Wrapper for DirectX 12 setShaderPipeline
     */
    public void setShaderPipeline(long pipelineHandle) {
        if (isInitialized()) {
            VitraD3D12Native.setShaderPipeline(pipelineHandle);
        }
    }

    /**
     * Upload and bind uniform buffer objects (UBOs)
     * DirectX 12 uses constant buffers uploaded to GPU
     */
    public void uploadAndBindUBOs() {
        if (isInitialized()) {
            // TODO: Need to implement constant buffer upload strategy
            // DirectX 12 uses ring buffer for per-frame constant data
            logger.trace("DirectX 12 uploadAndBindUBOs() - needs constant buffer implementation");
        }
    }

    /**
     * Set depth comparison function
     * Wrapper for DirectX 12 setDepthStencilState
     *
     * @param func DirectX comparison function (1=NEVER, 2=LESS, 3=EQUAL, 4=LESS_EQUAL, 5=GREATER, 6=NOT_EQUAL, 7=GREATER_EQUAL, 8=ALWAYS)
     */
    public void depthFunc(int func) {
        if (isInitialized()) {
            // Set depth state with specified comparison function
            // Parameters: depthEnable, depthWriteMask (1=ALL), depthFunc, stencilEnable, stencilReadMask, stencilWriteMask
            // Front/back face ops: failOp, depthFailOp, passOp, func (all set to default KEEP=1, ALWAYS=8)
            VitraD3D12Native.setDepthStencilState(
                true, 1, func,  // Enable depth, write all, use specified func
                false, (byte)0xFF, (byte)0xFF,  // Disable stencil, default masks
                1, 1, 1, 8,  // Front face: KEEP, KEEP, KEEP, ALWAYS
                1, 1, 1, 8   // Back face: KEEP, KEEP, KEEP, ALWAYS
            );
        }
    }

    /**
     * Set all transform matrices at once
     * Calls setProjectionMatrix, setModelViewMatrix, and setTextureMatrix
     *
     * @param projMatrix 16-element projection matrix (column-major)
     * @param mvMatrix 16-element model-view matrix (column-major)
     * @param texMatrix 16-element texture matrix (column-major), or null to skip
     */
    public void setTransformMatrices(float[] projMatrix, float[] mvMatrix, float[] texMatrix) {
        if (isInitialized()) {
            if (projMatrix != null && projMatrix.length == 16) {
                VitraD3D12Native.setProjectionMatrix(
                    projMatrix[0], projMatrix[1], projMatrix[2], projMatrix[3],
                    projMatrix[4], projMatrix[5], projMatrix[6], projMatrix[7],
                    projMatrix[8], projMatrix[9], projMatrix[10], projMatrix[11],
                    projMatrix[12], projMatrix[13], projMatrix[14], projMatrix[15]
                );
            }
            if (mvMatrix != null && mvMatrix.length == 16) {
                VitraD3D12Native.setModelViewMatrix(
                    mvMatrix[0], mvMatrix[1], mvMatrix[2], mvMatrix[3],
                    mvMatrix[4], mvMatrix[5], mvMatrix[6], mvMatrix[7],
                    mvMatrix[8], mvMatrix[9], mvMatrix[10], mvMatrix[11],
                    mvMatrix[12], mvMatrix[13], mvMatrix[14], mvMatrix[15]
                );
            }
            if (texMatrix != null && texMatrix.length == 16) {
                VitraD3D12Native.setTextureMatrix(
                    texMatrix[0], texMatrix[1], texMatrix[2], texMatrix[3],
                    texMatrix[4], texMatrix[5], texMatrix[6], texMatrix[7],
                    texMatrix[8], texMatrix[9], texMatrix[10], texMatrix[11],
                    texMatrix[12], texMatrix[13], texMatrix[14], texMatrix[15]
                );
            }
        }
    }

    /**
     * Set projection matrix
     * Wrapper for DirectX 12 setProjectionMatrix
     */
    public void setProjectionMatrix(float[] matrix) {
        if (isInitialized() && matrix != null && matrix.length == 16) {
            VitraD3D12Native.setProjectionMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        }
    }

    /**
     * Set model-view matrix
     * Wrapper for DirectX 12 setModelViewMatrix
     */
    public void setModelViewMatrix(float[] matrix) {
        if (isInitialized() && matrix != null && matrix.length == 16) {
            VitraD3D12Native.setModelViewMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        }
    }

    /**
     * Set texture matrix
     * Wrapper for DirectX 12 setTextureMatrix
     */
    public void setTextureMatrix(float[] matrix) {
        if (isInitialized() && matrix != null && matrix.length == 16) {
            VitraD3D12Native.setTextureMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        }
    }

    /**
     * Get maximum supported texture size
     * Wrapper for DirectX 12 getMaxTextureSize
     */
    public int getMaxTextureSize() {
        if (isInitialized()) {
            return VitraD3D12Native.getMaxTextureSize();
        }
        return 16384; // DirectX 12 minimum required: 16384x16384
    }

    /**
     * Set shader color uniform
     * Wrapper for DirectX 12 setShaderColor
     */
    public void setShaderColor(float r, float g, float b, float a) {
        if (isInitialized()) {
            VitraD3D12Native.setShaderColor(r, g, b, a);
        }
    }

    /**
     * Set shader light direction uniform
     * Wrapper for DirectX 12 setShaderLightDirection
     */
    public void setShaderLightDirection(int index, float x, float y, float z) {
        if (isInitialized()) {
            VitraD3D12Native.setShaderLightDirection(index, x, y, z);
        }
    }

    /**
     * Set shader fog color uniform
     * Wrapper for DirectX 12 setShaderFogColor
     * Note: Native method takes 3 params (RGB), alpha is typically 1.0
     */
    public void setShaderFogColor(float r, float g, float b, float a) {
        if (isInitialized()) {
            VitraD3D12Native.setShaderFogColor(r, g, b);
        }
    }

    /**
     * Clear render target with OpenGL-style mask
     * Interprets GL_COLOR_BUFFER_BIT, GL_DEPTH_BUFFER_BIT, GL_STENCIL_BUFFER_BIT
     *
     * @param mask OpenGL clear mask (GL_COLOR_BUFFER_BIT=0x4000, GL_DEPTH_BUFFER_BIT=0x100, GL_STENCIL_BUFFER_BIT=0x400)
     */
    public void clear(int mask) {
        if (isInitialized()) {
            // GL_COLOR_BUFFER_BIT = 0x4000 (16384)
            if ((mask & 0x4000) != 0) {
                // Clear color buffer to black
                VitraD3D12Native.clear(0.0f, 0.0f, 0.0f, 1.0f);
            }
            // GL_DEPTH_BUFFER_BIT = 0x100 (256)
            if ((mask & 0x100) != 0) {
                // Clear depth buffer to 1.0 (far plane)
                VitraD3D12Native.clearDepthBuffer(1.0f);
            }
            // GL_STENCIL_BUFFER_BIT = 0x400 (1024) - not implemented yet
        }
    }

    // Uniform management methods (DirectX 12 doesn't track dirty state internally like D3D11)

    /**
     * Mark uniforms as dirty, requiring re-upload to GPU
     * DirectX 12 handles uniform updates differently than D3D11,
     * so this is mainly for compatibility with mixin expectations
     */
    public void markUniformsDirty() {
        this.uniformsDirty = true;
    }

    /**
     * Check if uniforms are dirty (need re-upload)
     */
    public boolean areUniformsDirty() {
        return this.uniformsDirty;
    }

    /**
     * Force uniform update on next draw call
     * DirectX 12 uploads uniforms via constant buffers per-frame
     */
    public void forceUniformUpdate() {
        this.uniformsDirty = true;
    }

    /**
     * Clear uniform dirty flag after upload
     * Should be called after uploading uniforms to GPU
     */
    public void clearUniformsDirty() {
        this.uniformsDirty = false;
    }
}