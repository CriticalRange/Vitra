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

    // ============================================================================
    // RENDERER-AGNOSTIC WRAPPER METHODS
    // These methods delegate to the appropriate backend (DirectX 11 or 12)
    // Mixins should call these instead of VitraNativeRenderer directly
    // ============================================================================

    public void setViewport(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setViewport(x, y, width, height, 0.0f, 1.0f);
        } else {
            VitraNativeRenderer.setViewport(x, y, width, height);
        }
    }

    public void setScissor(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            // DirectX 12 scissor - would need implementation
            logger.debug("DirectX 12 scissor not yet implemented");
        } else {
            VitraNativeRenderer.setScissor(x, y, width, height);
        }
    }

    public void resetScissor() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 resetScissor not yet implemented");
        } else {
            VitraNativeRenderer.resetScissor();
        }
    }

    public void enableBlend() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 blend state handled via PSO");
        } else {
            VitraNativeRenderer.enableBlend();
        }
    }

    public void disableBlend() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 blend state handled via PSO");
        } else {
            VitraNativeRenderer.disableBlend();
        }
    }

    public void blendFunc(int sfactor, int dfactor) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 blend func handled via PSO");
        } else {
            VitraNativeRenderer.blendFunc(sfactor, dfactor);
        }
    }

    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 blend func handled via PSO");
        } else {
            VitraNativeRenderer.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        }
    }

    public void blendEquation(int mode) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 blend equation handled via PSO");
        } else {
            VitraNativeRenderer.blendEquation(mode);
        }
    }

    public void enableCull() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 cull state handled via PSO");
        } else {
            VitraNativeRenderer.enableCull();
        }
    }

    public void disableCull() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 cull state handled via PSO");
        } else {
            VitraNativeRenderer.disableCull();
        }
    }

    public void enableDepthTest() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 depth test handled via PSO");
        } else {
            VitraNativeRenderer.enableDepthTest();
        }
    }

    public void disableDepthTest() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 depth test handled via PSO");
        } else {
            VitraNativeRenderer.disableDepthTest();
        }
    }

    public void depthMask(boolean flag) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 depth mask handled via PSO");
        } else {
            VitraNativeRenderer.depthMask(flag);
        }
    }

    public void depthFunc(int func) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 depth func handled via PSO");
        } else {
            VitraNativeRenderer.setDepthFunc(func);
        }
    }

    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 color mask handled via PSO");
        } else {
            VitraNativeRenderer.colorMask(red, green, blue, alpha);
        }
    }

    public void polygonOffset(float factor, float units) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraNativeRenderer.polygonOffset(factor, units);
        }
    }

    public void enablePolygonOffset() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraNativeRenderer.enablePolygonOffset();
        }
    }

    public void disablePolygonOffset() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraNativeRenderer.disablePolygonOffset();
        }
    }

    public void logicOp(int opcode) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraNativeRenderer.logicOp(opcode);
        }
    }

    public void enableColorLogicOp() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraNativeRenderer.enableColorLogicOp();
        }
    }

    public void disableColorLogicOp() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraNativeRenderer.disableColorLogicOp();
        }
    }

    // Texture methods
    public void bindTexture(int textureId) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            // Use D3D11GlTexture mapping system (works for both DX11 and DX12)
            com.vitra.render.dx11.D3D11GlTexture.bindTexture(textureId);
        } else {
            com.vitra.render.dx11.D3D11GlTexture.bindTexture(textureId);
        }
    }

    public void deleteTexture(int textureId) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.dx11.D3D11GlTexture.deleteTexture(textureId);
        } else {
            VitraNativeRenderer.deleteTexture(textureId);
        }
    }

    public void texParameteri(int target, int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture parameters handled during texture creation");
        } else {
            VitraNativeRenderer.texParameteri(target, pname, param);
        }
    }

    public void pixelStore(int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 pixel store not needed");
        } else {
            VitraNativeRenderer.pixelStore(pname, param);
        }
    }

    // Shader/uniform methods
    public void setShaderColor(float r, float g, float b, float a) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setShaderColor(r, g, b, a);
        } else {
            VitraNativeRenderer.setShaderColor(r, g, b, a);
        }
    }

    public void setProjectionMatrix(float[] matrix) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setProjectionMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        } else {
            VitraNativeRenderer.setProjectionMatrix(matrix);
        }
    }

    public void setTextureMatrix(float[] matrix) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setTextureMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        } else {
            VitraNativeRenderer.setTextureMatrix(matrix);
        }
    }

    // Drawing topology
    public void setPrimitiveTopology(int mode) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 topology set via PSO or draw call");
        } else {
            VitraNativeRenderer.setPrimitiveTopology(mode);
        }
    }

    // Framebuffer methods
    public void bindFramebuffer(int target, int framebuffer) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 framebuffer binding handled differently");
        } else {
            VitraNativeRenderer.bindFramebuffer(target, framebuffer);
        }
    }

    public void recreateSwapChain() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.recreateSwapChain();
        } else {
            VitraNativeRenderer.recreateSwapChain();
        }
    }

    // Info/debug methods
    public String getDeviceInfo() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return "DirectX 12 Ultimate Renderer";
        } else {
            return VitraNativeRenderer.getDeviceInfo();
        }
    }

    public int getMaxTextureSize() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return com.vitra.render.jni.VitraD3D12Native.getMaxTextureSize();
        } else {
            return VitraNativeRenderer.getMaxTextureSize();
        }
    }

    // Additional methods needed by GlStateManagerM
    public void setClearColor(float r, float g, float b, float a) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.clear(r, g, b, a);
        } else {
            VitraNativeRenderer.setClearColor(r, g, b, a);
        }
    }

    public void clear(int mask) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            // DirectX 12 clear based on mask bits
            boolean clearColor = (mask & 0x4000) != 0; // GL_COLOR_BUFFER_BIT
            boolean clearDepth = (mask & 0x0100) != 0; // GL_DEPTH_BUFFER_BIT
            if (clearColor) com.vitra.render.jni.VitraD3D12Native.clear(0, 0, 0, 0);
            if (clearDepth) com.vitra.render.jni.VitraD3D12Native.clearDepthBuffer(1.0f);
        } else {
            VitraNativeRenderer.clear(mask);
        }
    }

    public void clearDepth(float depth) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.clearDepthBuffer(depth);
        } else {
            VitraNativeRenderer.clearDepth(depth);
        }
    }

    public void setPolygonMode(int mode) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 polygon mode handled via rasterizer state");
        } else {
            VitraNativeRenderer.setPolygonMode(mode);
        }
    }

    public void setTextureParameterf(int target, int pname, float param) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture parameters handled during texture creation");
        } else {
            VitraNativeRenderer.setTextureParameterf(target, pname, param);
        }
    }

    public int getTexLevelParameter(int target, int level, int pname) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return 0; // DirectX 12 doesn't have direct equivalent
        } else {
            return VitraNativeRenderer.getTexLevelParameter(target, level, pname);
        }
    }

    public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 framebuffer handled via render targets");
        } else {
            VitraNativeRenderer.framebufferTexture2D(target, attachment, textarget, texture, level);
        }
    }

    public void bindRenderbuffer(int target, int renderbuffer) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 renderbuffer handled via textures");
        } else {
            VitraNativeRenderer.bindRenderbuffer(target, renderbuffer);
        }
    }

    public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 framebuffer renderbuffer handled via render targets");
        } else {
            VitraNativeRenderer.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
        }
    }

    public void renderbufferStorage(int target, int internalformat, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 renderbuffer storage handled via textures");
        } else {
            VitraNativeRenderer.renderbufferStorage(target, internalformat, width, height);
        }
    }

    public int checkFramebufferStatus(int framebuffer, int target) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
        } else {
            return VitraNativeRenderer.checkFramebufferStatus(framebuffer, target);
        }
    }

    public void useProgram(int program) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 program binding handled via PSO");
        } else {
            VitraNativeRenderer.useProgram(program);
        }
    }

    public void deleteProgram(int program) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 program deletion handled via PSO cleanup");
        } else {
            VitraNativeRenderer.deleteProgram(program);
        }
    }

    // ============================================================================
    // Additional renderer-agnostic wrapper methods for mixin compatibility
    // ============================================================================

    public void activeTexture(int texture) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture unit selection via descriptor table");
        } else {
            VitraNativeRenderer.activeTexture(texture);
        }
    }

    public void bindBuffer(int target, int buffer) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 buffer binding via command list");
        } else {
            VitraNativeRenderer.bindBuffer(target, buffer);
        }
    }

    public void bufferData(int target, java.nio.ByteBuffer data, int usage) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 buffer data upload via staging");
        } else {
            VitraNativeRenderer.bufferData(target, data, usage);
        }
    }

    public void deleteBuffer(int buffer) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.releaseManagedResource(buffer);
        } else {
            VitraNativeRenderer.deleteBuffer(buffer);
        }
    }

    public java.nio.ByteBuffer mapBuffer(int target, int access, int length) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 buffer mapping via Map");
            long handle = com.vitra.render.jni.VitraD3D12Native.mapBuffer(target);
            return null; // TODO: implement proper ByteBuffer wrapping
        } else {
            return VitraNativeRenderer.mapBuffer(target, access, length);
        }
    }

    public void unmapBuffer(int target) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.unmapBuffer(target);
        } else {
            VitraNativeRenderer.unmapBuffer(target);
        }
    }

    public void texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            byte[] data = pixels != null ? new byte[pixels.remaining()] : null;
            if (data != null) pixels.get(data);
            com.vitra.render.jni.VitraD3D12Native.setTextureData(target, width, height, format, data);
        } else {
            VitraNativeRenderer.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture sub-image update");
            byte[] data = pixels != null ? new byte[pixels.remaining()] : null;
            if (data != null) pixels.get(data);
            com.vitra.render.jni.VitraD3D12Native.setTextureData(target, width, height, format, data);
        } else {
            long address = pixels != null ? org.lwjgl.system.MemoryUtil.memAddress(pixels) : 0;
            VitraNativeRenderer.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, address);
        }
    }

    public long createConstantBuffer(int size) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return com.vitra.render.jni.VitraD3D12Native.createManagedUploadBuffer(size);
        } else {
            return VitraNativeRenderer.createConstantBuffer(size);
        }
    }

    public void uploadAndBindUBOs() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 UBO upload via descriptor table");
        } else {
            VitraNativeRenderer.uploadAndBindUBOs();
        }
    }

    public void destroyResource(long handle) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.releaseManagedResource(handle);
        } else {
            VitraNativeRenderer.destroyResource(handle);
        }
    }

    public void setScissorRect(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setViewport(x, y, width, height, 0.0f, 1.0f);
        } else {
            VitraNativeRenderer.setScissorRect(x, y, width, height);
        }
    }

    public void setRasterizerState(int fillMode, int cullMode, boolean frontCounterClockwise) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setRasterizerState(fillMode, cullMode, frontCounterClockwise,
                0, 0.0f, 0.0f, true, false, false, 0, false);
        } else {
            VitraNativeRenderer.setRasterizerState(fillMode, cullMode, frontCounterClockwise);
        }
    }

    public long createGLProgramShader(byte[] bytecode, int length, int type) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            return com.vitra.render.jni.VitraD3D12Native.createShader(bytecode, type);
        } else {
            return VitraNativeRenderer.createGLProgramShader(bytecode, length, type);
        }
    }

    public long createShaderPipeline(long vertexShader, long pixelShader) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 PSO creation from shaders");
            return com.vitra.render.jni.VitraD3D12Native.getDefaultShaderPipeline();
        } else {
            return VitraNativeRenderer.createShaderPipeline(vertexShader, pixelShader);
        }
    }

    public void setShaderPipeline(long pipelineHandle) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setShaderPipeline(pipelineHandle);
        } else {
            VitraNativeRenderer.setShaderPipeline(pipelineHandle);
        }
    }

    public int generateGLTextureId() {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture ID generation");
            return (int) System.nanoTime(); // Temporary ID generation
        } else {
            return VitraNativeRenderer.generateGLTextureId();
        }
    }

    public int getTextureParameter(int target, int pname) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture parameter query");
            return 0;
        } else {
            return VitraNativeRenderer.getTextureParameter(target, pname);
        }
    }

    public int getTextureLevelParameter(int target, int level, int pname) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture level parameter query");
            return 0;
        } else {
            return VitraNativeRenderer.getTextureLevelParameter(target, level, pname);
        }
    }

    public void setPixelStore(int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 pixel store (no-op)");
        } else {
            VitraNativeRenderer.setPixelStore(pname, param);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, long pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, java.nio.IntBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 texture sub-image update (from address)");
        } else {
            VitraNativeRenderer.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
        }
    }

    public void setShaderLightDirection(int index, float x, float y, float z) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setShaderLightDirection(index, x, y, z);
        } else {
            VitraNativeRenderer.setShaderLightDirection(index, x, y, z);
        }
    }

    public void setShaderFogColor(float r, float g, float b, float a) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            com.vitra.render.jni.VitraD3D12Native.setShaderFogColor(r, g, b);
        } else {
            VitraNativeRenderer.setShaderFogColor(r, g, b, a);
        }
    }

    public void setTransformMatrices(float[] mvpArray, float[] mvArray, float[] projArray) {
        if (getRendererType() == RendererType.DIRECTX12 || getRendererType() == RendererType.DIRECTX12_ULTIMATE) {
            logger.debug("DirectX 12 transform matrices");
            // DX12 would handle matrices differently
        } else {
            VitraNativeRenderer.setTransformMatrices(mvpArray, mvArray, projArray);
        }
    }
}
