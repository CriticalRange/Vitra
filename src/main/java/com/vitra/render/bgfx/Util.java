package com.vitra.render.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXCallbackInterface;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.bgfx.BGFXResolution;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Simplified BGFX utility class that uses BGFX's native functionality directly.
 * This consolidates all BGFX operations into a single utility class.
 */
public class Util {
    private static final Logger LOGGER = LoggerFactory.getLogger("Util");

    /**
     * Initialize BGFX with DirectX 12 backend.
     * @param windowHandle The GLFW window handle
     * @param width The window width
     * @param height The window height
     * @param enableDebug Whether to enable D3D12 debug layer (requires Graphics Tools on Windows 10+)
     */
    public static synchronized boolean initialize(long windowHandle, int width, int height, boolean enableDebug) {
        return initialize(windowHandle, width, height, enableDebug, false);
    }

    /**
     * Initialize BGFX with DirectX 12 backend with verbose logging option.
     * @param windowHandle The GLFW window handle
     * @param width The window width
     * @param height The window height
     * @param enableDebug Whether to enable D3D12 debug layer
     * @param verboseMode Whether to enable verbose BGFX trace logging
     */
    public static synchronized boolean initialize(long windowHandle, int width, int height, boolean enableDebug, boolean verboseMode) {
        if (windowHandle == 0) {
            LOGGER.error("Invalid window handle");
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LOGGER.info("Initializing BGFX with DirectX 12 backend (debug={}, verbose={})", enableDebug, verboseMode);

            // ═══════════════════════════════════════════════════════════════════════════
            // STEP 1: Log debug configuration
            // ═══════════════════════════════════════════════════════════════════════════
            BgfxDebugCallback.logDebugConfig(enableDebug, verboseMode);

            // CRITICAL FIX: Get the Win32 HWND from GLFW window
            // windowHandle is GLFW's GLFWwindow*, not the native Win32 HWND
            // BGFX needs the actual HWND for DirectX 11
            long nativeWindowHandle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(windowHandle);

            if (nativeWindowHandle == 0) {
                LOGGER.error("Failed to get Win32 HWND from GLFW window");
                return false;
            }

            LOGGER.info("GLFW window: 0x{}, Win32 HWND: 0x{}",
                Long.toHexString(windowHandle), Long.toHexString(nativeWindowHandle));

            BGFXPlatformData platformData = BGFXPlatformData.calloc(stack);
            platformData.nwh(nativeWindowHandle);  // Use Win32 HWND, not GLFW window
            platformData.ndt(MemoryUtil.NULL);
            platformData.type(0);

            // Initialize BGFX structure - use malloc to see uninitialized values
            BGFXInit init = BGFXInit.malloc(stack);

            // CRITICAL: Call bgfx_init_ctor to properly initialize the structure
            // This sets up default values that BGFX expects
            BGFX.bgfx_init_ctor(init);

            // Now set our custom values - use DirectX 12 for better stability
            // DirectX 12 is more modern and has better multi-threading support
            init.type(BGFX.BGFX_RENDERER_TYPE_DIRECT3D12);
            init.platformData(platformData);

            // CRITICAL: Set vendorId and deviceId for automatic GPU selection
            init.vendorId(BGFX.BGFX_PCI_ID_NONE);
            init.deviceId((short) 0);

            // Enable debug mode - this tells BGFX to create D3D11 device with D3D11_CREATE_DEVICE_DEBUG
            // NOTE: On Windows 10+, this requires "Graphics Tools" optional feature to be installed
            init.debug(enableDebug);

            // Configure resolution - use COUNT for default backbuffer format
            BGFXResolution resolution = init.resolution();
            resolution.width(Math.max(1, width));
            resolution.height(Math.max(1, height));
            // CRITICAL FIX: Disable V-Sync to prevent Frame 4 deadlock
            // V-Sync was causing bgfx_frame() to block indefinitely waiting for GPU fence
            resolution.reset(BGFX.BGFX_RESET_NONE); // V-Sync DISABLED for testing
            resolution.format(BGFX.BGFX_TEXTURE_FORMAT_COUNT); // Use default format

            LOGGER.info("BGFX Init: renderer=D3D12, window=0x{}, resolution={}x{}, debug={}, vsync=DISABLED",
                Long.toHexString(windowHandle), width, height, enableDebug);

            if (enableDebug) {
                LOGGER.warn("=======================================================");
                LOGGER.warn("BGFX D3D12 DEBUG MODE ENABLED");
                LOGGER.warn("This requires Windows Graphics Tools to be installed!");
                LOGGER.warn("Install via: Settings -> Apps -> Optional Features");
                LOGGER.warn("             -> Add a feature -> Graphics Tools");
                LOGGER.warn("=======================================================");
            }

            LOGGER.info("Calling bgfx_init()...");
            boolean success = BGFX.bgfx_init(init);
            LOGGER.info("bgfx_init() returned: {}", success);
            if (!success) {
                LOGGER.error("BGFX initialization failed");
                if (enableDebug) {
                    LOGGER.error("Debug mode was enabled - if init failed, ensure Graphics Tools are installed");
                    LOGGER.error("You may need to disable debug mode in config/vitra.properties");
                }
                return false;
            }

            // Set up the default view (view 0) with the window dimensions
            BGFX.bgfx_set_view_rect(0, 0, 0, width, height);
            BGFX.bgfx_set_view_clear(0,
                BGFX.BGFX_CLEAR_COLOR | BGFX.BGFX_CLEAR_DEPTH,
                0x000000FF, // Black background (RGBA: 0,0,0,255)
                1.0f,
                0);

            // Touch the view to make sure it renders
            BGFX.bgfx_touch(0);

            LOGGER.info("BGFX view 0 configured: clear color=BLACK, rect={}x{}", width, height);

            LOGGER.info("BGFX successfully initialized with DirectX 12 (debug={})", enableDebug);

            // Configure runtime debug overlay based on config preference
            if (enableDebug) {
                // Use DirectX's native stats overlay instead of BGFX debug text
                // BGFX_DEBUG_STATS shows DirectX 12's built-in performance overlay
                int debugFlags = BGFX.BGFX_DEBUG_STATS;
                BGFX.bgfx_set_debug(debugFlags);
                LOGGER.info("BGFX debug overlay enabled (DirectX 12 native STATS)");
                LOGGER.info("Debug stats will appear in the top-left corner of the window");
            } else {
                BGFX.bgfx_set_debug(BGFX.BGFX_DEBUG_NONE);
                LOGGER.info("BGFX debug overlay disabled");
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Exception during BGFX initialization", e);
            return false;
        }
    }


    /**
     * Check if BGFX is initialized.
     */
    public static boolean isInitialized() {
        try {
            BGFX.bgfx_get_renderer_type();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a simple position+texcoord+color vertex layout
     * FIXED: Use correct renderer type for DirectX 11 instead of NOOP
     */
    public static BGFXVertexLayout createSimpleVertexLayout(MemoryStack stack) {
        BGFXVertexLayout layout = BGFXVertexLayout.malloc(stack);
        // Use DirectX 12 renderer type for proper vertex layout optimization
        BGFX.bgfx_vertex_layout_begin(layout, BGFX.BGFX_RENDERER_TYPE_DIRECT3D12);

        // Add position attribute (3 - floats)
        BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_POSITION, 3,
            BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);

        // Add texcoord attribute (2 - floats)
        BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_TEXCOORD0, 2,
            BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);

        // Add color attribute (4 bytes, normalized)
        BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_COLOR0, 4,
            BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false);

        BGFX.bgfx_vertex_layout_end(layout);
        return layout;
    }

