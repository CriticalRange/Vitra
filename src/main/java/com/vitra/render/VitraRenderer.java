package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.jni.VitraD3D12Renderer;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.D3D11BufferManager;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.debug.VitraDebugUtils;
import com.vitra.render.VRenderSystem;
import com.vitra.render.shader.Uniforms;
import com.vitra.render.shader.D3D11ConstantBuffer;
import com.vitra.render.shader.descriptor.UBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified renderer for Vitra supporting both DirectX 11 and DirectX 12 backends
 * Dynamically selects backend based on configuration
 * Uses native DirectX calls through JNI interface
 */
public class VitraRenderer extends AbstractRenderer {
    // Renderer backend instances
    private IVitraRenderer activeRenderer = null;

    // DirectX 11 components
    private D3D11ShaderManager shaderManager;
    private D3D11BufferManager bufferManager;

    // DirectX 12 components
    private VitraD3D12Renderer d3d12Renderer;

    // Constant buffer system (VulkanMod-style uniform management)
    private D3D11ConstantBuffer vertexConstantBuffer;     // b0 - vertex shader uniforms
    private D3D11ConstantBuffer fragmentConstantBuffer;   // b1 - fragment shader uniforms
    private UBO vertexUBO;                                // Vertex UBO descriptor
    private UBO fragmentUBO;                              // Fragment UBO descriptor
    private boolean uniformsDirty = false;                // Dirty flag for uniform updates

    public VitraRenderer() {
        super(VitraRenderer.class);
    }

    // Interface implementation methods
    @Override
    public void initialize() {
        // Get renderer type from config or default to DirectX 11
        RendererType rendererType = (config != null) ? config.getRendererType() : RendererType.DIRECTX11;
        initialize(rendererType);
    }

    @Override
    public void initialize(RendererType rendererType) {
        if (initialized) {
            logger.warn("VitraRenderer already initialized");
            return;
        }

        logger.info("Preparing Vitra unified renderer - Backend: {}", rendererType);

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException(rendererType + " is not supported on this platform. Vitra requires Windows 10+.");
            }

            // Select and initialize the appropriate backend
            switch (rendererType) {
                case DIRECTX11:
                    initializeDirectX11Backend();
                    break;
                case DIRECTX12:
                    initializeDirectX12Backend();
                    break;
                default:
                    throw new RuntimeException("Unsupported renderer type: " + rendererType);
            }

