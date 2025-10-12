package com.vitra.render.jni;

import com.vitra.debug.VitraDebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI interface for native DirectX 11 rendering operations with advanced debugging.
 * This replaces the BGFX implementation with direct DirectX 11 calls.
 *
 * Enhanced with:
 * - DirectX 11 Debug Layer and InfoQueue integration
 * - Minidump generation for crash analysis
 * - RenderDoc capture support
 * - Safe JNI exception handling with logging
 */
public class VitraNativeRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraNativeRenderer.class);

    // Debug state
    private static boolean debugEnabled = false;
    private static boolean safeModeEnabled = false;

    // Load the native library
    static {
        try {
            // Try loading from the jar resources first
            System.loadLibrary("vitra-native");
        } catch (UnsatisfiedLinkError e) {
            try {
                // Fallback to loading from the native directory
                String osName = System.getProperty("os.name").toLowerCase();
                String libName = osName.contains("win") ? "vitra-native.dll" : "libvitra-native.so";

                String nativePath = VitraNativeRenderer.class.getResource("/native/windows/" + libName).getPath();
                if (nativePath != null) {
                    System.load(nativePath);
                } else {
                    throw new RuntimeException("Native library not found: " + libName);
                }
            } catch (Exception ex) {
                throw new RuntimeException("Failed to load native library", ex);
            }
        }
    }

    /**
     * Initialize native DirectX 11 renderer with advanced debugging support
     * @param windowHandle - Native window handle (HWND for Windows)
     * @param width - Window width
     * @param height - Window height
     * @param enableDebug - Enable debug layer and advanced debugging
     * @return true if successful
     */
    public static native boolean initializeDirectX(long windowHandle, int width, int height, boolean enableDebug);

    /**
     * Initialize debugging system (called before initializeDirectX)
     */
    public static synchronized void initializeDebug(boolean enabled, boolean verbose, boolean renderDoc, boolean minidumps) {
        debugEnabled = enabled;
        VitraDebugUtils.initializeDebug(enabled, verbose, renderDoc, minidumps);

        if (enabled) {
            LOGGER.info("DirectX 11 debugging enabled with: verbose={}, renderDoc={}, minidumps={}",
                verbose, renderDoc, minidumps);
        }
    }

    /**
     * Safe wrapper around initializeDirectX with exception handling
     */
    public static boolean initializeDirectXSafe(long windowHandle, int width, int height, boolean enableDebug) {
        try {
            // Initialize debug system first if needed
            if (enableDebug && !VitraDebugUtils.isDebugInitialized()) {
                initializeDebug(true, false, true, true);
            }

            LOGGER.info("Initializing DirectX 11 renderer: window=0x{}, size={}x{}, debug={}",
                Long.toHexString(windowHandle), width, height, enableDebug);

            boolean success = initializeDirectX(windowHandle, width, height, enableDebug);

            if (success) {
                LOGGER.info("✓ DirectX 11 renderer initialized successfully");

                // Enable safe mode if debug is enabled
                if (enableDebug) {
                    safeModeEnabled = true;
                    LOGGER.info("Safe mode enabled - all operations will be wrapped with error handling");
                }
            } else {
                LOGGER.error("✗ DirectX 11 renderer initialization failed");
            }

            return success;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("DirectX 11 native library not available: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected error during DirectX 11 initialization", e);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("INIT_ERROR: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Shutdown the native renderer
     */
    public static native void shutdown();

    /**
     * Safe shutdown with debug cleanup
     */
    public static synchronized void shutdownSafe() {
        try {
            LOGGER.info("Shutting down DirectX 11 renderer...");

            // Process remaining debug messages
            if (debugEnabled) {
                VitraDebugUtils.processDebugMessages();
            }

            // Call native shutdown
            shutdown();

            // Shutdown debug system
            if (debugEnabled) {
                VitraDebugUtils.shutdownDebug();
            }

            safeModeEnabled = false;
            LOGGER.info("✓ DirectX 11 renderer shutdown complete");

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("DirectX 11 native library not available during shutdown: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error during DirectX 11 shutdown", e);
        }
    }

    /**
     * Resize the render viewport
     */
    public static native void resize(int width, int height);

    /**
     * Begin a new frame
     */
    public static native void beginFrame();

    /**
     * End current frame and present
     */
    public static native void endFrame();

    /**
     * Clear the render target
     */
    public static native void clear(float r, float g, float b, float a);

    /**
     * Create a vertex buffer
     * @param data - Vertex data
     * @param size - Size of data in bytes
     * @param stride - Vertex stride
     * @return Buffer handle
     */
    public static native long createVertexBuffer(byte[] data, int size, int stride);

    /**
     * Create an index buffer
     * @param data - Index data
     * @param size - Size of data in bytes
     * @param format - Index format (16 or 32 bit)
     * @return Buffer handle
     */
    public static native long createIndexBuffer(byte[] data, int size, int format);

    /**
     * Create a shader from compiled bytecode
     * @param bytecode - Compiled shader bytecode
     * @param size - Size of bytecode
     * @param type - Shader type (vertex, pixel, etc.)
     * @return Shader handle
     */
    public static native long createShader(byte[] bytecode, int size, int type);

    /**
     * Create a shader pipeline from vertex and pixel shaders
     * @param vertexShader - Vertex shader handle
     * @param pixelShader - Pixel shader handle
     * @return Pipeline handle
     */
    public static native long createShaderPipeline(long vertexShader, long pixelShader);

    /**
     * Destroy a resource (buffer, shader, pipeline)
     */
    public static native void destroyResource(long handle);

    /**
     * Set the current shader pipeline
     */
    public static native void setShaderPipeline(long pipeline);

    /**
     * Draw primitives
     * @param vertexBuffer - Vertex buffer handle
     * @param indexBuffer - Index buffer handle (0 for non-indexed)
     * @param vertexCount - Number of vertices
     * @param indexCount - Number of indices (0 for non-indexed)
     */
    public static native void draw(long vertexBuffer, long indexBuffer, int vertexCount, int indexCount);

    /**
     * Check if the native renderer is initialized
     */
    public static native boolean isInitialized();

    /**
     * Set primitive topology for drawing
     * @param topology - OpenGL primitive mode (GL_TRIANGLES, GL_LINES, etc.)
     */
    public static native void setPrimitiveTopology(int topology);

    /**
     * Draw mesh data directly from byte buffers (for 1.21.8 compatibility)
     * @param vertexBuffer - Vertex data buffer
     * @param indexBuffer - Index data buffer (null for non-indexed)
     * @param vertexCount - Number of vertices
     * @param indexCount - Number of indices (0 for non-indexed)
     * @param primitiveMode - Primitive mode (GL_TRIANGLES, etc.)
     * @param vertexSize - Size of each vertex in bytes
     */
    public static native void drawMeshData(
        java.nio.ByteBuffer vertexBuffer,
        java.nio.ByteBuffer indexBuffer,
        int vertexCount,
        int indexCount,
        int primitiveMode,
        int vertexSize
    );

    /**
     * Create texture from pixel data
     * @param data - Pixel data
     * @param width - Texture width
     * @param height - Texture height
     * @param format - Pixel format
     * @return Texture handle
     */
    public static native long createTexture(byte[] data, int width, int height, int format);

    /**
     * Bind texture to a slot
     * @param texture - Texture handle
     * @param slot - Texture slot (0-15)
     */
    public static native void bindTexture(long texture, int slot);

    /**
     * Set shader constant buffer
     * @param data - Buffer data
     * @param size - Size of data
     * @param slot - Constant buffer slot
     */
    public static native void setConstantBuffer(byte[] data, int size, int slot);

    /**
     * Set viewport
     * @param x - X position
     * @param y - Y position
     * @param width - Width
     * @param height - Height
     */
    public static native void setViewport(int x, int y, int width, int height);

    /**
     * Set scissor rectangle
     * @param x - X position
     * @param y - Y position
     * @param width - Width
     * @param height - Height
     */
    public static native void setScissorRect(int x, int y, int width, int height);

    // Shader type constants
    public static final int SHADER_TYPE_VERTEX = 0;
    public static final int SHADER_TYPE_PIXEL = 1;
    public static final int SHADER_TYPE_GEOMETRY = 2;
    public static final int SHADER_TYPE_COMPUTE = 3;

    // Index format constants
    public static final int INDEX_FORMAT_16_BIT = 0;
    public static final int INDEX_FORMAT_32_BIT = 1;

    // Texture format constants
    public static final int TEXTURE_FORMAT_RGBA8 = 0;
    public static final int TEXTURE_FORMAT_RGB8 = 1;
    public static final int TEXTURE_FORMAT_R8 = 2;
    public static final int TEXTURE_FORMAT_D24S8 = 3;

    // ==================== SAFE WRAPPER METHODS ====================

    /**
     * Safe beginFrame with error handling and debug integration
     */
    public static void beginFrameSafe() {
        if (!safeModeEnabled) {
            beginFrame();
            return;
        }

        try {
            beginFrame();

            // Process debug messages
            if (debugEnabled) {
                VitraDebugUtils.processDebugMessages();
            }

        } catch (Exception e) {
            LOGGER.error("Error in beginFrame", e);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("FRAME_ERROR: beginFrame failed - " + e.getMessage());
            }
        }
    }

    /**
     * Safe endFrame with error handling and debug integration
     */
    public static void endFrameSafe() {
        if (!safeModeEnabled) {
            endFrame();
            return;
        }

        try {
            endFrame();

            // Process debug messages after frame completion
            if (debugEnabled) {
                VitraDebugUtils.processDebugMessages();
            }

        } catch (Exception e) {
            LOGGER.error("Error in endFrame", e);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("FRAME_ERROR: endFrame failed - " + e.getMessage());
            }
        }
    }

    /**
     * Safe draw operation with validation
     */
    public static void drawSafe(long vertexBuffer, long indexBuffer, int vertexCount, int indexCount) {
        if (!safeModeEnabled) {
            draw(vertexBuffer, indexBuffer, vertexCount, indexCount);
            return;
        }

        // Validate parameters
        if (vertexBuffer == 0 && vertexCount > 0) {
            LOGGER.warn("Invalid draw call: null vertex buffer with {} vertices", vertexCount);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("DRAW_WARNING: Invalid vertex buffer");
            }
            return;
        }

        if (indexBuffer == 0 && indexCount > 0) {
            LOGGER.warn("Invalid draw call: null index buffer with {} indices", indexCount);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("DRAW_WARNING: Invalid index buffer");
            }
            return;
        }

        try {
            draw(vertexBuffer, indexBuffer, vertexCount, indexCount);
        } catch (Exception e) {
            LOGGER.error("Error in draw operation", e);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("DRAW_ERROR: " + e.getMessage());
            }
        }
    }

    // ==================== DEBUG INTEGRATION METHODS ====================

    /**
     * Trigger RenderDoc frame capture
     */
    public static boolean triggerRenderDocCapture() {
        if (!debugEnabled || !VitraDebugUtils.isRenderDocAvailable()) {
            LOGGER.warn("RenderDoc not available for capture");
            return false;
        }

        return VitraDebugUtils.triggerDebugCapture();
    }

    /**
     * Process debug messages from native code
     */
    public static void processDebugMessages() {
        if (debugEnabled) {
            VitraDebugUtils.processDebugMessages();
        }
    }

    /**
     * Get debug system statistics
     */
    public static String getDebugStats() {
        if (!debugEnabled) {
            return "Debug system disabled";
        }

        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX 11 Debug Stats ===\n");
        stats.append("Debug Enabled: ").append(debugEnabled).append("\n");
        stats.append("Safe Mode: ").append(safeModeEnabled).append("\n");
        stats.append(VitraDebugUtils.getDebugStats());
        stats.append("Renderer Initialized: ").append(isInitialized()).append("\n");
        return stats.toString();
    }

    /**
     * Check if debug features are available
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Check if safe mode is enabled (wraps all operations with error handling)
     */
    public static boolean isSafeModeEnabled() {
        return safeModeEnabled;
    }

    // ==================== NATIVE DEBUG METHODS ====================

    /**
     * Native method to get debug messages from DirectX debug layer
     */
    public static native String nativeGetDebugMessages();

    /**
     * Native method to clear debug message queue
     */
    public static native void nativeClearDebugMessages();

    /**
     * Native method to set debug message severity level
     */
    public static native void nativeSetDebugSeverity(int severity);

    /**
     * Native method to break on debug error (for debugging)
     */
    public static native void nativeBreakOnError(boolean enabled);

    /**
     * Native method to get device information
     */
    public static native String nativeGetDeviceInfo();

    /**
     * Native method to validate shader bytecode
     */
    public static native boolean nativeValidateShader(byte[] bytecode, int size);

    // Debug severity constants
    public static final int DEBUG_SEVERITY_INFO = 0;
    public static final int DEBUG_SEVERITY_WARNING = 1;
    public static final int DEBUG_SEVERITY_ERROR = 2;
    public static final int DEBUG_SEVERITY_CORRUPTION = 3;

    // ==================== BUFFER MAPPING METHODS ====================

    /**
     * Map a buffer for CPU access
     * @param bufferHandle - Buffer handle
     * @param size - Buffer size in bytes
     * @param accessFlags - Access flags: 1=read, 2=write, 3=read+write
     * @return Direct ByteBuffer pointing to mapped memory, or null on failure
     */
    public static native java.nio.ByteBuffer mapBuffer(long bufferHandle, int size, int accessFlags);

    /**
     * Unmap a previously mapped buffer
     * @param bufferHandle - Buffer handle
     */
    public static native void unmapBuffer(long bufferHandle);
}