    /**
     * Create a vertex buffer using BGFX's native functionality.
     * BGFX handles all validation internally - we just create and return the handle.
     */
    public static short createVertexBuffer(ByteBuffer data, int flags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            BGFXMemory memory = BGFX.bgfx_copy(data);
            BGFXVertexLayout layout = createSimpleVertexLayout(stack);
            return BGFX.bgfx_create_vertex_buffer(memory, layout, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create vertex buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create an index buffer using BGFX's native functionality.
     * BGFX handles all validation internally - we just create and return the handle.
     */
    public static short createIndexBuffer(ByteBuffer data, int flags) {
        try {
            BGFXMemory memory = BGFX.bgfx_copy(data);
            return BGFX.bgfx_create_index_buffer(memory, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create index buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create a dynamic vertex buffer with proper vertex layout.
     * BGFX handles all validation internally.
     */
    public static short createDynamicVertexBuffer(int numVertices, int flags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            BGFXVertexLayout layout = createSimpleVertexLayout(stack);
            return BGFX.bgfx_create_dynamic_vertex_buffer(numVertices, layout, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create dynamic vertex buffer with {} vertices", numVertices, e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create a dynamic index buffer.
     * BGFX handles all validation internally.
     */
    public static short createDynamicIndexBuffer(int numIndices, int flags) {
        try {
            return BGFX.bgfx_create_dynamic_index_buffer(numIndices, flags);
        } catch (Exception e) {
            LOGGER.error("Failed to create dynamic index buffer", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Allocate transient vertex buffer using BGFX's native functionality.
     * BGFX handles all allocation and validation internally.
     */
    public static boolean allocTransientVertexBuffer(BGFXTransientVertexBuffer tvb, int numVertices) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            BGFXVertexLayout layout = createSimpleVertexLayout(stack);
            BGFX.bgfx_alloc_transient_vertex_buffer(tvb, numVertices, layout);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to allocate transient vertex buffer", e);
            return false;
        }
    }

    /**
     * Allocate transient index buffer using BGFX's native functionality.
     * BGFX handles all allocation and validation internally.
     */
    public static boolean allocTransientIndexBuffer(BGFXTransientIndexBuffer tib, int numIndices) {
        try {
            BGFX.bgfx_alloc_transient_index_buffer(tib, numIndices, false);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to allocate transient index buffer", e);
            return false;
        }
    }

    /**
     * Load a shader binary using BGFX's native functionality.
     * BGFX handles all validation internally.
     */
    public static short loadShader(String shaderName) {
        try {
            String resourcePath = "/shaders/" + shaderName + ".bin";
            ByteBuffer shaderData = loadShaderBinary(resourcePath);

            if (shaderData == null) {
                LOGGER.error("Shader resource not found: {}", resourcePath);
                return BGFX.BGFX_INVALID_HANDLE;
            }

            BGFXMemory memory = BGFX.bgfx_copy(shaderData);
            MemoryUtil.memFree(shaderData);

            short shaderHandle = BGFX.bgfx_create_shader(memory);
            BGFX.bgfx_set_shader_name(shaderHandle, shaderName);
            LOGGER.debug("Loaded shader: {} (handle: {})", shaderName, shaderHandle);
            return shaderHandle;

        } catch (Exception e) {
            LOGGER.error("Failed to load shader: {}", shaderName, e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Create a shader program from vertex and fragment shaders.
     * BGFX handles all validation internally.
     */
    public static short createProgram(short vertexShader, short fragmentShader, boolean destroyShaders) {
        try {
            short programHandle = BGFX.bgfx_create_program(vertexShader, fragmentShader, destroyShaders);
            LOGGER.debug("Created program: {} (vs: {}, fs: {})", programHandle, vertexShader, fragmentShader);
            return programHandle;
        } catch (Exception e) {
            LOGGER.error("Failed to create program", e);
            return BGFX.BGFX_INVALID_HANDLE;
        }
    }

    /**
     * Load a complete shader program (vertex + fragment).
     * BGFX handles all validation internally.
     */
    public static short loadProgram(String shaderName) {
        short vertexShader = loadShader("vs_" + shaderName);
        if (vertexShader == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.error("Failed to load vertex shader: vs_{}", shaderName);
            return BGFX.BGFX_INVALID_HANDLE;
        }

        short fragmentShader = loadShader("fs_" + shaderName);
        if (fragmentShader == BGFX.BGFX_INVALID_HANDLE) {
            LOGGER.warn("Fragment shader not found: fs_{}, creating compute program", shaderName);
            // For compute shaders, fragment shader can be invalid
        }

        return createProgram(vertexShader, fragmentShader, true);
    }

    /**
     * Destroy a BGFX resource.
     * BGFX handles validation internally - we just call the appropriate destroy function.
     */
    public static void destroy(short handle, int type) {
        try {
            switch (type) {
                case RESOURCE_VERTEX_BUFFER:
                    BGFX.bgfx_destroy_vertex_buffer(handle);
                    break;
                case RESOURCE_INDEX_BUFFER:
                    BGFX.bgfx_destroy_index_buffer(handle);
                    break;
                case RESOURCE_DYNAMIC_VERTEX_BUFFER:
                    BGFX.bgfx_destroy_dynamic_vertex_buffer(handle);
                    break;
                case RESOURCE_DYNAMIC_INDEX_BUFFER:
                    BGFX.bgfx_destroy_dynamic_index_buffer(handle);
                    break;
                case RESOURCE_SHADER:
                    BGFX.bgfx_destroy_shader(handle);
                    break;
                case RESOURCE_PROGRAM:
                    BGFX.bgfx_destroy_program(handle);
                    break;
                default:
                    LOGGER.warn("Unknown resource type: {}", type);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to destroy resource: handle={}, type={}", handle, type, e);
        }
    }

    /**
     * Shutdown BGFX.
     */
    public static synchronized void shutdown() {
        try {
            LOGGER.info("Shutting down BGFX...");
            BGFX.bgfx_shutdown();
            LOGGER.info("BGFX shutdown completed");
        } catch (Exception e) {
            LOGGER.error("Exception during BGFX shutdown", e);
        }
    }

    /**
     * Get current renderer type.
     */
    public static int getRendererType() {
        try {
            return BGFX.bgfx_get_renderer_type();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Load shader binary from resources.
     */
    private static ByteBuffer loadShaderBinary(String resourcePath) {
        try (InputStream inputStream = Util.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return null;
            }

            byte[] data = inputStream.readAllBytes();
            if (data.length == 0) {
                return null;
            }

            ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
            buffer.put(data);
            buffer.flip();
            return buffer;
        } catch (IOException e) {
            LOGGER.error("Failed to read shader binary: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Check if a BGFX handle is valid.
     * BGFX uses BGFX_INVALID_HANDLE (0xFFFF) for invalid handles.
     * Note: This is primarily for debugging/logging - BGFX validates handles internally.
     */
    public static boolean isValidHandle(short handle) {
        return handle != BGFX.BGFX_INVALID_HANDLE;
    }

    /**
     * Resource type constants.
     */
    public static final int RESOURCE_VERTEX_BUFFER = 0;
    public static final int RESOURCE_INDEX_BUFFER = 1;
    public static final int RESOURCE_DYNAMIC_VERTEX_BUFFER = 2;
    public static final int RESOURCE_DYNAMIC_INDEX_BUFFER = 3;
    public static final int RESOURCE_SHADER = 4;
    public static final int RESOURCE_PROGRAM = 5;
}
