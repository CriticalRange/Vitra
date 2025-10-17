package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.D3D11BufferManager;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.debug.VitraDebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI DirectX 11 renderer for Vitra
 * Uses native DirectX 11 calls through JNI interface
 */
public class VitraRenderer extends AbstractRenderer {
    // DirectX 11 components
    private D3D11ShaderManager shaderManager;
    private D3D11BufferManager bufferManager;

    public VitraRenderer() {
        super(VitraRenderer.class);
    }

    // Interface implementation methods
    @Override
    public void initialize() {
        initialize(RendererType.DIRECTX11);
    }

    @Override
    public void initialize(RendererType rendererType) {
        if (initialized) {
            logger.warn("VitraRenderer already initialized");
            return;
        }

        logger.info("Preparing Vitra JNI DirectX 11 renderer (deferred initialization)");

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException("DirectX 11 is not supported on this platform. Vitra requires Windows 10+.");
            }

            // Initialize DirectX 11 components
            shaderManager = new D3D11ShaderManager();
            bufferManager = new D3D11BufferManager();

            initialized = true;
            logger.info("Vitra renderer prepared, native DirectX 11 will initialize when window handle is available");

        } catch (Exception e) {
            logger.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed", e);
        }
    }

    @Override
    public boolean initializeWithWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        logger.info("JNI DirectX 11 window handle set: 0x{}", Long.toHexString(windowHandle));

        // Actually initialize native DirectX 11 with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug and verbose mode from config
                boolean debugMode = isDebugMode();
                boolean verboseMode = isVerboseMode();

                logger.info("Initializing native DirectX 11 with debug={}, verbose={}", debugMode, verboseMode);
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

                boolean success = VitraNativeRenderer.initializeDirectXSafe(nativeWindowHandle, 1920, 1080, debugMode);
                if (success) {
                    logger.info("Native DirectX 11 initialized successfully (debug={})", debugMode);

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
        return super.isInitialized() && VitraNativeRenderer.isInitialized();
    }

    @Override
    public RendererType getRendererType() {
        return RendererType.DIRECTX11;
    }

    @Override
    public void beginFrame() {
        if (isInitialized()) {
            // Handle pending resize
            if (resizePending) {
                // Actual resize will be handled by resize() method
                resizePending = false;
            }
            VitraNativeRenderer.beginFrameSafe();
        }
    }

    @Override
    public void endFrame() {
        if (isInitialized()) {
            VitraNativeRenderer.endFrameSafe();
        }
    }

    @Override
    public void present() {
        // Frame presentation is handled by CommandEncoder.presentTexture()
        // which is called by Minecraft's rendering pipeline after the frame is complete
    }

    @Override
    public void resize(int width, int height) {
        if (isInitialized()) {
            VitraNativeRenderer.resize(width, height);
            logger.info("DirectX 11 view resized to {}x{}", width, height);
        }
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        if (isInitialized()) {
            VitraNativeRenderer.clear(r, g, b, a);
        }
    }

    // DirectX 11 doesn't support these advanced features
    @Override
    public boolean isRayTracingSupported() {
        return false;
    }

    @Override
    public boolean isVariableRateShadingSupported() {
        return false;
    }

    @Override
    public boolean isMeshShadersSupported() {
        return false;
    }

    @Override
    public boolean isGpuDrivenRenderingSupported() {
        return false;
    }

    // DirectX 11 doesn't expose these performance metrics
    @Override
    public float getGpuUtilization() {
        return 0.0f;
    }

    @Override
    public long getFrameTime() {
        return 0L;
    }

    @Override
    public int getDrawCallsPerFrame() {
        return 0;
    }

    @Override
    public void resetPerformanceCounters() {
        // No-op for DirectX 11
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
        stats.append("Initialized: ").append(isInitialized()).append("\n");
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
        return this;
    }

    @Override
    public com.vitra.render.jni.VitraD3D12Renderer getDirectX12Renderer() {
        return null;
    }

    @Override
    public Object getShaderManager() {
        return shaderManager;
    }

    @Override
    public Object getBufferManager() {
        return bufferManager;
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
        if (isInitialized()) {
            // Use device info to get a handle for verification
            // This doesn't return the actual device handle but provides a way to verify initialization
            return VitraNativeRenderer.isInitialized() ? 0x12345678L : 0L;
        }
        return 0L;
    }

    // Additional methods for VulkanMod compatibility

    public static VitraRenderer getRenderer() {
        return (VitraRenderer) com.vitra.VitraMod.getRenderer();
    }

    public void waitForIdle() {
        if (isInitialized()) {
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

    // New debug and utility methods for enhanced mixin structure

    // GUI Optimization Methods
    public void optimizeCrosshairRendering() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeCrosshairRendering();
        }
    }

    public void beginTextBatch() {
        if (isInitialized()) {
            VitraNativeRenderer.beginTextBatch();
        }
    }

    public void endTextBatch() {
        if (isInitialized()) {
            VitraNativeRenderer.endTextBatch();
        }
    }

    // Screen Optimization Methods
    public void optimizeScreenBackground() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeScreenBackground();
        }
    }

    public void optimizeDirtBackground(int vOffset) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeDirtBackground(vOffset);
        }
    }

    public void optimizeTooltipRendering(int lineCount) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeTooltipRendering(lineCount);
        }
    }

    public void optimizeSlotRendering(int x, int y) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeSlotRendering(x, y);
        }
    }

    public void optimizeSlotHighlight(int x, int y) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeSlotHighlight(x, y);
        }
    }

    public void optimizeContainerLabels() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeContainerLabels();
        }
    }

    public void optimizeContainerBackground() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeContainerBackground();
        }
    }

    public void optimizePanoramaRendering() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizePanoramaRendering();
        }
    }

    public void optimizeLogoRendering() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeLogoRendering();
        }
    }

    public void optimizeButtonRendering() {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeButtonRendering();
        }
    }

    public void optimizeFadingBackground(int alpha) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeFadingBackground(alpha);
        }
    }

    // Utility Methods
    public void prepareRenderContext() {
        if (isInitialized()) {
            VitraNativeRenderer.prepareRenderContext();
        }
    }

    public void cleanupRenderContext() {
        if (isInitialized()) {
            VitraNativeRenderer.cleanupRenderContext();
        }
    }

    public int getOptimalFramerateLimit() {
        if (isInitialized()) {
            return VitraNativeRenderer.getOptimalFramerateLimit();
        }
        return 0;
    }

    public void handleDisplayResize() {
        if (isInitialized()) {
            VitraNativeRenderer.handleDisplayResize();
        }
    }

    public void setWindowActiveState(boolean active) {
        if (isInitialized()) {
            VitraNativeRenderer.setWindowActiveState(active);
        }
    }

    public static boolean isDirectX11Initialized() {
        return VitraNativeRenderer.isInitialized();
    }

    // Shader Helper Methods
    public void precompileShaderForDirectX11(Object shader) {
        if (isInitialized()) {
            VitraNativeRenderer.precompileShaderForDirectX11(shader);
        }
    }

    public boolean isShaderDirectX11Compatible(Object shader) {
        if (isInitialized()) {
            return VitraNativeRenderer.isShaderDirectX11Compatible(shader);
        }
        return false;
    }

    public Object getOptimizedDirectX11Shader(Object original) {
        if (isInitialized()) {
            return VitraNativeRenderer.getOptimizedDirectX11Shader(original);
        }
        return original;
    }

    // Matrix Helper Methods
    public void optimizeMatrixMultiplication(Object matrix1, Object matrix2) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeMatrixMultiplication(matrix1, matrix2);
        }
    }

    public void optimizeMatrixInversion(Object matrix) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeMatrixInversion(matrix);
        }
    }

    public void optimizeMatrixTranspose(Object matrix) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeMatrixTranspose(matrix);
        }
    }

    public void adjustOrthographicProjection(float left, float right, float bottom, float top, float zNear, float zFar) {
        if (isInitialized()) {
            VitraNativeRenderer.adjustOrthographicProjection(left, right, bottom, top, zNear, zFar);
        }
    }

    public void adjustPerspectiveProjection(float fovy, float aspect, float zNear, float zFar) {
        if (isInitialized()) {
            VitraNativeRenderer.adjustPerspectiveProjection(fovy, aspect, zNear, zFar);
        }
    }

    public void optimizeTranslationMatrix(float x, float y, float z) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeTranslationMatrix(x, y, z);
        }
    }

    public void optimizeScaleMatrix(float x, float y, float z) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeScaleMatrix(x, y, z);
        }
    }

    public void optimizeRotationMatrix(float angle, float x, float y, float z) {
        if (isInitialized()) {
            VitraNativeRenderer.optimizeRotationMatrix(angle, x, y, z);
        }
    }

    public boolean isMatrixDirectX11Optimized(Object matrix) {
        if (isInitialized()) {
            return VitraNativeRenderer.isMatrixDirectX11Optimized(matrix);
        }
        return false;
    }
}
