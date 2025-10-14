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
public class VitraRenderer implements IVitraRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraRenderer.class);

    private boolean initialized = false;
    private long windowHandle = 0L;
    private VitraConfig config = null;
    private VitraCore core = null;

    // DirectX 11 components
    private D3D11ShaderManager shaderManager;
    private D3D11BufferManager bufferManager;

    // Interface implementation methods
    @Override
    public void initialize() {
        initialize(RendererType.DIRECTX11);
    }

    @Override
    public void initialize(RendererType rendererType) {
        if (initialized) {
            LOGGER.warn("VitraRenderer already initialized");
            return;
        }

        LOGGER.info("Preparing Vitra JNI DirectX 11 renderer (deferred initialization)");

        try {
            if (!rendererType.isSupported()) {
                throw new RuntimeException("DirectX 11 is not supported on this platform. Vitra requires Windows 10+.");
            }

            // Initialize DirectX 11 components
            shaderManager = new D3D11ShaderManager();
            bufferManager = new D3D11BufferManager();

            initialized = true;
            LOGGER.info("Vitra renderer prepared, native DirectX 11 will initialize when window handle is available");

        } catch (Exception e) {
            LOGGER.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed", e);
        }
    }

    @Override
    public void setConfig(VitraConfig config) {
        this.config = config;
    }

    @Override
    public void setCore(VitraCore core) {
        this.core = core;
        // Set this renderer reference in the core for shader loading
        if (core != null) {
            core.setRenderer(this);
        }
    }

    @Override
    public boolean initializeWithWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
        LOGGER.info("JNI DirectX 11 window handle set: 0x{}", Long.toHexString(windowHandle));

        // Actually initialize native DirectX 11 with the window handle
        if (windowHandle != 0L) {
            try {
                // Get debug and verbose mode from config, default to false if config not set
                boolean debugMode = (config != null) ? config.isDebugMode() : false;
                boolean verboseMode = (config != null) ? config.isVerboseLogging() : false;

                LOGGER.info("Initializing native DirectX 11 with debug={}, verbose={}", debugMode, verboseMode);
                if (verboseMode) {
                    LOGGER.warn("╔════════════════════════════════════════════════════════════╗");
                    LOGGER.warn("║  VERBOSE LOGGING ENABLED - JNI will log EVERYTHING        ║");
                    LOGGER.warn("║  This may impact performance and generate huge log files  ║");
                    LOGGER.warn("║  Disable in config/vitra.properties after debugging       ║");
                    LOGGER.warn("╚════════════════════════════════════════════════════════════╝");
                }

                // Initialize debug system before DirectX initialization
                VitraNativeRenderer.initializeDebug(debugMode, verboseMode);

                // Initialize OpenGL interceptor to redirect OpenGL calls to DirectX
                GLInterceptor.initialize();
                GLInterceptor.setActive(true);
                LOGGER.info("OpenGL interceptor initialized and activated");

                // Get Win32 HWND from GLFW window handle
                long nativeWindowHandle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(windowHandle);
                if (nativeWindowHandle == 0) {
                    LOGGER.error("Failed to get Win32 HWND from GLFW window");
                    return false;
                }

                LOGGER.info("GLFW window: 0x{}, Win32 HWND: 0x{}",
                    Long.toHexString(windowHandle), Long.toHexString(nativeWindowHandle));

                boolean success = VitraNativeRenderer.initializeDirectXSafe(nativeWindowHandle, 1920, 1080, debugMode);
                if (success) {
                    LOGGER.info("Native DirectX 11 initialized successfully (debug={})", debugMode);

                    // Preload shaders after initialization
                    if (shaderManager != null) {
                        LOGGER.info("Preloading DirectX shaders...");
                        shaderManager.preloadShaders();
                        LOGGER.info("Shader preloading completed: {}", shaderManager.getCacheStats());
                    }

                    // CRITICAL FIX: Ensure default shader pipeline is bound immediately after initialization
                    // This prevents black screen by ensuring shaders are available for first draw calls
                    long defaultPipeline = VitraNativeRenderer.getDefaultShaderPipeline();
                    if (defaultPipeline != 0) {
                        VitraNativeRenderer.setShaderPipeline(defaultPipeline);
                        LOGGER.info("Default shader pipeline bound: handle=0x{}", Long.toHexString(defaultPipeline));
                    } else {
                        LOGGER.error("Failed to get default shader pipeline handle - this will cause black screen!");
                    }

                    // Load shaders after native initialization
                    if (core != null) {
                        LOGGER.info("Loading custom shaders...");
                        core.loadShaders();
                    } else {
                        LOGGER.warn("VitraCore not set, skipping custom shader loading");
                    }

                    return true;
                } else {
                    LOGGER.error("Native DirectX 11 initialization failed");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Exception during native DirectX 11 initialization", e);
                return false;
            }
        }
        return false;
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra JNI DirectX 11 renderer...");

        try {
            // Clear shader and buffer caches
            if (shaderManager != null) {
                shaderManager.clearCache();
                LOGGER.info("Cleared shader cache: {}", shaderManager.getCacheStats());
            }

            if (bufferManager != null) {
                bufferManager.clearAll();
                LOGGER.info("Cleared buffer cache: {}", bufferManager.getBufferStats());
            }

            // Shutdown OpenGL interceptor
            GLInterceptor.setActive(false);
            GLInterceptor.shutdown();
            LOGGER.info("OpenGL interceptor shutdown");

            // Shutdown native renderer safely with debug cleanup
            VitraNativeRenderer.shutdownSafe();
            LOGGER.info("Native DirectX 11 shutdown completed");

        } catch (Exception e) {
            LOGGER.error("Exception during shutdown", e);
        }

        initialized = false;
        windowHandle = 0L;
        LOGGER.info("Vitra renderer shutdown complete");
    }

    @Override
    public boolean isInitialized() {
        return initialized && windowHandle != 0L && VitraNativeRenderer.isInitialized();
    }

    @Override
    public RendererType getRendererType() {
        return RendererType.DIRECTX11;
    }

    @Override
    public void beginFrame() {
        if (isInitialized()) {
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
            LOGGER.info("DirectX 11 view resized to {}x{}", width, height);
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
}
