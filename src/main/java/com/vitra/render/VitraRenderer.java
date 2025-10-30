package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraD3D11Renderer;
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
 * Unified renderer for Vitra supporting both DirectX and DirectX 12 backends
 * Dynamically selects backend based on configuration
 * Uses native DirectX calls through JNI interface
 */
public class VitraRenderer extends AbstractRenderer {
    // Renderer backend instances
    private IVitraRenderer activeRenderer = null;

    // DirectX components
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
        // Get renderer type from config or default to DirectX
        RendererType rendererType = (config != null) ? config.getRendererType() : RendererType.DIRECTX11;
        initialize(rendererType);
    }

    @Override
    public void initialize(RendererType rendererType) {
        if (initialized) {
            logger.warn("VitraRenderer already initialized");
            return;
        }

        if (rendererType == null) {
            throw new IllegalArgumentException("Renderer type cannot be null");
        }

        logger.info("Preparing Vitra unified renderer - Backend: {}", rendererType);

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException(rendererType + " is not supported on this platform. Vitra requires Windows 10+ with DirectX 11 or 12 support.");
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
                    throw new RuntimeException("Unsupported renderer type: " + rendererType + ". Supported types: " +
                        java.util.Arrays.stream(RendererType.values()).filter(RendererType::isSupported).toList());
            }

            initialized = true;
            logger.info("Vitra unified renderer prepared - Backend: {}, native will initialize when window handle is available", rendererType);

        } catch (Exception e) {
            initialized = false; // Reset on failure
            logger.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize DirectX backend
     */
    private void initializeDirectX11Backend() {
        logger.info("Initializing DirectX backend");
        activeRenderer = this; // Use this instance as DirectX renderer

        // Initialize DirectX components
        shaderManager = new D3D11ShaderManager();
        bufferManager = new D3D11BufferManager();

        logger.info("DirectX backend components initialized");
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

        // Actually initialize native DirectX with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug, verbose, and WARP mode from config
                boolean debugMode = isDebugMode();
                boolean verboseMode = isVerboseMode();
                boolean useWarp = isUseWarp();

                logger.info("Initializing native DirectX with debug={}, verbose={}, warp={}", debugMode, verboseMode, useWarp);
                if (verboseMode) {
                    logger.warn("╔════════════════════════════════════════════════════════════╗");
                    logger.warn("║  VERBOSE LOGGING ENABLED - JNI will log EVERYTHING        ║");
                    logger.warn("║  This may impact performance and generate huge log files  ║");
                    logger.warn("║  Disable in config/vitra.properties after debugging       ║");
                    logger.warn("╚════════════════════════════════════════════════════════════╝");
                }

                // Initialize debug system before DirectX initialization
                VitraD3D11Renderer.initializeDebug(debugMode, verboseMode);

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

                // Get actual window dimensions from GLFW
                int[] widthArray = new int[1];
                int[] heightArray = new int[1];
                org.lwjgl.glfw.GLFW.glfwGetWindowSize(windowHandle, widthArray, heightArray);
                int windowWidth = widthArray[0];
                int windowHeight = heightArray[0];
                logger.info("Initializing DirectX with window dimensions: {}x{}", windowWidth, windowHeight);

                boolean success = VitraD3D11Renderer.initializeDirectXSafe(nativeWindowHandle, windowWidth, windowHeight, debugMode, useWarp);
                if (success) {
                    logger.info("Native DirectX initialized successfully (debug={})", debugMode);

                    // Initialize uniform system (VulkanMod-style supplier-based architecture)
                    logger.info("Initializing uniform system...");
                    initializeUniformSystem();
                    logger.info("Uniform system initialized: vertex CB={}, fragment CB={}",
                        vertexConstantBuffer, fragmentConstantBuffer);

                    // NOTE: Shader preloading disabled - we now use runtime HLSL compilation in ShaderInstanceMixin
                    // The old .cso preload system conflicts with the new runtime compilation system
                    // ShaderInstanceMixin handles all shader loading with shader variants at runtime
                    // if (shaderManager != null) {
                    //     logger.info("Preloading DirectX shaders...");
                    //     shaderManager.preloadShaders();
                    //     logger.info("Shader preloading completed: {}", shaderManager.getCacheStats());
                    // }
                    logger.info("Using runtime HLSL shader compilation (ShaderInstanceMixin) - no preloading needed");

                    // REMOVED: Default shader pipeline binding (obsolete with VulkanMod pattern)
                    // Shaders are now loaded by Minecraft and pipelines are created by ShaderInstanceMixin
                    // Each shader creates its own pipeline with the correct vertex format
                    logger.info("Native D3D11 initialized - shaders will be loaded by Minecraft");

                    // Load shaders after native initialization
                    if (core != null) {
                        logger.info("Loading custom shaders...");
                        core.loadShaders();
                    } else {
                        logger.warn("VitraCore not set, skipping custom shader loading");
                    }

                    return true;
                } else {
                    logger.error("Native DirectX initialization failed");
                    return false;
                }
            } catch (Exception e) {
                logger.error("Exception during native DirectX initialization", e);
                return false;
            }
        }
        return false;
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        logger.info("Shutting down Vitra JNI DirectX renderer...");

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
            VitraD3D11Renderer.shutdownSafe();
            logger.info("Native DirectX shutdown completed");

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

        // For D3D11, activeRenderer == this, so avoid infinite recursion
        if (activeRenderer == this) {
            // Return true if Java-side components are initialized
            // Native initialization happens later in initializeWithWindowHandle()
            return initialized;
        }

        // For D3D12, activeRenderer is a separate instance
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

        // For D3D11, activeRenderer == this, so avoid infinite recursion
        if (activeRenderer == this) {
            // Check native DirectX 11 initialization and window handle
            return initialized && VitraD3D11Renderer.isInitialized() && windowHandle != 0L;
        }

        // For D3D12, activeRenderer is a separate instance
        return activeRenderer.isInitialized() && windowHandle != 0L;
    }

    @Override
    public RendererType getRendererType() {
        // Return the configured renderer type
        return (config != null) ? config.getRendererType() : RendererType.DIRECTX11;
    }

    @Override
    public void beginFrame() {
        if (activeRenderer == null || !activeRenderer.isInitialized()) return;

        // Special handling for DirectX specific features
        if (getRendererType() == RendererType.DIRECTX11) {
            // Handle pending resize
            if (resizePending) {
                // Actual resize will be handled by resize() method
                resizePending = false;
            }

            // Update constant buffers EVERY frame for DirectX
            // DirectX may unbind constant buffers during pipeline changes,
            // so we need to rebind them every frame to be safe
            uploadConstantBuffers();
            uniformsDirty = false;
        }

        // For D3D11, activeRenderer == this, so call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                VitraD3D11Renderer.beginFrame();
            }
        } else {
            // For D3D12, delegate to active renderer
            activeRenderer.beginFrame();
        }
    }

    @Override
    public void endFrame() {
        if (activeRenderer == null || !activeRenderer.isInitialized()) return;

        // For D3D11, activeRenderer == this, so call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                VitraD3D11Renderer.endFrame();
                // Present the frame to the screen
                VitraD3D11Renderer.presentFrame();
            }
        } else {
            // For D3D12, delegate to active renderer
            activeRenderer.endFrame();
        }

        // Process debug messages for DirectX
        if (getRendererType() == RendererType.DIRECTX11 && VitraD3D11Renderer.isDebugEnabled()) {
            VitraD3D11Renderer.processDebugMessages();
        }
        // Process debug messages for DirectX 12
        else if (getRendererType() == RendererType.DIRECTX12 && d3d12Renderer != null) {
            d3d12Renderer.processDebugMessages();
        }
    }

    @Override
    public void present() {
        // Frame presentation is handled by CommandEncoder.presentTexture()
        // which is called by Minecraft's rendering pipeline after the frame is complete
    }

    @Override
    public void resize(int width, int height) {
        if (activeRenderer == null || !activeRenderer.isInitialized()) return;

        // For D3D11, activeRenderer == this, so call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                VitraD3D11Renderer.resize(width, height);
                logger.info("Renderer view resized to {}x{} (backend: {})", width, height, getRendererType());
            }
        } else {
            // For D3D12, delegate to active renderer
            activeRenderer.resize(width, height);
            logger.info("Renderer view resized to {}x{} (backend: {})", width, height, getRendererType());
        }
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        if (activeRenderer == null || !activeRenderer.isInitialized()) return;

        // For D3D11, activeRenderer == this, so call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                // D3D11 clear uses mask, so set clear color then clear
                VitraD3D11Renderer.setClearColor(r, g, b, a);
                VitraD3D11Renderer.clear(0x4100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
            }
        } else {
            // For D3D12, delegate to active renderer
            activeRenderer.clear(r, g, b, a);
        }
    }

    // Feature support delegates to active renderer
    @Override
    public boolean isRayTracingSupported() {
        if (activeRenderer == null) return false;
        // D3D11 doesn't support ray tracing
        if (activeRenderer == this) return false;
        return activeRenderer.isRayTracingSupported();
    }

    @Override
    public boolean isVariableRateShadingSupported() {
        if (activeRenderer == null) return false;
        // D3D11 doesn't support variable rate shading
        if (activeRenderer == this) return false;
        return activeRenderer.isVariableRateShadingSupported();
    }

    @Override
    public boolean isMeshShadersSupported() {
        if (activeRenderer == null) return false;
        // D3D11 doesn't support mesh shaders
        if (activeRenderer == this) return false;
        return activeRenderer.isMeshShadersSupported();
    }

    @Override
    public boolean isGpuDrivenRenderingSupported() {
        if (activeRenderer == null) return false;
        // D3D11 doesn't support GPU-driven rendering
        if (activeRenderer == this) return false;
        return activeRenderer.isGpuDrivenRenderingSupported();
    }

    // Performance metrics delegate to active renderer
    @Override
    public float getGpuUtilization() {
        if (activeRenderer == null) return 0.0f;
        // D3D11 doesn't track GPU utilization
        if (activeRenderer == this) return 0.0f;
        return activeRenderer.getGpuUtilization();
    }

    @Override
    public long getFrameTime() {
        if (activeRenderer == null) return 0L;
        // D3D11 doesn't track frame time
        if (activeRenderer == this) return 0L;
        return activeRenderer.getFrameTime();
    }

    @Override
    public int getDrawCallsPerFrame() {
        if (activeRenderer == null) return 0;
        // D3D11 doesn't track draw calls
        if (activeRenderer == this) return 0;
        return activeRenderer.getDrawCallsPerFrame();
    }

    @Override
    public void resetPerformanceCounters() {
        if (activeRenderer == null) return;
        // D3D11 doesn't have performance counters to reset
        if (activeRenderer == this) return;
        activeRenderer.resetPerformanceCounters();
    }

    @Override
    public void setDebugMode(boolean enabled) {
        // Debug mode is handled during initialization
    }

    @Override
    public void captureFrame(String filename) {
        // Not implemented for DirectX
    }

    @Override
    public String getRendererStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX Renderer Stats ===\n");
        stats.append("Prepared: ").append(isInitialized()).append("\n");
        stats.append("Fully Initialized: ").append(isFullyInitialized()).append("\n");
        stats.append("Window Handle: 0x").append(Long.toHexString(windowHandle)).append("\n");
        stats.append("Debug Mode: ").append(config != null ? config.isDebugMode() : false).append("\n");
        stats.append("Verbose Logging: ").append(config != null ? config.isVerboseLogging() : false).append("\n");

        // Include debug stats if available
        if (VitraD3D11Renderer.isDebugEnabled()) {
            stats.append("\n--- Debug System ---\n");
            stats.append(VitraD3D11Renderer.getDebugStats());
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
        VitraD3D11Renderer.processDebugMessages();
    }

    public boolean isDebugAvailable() {
        return VitraD3D11Renderer.isDebugEnabled();
    }

    // Feature access methods
    @Override
    public VitraRenderer getD3D11Renderer() {
        return (getRendererType() == RendererType.DIRECTX11) ? this : null;
    }

    @Override
    public com.vitra.render.jni.VitraD3D12Renderer getD3D12Renderer() {
        return (getRendererType() == RendererType.DIRECTX12) ? d3d12Renderer : null;
    }

    @Override
    public Object getShaderManager() {
        if (activeRenderer == null) return null;
        // For D3D11, return the shader manager directly
        if (activeRenderer == this) return shaderManager;
        return activeRenderer.getShaderManager();
    }

    @Override
    public Object getBufferManager() {
        if (activeRenderer == null) return null;
        // For D3D11, return the buffer manager directly
        if (activeRenderer == this) return bufferManager;
        return activeRenderer.getBufferManager();
    }

    // Getters for DirectX components
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
            return VitraD3D11Renderer.isInitialized() ? 0x12345678L : 0L;
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
            VitraD3D11Renderer.waitForIdle();
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
            VitraD3D11Renderer.drawMesh(vertexBuffer, indexBuffer, vertexCount, 0, primitiveMode, vertexSize);
        }
    }

    // Screen rendering methods
    @Override
    public void clearDepthBuffer() {
        if (activeRenderer == null) return;
        // For D3D11, call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                VitraD3D11Renderer.clearDepthBuffer(1.0f); // Clear to max depth
            }
        } else {
            activeRenderer.clearDepthBuffer();
        }
    }

    // Screenshot and GPU synchronization methods
    @Override
    public void waitForGpuCommands() {
        if (activeRenderer == null) return;
        // For D3D11, call native directly
        if (activeRenderer == this) {
            if (isFullyInitialized()) {
                VitraD3D11Renderer.waitForIdle();
            }
        } else {
            activeRenderer.waitForGpuCommands();
        }
    }

    // Window and display methods
    public void setVsync(boolean vsync) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.setVsync(vsync);
            System.out.println("[Vitra] Vsync " + (vsync ? "enabled" : "disabled"));
        }
    }

    public void presentFrame() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.presentFrame();
        }
    }

    // New debug and utility methods for enhanced mixin structure

    // GUI Optimization Methods
    public void optimizeCrosshairRendering() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeCrosshairRendering();
        }
    }

    public void beginTextBatch() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.beginTextBatch();
        }
    }

    public void endTextBatch() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.endTextBatch();
        }
    }

    // Screen Optimization Methods
    public void optimizeScreenBackground() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeScreenBackground(0); // 0 = default screen type
        }
    }

    public void optimizeDirtBackground(int vOffset) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeDirtBackground(vOffset);
        }
    }

    public void optimizeTooltipRendering(int lineCount) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeTooltipRendering(lineCount);
        }
    }

    public void optimizeSlotRendering(int x, int y) {
        if (isFullyInitialized()) {
            // Combine x and y into a single slot ID (or use just x as slot ID)
            int slotId = x + y * 100; // Simple combination
            VitraD3D11Renderer.optimizeSlotRendering(slotId);
        }
    }

    public void optimizeSlotHighlight(int x, int y) {
        if (isFullyInitialized()) {
            // Combine x and y into a single slot ID
            int slotId = x + y * 100; // Simple combination
            VitraD3D11Renderer.optimizeSlotHighlight(slotId);
        }
    }

    public void optimizeContainerLabels() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeContainerLabels(0); // 0 = default label ID
        }
    }

    public void optimizeContainerBackground() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeContainerBackground(0); // 0 = default container ID
        }
    }

    public void optimizePanoramaRendering() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizePanoramaRendering(0); // 0 = default panorama ID
        }
    }

    public void optimizeLogoRendering() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeLogoRendering(64); // 64 = default logo size
        }
    }

    public void optimizeButtonRendering() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeButtonRendering(0); // 0 = default button ID
        }
    }

    public void optimizeFadingBackground(int alpha) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeFadingBackground(alpha);
        }
    }

    // Utility Methods
    public void prepareRenderContext() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.prepareRenderContext();
        }
    }

    public void cleanupRenderContext() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.cleanupRenderContext();
        }
    }

    public int getOptimalFramerateLimit() {
        if (isFullyInitialized()) {
            return VitraD3D11Renderer.getOptimalFramerateLimit();
        }
        return 0;
    }

    public void handleDisplayResize() {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.handleDisplayResize(1920, 1080); // Default dimensions, will be updated by actual resize events
        }
    }

    public void setWindowActiveState(boolean active) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.setWindowActiveState(active);
        }
    }

    public static boolean isDirectX11Initialized() {
        return VitraD3D11Renderer.isInitialized();
    }

    // Shader Helper Methods
    public void precompileShaderForDirectX11(Object shader) {
        if (isFullyInitialized()) {
            String shaderSource = shader.toString();
            VitraD3D11Renderer.precompileShaderForDirectX11(shaderSource, 0); // 0 = vertex shader
        }
    }

    public boolean isShaderDirectX11Compatible(Object shader) {
        if (isFullyInitialized()) {
            // Convert shader to byte array for compatibility check
            byte[] shaderData = shader.toString().getBytes();
            return VitraD3D11Renderer.isShaderDirectX11Compatible(shaderData);
        }
        return false;
    }

    public Object getOptimizedDirectX11Shader(Object original) {
        if (isFullyInitialized()) {
            String shaderName = original.toString();
            long handle = VitraD3D11Renderer.getOptimizedDirectX11Shader(shaderName);
            return handle != 0 ? handle : original;
        }
        return original;
    }

    // Matrix Helper Methods
    public void optimizeMatrixMultiplication(Object matrix1, Object matrix2) {
        if (isFullyInitialized()) {
            float[] mat1 = convertToFloatArray(matrix1);
            float[] mat2 = convertToFloatArray(matrix2);
            float[] result = VitraD3D11Renderer.optimizeMatrixMultiplication(mat1, mat2);
            // Optionally store the result back if the matrices are mutable
        }
    }

    public void optimizeMatrixInversion(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            float[] result = VitraD3D11Renderer.optimizeMatrixInversion(mat);
            // Optionally store the result back if the matrix is mutable
        }
    }

    public void optimizeMatrixTranspose(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            float[] result = VitraD3D11Renderer.optimizeMatrixTranspose(mat);
            // Optionally store the result back if the matrix is mutable
        }
    }

    public void adjustOrthographicProjection(float left, float right, float bottom, float top, float zNear, float zFar) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.adjustOrthographicProjection(left, right, bottom, top, zNear, zFar);
        }
    }

    public void adjustPerspectiveProjection(float fovy, float aspect, float zNear, float zFar) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.adjustPerspectiveProjection(fovy, aspect, zNear, zFar);
        }
    }

    public void optimizeTranslationMatrix(float x, float y, float z) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeTranslationMatrix(x, y, z);
        }
    }

    public void optimizeScaleMatrix(float x, float y, float z) {
        if (isFullyInitialized()) {
            VitraD3D11Renderer.optimizeScaleMatrix(x, y, z);
        }
    }

    public void optimizeRotationMatrix(float angle, float x, float y, float z) {
        if (isFullyInitialized()) {
            float[] axis = {x, y, z};
            float[] result = VitraD3D11Renderer.optimizeRotationMatrix(angle, axis);
            // Optionally store the result back if needed
        }
    }

    public boolean isMatrixDirectX11Optimized(Object matrix) {
        if (isFullyInitialized()) {
            float[] mat = convertToFloatArray(matrix);
            return VitraD3D11Renderer.isMatrixDirectX11Optimized(mat);
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
     * Called during DirectX initialization after device creation.
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

    /**
     * Setup orthographic projection for UI rendering
     *
     * This method MUST be called before rendering UI elements to prevent them from
     * rotating with the world/panorama. It sets:
     * - Orthographic projection matrix (screen-space 2D)
     * - Identity model-view matrix
     * - Disables depth test/write
     * - Enables alpha blending
     *
     * @param width Screen width in pixels
     * @param height Screen height in pixels
     */
    public void setupUIProjection(int width, int height) {
        logger.info("[DEBUG_UI] Setting up orthographic projection for UI: {}x{}", width, height);

        // Create orthographic projection matrix (0,0 = top-left, width,height = bottom-right)
        // This maps screen pixels directly to clip space without perspective
        org.joml.Matrix4f orthoMatrix = new org.joml.Matrix4f()
            .setOrtho(0.0f, (float)width, (float)height, 0.0f, 0.0f, 1.0f);

        // Set identity model-view (no rotation, no translation)
        org.joml.Matrix4f identityMatrix = new org.joml.Matrix4f().identity();

        // Update VRenderSystem with UI matrices
        // Use JOML's .get() method to copy matrix data into ByteBuffer (VulkanMod pattern)
        orthoMatrix.get(0, VRenderSystem.getProjectionMatrix().byteBuffer());
        identityMatrix.get(0, VRenderSystem.getModelViewMatrix().byteBuffer());
        VRenderSystem.calculateMVP();  // Recalculate MVP = Projection * ModelView

        logger.info("[DEBUG_UI] Uploading ORTHO matrix to UI shader (no rotation)");

        // Force immediate upload to GPU
        uploadConstantBuffers();

        logger.info("[DEBUG_UI] UI projection setup complete - UI should render without rotation");
    }

    // ============================================================================
    // RENDERER-AGNOSTIC WRAPPER METHODS
    // These methods delegate to the appropriate backend (DirectX or 12)
    // Mixins should call these instead of VitraNativeRenderer directly
    // ============================================================================

    public void setViewport(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setViewport(x, y, width, height, 0.0f, 1.0f);
        } else {
            VitraD3D11Renderer.setViewport(x, y, width, height);
        }
    }

    public void setScissor(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12) {
            // DirectX 12 scissor - would need implementation
            logger.debug("DirectX 12 scissor not yet implemented");
        } else {
            VitraD3D11Renderer.setScissor(x, y, width, height);
        }
    }

    public void resetScissor() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 resetScissor not yet implemented");
        } else {
            VitraD3D11Renderer.resetScissor();
        }
    }

    public void enableBlend() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 blend state handled via PSO");
        } else {
            VitraD3D11Renderer.enableBlend();
        }
    }

    public void disableBlend() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 blend state handled via PSO");
        } else {
            VitraD3D11Renderer.disableBlend();
        }
    }

    public void blendFunc(int sfactor, int dfactor) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 blend func handled via PSO");
        } else {
            VitraD3D11Renderer.blendFunc(sfactor, dfactor);
        }
    }

    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 blend func handled via PSO");
        } else {
            VitraD3D11Renderer.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        }
    }

    public void blendEquation(int mode) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 blend equation handled via PSO");
        } else {
            VitraD3D11Renderer.blendEquation(mode);
        }
    }

    public void enableCull() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 cull state handled via PSO");
        } else {
            VitraD3D11Renderer.enableCull();
        }
    }

    public void disableCull() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 cull state handled via PSO");
        } else {
            VitraD3D11Renderer.disableCull();
        }
    }

    public void enableDepthTest() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 depth test handled via PSO");
        } else {
            VitraD3D11Renderer.enableDepthTest();
        }
    }

    public void disableDepthTest() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 depth test handled via PSO");
        } else {
            VitraD3D11Renderer.disableDepthTest();
        }
    }

    public void depthMask(boolean flag) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 depth mask handled via PSO");
        } else {
            VitraD3D11Renderer.depthMask(flag);
        }
    }

    public void depthFunc(int func) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 depth func handled via PSO");
        } else {
            VitraD3D11Renderer.depthFunc(func);
        }
    }

    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 color mask handled via PSO");
        } else {
            VitraD3D11Renderer.colorMask(red, green, blue, alpha);
        }
    }

    public void polygonOffset(float factor, float units) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraD3D11Renderer.polygonOffset(factor, units);
        }
    }

    public void enablePolygonOffset() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraD3D11Renderer.enablePolygonOffset();
        }
    }

    public void disablePolygonOffset() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 polygon offset handled via rasterizer state");
        } else {
            VitraD3D11Renderer.disablePolygonOffset();
        }
    }

    public void logicOp(int opcode) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraD3D11Renderer.logicOp(opcode);
        }
    }

    public void enableColorLogicOp() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraD3D11Renderer.enableColorLogicOp();
        }
    }

    public void disableColorLogicOp() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 logic op not commonly used");
        } else {
            VitraD3D11Renderer.disableColorLogicOp();
        }
    }

    // Texture methods
    public void bindTexture(int textureId) {
        if (getRendererType() == RendererType.DIRECTX12) {
            // Use D3D11GlTexture mapping system (works for both DX11 and DX12)
            com.vitra.render.d3d11.D3D11GlTexture.bindTexture(textureId);
        } else {
            com.vitra.render.d3d11.D3D11GlTexture.bindTexture(textureId);
        }
    }

    public void deleteTexture(int textureId) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.d3d11.D3D11GlTexture.deleteTexture(textureId);
        } else {
            VitraD3D11Renderer.deleteTexture(textureId);
        }
    }

    public void texParameteri(int target, int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture parameters handled during texture creation");
        } else {
            VitraD3D11Renderer.texParameteri(target, pname, param);
        }
    }

    public void pixelStore(int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 pixel store not needed");
        } else {
            VitraD3D11Renderer.pixelStore(pname, param);
        }
    }

    // Shader/uniform methods
    public void setShaderColor(float r, float g, float b, float a) {
        // CRITICAL FIX: Update VRenderSystem buffer first (like VulkanMod does)
        // This buffer is what gets uploaded to shaders via the uniform system
        VRenderSystem.setShaderColor(r, g, b, a);

        // Then optionally sync to native (currently stub, not needed for D3D11)
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setShaderColor(r, g, b, a);
        } else {
            VitraD3D11Renderer.setShaderColor(r, g, b, a);
        }
    }

    public void setProjectionMatrix(float[] matrix) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setProjectionMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        } else {
            VitraD3D11Renderer.setProjectionMatrix(matrix);
        }
    }

    public void setTextureMatrix(float[] matrix) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setTextureMatrix(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        } else {
            VitraD3D11Renderer.setTextureMatrix(matrix);
        }
    }

    // Drawing topology
    public void setPrimitiveTopology(int mode) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 topology set via PSO or draw call");
        } else {
            VitraD3D11Renderer.setPrimitiveTopology(mode);
        }
    }

    // Framebuffer methods
    public void bindFramebuffer(int target, int framebuffer) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 framebuffer binding handled differently");
        } else {
            VitraD3D11Renderer.bindFramebuffer(target, framebuffer);
        }
    }

    public void recreateSwapChain() {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.recreateSwapChain();
        } else {
            VitraD3D11Renderer.recreateSwapChain();
        }
    }

    // Info/debug methods
    public String getDeviceInfo() {
        if (getRendererType() == RendererType.DIRECTX12) {
            return "DirectX 12 Ultimate Renderer";
        } else {
            return VitraD3D11Renderer.getDeviceInfo();
        }
    }

    public int getMaxTextureSize() {
        if (getRendererType() == RendererType.DIRECTX12) {
            return com.vitra.render.jni.VitraD3D12Native.getMaxTextureSize();
        } else {
            return VitraD3D11Renderer.getMaxTextureSize();
        }
    }

    // Additional methods needed by GlStateManagerM
    public void setClearColor(float r, float g, float b, float a) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.clear(r, g, b, a);
        } else {
            VitraD3D11Renderer.setClearColor(r, g, b, a);
        }
    }

    public void clear(int mask) {
        if (getRendererType() == RendererType.DIRECTX12) {
            // DirectX 12 clear based on mask bits
            boolean clearColor = (mask & 0x4000) != 0; // GL_COLOR_BUFFER_BIT
            boolean clearDepth = (mask & 0x0100) != 0; // GL_DEPTH_BUFFER_BIT
            if (clearColor) com.vitra.render.jni.VitraD3D12Native.clear(0, 0, 0, 0);
            if (clearDepth) com.vitra.render.jni.VitraD3D12Native.clearDepthBuffer(1.0f);
        } else {
            VitraD3D11Renderer.clear(mask);
        }
    }

    public void clearDepth(float depth) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.clearDepthBuffer(depth);
        } else {
            VitraD3D11Renderer.clearDepth(depth);
        }
    }

    /**
     * VulkanMod-compatible clear method for clearing attachments (color/depth buffers)
     * Used by ScreenMixin to clear depth buffer after blurred background
     * @param mask GL_DEPTH_BUFFER_BIT (256) or GL_COLOR_BUFFER_BIT (0x4000) or both
     */
    public void clearAttachments(int mask) {
        clear(mask);
    }

    public void setPolygonMode(int mode) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 polygon mode handled via rasterizer state");
        } else {
            VitraD3D11Renderer.setPolygonMode(mode);
        }
    }

    public void setTextureParameterf(int target, int pname, float param) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture parameters handled during texture creation");
        } else {
            VitraD3D11Renderer.setTextureParameterf(target, pname, param);
        }
    }

    public int getTexLevelParameter(int target, int level, int pname) {
        if (getRendererType() == RendererType.DIRECTX12) {
            return 0; // DirectX 12 doesn't have direct equivalent
        } else {
            return VitraD3D11Renderer.getTexLevelParameter(target, level, pname);
        }
    }

    public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 framebuffer handled via render targets");
        } else {
            VitraD3D11Renderer.framebufferTexture2D(target, attachment, textarget, texture, level);
        }
    }

    public void bindRenderbuffer(int target, int renderbuffer) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 renderbuffer handled via textures");
        } else {
            VitraD3D11Renderer.bindRenderbuffer(target, renderbuffer);
        }
    }

    public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 framebuffer renderbuffer handled via render targets");
        } else {
            VitraD3D11Renderer.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
        }
    }

    public void renderbufferStorage(int target, int internalformat, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 renderbuffer storage handled via textures");
        } else {
            VitraD3D11Renderer.renderbufferStorage(target, internalformat, width, height);
        }
    }

    public int checkFramebufferStatus(int framebuffer, int target) {
        if (getRendererType() == RendererType.DIRECTX12) {
            return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
        } else {
            return VitraD3D11Renderer.checkFramebufferStatus(framebuffer, target);
        }
    }

    public void useProgram(int program) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 program binding handled via PSO");
        } else {
            VitraD3D11Renderer.useProgram(program);
        }
    }

    public void deleteProgram(int program) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 program deletion handled via PSO cleanup");
        } else {
            VitraD3D11Renderer.deleteProgram(program);
        }
    }

    // ============================================================================
    // Additional renderer-agnostic wrapper methods for mixin compatibility
    // ============================================================================

    public void activeTexture(int texture) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture unit selection via descriptor table");
        } else {
            VitraD3D11Renderer.activeTexture(texture);
        }
    }

    public void bindBuffer(int target, int buffer) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 buffer binding via command list");
        } else {
            VitraD3D11Renderer.bindBuffer(target, buffer);
        }
    }

    public void bufferData(int target, java.nio.ByteBuffer data, int usage) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 buffer data upload via staging");
        } else {
            VitraD3D11Renderer.bufferData(target, data, usage);
        }
    }

    public void deleteBuffer(int buffer) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.releaseManagedResource(buffer);
        } else {
            VitraD3D11Renderer.deleteBuffer(buffer);
        }
    }

    public java.nio.ByteBuffer mapBuffer(int target, int access, int length) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 buffer mapping via Map");
            long handle = com.vitra.render.jni.VitraD3D12Native.mapBuffer(target);
            return null; // TODO: implement proper ByteBuffer wrapping
        } else {
            return VitraD3D11Renderer.mapBuffer(target, access, length);
        }
    }

    public void unmapBuffer(int target) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.unmapBuffer(target);
        } else {
            VitraD3D11Renderer.unmapBuffer(target);
        }
    }

    public void texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            byte[] data = pixels != null ? new byte[pixels.remaining()] : null;
            if (data != null) pixels.get(data);
            com.vitra.render.jni.VitraD3D12Native.setTextureData(target, width, height, format, data);
        } else {
            VitraD3D11Renderer.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture sub-image update");
            byte[] data = pixels != null ? new byte[pixels.remaining()] : null;
            if (data != null) pixels.get(data);
            com.vitra.render.jni.VitraD3D12Native.setTextureData(target, width, height, format, data);
        } else {
            long address = pixels != null ? org.lwjgl.system.MemoryUtil.memAddress(pixels) : 0;
            VitraD3D11Renderer.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, address);
        }
    }

    public long createConstantBuffer(int size) {
        if (getRendererType() == RendererType.DIRECTX12) {
            return com.vitra.render.jni.VitraD3D12Native.createManagedUploadBuffer(size);
        } else {
            return VitraD3D11Renderer.createConstantBuffer(size);
        }
    }

    public void uploadAndBindUBOs() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 UBO upload via descriptor table");
        } else {
            VitraD3D11Renderer.uploadAndBindUBOs();
        }
    }

    public void destroyResource(long handle) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.releaseManagedResource(handle);
        } else {
            VitraD3D11Renderer.destroyResource(handle);
        }
    }

    public void setScissorRect(int x, int y, int width, int height) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setViewport(x, y, width, height, 0.0f, 1.0f);
        } else {
            VitraD3D11Renderer.setScissorRect(x, y, width, height);
        }
    }

    public void setRasterizerState(int fillMode, int cullMode, boolean frontCounterClockwise) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setRasterizerState(fillMode, cullMode, frontCounterClockwise,
                0, 0.0f, 0.0f, true, false, false, 0, false);
        } else {
            VitraD3D11Renderer.setRasterizerState(fillMode, cullMode, frontCounterClockwise);
        }
    }

    public long createGLProgramShader(byte[] bytecode, int length, int type) {
        if (getRendererType() == RendererType.DIRECTX12) {
            return com.vitra.render.jni.VitraD3D12Native.createShader(bytecode, type);
        } else {
            return VitraD3D11Renderer.createGLProgramShader(bytecode, length, type);
        }
    }

    public long createShaderPipeline(long vertexShader, long pixelShader) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 PSO creation from shaders");
            return com.vitra.render.jni.VitraD3D12Native.getDefaultShaderPipeline();
        } else {
            return VitraD3D11Renderer.createShaderPipeline(vertexShader, pixelShader);
        }
    }

    public void setShaderPipeline(long pipelineHandle) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setShaderPipeline(pipelineHandle);
        } else {
            VitraD3D11Renderer.setShaderPipeline(pipelineHandle);
        }
    }

    public int generateGLTextureId() {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture ID generation");
            return (int) System.nanoTime(); // Temporary ID generation
        } else {
            return VitraD3D11Renderer.generateGLTextureId();
        }
    }

    public int getTextureParameter(int target, int pname) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture parameter query");
            return 0;
        } else {
            return VitraD3D11Renderer.getTextureParameter(target, pname);
        }
    }

    public int getTextureLevelParameter(int target, int level, int pname) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture level parameter query");
            return 0;
        } else {
            return VitraD3D11Renderer.getTextureLevelParameter(target, level, pname);
        }
    }

    public void setPixelStore(int pname, int param) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 pixel store (no-op)");
        } else {
            VitraD3D11Renderer.setPixelStore(pname, param);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, long pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraD3D11Renderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, java.nio.ByteBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraD3D11Renderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void glGetTexImage(int tex, int level, int format, int type, java.nio.IntBuffer pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture readback");
        } else {
            VitraD3D11Renderer.glGetTexImage(tex, level, format, type, pixels);
        }
    }

    public void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 texture sub-image update (from address)");
        } else {
            VitraD3D11Renderer.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
        }
    }

    public void setShaderLightDirection(int index, float x, float y, float z) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setShaderLightDirection(index, x, y, z);
        } else {
            VitraD3D11Renderer.setShaderLightDirection(index, x, y, z);
        }
    }

    public void setShaderFogColor(float r, float g, float b, float a) {
        if (getRendererType() == RendererType.DIRECTX12) {
            com.vitra.render.jni.VitraD3D12Native.setShaderFogColor(r, g, b);
        } else {
            VitraD3D11Renderer.setShaderFogColor(r, g, b, a);
        }
    }

    public void setTransformMatrices(float[] mvpArray, float[] mvArray, float[] projArray) {
        if (getRendererType() == RendererType.DIRECTX12) {
            logger.debug("DirectX 12 transform matrices");
            // DX12 would handle matrices differently
        } else {
            VitraD3D11Renderer.setTransformMatrices(mvpArray, mvArray, projArray);
        }
    }
}
