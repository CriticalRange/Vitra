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
        LOGGER.info("Deferring BGFX initialization - will initialize on first frame");
        this.currentWidth = width;
        this.currentHeight = height;
        return true;
    }

    private boolean initializeBgfx() {
        if (initialized || initializationAttempted) {
            return initialized;
        }

        initializationAttempted = true;
        LOGGER.info("Initializing BGFX render context for {} ({}x{})",
                   rendererType.getDisplayName(), currentWidth, currentHeight);

        try {
            // Based on BGFX source analysis, we need to use the single-threaded approach
            // The key insight is to avoid multi-threading conflicts by using simpler initialization
            LOGGER.info("Using BGFX single-threaded compatible initialization");

            // Initialize BGFX with proper configuration based on source code analysis
            try (MemoryStack stack = stackPush()) {
                BGFXInit init = BGFXInit.malloc(stack);
                bgfx_init_ctor(init);

                // Try Direct3D 11 first (avoids OpenGL context conflicts)
                init.type(BGFX_RENDERER_TYPE_DIRECT3D11);
                init.resolution().width(currentWidth).height(currentHeight).reset(BGFX_RESET_VSYNC);

                // Set platform data if available
                if (WindowUtil.hasValidWindowHandle()) {
                    setPlatformDataOnInit(init, stack);
                }

                boolean success = bgfx_init(init);
                if (success) {
                    LOGGER.info("BGFX initialization successful with {} backend",
                               getRendererName(bgfx_get_renderer_type()));

                    // Set debug flags
                    bgfx_set_debug(BGFX_DEBUG_TEXT);
                    this.initialized = true;
                    return true;
                } else {
                    LOGGER.error("BGFX initialization failed");
                    return false;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to initialize BGFX render context", e);
            return false;
        } catch (Error e) {
            LOGGER.error("Native error during BGFX initialization", e);
            return false;
        }
    }

    private void setPlatformDataOnInit(BGFXInit init, MemoryStack stack) {
        try {
            LOGGER.info("Setting BGFX platform data during initialization");

            // Get the window handle from Minecraft
            long windowHandle = WindowUtil.getMinecraftWindowHandle();
            LOGGER.info("Using Minecraft window handle: 0x{}", Long.toHexString(windowHandle));

            // Set platform data directly on the init structure
            BGFXPlatformData platformData = init.platformData();
            platformData.nwh(windowHandle);
            platformData.ndt(0); // Default display
            platformData.context(0); // Will use existing OpenGL context

            LOGGER.info("BGFX platform data set successfully on init structure");

        } catch (Exception e) {
            LOGGER.warn("Failed to set BGFX platform data: {}", e.getMessage());
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
            case OPENGL: return BGFX_RENDERER_TYPE_OPENGL;
            case VULKAN: return BGFX_RENDERER_TYPE_VULKAN;
            case DIRECTX12: return BGFX_RENDERER_TYPE_DIRECT3D12;
            default: return BGFX_RENDERER_TYPE_COUNT; // Let BGFX choose
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
            case BGFX_RENDERER_TYPE_OPENGL: return "OpenGL";
            case BGFX_RENDERER_TYPE_VULKAN: return "Vulkan";
            case BGFX_RENDERER_TYPE_DIRECT3D12: return "DirectX 12";
            case BGFX_RENDERER_TYPE_DIRECT3D11: return "DirectX 11";
            default: return "Unknown";
        }
    }
}