            initialized = true;
            logger.info("Vitra unified renderer prepared - Backend: {}, native will initialize when window handle is available", rendererType);

        } catch (Exception e) {
            logger.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed", e);
        }
    }

    /**
     * Initialize DirectX 11 backend
     */
    private void initializeDirectX11Backend() {
        logger.info("Initializing DirectX 11 backend");
        activeRenderer = this; // Use this instance as DirectX 11 renderer

        // Initialize DirectX 11 components
        shaderManager = new D3D11ShaderManager();
        bufferManager = new D3D11BufferManager();

        logger.info("DirectX 11 backend components initialized");
    }

    /**
     * Initialize DirectX 12 backend
     */
    private void initializeDirectX12Backend() {
        logger.info("Initializing DirectX 12 backend");

        // Create DirectX 12 renderer instance
        d3d12Renderer = new VitraD3D12Renderer();
        activeRenderer = d3d12Renderer;

        // DirectX 12 doesn't use the legacy shader/buffer managers
        shaderManager = null;
        bufferManager = null;

        logger.info("DirectX 12 backend initialized");
    }

    @Override
    public boolean initializeWithWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        logger.info("Vitra unified renderer window handle set: 0x{}", Long.toHexString(windowHandle));

        // Actually initialize native DirectX 11 with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug, verbose, and WARP mode from config
                boolean debugMode = isDebugMode();
                boolean verboseMode = isVerboseMode();
                boolean useWarp = isUseWarp();

                logger.info("Initializing native DirectX 11 with debug={}, verbose={}, warp={}", debugMode, verboseMode, useWarp);
                if (verboseMode) {
                    logger.warn("╔════════════════════════════════════════════════════════════╗");
                    logger.warn("║  VERBOSE LOGGING ENABLED - JNI will log EVERYTHING        ║");
                    logger.warn("║  This may impact performance and generate huge log files  ║");
                    logger.warn("║  Disable in config/vitra.properties after debugging       ║");
                    logger.warn("╚════════════════════════════════════════════════════════════╝");
                }

                // Initialize debug system before DirectX initialization
                VitraNativeRenderer.initializeDebug(debugMode, verboseMode);

                // Initialize OpenGL interceptor to redirect OpenGL calls to DirectX
                GLInterceptor.initialize();
                GLInterceptor.setActive(true);
                logger.info("OpenGL interceptor initialized and activated");

                // Get Win32 HWND from GLFW window handle
                long nativeWindowHandle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(windowHandle);
                if (nativeWindowHandle == 0) {
                    logger.error("Failed to get Win32 HWND from GLFW window");
                    return false;
                }

                logger.info("GLFW window: 0x{}, Win32 HWND: 0x{}",
                    Long.toHexString(windowHandle), Long.toHexString(nativeWindowHandle));

                boolean success = VitraNativeRenderer.initializeDirectXSafe(nativeWindowHandle, 1920, 1080, debugMode, useWarp);
                if (success) {
                    logger.info("Native DirectX 11 initialized successfully (debug={})", debugMode);

                    // Initialize uniform system (VulkanMod-style supplier-based architecture)
                    logger.info("Initializing uniform system...");
                    initializeUniformSystem();
                    logger.info("Uniform system initialized: vertex CB={}, fragment CB={}",
                        vertexConstantBuffer, fragmentConstantBuffer);

                    // Preload shaders after initialization
                    if (shaderManager != null) {
                        logger.info("Preloading DirectX shaders...");
                        shaderManager.preloadShaders();
                        logger.info("Shader preloading completed: {}", shaderManager.getCacheStats());
                    }

                    // CRITICAL FIX: Ensure default shader pipeline is bound immediately after initialization
                    // This prevents black screen by ensuring shaders are available for first draw calls
                    long defaultPipeline = VitraNativeRenderer.getDefaultShaderPipeline();
                    if (defaultPipeline != 0) {
                        VitraNativeRenderer.setShaderPipeline(defaultPipeline);
                        logger.info("Default shader pipeline bound: handle=0x{}", Long.toHexString(defaultPipeline));
                    } else {
                        logger.error("Failed to get default shader pipeline handle - this will cause black screen!");
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
                    logger.error("Native DirectX 11 initialization failed");
                    return false;
                }
            } catch (Exception e) {
                logger.error("Exception during native DirectX 11 initialization", e);
                return false;
            }
        }
        return false;
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        logger.info("Shutting down Vitra JNI DirectX 11 renderer...");

        try {
            // Cleanup constant buffers
            if (vertexConstantBuffer != null) {
                vertexConstantBuffer.cleanup();
                vertexConstantBuffer = null;
                logger.info("Vertex constant buffer cleaned up");
            }

            if (fragmentConstantBuffer != null) {
                fragmentConstantBuffer.cleanup();
                fragmentConstantBuffer = null;
                logger.info("Fragment constant buffer cleaned up");
            }

            // Cleanup VRenderSystem
            VRenderSystem.cleanup();
            logger.info("VRenderSystem cleaned up");

            // Clear shader and buffer caches
            if (shaderManager != null) {
                shaderManager.clearCache();
                logger.info("Cleared shader cache: {}", shaderManager.getCacheStats());
            }

            if (bufferManager != null) {
                bufferManager.clearAll();
                logger.info("Cleared buffer cache: {}", bufferManager.getBufferStats());
            }

            // Shutdown OpenGL interceptor
            GLInterceptor.setActive(false);
            GLInterceptor.shutdown();
            logger.info("OpenGL interceptor shutdown");

            // Shutdown native renderer safely with debug cleanup
            VitraNativeRenderer.shutdownSafe();
            logger.info("Native DirectX 11 shutdown completed");

        } catch (Exception e) {
            logger.error("Exception during shutdown", e);
        }

        initialized = false;
        windowHandle = 0L;
        logger.info("Vitra renderer shutdown complete");
    }

    @Override
    public boolean isInitialized() {
        // Check if unified renderer is prepared and active renderer is initialized
        if (!initialized) return false;
        if (activeRenderer != null) {
            return activeRenderer.isInitialized();
        }
        return false;
    }

    /**
     * Check if active renderer is fully initialized (has window handle and native context)
     */
    public boolean isFullyInitialized() {
        if (!initialized || activeRenderer == null) return false;
        return activeRenderer.isInitialized() && windowHandle != 0L;
    }

    @Override
    public RendererType getRendererType() {
        // Return the configured renderer type
        return (config != null) ? config.getRendererType() : RendererType.DIRECTX11;
    }

    @Override
    public void beginFrame() {
        // Delegate to active renderer
        if (activeRenderer != null && activeRenderer.isInitialized()) {
            // Special handling for DirectX 11 specific features
            if (getRendererType() == RendererType.DIRECTX11) {
                // Handle pending resize
                if (resizePending) {
                    // Actual resize will be handled by resize() method
                    resizePending = false;
                }

                // Update constant buffers EVERY frame for DirectX 11
                // DirectX 11 may unbind constant buffers during pipeline changes,
                // so we need to rebind them every frame to be safe
                uploadConstantBuffers();
                uniformsDirty = false;
            }

            activeRenderer.beginFrame();
        }
    }

    @Override
    public void endFrame() {
        if (activeRenderer != null && activeRenderer.isInitialized()) {
            activeRenderer.endFrame();

            // Process debug messages for DirectX 11
            if (getRendererType() == RendererType.DIRECTX11 && VitraNativeRenderer.isDebugEnabled()) {
                VitraNativeRenderer.processDebugMessages();
            }
            // Process debug messages for DirectX 12
            else if (getRendererType() == RendererType.DIRECTX12 && d3d12Renderer != null) {
                d3d12Renderer.processDebugMessages();
            }
        }
    }

    @Override
    public void present() {
        // Frame presentation is handled by CommandEncoder.presentTexture()
        // which is called by Minecraft's rendering pipeline after the frame is complete
    }

    @Override
    public void resize(int width, int height) {
        if (activeRenderer != null && activeRenderer.isInitialized()) {
            activeRenderer.resize(width, height);
            logger.info("Renderer view resized to {}x{} (backend: {})", width, height, getRendererType());
        }
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        if (activeRenderer != null && activeRenderer.isInitialized()) {
            activeRenderer.clear(r, g, b, a);
        }
    }

    // Feature support delegates to active renderer
    @Override
    public boolean isRayTracingSupported() {
        if (activeRenderer != null) {
            return activeRenderer.isRayTracingSupported();
        }
        return false;
    }

    @Override
    public boolean isVariableRateShadingSupported() {
        if (activeRenderer != null) {
            return activeRenderer.isVariableRateShadingSupported();
        }
        return false;
    }

    @Override
    public boolean isMeshShadersSupported() {
        if (activeRenderer != null) {
            return activeRenderer.isMeshShadersSupported();
        }
        return false;
    }

    @Override
    public boolean isGpuDrivenRenderingSupported() {
        if (activeRenderer != null) {
            return activeRenderer.isGpuDrivenRenderingSupported();
        }
        return false;
    }

    // Performance metrics delegate to active renderer
    @Override
    public float getGpuUtilization() {
        if (activeRenderer != null) {
            return activeRenderer.getGpuUtilization();
        }
        return 0.0f;
    }

    @Override
    public long getFrameTime() {
        if (activeRenderer != null) {
            return activeRenderer.getFrameTime();
        }
        return 0L;
    }

    @Override
    public int getDrawCallsPerFrame() {
        if (activeRenderer != null) {
            return activeRenderer.getDrawCallsPerFrame();
        }
        return 0;
    }

    @Override
    public void resetPerformanceCounters() {
        if (activeRenderer != null) {
            activeRenderer.resetPerformanceCounters();
        }
    }

    @Override
    public void setDebugMode(boolean enabled) {
        // Debug mode is handled during initialization
    }

    @Override
    public void captureFrame(String filename) {
        // Not implemented for DirectX 11
    }

    @Override
    public String getRendererStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX 11 Renderer Stats ===\n");
        stats.append("Prepared: ").append(isInitialized()).append("\n");
        stats.append("Fully Initialized: ").append(isFullyInitialized()).append("\n");
        stats.append("Window Handle: 0x").append(Long.toHexString(windowHandle)).append("\n");
        stats.append("Debug Mode: ").append(config != null ? config.isDebugMode() : false).append("\n");
        stats.append("Verbose Logging: ").append(config != null ? config.isVerboseLogging() : false).append("\n");

        // Include debug stats if available
        if (VitraNativeRenderer.isDebugEnabled()) {
            stats.append("\n--- Debug System ---\n");
            stats.append(VitraNativeRenderer.getDebugStats());
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

    // Debug-specific methods
    public void processDebugMessages() {
        VitraNativeRenderer.processDebugMessages();
    }

    public boolean isDebugAvailable() {
        return VitraNativeRenderer.isDebugEnabled();
    }

    // Feature access methods
    @Override
    public VitraRenderer getDirectX11Renderer() {
        return (getRendererType() == RendererType.DIRECTX11) ? this : null;
    }

    @Override
    public com.vitra.render.jni.VitraD3D12Renderer getDirectX12Renderer() {
        return (getRendererType() == RendererType.DIRECTX12) ? d3d12Renderer : null;
    }

    @Override
    public Object getShaderManager() {
        if (activeRenderer != null) {
            return activeRenderer.getShaderManager();
        }
        return null;
    }

    @Override
    public Object getBufferManager() {
        if (activeRenderer != null) {
            return activeRenderer.getBufferManager();
        }
        return null;
    }

    // Getters for DirectX 11 components
    public D3D11ShaderManager getD3D11ShaderManager() {
        return shaderManager;
    }

    public D3D11BufferManager getD3D11BufferManager() {
        return bufferManager;
    }

    /**
     * Get native device handle for debugging and verification
     */
    public long getNativeHandle() {
        if (isFullyInitialized()) {
            // Use device info to get a handle for verification
            // This doesn't return the actual device handle but provides a way to verify initialization
            return VitraNativeRenderer.isInitialized() ? 0x12345678L : 0L;
        }
        return 0L;
    }

    // Additional methods for VulkanMod compatibility

    /**
     * Get singleton VitraRenderer instance
     *
     * Used by UniformM mixin to mark uniforms dirty.
     * Returns null if renderer not yet initialized.
     */
    public static VitraRenderer getInstance() {
        try {
            return (VitraRenderer) com.vitra.VitraMod.getRenderer();
        } catch (Exception e) {
            return null;
        }
    }

    public static VitraRenderer getRenderer() {
        return getInstance();
    }

    public void waitForIdle() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.waitForIdle();
        }
    }

    public void cleanup() {
        shutdown();
    }

    public void scheduleResize() {
        // Mark that resize should happen on next frame
        resizePending = true;
    }

    private boolean resizePending = false;

    // Helper methods for parameter conversion
    private int getPrimitiveModeFromObject(Object mode) {
        // Convert mode object to primitive mode constant
        if (mode instanceof Number) {
            return ((Number) mode).intValue();
        }
        // Default to triangles if we can't determine the mode
        return 4; // GL_TRIANGLES equivalent
    }

    private int getVertexSizeFromFormat(Object format) {
        // Convert format object to vertex size in bytes
        if (format instanceof Number) {
            return ((Number) format).intValue();
        }
        // Default vertex size for standard Minecraft vertex format
        return 28; // Position (12) + Color (4) + Texture (8) + Lightmap (4) = 28 bytes
    }

    // Mesh drawing methods for BufferUploader compatibility
    public void drawMesh(Object vertexBuffer, Object indexBuffer, Object mode, Object format, int vertexCount) {
        if (isFullyInitialized()) {
            // Extract primitive mode from mode object and vertex count from format
            int primitiveMode = getPrimitiveModeFromObject(mode);
            int vertexSize = getVertexSizeFromFormat(format);
            VitraNativeRenderer.drawMesh(vertexBuffer, indexBuffer, vertexCount, 0, primitiveMode, vertexSize);
        }
    }

    // Screen rendering methods
    @Override
    public void clearDepthBuffer() {
        if (activeRenderer != null) {
            activeRenderer.clearDepthBuffer();
        }
    }

    // Screenshot and GPU synchronization methods
    @Override
    public void waitForGpuCommands() {
        if (activeRenderer != null) {
            activeRenderer.waitForGpuCommands();
        }
    }

    // Window and display methods
    public void setVsync(boolean vsync) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.setVsync(vsync);
            System.out.println("[Vitra] Vsync " + (vsync ? "enabled" : "disabled"));
        }
    }

    public void presentFrame() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.presentFrame();
        }
    }

    // New debug and utility methods for enhanced mixin structure

    // GUI Optimization Methods
    public void optimizeCrosshairRendering() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeCrosshairRendering();
        }
    }

    public void beginTextBatch() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.beginTextBatch();
        }
    }

    public void endTextBatch() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.endTextBatch();
        }
    }

    // Screen Optimization Methods
    public void optimizeScreenBackground() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeScreenBackground(0); // 0 = default screen type
        }
    }

    public void optimizeDirtBackground(int vOffset) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeDirtBackground(vOffset);
        }
    }

    public void optimizeTooltipRendering(int lineCount) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeTooltipRendering(lineCount);
        }
    }

    public void optimizeSlotRendering(int x, int y) {
        if (isFullyInitialized()) {
            // Combine x and y into a single slot ID (or use just x as slot ID)
            int slotId = x + y * 100; // Simple combination
            VitraNativeRenderer.optimizeSlotRendering(slotId);
        }
    }

    public void optimizeSlotHighlight(int x, int y) {
        if (isFullyInitialized()) {
            // Combine x and y into a single slot ID
            int slotId = x + y * 100; // Simple combination
            VitraNativeRenderer.optimizeSlotHighlight(slotId);
        }
    }

    public void optimizeContainerLabels() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeContainerLabels(0); // 0 = default label ID
        }
    }

    public void optimizeContainerBackground() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeContainerBackground(0); // 0 = default container ID
        }
    }

    public void optimizePanoramaRendering() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizePanoramaRendering(0); // 0 = default panorama ID
        }
    }

    public void optimizeLogoRendering() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeLogoRendering(64); // 64 = default logo size
        }
    }

    public void optimizeButtonRendering() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeButtonRendering(0); // 0 = default button ID
        }
    }

    public void optimizeFadingBackground(int alpha) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeFadingBackground(alpha);
        }
    }

    // Utility Methods
    public void prepareRenderContext() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.prepareRenderContext();
        }
    }

    public void cleanupRenderContext() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.cleanupRenderContext();
        }
    }

    public int getOptimalFramerateLimit() {
        if (isFullyInitialized()) {
            return VitraNativeRenderer.getOptimalFramerateLimit();
        }
        return 0;
    }

    public void handleDisplayResize() {
        if (isFullyInitialized()) {
            VitraNativeRenderer.handleDisplayResize(1920, 1080); // Default dimensions, will be updated by actual resize events
        }
    }

    public void setWindowActiveState(boolean active) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.setWindowActiveState(active);
        }
    }

    public static boolean isDirectX11Initialized() {
        return VitraNativeRenderer.isInitialized();
    }

    // Shader Helper Methods
    public void precompileShaderForDirectX11(Object shader) {
        if (isFullyInitialized()) {
            String shaderSource = shader.toString();
            VitraNativeRenderer.precompileShaderForDirectX11(shaderSource, 0); // 0 = vertex shader
        }
    }

    public boolean isShaderDirectX11Compatible(Object shader) {
        if (isFullyInitialized()) {
            // Convert shader to byte array for compatibility check
            byte[] shaderData = shader.toString().getBytes();
            return VitraNativeRenderer.isShaderDirectX11Compatible(shaderData);
        }
        return false;
    }

    public Object getOptimizedDirectX11Shader(Object original) {
        if (isFullyInitialized()) {
            String shaderName = original.toString();
            long handle = VitraNativeRenderer.getOptimizedDirectX11Shader(shaderName);
            return handle != 0 ? handle : original;
        }
        return original;
    }

    // Matrix Helper Methods
    public void optimizeMatrixMultiplication(Object matrix1, Object matrix2) {
        if (isFullyInitialized()) {
            float[] mat1 = convertToFloatArray(matrix1);
            float[] mat2 = convertToFloatArray(matrix2);
            float[] result = VitraNativeRenderer.optimizeMatrixMultiplication(mat1, mat2);
            // Optionally store the result back if the matrices are mutable
        }
    }

    public void optimizeMatrixInversion(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            float[] result = VitraNativeRenderer.optimizeMatrixInversion(mat);
            // Optionally store the result back if the matrix is mutable
        }
    }

    public void optimizeMatrixTranspose(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            float[] result = VitraNativeRenderer.optimizeMatrixTranspose(mat);
            // Optionally store the result back if the matrix is mutable
        }
    }

    public void adjustOrthographicProjection(float left, float right, float bottom, float top, float zNear, float zFar) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.adjustOrthographicProjection(left, right, bottom, top, zNear, zFar);
        }
    }

    public void adjustPerspectiveProjection(float fovy, float aspect, float zNear, float zFar) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.adjustPerspectiveProjection(fovy, aspect, zNear, zFar);
        }
    }

    public void optimizeTranslationMatrix(float x, float y, float z) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeTranslationMatrix(x, y, z);
        }
    }

    public void optimizeScaleMatrix(float x, float y, float z) {
        if (isFullyInitialized()) {
            VitraNativeRenderer.optimizeScaleMatrix(x, y, z);
        }
    }

    public void optimizeRotationMatrix(float angle, float x, float y, float z) {
        if (isFullyInitialized()) {
            float[] axis = {x, y, z};
            float[] result = VitraNativeRenderer.optimizeRotationMatrix(angle, axis);
            // Optionally store the result back if needed
        }
    }

    public boolean isMatrixDirectX11Optimized(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            return VitraNativeRenderer.isMatrixDirectX11Optimized(mat);
        }
        return false;
    }

    // Helper method to convert matrix objects to float arrays
    private float[] convertToFloatArray(Object matrix) {
        if (matrix instanceof float[]) {
            return (float[]) matrix;
        } else if (matrix instanceof double[]) {
            double[] d = (double[]) matrix;
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) {
                f[i] = (float) d[i];
            }
            return f;
        } else {
            // Fallback: create a 4x4 identity matrix
            return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
            };
        }
    }

    // ==================== UNIFORM SYSTEM (VulkanMod-style) ====================

    /**
     * Initialize uniform system - creates UBO descriptors and GPU constant buffers
     *
     * Called during DirectX 11 initialization after device creation.
     * Sets up VulkanMod-style supplier-based uniform management.
     */
    private void initializeUniformSystem() {
        try {
            // Initialize VRenderSystem (CPU-side uniform storage)
            VRenderSystem.initialize();
            logger.info("VRenderSystem initialized (CPU-side uniform storage)");

            // Setup default uniform suppliers (links uniform names to VRenderSystem getters)
            Uniforms.setupDefaultUniforms();
            logger.info("Uniform supplier registry initialized ({} suppliers registered)",
                Uniforms.mat4f_uniformMap.size() + Uniforms.vec4f_uniformMap.size() +
                Uniforms.vec3f_uniformMap.size() + Uniforms.vec1f_uniformMap.size() +
                Uniforms.vec1i_uniformMap.size());

            // Create standard Minecraft UBO descriptors
            vertexUBO = UBO.createStandardVertexUBO();      // b0: ModelViewMat, ProjMat, TextureMat, ChunkOffset
            fragmentUBO = UBO.createStandardFragmentUBO();  // b1: ColorModulator, FogColor, FogStart, FogEnd, FogShape

            logger.info("UBO descriptors created:");
            logger.info("  Vertex UBO: {}", vertexUBO);
            logger.info("  Fragment UBO: {}", fragmentUBO);

            // Create GPU constant buffers
            vertexConstantBuffer = new D3D11ConstantBuffer(vertexUBO);
            fragmentConstantBuffer = new D3D11ConstantBuffer(fragmentUBO);

            logger.info("GPU constant buffers allocated:");
            logger.info("  {}", vertexConstantBuffer);
            logger.info("  {}", fragmentConstantBuffer);

            // Initial uniform upload (ensures constant buffers are initialized)
            uploadConstantBuffers();
            logger.info("Initial constant buffer upload completed");

        } catch (Exception e) {
            logger.error("Failed to initialize uniform system", e);
            throw new RuntimeException("Uniform system initialization failed", e);
        }
    }

    /**
     * Upload constant buffers to GPU
     *
     * Called when uniforms are marked dirty (via UniformM.cancelUpload()).
     * Syncs VRenderSystem from RenderSystem, then uploads to GPU constant buffers.
     *
     * Data flow:
     * 1. Minecraft calls RenderSystem.setModelViewMatrix() etc.
     * 2. UniformM intercepts Uniform.upload() and calls markUniformsDirty()
     * 3. This method syncs VRenderSystem from RenderSystem
     * 4. UBO.update() calls suppliers to get current uniform values
     * 5. D3D11ConstantBuffer.update() copies data to GPU via Map/Unmap
     * 6. D3D11ConstantBuffer.bind() binds constant buffers to shader stages
     */
    private void uploadConstantBuffers() {
        if (vertexConstantBuffer == null || fragmentConstantBuffer == null) {
            logger.warn("Constant buffers not initialized, skipping upload");
            return;
        }

        try {
            // Sync VRenderSystem from Minecraft's RenderSystem
            // This copies current uniform values from RenderSystem to VRenderSystem
            VRenderSystem.syncFromRenderSystem();

            // Update and bind vertex constant buffer (b0)
            vertexConstantBuffer.updateAndBind(vertexUBO);

            // Update and bind fragment constant buffer (b1)
            fragmentConstantBuffer.updateAndBind(fragmentUBO);

        } catch (Exception e) {
            logger.error("Failed to upload constant buffers", e);
        }
    }

    /**
     * Mark uniforms as dirty - will be uploaded before next draw call
     *
     * Called by UniformM mixin when Minecraft calls Uniform.upload().
     * Instead of using OpenGL glUniform* calls, we mark uniforms dirty
     * and batch update all constant buffers before the next draw call.
     */
    public void markUniformsDirty() {
        this.uniformsDirty = true;
    }

    /**
     * Get uniform dirty flag (for debugging)
     */
    public boolean areUniformsDirty() {
        return this.uniformsDirty;
    }

    /**
     * Force immediate constant buffer update
     *
     * Normally uniforms are updated lazily at beginFrame().
     * This method forces an immediate update (useful for debugging).
     */
    public void forceUniformUpdate() {
        uploadConstantBuffers();
        uniformsDirty = false;
    }
}
