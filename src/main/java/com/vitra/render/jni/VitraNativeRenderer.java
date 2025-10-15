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
    public static synchronized void initializeDebug(boolean enabled, boolean verbose) {
        debugEnabled = enabled;
        VitraDebugUtils.initializeDebug(enabled, verbose);

        if (enabled) {
            LOGGER.info("DirectX 11 debugging enabled with verbose={}", verbose);
        }
    }

    /**
     * Safe wrapper around initializeDirectX with exception handling
     */
    public static boolean initializeDirectXSafe(long windowHandle, int width, int height, boolean enableDebug) {
        try {
            // Initialize debug system first if needed
            if (enableDebug && !VitraDebugUtils.isDebugInitialized()) {
                initializeDebug(true, false);
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
     * Set clear color for subsequent clear operations (FIX: Missing - causes pink background)
     * @param r - Red component (0.0-1.0)
     * @param g - Green component (0.0-1.0)
     * @param b - Blue component (0.0-1.0)
     * @param a - Alpha component (0.0-1.0)
     */
    public static native void setClearColor(float r, float g, float b, float a);

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
    public static native long createGLProgramShader(byte[] bytecode, int size, int type);

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
     * Get the default shader pipeline handle (created during initialization)
     * @return Default pipeline handle, or 0 if not available
     */
    public static native long getDefaultShaderPipeline();

    /**
     * Draw primitives with full DrawIndexed support
     * @param vertexBuffer - Vertex buffer handle
     * @param indexBuffer - Index buffer handle (0 for non-indexed)
     * @param baseVertex - Base vertex location (offset into vertex buffer)
     * @param firstIndex - Start index location (offset into index buffer)
     * @param indexCount - Number of indices to draw
     * @param instanceCount - Number of instances (1 for non-instanced)
     */
    public static native void draw(long vertexBuffer, long indexBuffer, int baseVertex, int firstIndex, int indexCount, int instanceCount);

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
     * Update existing texture data without recreating the texture
     * Uses ID3D11DeviceContext::UpdateSubresource for efficient data upload
     *
     * @param textureHandle - Existing texture handle
     * @param data - New pixel data
     * @param width - Texture width
     * @param height - Texture height
     * @param mipLevel - Mip level to update (0 for base level)
     * @return true if update succeeded, false if texture needs to be recreated
     */
    public static native boolean updateTexture(long textureHandle, byte[] data, int width, int height, int mipLevel);

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

    // ==================== UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts) ====================

    /**
     * Set 4-component float uniform (FIX: Missing - causes transformation issues)
     * @param location - Uniform location
     * @param v0 - First component
     * @param v1 - Second component
     * @param v2 - Third component
     * @param v3 - Fourth component
     */
    public static native void setUniform4f(int location, float v0, float v1, float v2, float v3);

    /**
     * Set 4x4 matrix uniform (FIX: Missing - causes vertex positioning issues)
     * @param location - Uniform location
     * @param matrix - Matrix data (16 floats)
     * @param transpose - Whether to transpose the matrix
     */
    public static native void setUniformMatrix4f(int location, float[] matrix, boolean transpose);

    /**
     * Set integer uniform (FIX: Missing - causes texture sampling issues)
     * @param location - Uniform location
     * @param value - Integer value
     */
    public static native void setUniform1i(int location, int value);

    /**
     * Set float uniform (FIX: Missing - causes parameter issues)
     * @param location - Uniform location
     * @param value - Float value
     */
    public static native void setUniform1f(int location, float value);

    /**
     * Set 2-component float uniform
     * @param location - Uniform location
     * @param v0 - First component
     * @param v1 - Second component
     */
    public static native void setUniform2f(int location, float v0, float v1);

    /**
     * Set 3-component float uniform
     * @param location - Uniform location
     * @param v0 - First component
     * @param v1 - Second component
     * @param v2 - Third component
     */
    public static native void setUniform3f(int location, float v0, float v1, float v2);

    /**
     * Set active shader program (FIX: Missing - causes shader state issues)
     * @param program - Program handle/ID
     */
    public static native void useProgram(int program);

    // ==================== TEXTURE METHODS ====================

    /**
     * Set texture parameter (CRITICAL for fixing yellow rays)
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @param pname - Parameter name (GL_TEXTURE_MIN_FILTER, etc.)
     * @param param - Parameter value
     */
    public static native void setTextureParameter(int target, int pname, int param);

    /**
     * Set texture parameter (float version)
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @param pname - Parameter name (GL_TEXTURE_MIN_FILTER, etc.)
     * @param param - Parameter value (float)
     */
    public static native void setTextureParameterf(int target, int pname, float param);

    /**
     * Get texture parameter
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @param pname - Parameter name (GL_TEXTURE_MIN_FILTER, etc.)
     * @return Parameter value
     */
    public static native int getTextureParameter(int target, int pname);

    /**
     * Get texture level parameter
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @param level - Mipmap level
     * @param pname - Parameter name (GL_TEXTURE_WIDTH, etc.)
     * @return Parameter value
     */
    public static native int getTextureLevelParameter(int target, int level, int pname);

    /**
     * Set pixel storage mode (important for texture alignment)
     * @param pname - Parameter name (GL_UNPACK_ALIGNMENT, etc.)
     * @param param - Parameter value
     */
    public static native void setPixelStore(int pname, int param);

    /**
     * Get texture image data (for readback/download)
     * @param tex - Texture target (GL_TEXTURE_2D, etc.)
     * @param level - Mipmap level
     * @param format - Pixel format (GL_RGBA, etc.)
     * @param type - Data type (GL_UNSIGNED_BYTE, etc.)
     * @param pixels - Output pixel data
     */
    public static native void glGetTexImage(int tex, int level, int format, int type, long pixels);

    /**
     * Get texture image data (ByteBuffer version)
     * @param tex - Texture target (GL_TEXTURE_2D, etc.)
     * @param level - Mipmap level
     * @param format - Pixel format (GL_RGBA, etc.)
     * @param type - Data type (GL_UNSIGNED_BYTE, etc.)
     * @param pixels - Output pixel data
     */
    public static native void glGetTexImage(int tex, int level, int format, int type, java.nio.ByteBuffer pixels);

    /**
     * Get texture image data (IntBuffer version)
     * @param tex - Texture target (GL_TEXTURE_2D, etc.)
     * @param level - Mipmap level
     * @param format - Pixel format (GL_RGBA, etc.)
     * @param type - Data type (GL_UNSIGNED_BYTE, etc.)
     * @param pixels - Output pixel data
     */
    public static native void glGetTexImage(int tex, int level, int format, int type, java.nio.IntBuffer pixels);

    /**
     * Copy texture sub-image (CRITICAL for dynamic texture updates)
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @param level - Mipmap level
     * @param xoffset - X offset in pixels
     * @param yoffset - Y offset in pixels
     * @param width - Width in pixels
     * @param height - Height in pixels
     * @param format - Pixel format (GL_RGBA, etc.)
     * @param type - Data type (GL_UNSIGNED_BYTE, etc.)
     * @param pixels - Pixel data
     */
    public static native void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);

    /**
     * Alias for glCopyTexSubImage2D to match GLInterceptor call
     */
    public static void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    // ==================== BUFFER METHODS ====================

    /**
     * Map a buffer for CPU access (CRITICAL for vertex data access)
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

    /**
     * Get buffer parameter
     * @param target - Buffer target (GL_ARRAY_BUFFER, etc.)
     * @param pname - Parameter name (GL_BUFFER_SIZE, etc.)
     * @param params - Output parameter values
     */
    public static native void glGetBufferParameteriv(int target, int pname, java.nio.IntBuffer params);

    // ==================== SHADER CREATION METHODS ====================

    /**
     * Create a shader (vertex or pixel)
     * @param type - Shader type (GL_VERTEX_SHADER, GL_FRAGMENT_SHADER, etc.)
     * @return Shader handle/ID
     */
    public static native int createGLProgramShader(int type);

    /**
     * Set shader source code
     * @param shader - Shader handle/ID
     * @param source - Shader source code as string
     */
    public static native void shaderSource(int shader, String source);

    /**
     * Compile shader
     * @param shader - Shader handle/ID
     */
    public static native void compileShader(int shader);

    /**
     * Create shader program
     * @return Program handle/ID
     */
    public static native int createProgram();

    /**
     * Attach shader to program
     * @param program - Program handle/ID
     * @param shader - Shader handle/ID
     */
    public static native void attachShader(int program, int shader);

    /**
     * Link shader program
     * @param program - Program handle/ID
     */
    public static native void linkProgram(int program);

    /**
     * Validate shader program
     * @param program - Program handle/ID
     */
    public static native void validateProgram(int program);

    /**
     * Delete shader
     * @param shader - Shader handle/ID
     */
    public static native void deleteShader(int shader);

    /**
     * Delete shader program
     * @param program - Program handle/ID
     */
    public static native void deleteProgram(int program);

    // ==================== VERTEX ATTRIBUTE METHODS ====================

    /**
     * Enable vertex attribute array
     * @param index - Attribute index
     */
    public static native void enableVertexAttribArray(int index);

    /**
     * Disable vertex attribute array
     * @param index - Attribute index
     */
    public static native void disableVertexAttribArray(int index);

    /**
     * Set vertex attribute pointer
     * @param index - Attribute index
     * @param size - Number of components (1-4)
     * @param type - Data type (GL_FLOAT, etc.)
     * @param normalized - Whether to normalize values
     * @param stride - Stride between attributes
     * @param pointer - Pointer to vertex data
     */
    public static native void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);

    /**
     * Set vertex attribute pointer (ByteBuffer version)
     * @param index - Attribute index
     * @param size - Number of components (1-4)
     * @param type - Data type (GL_FLOAT, etc.)
     * @param normalized - Whether to normalize values
     * @param stride - Stride between attributes
     * @param pointer - Pointer to vertex data
     */
    public static native void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, java.nio.ByteBuffer pointer);

    /**
     * Set integer vertex attribute pointer
     * @param index - Attribute index
     * @param size - Number of components (1-4)
     * @param type - Data type (GL_INT, etc.)
     * @param stride - Stride between attributes
     * @param pointer - Pointer to vertex data
     */
    public static native void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer);

    /**
     * Get uniform location (string version)
     * @param program - Program handle/ID
     * @param name - Uniform name
     * @return Uniform location, or -1 if not found
     */
    public static native int glGetUniformLocation(int program, String name);

    /**
     * Get uniform location (ByteBuffer version)
     * @param program - Program handle/ID
     * @param name - Uniform name as ByteBuffer
     * @return Uniform location, or -1 if not found
     */
    public static native int glGetUniformLocation(int program, java.nio.ByteBuffer name);

    /**
     * Get attribute location (string version)
     * @param program - Program handle/ID
     * @param name - Attribute name
     * @return Attribute location, or -1 if not found
     */
    public static native int glGetAttribLocation(int program, String name);

    /**
     * Get attribute location (ByteBuffer version)
     * @param program - Program handle/ID
     * @param name - Attribute name as ByteBuffer
     * @return Attribute location, or -1 if not found
     */
    public static native int glGetAttribLocation(int program, java.nio.ByteBuffer name);

    
    // ==================== MISC METHODS ====================

    /**
     * Set line width
     * @param width - Line width in pixels
     */
    public static native void setLineWidth(float width);

    /**
     * Set depth mask
     * @param flag - Enable/disable depth writes
     */
    public static native void setDepthMask(boolean flag);

    /**
     * Set polygon offset
     * @param factor - Depth bias factor
     * @param units - Depth bias units
     */
    public static native void setPolygonOffset(float factor, float units);

    /**
     * Set blend function (CRITICAL for proper rendering)
     * @param sfactor - Source blend factor
     * @param dfactor - Destination blend factor
     */
    public static native void setBlendFunc(int sfactor, int dfactor);

    /**
     * Finish rendering commands
     */
    public static native void finish();

    /**
     * Set implementation hint
     * @param target - Hint target
     * @param hint - Hint value
     */
    public static native void setHint(int target, int hint);

    /**
     * Get maximum texture size
     * @return Maximum texture dimension
     */
    public static native int getMaxTextureSize();

    /**
     * Clear depth buffer
     * @param depth - Depth value (0.0 to 1.0)
     */
    public static native void clearDepth(float depth);

    /**
     * Set color write mask
     * @param red - Enable red channel writes
     * @param green - Enable green channel writes
     * @param blue - Enable blue channel writes
     * @param alpha - Enable alpha channel writes
     */
    public static native void setColorMask(boolean red, boolean green, boolean blue, boolean alpha);

    // ==================== ADDITIONAL glGet METHODS ====================

    /**
     * Get integer state
     * @param pname - Parameter name (GL_VIEWPORT, etc.)
     * @return Integer value
     */
    public static native int glGetInteger(int pname);

    /**
     * Get error state
     * @return Error code, or GL_NO_ERROR (0) if no error
     */
    public static native int glGetError();

    /**
     * Get buffer pointer (for vertex buffer binding)
     * @param target - Buffer target (GL_ARRAY_BUFFER, etc.)
     * @param pname - Parameter name (GL_BUFFER_MAP_POINTER, etc.)
     * @param params - Output pointer value
     */
    public static native void glGetBufferPointerv(int target, int pname, java.nio.ByteBuffer params);

    /**
     * Get buffer sub-data (for reading back buffer contents)
     * @param target - Buffer target (GL_ARRAY_BUFFER, etc.)
     * @param offset - Offset in bytes
     * @param size - Size in bytes
     * @param data - Output data buffer
     */
    public static native void glGetBufferSubData(int target, long offset, long size, java.nio.ByteBuffer data);

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
    public static void drawSafe(long vertexBuffer, long indexBuffer, int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        if (!safeModeEnabled) {
            draw(vertexBuffer, indexBuffer, baseVertex, firstIndex, indexCount, instanceCount);
            return;
        }

        // Validate parameters
        if (vertexBuffer == 0 && indexCount > 0) {
            LOGGER.warn("Invalid draw call: null vertex buffer with {} indices", indexCount);
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
            draw(vertexBuffer, indexBuffer, baseVertex, firstIndex, indexCount, instanceCount);
        } catch (Exception e) {
            LOGGER.error("Error in draw operation", e);
            if (debugEnabled) {
                VitraDebugUtils.queueDebugMessage("DRAW_ERROR: " + e.getMessage());
            }
        }
    }

    // ==================== DEBUG INTEGRATION METHODS ====================

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

    // ==================== NATIVE LOGGING CALLBACKS ====================

    /**
     * Native logging callback - called from C++ code to log DEBUG messages
     * This allows native C++ code to integrate with Java's SLF4J logging
     * @param message - Log message from native code
     */
    public static void nativeLogDebug(String message) {
        if (message != null && !message.isEmpty()) {
            LOGGER.debug("[Native] {}", message);
        }
    }

    /**
     * Native logging callback - called from C++ code to log INFO messages
     * @param message - Log message from native code
     */
    public static void nativeLogInfo(String message) {
        if (message != null && !message.isEmpty()) {
            LOGGER.info("[Native] {}", message);
        }
    }

    /**
     * Native logging callback - called from C++ code to log WARNING messages
     * @param message - Log message from native code
     */
    public static void nativeLogWarn(String message) {
        if (message != null && !message.isEmpty()) {
            LOGGER.warn("[Native] {}", message);
        }
    }

    /**
     * Native logging callback - called from C++ code to log ERROR messages
     * @param message - Log message from native code
     */
    public static void nativeLogError(String message) {
        if (message != null && !message.isEmpty()) {
            LOGGER.error("[Native] {}", message);
        }
    }

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

    
    // ==================== RENDER STATE MANAGEMENT ====================

    /**
     * Set blend state for alpha blending
     * @param enabled - Enable/disable blending
     * @param srcBlend - Source blend factor (GL constant)
     * @param destBlend - Destination blend factor (GL constant)
     * @param blendOp - Blend operation (GL constant)
     */
    public static native void setBlendState(boolean enabled, int srcBlend, int destBlend, int blendOp);

    /**
     * Set depth-stencil state
     * @param depthTestEnabled - Enable/disable depth testing
     * @param depthWriteEnabled - Enable/disable depth writing
     * @param depthFunc - Depth comparison function (GL constant)
     */
    public static native void setDepthState(boolean depthTestEnabled, boolean depthWriteEnabled, int depthFunc);

    /**
     * Set rasterizer state
     * @param cullMode - Cull mode (GL_FRONT, GL_BACK, 0=none)
     * @param fillMode - Fill mode (GL_FILL, GL_LINE, GL_POINT)
     * @param scissorEnabled - Enable/disable scissor test
     */
    public static native void setRasterizerState(int cullMode, int fillMode, boolean scissorEnabled);

    
    // OpenGL constants for render state
    public static final int GL_ZERO = 0;
    public static final int GL_ONE = 1;
    public static final int GL_SRC_COLOR = 0x0300;
    public static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_DST_ALPHA = 0x0304;
    public static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    public static final int GL_DST_COLOR = 0x0306;
    public static final int GL_ONE_MINUS_DST_COLOR = 0x0307;

    public static final int GL_FUNC_ADD = 0x8006;
    public static final int GL_MIN = 0x8007;
    public static final int GL_MAX = 0x8008;
    public static final int GL_FUNC_SUBTRACT = 0x800A;
    public static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;

    public static final int GL_NEVER = 0x0200;
    public static final int GL_LESS = 0x0201;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_GREATER = 0x0204;
    public static final int GL_NOTEQUAL = 0x0205;
    public static final int GL_GEQUAL = 0x0206;
    public static final int GL_ALWAYS = 0x0207;

    public static final int GL_FRONT = 0x0404;
    public static final int GL_BACK = 0x0405;

    public static final int GL_POINT = 0x1B00;
    public static final int GL_LINE = 0x1B01;
    public static final int GL_FILL = 0x1B02;

    // ==================== MATRIX MANAGEMENT ====================

    /**
     * Set orthographic projection matrix for 2D rendering (GUI, menus, etc.)
     * This configures the transformation matrix to properly project 2D screen coordinates
     * to NDC (Normalized Device Coordinate) space [-1, 1]
     *
     * @param left - Left edge of the viewing volume (usually 0)
     * @param right - Right edge of the viewing volume (usually screen width)
     * @param bottom - Bottom edge of the viewing volume (usually screen height)
     * @param top - Top edge of the viewing volume (usually 0)
     * @param zNear - Near clipping plane (usually 0 or -1)
     * @param zFar - Far clipping plane (usually 1 or 1000)
     */
    public static native void setOrthographicProjection(float left, float right, float bottom, float top, float zNear, float zFar);

    /**
     * Set projection matrix from Minecraft's RenderSystem
     * This synchronizes Minecraft's Matrix4f projection matrix (JOML column-major) to DirectX 11 (row-major)
     *
     * @param matrixData - Float array containing 16 elements of the 4x4 matrix in column-major order
     */
    public static native void setProjectionMatrix(float[] matrixData);

    /**
     * Set ALL transformation matrices from Minecraft's RenderSystem
     * This is the CORRECT way to sync matrices - we need MVP, ModelView, and Projection!
     *
     * @param mvpData - Model-View-Projection matrix (16 floats, column-major)
     * @param modelViewData - Model-View matrix (16 floats, column-major)
     * @param projectionData - Projection matrix (16 floats, column-major)
     */
    public static native void setTransformMatrices(float[] mvpData, float[] modelViewData, float[] projectionData);

    // ==================== BUFFER/TEXTURE COPY OPERATIONS ====================

    /**
     * Copy data from one buffer to another
     * Uses ID3D11DeviceContext::CopySubresourceRegion for GPU-side copy
     *
     * @param srcBufferHandle - Source buffer handle
     * @param dstBufferHandle - Destination buffer handle
     * @param srcOffset - Source offset in bytes
     * @param dstOffset - Destination offset in bytes
     * @param size - Number of bytes to copy
     */
    public static native void copyBuffer(long srcBufferHandle, long dstBufferHandle,
                                          int srcOffset, int dstOffset, int size);

    /**
     * Copy entire texture to another texture
     * Uses ID3D11DeviceContext::CopyResource
     * Requires textures to have identical dimensions and formats
     *
     * @param srcTextureHandle - Source texture handle
     * @param dstTextureHandle - Destination texture handle
     */
    public static native void copyTexture(long srcTextureHandle, long dstTextureHandle);

    /**
     * Copy a specific region of a texture
     * Uses ID3D11DeviceContext::CopySubresourceRegion
     *
     * @param srcTextureHandle - Source texture handle
     * @param dstTextureHandle - Destination texture handle
     * @param srcX - Source X offset
     * @param srcY - Source Y offset
     * @param srcZ - Source Z offset
     * @param dstX - Destination X offset
     * @param dstY - Destination Y offset
     * @param dstZ - Destination Z offset
     * @param width - Region width
     * @param height - Region height
     * @param depth - Region depth
     * @param mipLevel - Mip level to copy
     */
    public static native void copyTextureRegion(long srcTextureHandle, long dstTextureHandle,
                                                 int srcX, int srcY, int srcZ,
                                                 int dstX, int dstY, int dstZ,
                                                 int width, int height, int depth, int mipLevel);

    /**
     * Copy texture data to a buffer (for readback/download)
     * Uses staging texture as intermediate step
     *
     * @param textureHandle - Source texture handle
     * @param bufferHandle - Destination buffer handle
     * @param mipLevel - Mip level to copy
     */
    public static native void copyTextureToBuffer(long textureHandle, long bufferHandle, int mipLevel);

    // ==================== GPU SYNCHRONIZATION (FENCES/QUERIES) ====================

    /**
     * Create a GPU fence for CPU-GPU synchronization
     * Implemented as ID3D11Query with D3D11_QUERY_EVENT
     *
     * @return Fence handle, or 0 on failure
     */
    public static native long createFence();

    /**
     * Signal a fence - GPU will signal when all previous commands complete
     * Issues ID3D11DeviceContext::End() on the query
     *
     * @param fenceHandle - Fence handle
     */
    public static native void signalFence(long fenceHandle);

    /**
     * Check if a fence has been signaled (non-blocking)
     * Uses ID3D11DeviceContext::GetData() with D3D11_ASYNC_GETDATA_DONOTFLUSH
     *
     * @param fenceHandle - Fence handle
     * @return true if GPU has completed all commands up to this fence
     */
    public static native boolean isFenceSignaled(long fenceHandle);

    /**
     * Wait for a fence to be signaled (blocking)
     * Spins until GPU completes all commands
     *
     * @param fenceHandle - Fence handle
     */
    public static native void waitForFence(long fenceHandle);

    // ==================== RENDERDOC INTEGRATION ====================

    /**
     * Check if RenderDoc is available
     * @return true if RenderDoc is loaded and available
     */
    public static native boolean renderDocIsAvailable();

    /**
     * Start a RenderDoc frame capture
     * Captures the next frame rendered after this call
     * @return true if capture started successfully
     */
    public static native boolean renderDocStartFrameCapture();

    /**
     * End a RenderDoc frame capture
     * Completes the current capture and saves to file
     * @return true if capture ended successfully
     */
    public static native boolean renderDocEndFrameCapture();

    /**
     * Trigger a RenderDoc capture for the next frame
     * Convenience method that triggers capture without manual start/end
     */
    public static native void renderDocTriggerCapture();

    /**
     * Check if RenderDoc is currently capturing a frame
     * @return true if a capture is in progress
     */
    public static native boolean renderDocIsCapturing();

    /**
     * Set a RenderDoc capture option
     * @param option - RenderDoc capture option ID
     * @param value - Option value
     */
    public static native void renderDocSetCaptureOption(int option, int value);

    // RenderDoc capture option constants
    public static final int RENDERDOC_OPTION_ALLOW_VSYNC = 0;
    public static final int RENDERDOC_OPTION_ALLOW_FULLSCREEN = 1;
    public static final int RENDERDOC_OPTION_API_VALIDATION = 2;
    public static final int RENDERDOC_OPTION_CAPTURE_CALLSTACKS = 3;
    public static final int RENDERDOC_OPTION_CAPTURE_CALLSTACKS_ONLY_DRAWS = 4;
    public static final int RENDERDOC_OPTION_DELAY_FOR_DEBUGGER = 5;
    public static final int RENDERDOC_OPTION_VERIFY_BUFFER_WRITES = 6;
    public static final int RENDERDOC_OPTION_HOOK_INTO_CHILDREN = 7;
    public static final int RENDERDOC_OPTION_REF_ALL_RESOURCES = 8;
    public static final int RENDERDOC_OPTION_CAPTURE_ALL_CMD_LISTS = 9;
    public static final int RENDERDOC_OPTION_DEBUG_OUTPUT_MUTE = 10;

    // ==================== RENDERDOC CONVENIENCE METHODS ====================

    /**
     * Safe wrapper to start RenderDoc capture with error handling
     * @return true if capture started successfully
     */
    public static boolean startRenderDocCapture() {
        if (!renderDocIsAvailable()) {
            LOGGER.warn("RenderDoc is not available - capture cannot be started");
            return false;
        }

        try {
            LOGGER.info("Starting RenderDoc frame capture...");
            boolean success = renderDocStartFrameCapture();
            if (success) {
                LOGGER.info("✓ RenderDoc frame capture started");
            } else {
                LOGGER.error("✗ Failed to start RenderDoc frame capture");
            }
            return success;
        } catch (Exception e) {
            LOGGER.error("Error starting RenderDoc capture", e);
            return false;
        }
    }

    /**
     * Safe wrapper to end RenderDoc capture with error handling
     * @return true if capture ended successfully
     */
    public static boolean endRenderDocCapture() {
        if (!renderDocIsAvailable()) {
            LOGGER.warn("RenderDoc is not available - cannot end capture");
            return false;
        }

        try {
            LOGGER.info("Ending RenderDoc frame capture...");
            boolean success = renderDocEndFrameCapture();
            if (success) {
                LOGGER.info("✓ RenderDoc frame capture ended successfully");
            } else {
                LOGGER.warn("⚠ No RenderDoc capture was in progress");
            }
            return success;
        } catch (Exception e) {
            LOGGER.error("Error ending RenderDoc capture", e);
            return false;
        }
    }

    /**
     * Safe wrapper to trigger RenderDoc capture for next frame
     * Captures the next frame automatically without manual start/end
     */
    public static void triggerRenderDocCapture() {
        if (!renderDocIsAvailable()) {
            LOGGER.warn("RenderDoc is not available - cannot trigger capture");
            return;
        }

        try {
            LOGGER.info("Triggering RenderDoc capture for next frame...");
            renderDocTriggerCapture();
            LOGGER.info("✓ RenderDoc capture triggered for next frame");
        } catch (Exception e) {
            LOGGER.error("Error triggering RenderDoc capture", e);
        }
    }

    /**
     * Check if RenderDoc is ready for use
     * @return true if RenderDoc is available and ready
     */
    public static boolean isRenderDocReady() {
        return renderDocIsAvailable() && !renderDocIsCapturing();
    }

    /**
     * Enable useful RenderDoc capture options for debugging
     */
    public static void configureRenderDocForDebugging() {
        if (!renderDocIsAvailable()) {
            LOGGER.warn("RenderDoc is not available - cannot configure options");
            return;
        }

        try {
            // Enable capture all command lists (helps with deferred rendering)
            renderDocSetCaptureOption(RENDERDOC_OPTION_CAPTURE_ALL_CMD_LISTS, 1);

            // Verify buffer writes for better debugging
            renderDocSetCaptureOption(RENDERDOC_OPTION_VERIFY_BUFFER_WRITES, 1);

            // Include all resources in captures
            renderDocSetCaptureOption(RENDERDOC_OPTION_REF_ALL_RESOURCES, 1);

            // Enable API validation for better error reporting
            renderDocSetCaptureOption(RENDERDOC_OPTION_API_VALIDATION, 1);

            LOGGER.info("✓ RenderDoc configured for debugging with enhanced options");
        } catch (Exception e) {
            LOGGER.error("Error configuring RenderDoc options", e);
        }
    }

    /**
     * Get RenderDoc status information
     * @return Status string with RenderDoc availability and state
     */
    public static String getRenderDocStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== RenderDoc Status ===\n");
        status.append("Available: ").append(renderDocIsAvailable() ? "Yes" : "No").append("\n");

        if (renderDocIsAvailable()) {
            status.append("Capturing: ").append(renderDocIsCapturing() ? "Yes" : "No").append("\n");
            status.append("Ready: ").append(isRenderDocReady() ? "Yes" : "No").append("\n");
            status.append("\nNote: Launch Minecraft through RenderDoc UI for best results.\n");
            status.append("      Use F12 key in-game to capture frames when running through RenderDoc.");
        } else {
            status.append("\nTo enable RenderDoc:\n");
            status.append("1. Download and install RenderDoc from https://renderdoc.org/\n");
            status.append("2. Launch Minecraft through RenderDoc's 'Launch Application' option\n");
            status.append("3. OR inject renderdoc.dll before Minecraft starts graphics initialization");
        }

        return status.toString();
    }

    // ==================== OPENGL 3.0+ METHODS (GL30) ====================

    /**
     * Create framebuffer object
     * @param framebufferId - Framebuffer ID
     * @return Framebuffer handle
     */
    public static native long createFramebuffer(int framebufferId);

    /**
     * Bind framebuffer
     * @param framebufferHandle - Framebuffer handle
     * @param target - Framebuffer target (GL_FRAMEBUFFER, etc.)
     */
    public static native void bindFramebuffer(long framebufferHandle, int target);

    /**
     * Attach texture to framebuffer
     * @param framebufferHandle - Framebuffer handle
     * @param target - Framebuffer target
     * @param attachment - Attachment point
     * @param textarget - Texture target
     * @param textureHandle - Texture handle
     * @param level - Mipmap level
     */
    public static native void framebufferTexture2D(long framebufferHandle, int target, int attachment, int textarget, long textureHandle, int level);

    /**
     * Attach renderbuffer to framebuffer
     * @param framebufferHandle - Framebuffer handle
     * @param target - Framebuffer target
     * @param attachment - Attachment point
     * @param renderbuffertarget - Renderbuffer target
     * @param renderbufferHandle - Renderbuffer handle
     */
    public static native void framebufferRenderbuffer(long framebufferHandle, int target, int attachment, int renderbuffertarget, long renderbufferHandle);

    /**
     * Check framebuffer completeness
     * @param framebufferHandle - Framebuffer handle
     * @param target - Framebuffer target
     * @return Framebuffer status
     */
    public static native int checkFramebufferStatus(long framebufferHandle, int target);

    /**
     * Destroy framebuffer
     * @param framebufferHandle - Framebuffer handle
     */
    public static native void destroyFramebuffer(long framebufferHandle);

    /**
     * Create renderbuffer
     * @param renderbufferId - Renderbuffer ID
     * @return Renderbuffer handle
     */
    public static native long createRenderbuffer(int renderbufferId);

    /**
     * Bind renderbuffer
     * @param renderbufferHandle - Renderbuffer handle
     * @param target - Renderbuffer target
     */
    public static native void bindRenderbuffer(long renderbufferHandle, int target);

    /**
     * Set renderbuffer storage
     * @param renderbufferHandle - Renderbuffer handle
     * @param target - Renderbuffer target
     * @param internalformat - Internal format
     * @param width - Width
     * @param height - Height
     */
    public static native void renderbufferStorage(long renderbufferHandle, int target, int internalformat, int width, int height);

    /**
     * Destroy renderbuffer
     * @param renderbufferHandle - Renderbuffer handle
     */
    public static native void destroyRenderbuffer(long renderbufferHandle);

    /**
     * Create vertex array object
     * @param vertexArrayId - Vertex array ID
     * @return Vertex array handle
     */
    public static native long createVertexArray(int vertexArrayId);

    /**
     * Bind vertex array
     * @param vertexArrayHandle - Vertex array handle
     */
    public static native void bindVertexArray(long vertexArrayHandle);

    /**
     * Destroy vertex array
     * @param vertexArrayHandle - Vertex array handle
     */
    public static native void destroyVertexArray(long vertexArrayHandle);

    /**
     * Set blend equation
     * @param modeRGB - RGB blend mode
     * @param modeAlpha - Alpha blend mode
     */
    public static native void setBlendEquation(int modeRGB, int modeAlpha);

    /**
     * Set draw buffers
     * @param buffers - Array of draw buffer indices
     */
    public static native void setDrawBuffers(int[] buffers);

    /**
     * Set separate stencil operations
     * @param face - Face (front/back)
     * @param sfail - Stencil fail operation
     * @param dpfail - Depth fail operation
     * @param dppass - Depth pass operation
     */
    public static native void setStencilOpSeparate(int face, int sfail, int dpfail, int dppass);

    /**
     * Set separate stencil function
     * @param face - Face (front/back)
     * @param func - Stencil function
     * @param ref - Reference value
     * @param mask - Mask
     */
    public static native void setStencilFuncSeparate(int face, int func, int ref, int mask);

    /**
     * Set separate stencil mask
     * @param face - Face (front/back)
     * @param mask - Stencil mask
     */
    public static native void setStencilMaskSeparate(int face, int mask);

  }
