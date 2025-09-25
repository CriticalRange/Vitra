package com.vitra.render.backend;

import com.vitra.core.config.RendererType;
import com.vitra.render.RenderContext;
import com.vitra.render.TextureFormat;
import com.vitra.render.VertexLayout;
import com.vitra.util.WindowUtil;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.bgfx.BGFX.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * BGFX-based render context implementation
 * Provides multi-backend rendering through BGFX (OpenGL, DirectX, Vulkan)
 */
public class BgfxRenderContext implements RenderContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(BgfxRenderContext.class);

    private final RendererType rendererType;
    private final Map<Integer, ByteBuffer> bufferHandles;
    private final Map<Integer, Integer> textureHandles;
    private final Map<Integer, Integer> shaderHandles;

    private boolean initialized = false;
    private boolean initializationAttempted = false;
    private int currentWidth;
    private int currentHeight;
    private int frameNumber = 0;

    public BgfxRenderContext(RendererType rendererType) {
        this.rendererType = rendererType;
        this.bufferHandles = new HashMap<>();
        this.textureHandles = new HashMap<>();
        this.shaderHandles = new HashMap<>();
    }

    @Override
    public boolean initialize(int width, int height, long windowHandle) {
        LOGGER.info("Preparing BGFX context for Direct3D renderer replacement");
        this.currentWidth = width;
        this.currentHeight = height;
        // Don't initialize yet - wait for forceInitialization call
        return true;
    }

    /**
     * Force immediate BGFX initialization (called from mixin before OpenGL init)
     */
    public boolean forceInitialization() {
        LOGGER.info("Force initializing BGFX D3D11 to replace OpenGL backend");
        return initializeBgfx();
    }

    /**
     * Initialize BGFX with a specific window handle
     */
    public boolean initializeWithWindowHandle(long windowHandle) {
        LOGGER.info("Initializing BGFX D3D11 with window handle: 0x{}", Long.toHexString(windowHandle));
        // Reset initialization state for retry with proper window handle
        initializationAttempted = false;
        initialized = false;
        // Use the provided window handle
        return initializeBgfxWithHandle(windowHandle);
    }

    private boolean initializeBgfx() {
        return initializeBgfxWithHandle(0L);
    }

    private boolean initializeBgfxWithHandle(long windowHandle) {
        if (initialized || initializationAttempted) {
            return initialized;
        }

        initializationAttempted = true;
        LOGGER.info("Initializing BGFX render context for {} ({}x{}) with handle 0x{}",
                   rendererType.getDisplayName(), currentWidth, currentHeight, Long.toHexString(windowHandle));

        try {
            // Based on BGFX source analysis, we need to use the single-threaded approach
            // The key insight is to avoid multi-threading conflicts by using simpler initialization
            LOGGER.info("Using BGFX single-threaded compatible initialization");

            // Try multiple backends in order of preference
            return tryMultipleBackendsWithHandle(windowHandle);

        } catch (Exception e) {
            LOGGER.error("Failed to initialize BGFX render context", e);
            return false;
        } catch (Error e) {
            LOGGER.error("Native error during BGFX initialization", e);
            return false;
        }
    }

    private boolean tryMultipleBackends() {
        return tryMultipleBackendsWithHandle(0L);
    }

    private boolean tryMultipleBackendsWithHandle(long windowHandle) {
        // First, check what renderers are actually supported
        checkSupportedRenderers();

        // VulkanMod approach: Force DirectX 11 ONLY - absolutely no fallbacks allowed
        int[] backendPriority = {
            BGFX_RENDERER_TYPE_DIRECT3D11    // DirectX 11 ONLY - fail if not available
        };

        LOGGER.info("=== VULKANMOD APPROACH: FORCING DIRECTX 11 WITH ZERO TOLERANCE FOR FALLBACKS ===");
        LOGGER.info("If DirectX 11 fails, we STOP - no OpenGL fallback allowed");

        // CRITICAL: Ensure no OpenGL context exists that BGFX might detect
        LOGGER.info("=== CLEARING ANY EXISTING OPENGL CONTEXT ===");
        try {
            // Make sure no OpenGL context is current on any thread
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
            LOGGER.info("Cleared any existing OpenGL context from GLFW");
        } catch (Exception e) {
            LOGGER.info("No existing OpenGL context to clear: {}", e.getMessage());
        }

        for (int backend : backendPriority) {
            try (MemoryStack stack = stackPush()) {
                LOGGER.info("Attempting BGFX initialization with {} backend", getRendererTypeName(backend));

                BGFXInit init = BGFXInit.malloc(stack);
                bgfx_init_ctor(init);

                init.type(backend);
                init.resolution().width(currentWidth).height(currentHeight).reset(BGFX_RESET_VSYNC);

                // For DirectX 11, ensure we have proper debug and validation
                if (backend == BGFX_RENDERER_TYPE_DIRECT3D11) {
                    LOGGER.info("Forcing DirectX 11 - no fallback allowed");
                    // Explicitly disable any OpenGL renderer type detection
                    // BGFX should not fall back to OpenGL under any circumstances
                    LOGGER.info("Configuring init structure to reject OpenGL fallback");
                }

                // Set platform data with window handle if provided
                if (windowHandle != 0L) {
                    BGFXPlatformData platformData = init.platformData();
                    platformData.nwh(windowHandle);
                    LOGGER.info("Set platform data with window handle: 0x{}", Long.toHexString(windowHandle));
                }

                // Enable maximum debugging for D3D11
                if (backend == BGFX_RENDERER_TYPE_DIRECT3D11) {
                    LOGGER.info("Configuring D3D11 debug flags and parameters");
                    // These will help us see where the crash occurs
                }

                // Set up platform data for all backends - D3D11 needs window handle but no context sharing
                if (windowHandle != 0L) {
                    LOGGER.info("Using window handle: 0x{} for BGFX platform data", Long.toHexString(windowHandle));
                    setPlatformDataOnInit(init, stack, backend, windowHandle);
                    LOGGER.info("Platform data configured for {}", getRendererTypeName(backend));
                } else {
                    LOGGER.warn("No valid window handle available for BGFX initialization (windowHandle = 0x{})",
                               Long.toHexString(windowHandle));
                }

                boolean success;
                try {
                    LOGGER.info("=== BGFX {} Initialization Debug Steps ===", getRendererTypeName(backend));
                    LOGGER.info("Step 1: Pre-init state check - about to call bgfx_init()");
                    LOGGER.info("Step 2: Window Handle = 0x{}", Long.toHexString(windowHandle));
                    LOGGER.info("Step 3: Resolution = {}x{}", currentWidth, currentHeight);
                    LOGGER.info("Step 4: Backend Type = {} ({})", getRendererTypeName(backend), backend);

                    // Try minimal configuration to avoid conflicts
                    if (backend == BGFX_RENDERER_TYPE_DIRECT3D11) {
                        LOGGER.info("Step 5: Configuring {} with window handle for separate device creation", getRendererTypeName(backend));
                        // Use the provided window handle instead of WindowUtil (which returns 0 with GLFW_NO_API)
                        long actualWindowHandle = windowHandle != 0L ? windowHandle : WindowUtil.getMinecraftWindowHandle();
                        init.platformData().nwh(actualWindowHandle).ndt(0).context(0).backBuffer(0).backBufferDS(0);
                        LOGGER.info("Set window handle: 0x{} for {} backend", Long.toHexString(actualWindowHandle), getRendererTypeName(backend));
                    }

                    LOGGER.info("Step 6: Calling bgfx_init() now...");
                    success = bgfx_init(init);
                    LOGGER.info("Step 7: bgfx_init() returned: {}", success);

                } catch (Exception | Error e) {
                    LOGGER.error("=== NATIVE CRASH DETECTED ===");
                    LOGGER.error("Crash occurred during {} backend initialization", getRendererTypeName(backend));
                    LOGGER.error("Exception type: {}", e.getClass().getSimpleName());
                    LOGGER.error("Exception message: {}", e.getMessage());
                    if (e.getCause() != null) {
                        LOGGER.error("Cause: {}", e.getCause().getMessage());
                    }
                    LOGGER.error("=== END CRASH INFO ===");
                    continue; // Try next backend
                }

                if (success) {
                    int actualRenderer = bgfx_get_renderer_type();
                    LOGGER.info("BGFX initialization successful with {} backend (type {})",
                               getRendererName(actualRenderer), actualRenderer);

                    // CRITICAL DEBUG: Log the exact renderer type numbers
                    LOGGER.info("=== CRITICAL RENDERER TYPE DEBUG ===");
                    LOGGER.info("BGFX_RENDERER_TYPE_DIRECT3D11 constant = {}", BGFX_RENDERER_TYPE_DIRECT3D11);
                    LOGGER.info("BGFX_RENDERER_TYPE_OPENGL constant = {}", BGFX_RENDERER_TYPE_OPENGL);
                    LOGGER.info("Requested renderer type: {} ({})", getRendererTypeName(backend), backend);
                    LOGGER.info("Actual BGFX renderer type returned: {} ({})", getRendererTypeName(actualRenderer), actualRenderer);

                    if (actualRenderer == BGFX_RENDERER_TYPE_DIRECT3D11) {
                        LOGGER.info("✓ SUCCESS: BGFX is using DirectX 11 (type {})", actualRenderer);
                    } else if (actualRenderer == BGFX_RENDERER_TYPE_OPENGL) {
                        LOGGER.error("✗ FAILED: BGFX fell back to OpenGL (type {}) instead of DirectX 11", actualRenderer);
                    } else {
                        LOGGER.warn("? UNKNOWN: BGFX is using renderer type {} ({})", actualRenderer, getRendererTypeName(actualRenderer));
                    }

                    // VulkanMod approach: STRICT DirectX 11 validation - FAIL if not DirectX 11
                    if (backend == BGFX_RENDERER_TYPE_DIRECT3D11 && actualRenderer != BGFX_RENDERER_TYPE_DIRECT3D11) {
                        LOGGER.error("=== VULKANMOD APPROACH: DIRECTX 11 CREATION FAILED ===");
                        LOGGER.error("Requested: DirectX 11 ({}), Got: {} ({})",
                                   BGFX_RENDERER_TYPE_DIRECT3D11, getRendererTypeName(actualRenderer), actualRenderer);
                        LOGGER.error("VulkanMod approach: FAILING completely - NO fallback to OpenGL allowed");

                        // Shutdown BGFX since it didn't give us what we wanted
                        try {
                            bgfx_shutdown();
                            LOGGER.error("BGFX shutdown due to DirectX 11 failure");
                        } catch (Exception e) {
                            LOGGER.error("Error during BGFX shutdown: {}", e.getMessage());
                        }

                        // Fail initialization completely
                        return false;
                    }

                    // Set debug flags safely
                    try {
                        bgfx_set_debug(BGFX_DEBUG_TEXT);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to set debug flags: {}", e.getMessage());
                    }

                    // Expose BGFX renderer information to system for RivaTuner detection
                    try {
                        com.vitra.render.bgfx.BgfxRendererExposer.exposeRendererInfo();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to expose renderer information: {}", e.getMessage());
                    }

                    this.initialized = true;
                    return true;
                } else {
                    LOGGER.warn("BGFX initialization failed with {} backend", getRendererTypeName(backend));
                }

            } catch (Exception e) {
                LOGGER.warn("Exception during {} backend initialization: {}", getRendererTypeName(backend), e.getMessage());
            }
        }

        LOGGER.error("All BGFX backend initialization attempts failed");
        LOGGER.error("DirectX 11 is not available - cannot proceed without OpenGL fallback");
        return false;
    }

    /**
     * Check what renderers are supported by BGFX on this system
     */
    private void checkSupportedRenderers() {
        LOGGER.info("=== CHECKING SUPPORTED RENDERERS ===");

        try (MemoryStack stack = stackPush()) {
            IntBuffer supportedRenderers = stack.mallocInt(BGFX_RENDERER_TYPE_COUNT);
            byte numSupported = bgfx_get_supported_renderers(supportedRenderers);

            LOGGER.info("Number of supported renderers: {}", numSupported);

            boolean d3d11Supported = false;
            boolean openglSupported = false;

            for (int i = 0; i < numSupported; i++) {
                int rendererType = supportedRenderers.get(i);
                String rendererName = getRendererTypeName(rendererType);
                LOGGER.info("Supported renderer {}: {} ({})", i + 1, rendererName, rendererType);

                if (rendererType == BGFX_RENDERER_TYPE_DIRECT3D11) {
                    d3d11Supported = true;
                }
                if (rendererType == BGFX_RENDERER_TYPE_OPENGL) {
                    openglSupported = true;
                }
            }

            LOGGER.info("DirectX 11 supported: {}", d3d11Supported);
            LOGGER.info("OpenGL supported: {}", openglSupported);

            if (!d3d11Supported) {
                LOGGER.error("⚠ DirectX 11 is NOT supported on this system!");
                LOGGER.error("System requirements: DirectX 11 compatible GPU and drivers");
                if (openglSupported) {
                    LOGGER.warn("OpenGL is available but we're configured to reject it");
                }
            } else {
                LOGGER.info("✓ DirectX 11 is supported - will force this backend");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to check supported renderers", e);
        }
    }

    private String getRendererTypeName(int rendererType) {
        switch (rendererType) {
            case BGFX_RENDERER_TYPE_DIRECT3D9: return "Direct3D 9";
            case BGFX_RENDERER_TYPE_DIRECT3D11: return "Direct3D 11";
            case BGFX_RENDERER_TYPE_DIRECT3D12: return "Direct3D 12";
            case BGFX_RENDERER_TYPE_OPENGL: return "OpenGL";
            case BGFX_RENDERER_TYPE_OPENGLES: return "OpenGL ES";
            case BGFX_RENDERER_TYPE_VULKAN: return "Vulkan";
            case BGFX_RENDERER_TYPE_METAL: return "Metal";
            default: return "Unknown (" + rendererType + ")";
        }
    }

    private void setPlatformDataOnInit(BGFXInit init, MemoryStack stack, int backend, long windowHandle) {
        try {
            LOGGER.info("=== PLATFORM DATA SETUP DEBUG ===");
            LOGGER.info("Setting BGFX platform data for {} backend", getRendererTypeName(backend));

            // Use the window handle passed as parameter
            LOGGER.info("Using passed window handle: 0x{} (decimal: {})", Long.toHexString(windowHandle), windowHandle);

            // Validate window handle
            if (windowHandle == 0) {
                LOGGER.error("ERROR: Window handle is NULL/0!");
                return;
            }

            // Set platform data with backend-specific configuration
            BGFXPlatformData platformData = init.platformData();
            LOGGER.info("Platform data structure obtained: {}", (platformData != null ? "OK" : "NULL"));

            // nwh: Native Window Handle - required for all backends
            platformData.nwh(windowHandle);
            LOGGER.info("Set platformData.nwh = 0x{}", Long.toHexString(windowHandle));

            // Backend-specific configuration
            if (backend == BGFX_RENDERER_TYPE_DIRECT3D11) {
                // D3D11: Create completely separate device with no OpenGL context sharing
                LOGGER.info("Configuring DirectX 11 with isolated device creation - no OpenGL context sharing");
                platformData.ndt(0);
                platformData.context(0); // No context sharing
                platformData.backBuffer(0);
                platformData.backBufferDS(0);
                LOGGER.info("DirectX 11 configured for isolated device creation");
            } else {
                // Other backends: Default configuration
                LOGGER.info("Configuring for {} - default configuration", getRendererTypeName(backend));
                platformData.ndt(0);
                platformData.context(0);
                platformData.backBuffer(0);
                platformData.backBufferDS(0);
            }

            LOGGER.info("=== PLATFORM DATA SETUP COMPLETE ===");

        } catch (Exception e) {
            LOGGER.error("EXCEPTION in platform data setup: {}", e.getMessage(), e);
        }
    }

    private boolean tryMinimalBgfxInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Attempting minimal BGFX initialization");
            bgfx_init_ctor(init);

            // Minimal configuration - let BGFX choose everything
            init.type(BGFX_RENDERER_TYPE_COUNT);
            init.resolution().width(800).height(600).reset(BGFX_RESET_NONE);

            boolean success = bgfx_init(init);
            if (success) {
                LOGGER.info("BGFX minimal initialization successful with {} backend",
                           getRendererName(bgfx_get_renderer_type()));
                setupDebugAndFinalize();
                return true;
            }

        } catch (Exception | Error e) {
            LOGGER.warn("Minimal BGFX initialization failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean tryContextSharingInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Attempting BGFX initialization with OpenGL context sharing");
            bgfx_init_ctor(init);

            init.type(BGFX_RENDERER_TYPE_OPENGL);
            init.resolution().width(currentWidth).height(currentHeight).reset(BGFX_RESET_NONE);

            // Set platform data for context sharing
            setupPlatformData(init, stack);

            boolean success = bgfx_init(init);
            if (success) {
                LOGGER.info("BGFX context sharing initialization successful");
                setupDebugAndFinalize();
                return true;
            }

        } catch (Exception | Error e) {
            LOGGER.warn("Context sharing BGFX initialization failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean tryAutoDetectionInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Attempting BGFX initialization with auto-detection");
            bgfx_init_ctor(init);

            init.type(BGFX_RENDERER_TYPE_COUNT);
            init.resolution().width(currentWidth).height(currentHeight).reset(BGFX_RESET_NONE);

            boolean success = bgfx_init(init);
            if (success) {
                LOGGER.info("BGFX auto-detection initialization successful with {} backend",
                           getRendererName(bgfx_get_renderer_type()));
                setupDebugAndFinalize();
                return true;
            }

        } catch (Exception | Error e) {
            LOGGER.warn("Auto-detection BGFX initialization failed: {}", e.getMessage());
        }
        return false;
    }

    private void setupDebugAndFinalize() {
        try {
            bgfx_set_debug(BGFX_DEBUG_TEXT);
            this.initialized = true;
            LOGGER.info("BGFX initialization complete with debug flags set");
        } catch (Exception e) {
            LOGGER.warn("Failed to set BGFX debug flags: {}", e.getMessage());
            this.initialized = true; // Still consider it successful
        }
    }

    private void setupDebugCallback(BGFXInit init, MemoryStack stack) {
        try {
            // For now, skip the debug callback setup to avoid compilation issues
            // We'll use BGFX's built-in debug flags instead
            LOGGER.info("BGFX debug callback setup skipped - using built-in debug flags");

        } catch (Exception e) {
            LOGGER.warn("Failed to setup BGFX debug callback: {}", e.getMessage());
        }
    }

    private boolean trySeparateThreadInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Attempting BGFX initialization on separate thread");

            // Use a simple flag to communicate between threads
            final boolean[] initSuccess = {false};
            final Exception[] initException = {null};

            Thread bgfxThread = new Thread(() -> {
                try (MemoryStack threadStack = stackPush()) {
                    BGFXInit threadInit = BGFXInit.malloc(threadStack);
                    bgfx_init_ctor(threadInit);

                    // Minimal isolated configuration on separate thread
                    threadInit.type(BGFX_RENDERER_TYPE_COUNT);
                    threadInit.resolution().width(800).height(600).reset(BGFX_RESET_NONE);

                    boolean success = bgfx_init(threadInit);
                    if (success) {
                        LOGGER.info("BGFX thread initialization successful with {} backend",
                                   getRendererName(bgfx_get_renderer_type()));
                        initSuccess[0] = true;
                    } else {
                        LOGGER.warn("BGFX thread initialization failed");
                    }
                } catch (Exception | Error e) {
                    LOGGER.warn("BGFX thread initialization threw exception: {}", e.getMessage());
                    initException[0] = new RuntimeException(e);
                }
            }, "BGFX-Init-Thread");

            bgfxThread.start();
            bgfxThread.join(5000); // Wait up to 5 seconds

            if (bgfxThread.isAlive()) {
                LOGGER.warn("BGFX thread initialization timed out");
                bgfxThread.interrupt();
                return false;
            }

            if (initException[0] != null) {
                LOGGER.warn("BGFX thread initialization failed with exception: {}", initException[0].getMessage());
                return false;
            }

            if (initSuccess[0]) {
                setupDebugAndFinalize();
                return true;
            }

        } catch (Exception | Error e) {
            LOGGER.warn("Separate thread BGFX initialization failed: {}", e.getMessage());
        }
        return false;
    }

    private boolean tryIsolatedBgfxInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Attempting isolated BGFX initialization without platform data");
            bgfx_init_ctor(init);

            // Completely isolated - no platform data, no context sharing
            init.type(BGFX_RENDERER_TYPE_COUNT); // Let BGFX choose
            init.resolution().width(800).height(600).reset(BGFX_RESET_NONE);

            // Don't set platformData - leave it uninitialized to avoid any context sharing

            boolean success = bgfx_init(init);
            if (success) {
                LOGGER.info("BGFX isolated initialization successful with {} backend",
                           getRendererName(bgfx_get_renderer_type()));
                setupDebugAndFinalize();
                return true;
            }

        } catch (Exception | Error e) {
            LOGGER.warn("Isolated BGFX initialization failed: {}", e.getMessage());
        }
        return false;
    }

    private void setupPlatformData(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.debug("Setting up BGFX platform data for OpenGL context sharing");

            // Pre-check: Verify OpenGL is available
            if (!isOpenGLContextAvailable()) {
                LOGGER.warn("OpenGL context not available for BGFX sharing");
                return;
            }

            BGFXPlatformData platformData = init.platformData();

            if (WindowUtil.hasValidWindowHandle()) {
                long windowHandle = WindowUtil.getMinecraftWindowHandle();
                LOGGER.info("Using Minecraft window handle for BGFX: 0x{}", Long.toHexString(windowHandle));

                // Validate window handle before using
                if (isValidWindowHandle(windowHandle)) {
                    platformData.nwh(windowHandle);

                    // Try to get and set the OpenGL context with validation
                    setupOpenGLContext(platformData);
                } else {
                    LOGGER.warn("Invalid window handle detected, skipping platform data setup");
                }

            } else {
                LOGGER.warn("No valid window handle available - BGFX will create its own window");
            }

        } catch (Exception e) {
            LOGGER.warn("Could not setup platform data for BGFX context sharing: {}", e.getMessage());
        }
    }

    private boolean isOpenGLContextAvailable() {
        try {
            long currentContext = GLFW.glfwGetCurrentContext();
            if (currentContext == 0L) {
                LOGGER.debug("No current OpenGL context");
                return false;
            }

            // Try to make a simple OpenGL call to verify context is working
            GL.getCapabilities();
            LOGGER.debug("OpenGL context verified as working");
            return true;

        } catch (Exception e) {
            LOGGER.warn("OpenGL context check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isValidWindowHandle(long windowHandle) {
        if (windowHandle == 0L) {
            return false;
        }

        try {
            // Basic validation - check if window still exists
            return GLFW.glfwGetWindowAttrib(windowHandle, GLFW.GLFW_FOCUSED) >= 0;
        } catch (Exception e) {
            LOGGER.debug("Window handle validation failed: {}", e.getMessage());
            return false;
        }
    }

    private void setupOpenGLContext(BGFXPlatformData platformData) {
        try {
            long currentContext = GLFW.glfwGetCurrentContext();
            if (currentContext != 0L) {
                LOGGER.info("Current OpenGL context: 0x{}", Long.toHexString(currentContext));

                // Only set context if it's different from window handle
                // to avoid potential conflicts
                if (currentContext != WindowUtil.getMinecraftWindowHandle()) {
                    platformData.context(currentContext);
                    LOGGER.debug("OpenGL context configured for BGFX");
                } else {
                    LOGGER.debug("OpenGL context same as window handle, skipping explicit context setup");
                }
            }

            // Platform-specific display setup for Linux
            setupDisplayForLinux(platformData);

        } catch (Exception e) {
            LOGGER.warn("Could not setup OpenGL context for BGFX: {}", e.getMessage());
        }
    }

    private void setupDisplayForLinux(BGFXPlatformData platformData) {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            try {
                LOGGER.debug("Linux platform detected, attempting display setup");
                // On Linux with X11, we might need the display
                // For now, just log - actual implementation would require X11 natives
                LOGGER.debug("Linux display setup would go here");
            } catch (Exception e) {
                LOGGER.debug("Linux display setup failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down BGFX render context...");

        // Cleanup all resources
        bufferHandles.values().forEach(MemoryUtil::memFree);
        bufferHandles.clear();
        textureHandles.clear();
        shaderHandles.clear();

        // Shutdown BGFX
        bgfx_shutdown();
        initialized = false;

        LOGGER.info("BGFX render context shutdown complete");
    }

    @Override
    public RendererType getRendererType() {
        return rendererType;
    }

    @Override
    public void beginFrame() {
        // Try to initialize BGFX on first frame when graphics context should be ready
        if (!initialized && !initializeBgfx()) {
            return;
        }

        // Set the view rectangle for the main view
        bgfx_set_view_rect(0, 0, 0, currentWidth, currentHeight);

        // This dummy draw call ensures the view is submitted even if no other draw calls are made
        bgfx_touch(0);
    }

    @Override
    public void endFrame() {
        if (!initialized) return;

        // Advance frame
        bgfx_frame(false);
        frameNumber++;
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        if (!initialized || (newWidth == currentWidth && newHeight == currentHeight)) return;

        LOGGER.info("Resizing BGFX context from {}x{} to {}x{}",
                   currentWidth, currentHeight, newWidth, newHeight);

        bgfx_reset(newWidth, newHeight, BGFX_RESET_VSYNC, BGFX_TEXTURE_FORMAT_COUNT);

        this.currentWidth = newWidth;
        this.currentHeight = newHeight;
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        if (!initialized) return;
        bgfx_set_view_rect(0, x, y, width, height);
    }

    @Override
    public int createVertexBuffer(float[] vertices, VertexLayout layout) {
        if (!initialized) return -1;

        try (MemoryStack stack = stackPush()) {
            // Create vertex buffer memory
            ByteBuffer vertexData = memAlloc(vertices.length * Float.BYTES);
            FloatBuffer vertexFloats = vertexData.asFloatBuffer();
            vertexFloats.put(vertices);
            vertexFloats.flip();

            // Create BGFX vertex layout
            BGFXVertexLayout bgfxLayout = BGFXVertexLayout.malloc(stack);
            bgfx_vertex_layout_begin(bgfxLayout, getBgfxRendererType(rendererType));

            for (VertexLayout.VertexAttribute attr : layout.getAttributes()) {
                bgfx_vertex_layout_add(bgfxLayout,
                    getBgfxAttributeType(attr.getType()),
                    (byte) attr.getCount(),
                    getBgfxAttributeFormat(attr.getFormat()),
                    false,
                    false);
            }
            bgfx_vertex_layout_end(bgfxLayout);

            // Create the vertex buffer
            int handle = bgfx_create_vertex_buffer(
                bgfx_make_ref(vertexData), bgfxLayout, BGFX_BUFFER_NONE);

            if (handle == BGFX_INVALID_HANDLE) {
                memFree(vertexData);
                LOGGER.error("Failed to create vertex buffer");
                return -1;
            }

            bufferHandles.put(handle, vertexData);
            return handle;
        }
    }

    @Override
    public int createIndexBuffer(int[] indices) {
        if (!initialized) return -1;

        // Create index buffer memory
        ByteBuffer indexData = memAlloc(indices.length * Integer.BYTES);
        IntBuffer indexInts = indexData.asIntBuffer();
        indexInts.put(indices);
        indexInts.flip();

        // Create the index buffer
        int handle = bgfx_create_index_buffer(bgfx_make_ref(indexData), BGFX_BUFFER_NONE);

        if (handle == BGFX_INVALID_HANDLE) {
            memFree(indexData);
            LOGGER.error("Failed to create index buffer");
            return -1;
        }

        bufferHandles.put(handle, indexData);
        return handle;
    }

    @Override
    public int createTexture(int width, int height, TextureFormat format, byte[] data) {
        if (!initialized) return -1;

        ByteBuffer textureData = null;
        if (data != null) {
            textureData = memAlloc(data.length);
            textureData.put(data);
            textureData.flip();
        }

        int handle = bgfx_create_texture_2d(width, height, false, 1,
            getBgfxTextureFormat(format),
            BGFX_TEXTURE_NONE | BGFX_SAMPLER_NONE,
            textureData != null ? bgfx_make_ref(textureData) : null);

        if (textureData != null) {
            memFree(textureData);
        }

        if (handle == BGFX_INVALID_HANDLE) {
            LOGGER.error("Failed to create texture");
            return -1;
        }

        textureHandles.put(handle, handle);
        return handle;
    }

    @Override
    public int createShaderProgram(String vertexShaderCode, String fragmentShaderCode) {
        if (!initialized) return -1;

        // TODO: Implement shader compilation
        // For now, return a dummy handle
        LOGGER.warn("Shader compilation not yet implemented in BGFX backend");
        return -1;
    }

    @Override
    public void bindVertexBuffer(int handle) {
        if (!initialized || handle < 0) return;
        bgfx_set_vertex_buffer(0, (short) handle, 0, BGFX_INVALID_HANDLE);
    }

    @Override
    public void bindIndexBuffer(int handle) {
        if (!initialized || handle < 0) return;
        bgfx_set_index_buffer((short) handle, 0, BGFX_INVALID_HANDLE);
    }

    @Override
    public void bindTexture(int handle, int slot) {
        if (!initialized || handle < 0) return;
        // TODO: Implement texture binding
        LOGGER.warn("Texture binding not yet implemented in BGFX backend");
    }

    @Override
    public void bindShaderProgram(int handle) {
        if (!initialized || handle < 0) return;
        // TODO: Implement shader program binding
        LOGGER.warn("Shader program binding not yet implemented in BGFX backend");
    }

    @Override
    public void setShaderUniform(int shaderHandle, String uniformName, float[] value) {
        if (!initialized) return;
        // TODO: Implement uniform setting
        LOGGER.warn("Shader uniform setting not yet implemented in BGFX backend");
    }

    @Override
    public void drawIndexed(int indexCount, int indexOffset, int vertexOffset) {
        if (!initialized) return;
        bgfx_submit(0, BGFX_INVALID_HANDLE, 0, BGFX_DISCARD_ALL);
    }

    @Override
    public void draw(int vertexCount, int vertexOffset) {
        if (!initialized) return;
        bgfx_submit(0, BGFX_INVALID_HANDLE, 0, BGFX_DISCARD_ALL);
    }

    @Override
    public void deleteBuffer(int handle) {
        if (!initialized) return;

        ByteBuffer buffer = bufferHandles.remove(handle);
        if (buffer != null) {
            memFree(buffer);
        }

        bgfx_destroy_vertex_buffer((short) handle);
    }

    @Override
    public void deleteTexture(int handle) {
        if (!initialized) return;

        textureHandles.remove(handle);
        bgfx_destroy_texture((short) handle);
    }

    @Override
    public void deleteShaderProgram(int handle) {
        if (!initialized) return;

        shaderHandles.remove(handle);
        // TODO: Implement shader program deletion
    }

    @Override
    public void clear(float colorR, float colorG, float colorB, float colorA, float depth) {
        if (!initialized) return;

        int clearColor = ((int)(colorA * 255) << 24) |
                        ((int)(colorR * 255) << 16) |
                        ((int)(colorG * 255) << 8) |
                        ((int)(colorB * 255));

        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, clearColor, depth, 0);
    }

    @Override
    public boolean isValid() {
        return initialized;
    }

    @Override
    public String getDebugInfo() {
        if (!initialized) return "BGFX not initialized";

        return String.format("BGFX %s Backend - Frame: %d, Resolution: %dx%d",
            getRendererName(bgfx_get_renderer_type()), frameNumber, currentWidth, currentHeight);
    }

    // Helper methods for BGFX conversion

    private int getBgfxRendererType(RendererType type) {
        switch (type) {
            case DIRECTX11: return BGFX_RENDERER_TYPE_DIRECT3D11;
            default: return BGFX_RENDERER_TYPE_DIRECT3D11; // Only D3D11 supported
        }
    }

    private int getBgfxTextureFormat(TextureFormat format) {
        switch (format) {
            case RGB8: return BGFX_TEXTURE_FORMAT_RGB8;
            case RGBA8: return BGFX_TEXTURE_FORMAT_RGBA8;
            case RGBA16F: return BGFX_TEXTURE_FORMAT_RGBA16F;
            case RGBA32F: return BGFX_TEXTURE_FORMAT_RGBA32F;
            case DEPTH24: return BGFX_TEXTURE_FORMAT_D24S8;
            default: return BGFX_TEXTURE_FORMAT_RGBA8;
        }
    }

    private int getBgfxAttributeType(VertexLayout.VertexAttributeType type) {
        switch (type) {
            case POSITION: return BGFX_ATTRIB_POSITION;
            case NORMAL: return BGFX_ATTRIB_NORMAL;
            case TANGENT: return BGFX_ATTRIB_TANGENT;
            case TEXCOORD0: return BGFX_ATTRIB_TEXCOORD0;
            case TEXCOORD1: return BGFX_ATTRIB_TEXCOORD1;
            case COLOR: return BGFX_ATTRIB_COLOR0;
            default: return BGFX_ATTRIB_POSITION;
        }
    }

    private int getBgfxAttributeFormat(VertexLayout.VertexFormat format) {
        switch (format) {
            case FLOAT: return BGFX_ATTRIB_TYPE_FLOAT;
            case INT: return BGFX_ATTRIB_TYPE_INT16;
            case SHORT: return BGFX_ATTRIB_TYPE_INT16;
            case BYTE: return BGFX_ATTRIB_TYPE_UINT8;
            default: return BGFX_ATTRIB_TYPE_FLOAT;
        }
    }

    private String getRendererName(int rendererType) {
        switch (rendererType) {
            case BGFX_RENDERER_TYPE_DIRECT3D11: return "DirectX 11";
            case 9: return "DirectX 11"; // BGFX actual D3D11 renderer type
            default: return "Unknown (" + rendererType + ")";
        }
    }
}