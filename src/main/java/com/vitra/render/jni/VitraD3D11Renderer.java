package com.vitra.render.jni;

import com.vitra.debug.VitraDebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI interface for native D3D11 rendering operations with advanced debugging.
 * This replaces the BGFX implementation with direct D3D11 calls.
 *
 * Enhanced with:
 * - D3D11 Debug Layer and InfoQueue integration
 * - Minidump generation for crash analysis
 * - RenderDoc capture support
 * - Safe JNI exception handling with logging
 */
public class VitraD3D11Renderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraD3D11Renderer.class);

    // Debug state
    private static boolean debugEnabled = false;
    private static boolean safeModeEnabled = false;

    // Load the native library
    static {
        try {
            // Verify Windows platform
            String osName = System.getProperty("os.name").toLowerCase();
            if (!osName.contains("win")) {
                throw new RuntimeException("Vitra D3D11 mod requires Windows operating system. Current OS: " + osName);
            }

            // Try loading from the jar resources first
            System.loadLibrary("vitra-d3d11");
            LOGGER.info("✓ Loaded native D3D11 library");
        } catch (UnsatisfiedLinkError e) {
            try {
                // Fallback to loading from the native directory
                String nativePath = VitraD3D11Renderer.class.getResource("/native/windows/vitra-d3d11.dll").getPath();
                if (nativePath != null) {
                    System.load(nativePath);
                    LOGGER.info("✓ Loaded native D3D11 library from resources");
                } else {
                    throw new RuntimeException("D3D11 native library (vitra-d3d11.dll) not found");
                }
            } catch (Exception ex) {
                throw new RuntimeException("Failed to load native D3D11 library. Ensure you are running on Windows with D3D11 support.", ex);
            }
        }
    }

    /**
     * Initialize native D3D11 renderer with advanced debugging support
     * @param windowHandle - Native window handle (HWND for Windows)
     * @param width - Window width
     * @param height - Window height
     * @param enableDebug - Enable debug layer and advanced debugging
     * @param useWarp - Use WARP (Windows Advanced Rasterization Platform) CPU renderer
     * @return true if successful
     */
    public static native boolean initializeDirectX(long windowHandle, int width, int height, boolean enableDebug, boolean useWarp);

    /**
     * Initialize debugging system (called before initializeDirectX)
     */
    public static synchronized void initializeDebug(boolean enabled, boolean verbose) {
        debugEnabled = enabled;
        VitraDebugUtils.initializeDebug(enabled, verbose);

        if (enabled) {
            LOGGER.info("D3D11 debugging enabled with verbose={}", verbose);
        }
    }

    /**
     * Safe wrapper around initializeDirectX with exception handling
     */
    public static boolean initializeDirectXSafe(long windowHandle, int width, int height, boolean enableDebug, boolean useWarp) {
        try {
            // Initialize debug system first if needed
            if (enableDebug && !VitraDebugUtils.isDebugInitialized()) {
                initializeDebug(true, false);
            }

            LOGGER.info("Initializing D3D11 renderer: window=0x{}, size={}x{}, debug={}, warp={}",
                Long.toHexString(windowHandle), width, height, enableDebug, useWarp);

            boolean success = initializeDirectX(windowHandle, width, height, enableDebug, useWarp);

            if (success) {
                LOGGER.info("✓ D3D11 renderer initialized successfully");

                // Enable safe mode if debug is enabled
                if (enableDebug) {
                    safeModeEnabled = true;
                    LOGGER.info("Safe mode enabled - all operations will be wrapped with error handling");
                }
            } else {
                LOGGER.error("✗ D3D11 renderer initialization failed");
            }

            return success;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("D3D11 native library not available: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected error during D3D11 initialization", e);
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
            LOGGER.info("Shutting down D3D11 renderer...");

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
            LOGGER.info("✓ D3D11 renderer shutdown complete");

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("D3D11 native library not available during shutdown: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error during D3D11 shutdown", e);
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
     * Reset dynamic rendering state (VulkanMod Renderer.resetDynamicState() pattern)
     *
     * Resets per-frame dynamic state that persists across draw calls:
     * - Depth bias (OMSetDepthBias)
     * - Stencil ref (OMSetStencilRef)
     * - Blend factor (OMSetBlendState)
     *
     * VulkanMod calls this in beginRenderPass() to ensure clean state each frame.
     * Reference: VulkanMod Renderer.java:600-604
     */
    public static native void resetDynamicState();

    /**
     * Clear the render target (DEPRECATED - use clear(int mask) instead)
     * This old method is kept for compatibility but should not be used
     */
    @Deprecated
    public static void clearOld(float r, float g, float b, float a) {
        // Deprecated - redirect to new implementation
        setClearColor(r, g, b, a);
        clear(0x4100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
    }

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
     * Create shader pipeline from HLSL source (VulkanMod pattern).
     * Compiles HLSL shaders and creates pipeline in one step.
     *
     * @param vsSource Vertex shader HLSL source code
     * @param psSource Pixel shader HLSL source code
     * @param debugName Name for debugging
     * @return Pipeline handle, or 0 on failure
     */
    public static native long createShaderPipelineFromSource(String vsSource, String psSource, String debugName);

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
     * Draw with explicit vertex format specification.
     * This allows the native code to create the correct input layout based on actual vertex data.
     *
     * @param vertexBuffer - Vertex buffer handle
     * @param indexBuffer - Index buffer handle (0 for non-indexed draw)
     * @param baseVertex - Base vertex offset
     * @param firstIndex - First index offset
     * @param vertexOrIndexCount - Vertex count (non-indexed) or index count (indexed)
     * @param instanceCount - Number of instances (1 for non-instanced)
     * @param vertexFormatDesc - Encoded vertex format: [elementCount, usage1, type1, count1, offset1, ...]
     */
    public static native void drawWithVertexFormat(long vertexBuffer, long indexBuffer, int baseVertex, int firstIndex, int vertexOrIndexCount, int instanceCount, int[] vertexFormatDesc);

    /**
     * Set input layout from vertex format for the given vertex shader.
     * This should be called when binding a pipeline to ensure the input layout matches the vertex format.
     * CRITICAL FIX: This prevents "TEXCOORD linkage error" when draw() is called without vertex format.
     * @param vertexShaderHandle - Handle to the vertex shader
     * @param vertexFormatDesc - Vertex format descriptor array [elementCount, usage1, type1, count1, offset1, ...]
     */
    public static native void setInputLayoutFromVertexFormat(long vertexShaderHandle, int[] vertexFormatDesc);

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
     * Draw mesh data directly from byte buffers (for 1.21.1 compatibility)
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
    public static native long createTextureFromData(byte[] data, int width, int height, int format);

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
    public static native boolean updateTextureMipLevel(long textureHandle, byte[] data, int width, int height, int mipLevel);

    /**
     * Bind a D3D11 texture to a shader resource slot
     * CRITICAL: This fixes the yellow screen issue by properly binding textures to shaders
     *
     * @param slot - Texture slot (0-15, corresponding to t0-t15 in HLSL)
     * @param textureHandle - D3D11 texture handle (0 to unbind)
     */
    public static native void bindTexture(int slot, long textureHandle);

    /**
     * Alias for bindTexture() - used by D3D11TextureSelector
     * Binds a D3D11 texture to a specific shader resource view slot (t0-t15 in HLSL)
     *
     * @param slot - Texture slot (0-15)
     * @param textureHandle - D3D11 texture handle from D3D11TextureSelector
     */
    public static void bindTextureToSlot(int slot, long textureHandle) {
        bindTexture(slot, textureHandle);
    }

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

    // REMOVED: Stub version createGLProgramShader(int type)
    // Use the bytecode version: createGLProgramShader(byte[], int, int) instead

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

    // ==================== HLSL SHADER COMPILATION METHODS (D3D11) ====================

    /**
     * Compile HLSL shader source to bytecode using D3DCompile.
     *
     * @param source - HLSL shader source code as UTF-8 bytes
     * @param sourceLength - Length of source code
     * @param target - Shader target profile ("vs_5_0", "ps_5_0", etc.)
     * @param debugName - Debug name for the shader (for error messages)
     * @return Shader handle, or 0 on failure
     */
    public static native long compileShaderBYTEARRAY(byte[] source, int sourceLength, String target, String debugName);

    /**
     * Compile HLSL shader from file.
     *
     * @param filePath - Path to HLSL shader file
     * @param target - Shader target profile ("vs_5_0", "ps_5_0", etc.)
     * @param debugName - Debug name for the shader
     * @return Shader handle, or 0 on failure
     */
    public static native long compileShaderFromFile(String filePath, String target, String debugName);

    /**
     * Get bytecode from compiled shader blob handle.
     * Returns the bytecode bytes for a shader blob that was compiled with compileShader().
     *
     * @param blobHandle - Blob handle returned by compileShader
     * @return Shader bytecode bytes, or null if blob not found
     */
    public static native byte[] getBlobBytecode(long blobHandle);

    /**
     * Create shader from precompiled bytecode.
     *
     * @param bytecode - Compiled shader bytecode
     * @param bytecodeLength - Length of bytecode
     * @param shaderType - Shader type ("vertex" or "pixel")
     * @return Shader handle, or 0 on failure
     */
    public static native long createShaderFromBytecode(byte[] bytecode, int bytecodeLength, String shaderType);

    /**
     * Bind a shader pipeline for rendering.
     *
     * @param pipelineHandle - Pipeline handle
     */
    public static native void bindShaderPipeline(long pipelineHandle);

    /**
     * Destroy a shader pipeline.
     *
     * @param pipelineHandle - Pipeline handle
     */
    public static native void destroyShaderPipeline(long pipelineHandle);

    /**
     * Create a constant buffer for uniforms.
     *
     * @param size - Buffer size in bytes (must be 16-byte aligned)
     * @return Constant buffer handle, or 0 on failure
     */
    public static native long createConstantBuffer(int size);

    /**
     * Update constant buffer data.
     *
     * @param bufferHandle - Constant buffer handle
     * @param data - Buffer data (16-byte aligned)
     */
    public static native void updateConstantBuffer(long bufferHandle, byte[] data);

    /**
     * Bind constant buffer to vertex shader stage.
     *
     * @param slot - Buffer slot (b0-b3)
     * @param bufferHandle - Constant buffer handle
     */
    public static native void bindConstantBufferVS(int slot, long bufferHandle);

    /**
     * Bind constant buffer to pixel shader stage.
     *
     * @param slot - Buffer slot (b0-b3)
     * @param bufferHandle - Constant buffer handle
     */
    public static native void bindConstantBufferPS(int slot, long bufferHandle);

    /**
     * Get the last shader compilation error message.
     *
     * @return Error message string, or null if no error
     */
    public static native String getLastShaderError();

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
     * ==================== DEPTH STENCIL BUFFER METHODS (CRITICAL FOR 3D RENDERING) ====================
     *
     * These methods are ESSENTIAL for fixing the black screen issue.
     * Based on D3D11 documentation: https://learn.microsoft.com/en-us/windows/win32/direct3d11
     *
     * Missing depth stencil buffer is the PRIMARY cause of black screen in 3D applications.
     * Without proper depth testing, 3D geometry cannot be rendered correctly.
     */

    /**
     * Create depth stencil buffer (CRITICAL - fixes black screen)
     * Creates ID3D11Texture2D with D3D11_BIND_DEPTH_STENCIL and corresponding depth stencil view
     *
     * @param width - Buffer width in pixels (should match backbuffer width)
     * @param height - Buffer height in pixels (should match backbuffer height)
     * @param format - Depth format (0=D24_UNORM_S8_UINT, 1=D32_FLOAT, 2=D16_UNORM)
     * @return Depth stencil buffer handle, or 0 on failure
     */
    public static native long createDepthStencilBuffer(int width, int height, int format);

    /**
     * Bind depth stencil buffer to output merger stage (CRITICAL - fixes black screen)
     * Binds depth stencil view to OMSetRenderTargets for depth testing
     *
     * @param depthStencilHandle - Depth stencil buffer handle from createDepthStencilBuffer()
     * @return true if successful, false on failure
     */
    public static native boolean bindDepthStencilBuffer(long depthStencilHandle);

    /**
     * Clear depth stencil buffer (CRITICAL - fixes black screen)
     * Calls ID3D11DeviceContext::ClearDepthStencilView with D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL
     *
     * @param depthStencilHandle - Depth stencil buffer handle
     * @param clearDepth - Whether to clear depth buffer
     * @param clearStencil - Whether to clear stencil buffer
     * @param depthValue - Depth clear value (0.0 to 1.0, typically 1.0)
     * @param stencilValue - Stencil clear value (0-255, typically 0)
     * @return true if successful, false on failure
     */
    public static native boolean clearDepthStencilBuffer(long depthStencilHandle, boolean clearDepth, boolean clearStencil, float depthValue, int stencilValue);

    /**
     * Create depth stencil state (CRITICAL - fixes black screen)
     * Creates ID3D11DepthStencilState with proper depth testing configuration
     *
     * @param depthEnable - Enable depth testing (should be true for 3D)
     * @param depthWriteEnable - Enable depth writes (should be true for 3D)
     * @param depthFunc - Depth comparison function (0=NEVER, 1=LESS, 2=EQUAL, 3=LEQUAL, 4=GREATER, 5=NOTEQUAL, 6=GEQUAL, 7=ALWAYS)
     * @param stencilEnable - Enable stencil testing (can be false for basic 3D)
     * @param stencilReadMask - Stencil read mask (0-255, typically 0xFF)
     * @param stencilWriteMask - Stencil write mask (0-255, typically 0xFF)
     * @return Depth stencil state handle, or 0 on failure
     */
    public static native long createDepthStencilState(boolean depthEnable, boolean depthWriteEnable, int depthFunc,
                                                     boolean stencilEnable, int stencilReadMask, int stencilWriteMask);

    /**
     * Bind depth stencil state (CRITICAL - fixes black screen)
     * Binds depth stencil state to OMSetDepthStencilState for depth testing
     *
     * @param depthStencilStateHandle - Depth stencil state handle from createDepthStencilState()
     * @param stencilRef - Stencil reference value (typically 1)
     * @return true if successful, false on failure
     */
    public static native boolean bindDepthStencilState(long depthStencilStateHandle, int stencilRef);

    /**
     * Set depth stencil state parameters (convenience method)
     * Updates existing depth stencil state with new parameters
     *
     * @param depthStencilStateHandle - Depth stencil state handle
     * @param depthEnable - Enable depth testing
     * @param depthWriteEnable - Enable depth writes
     * @param depthFunc - Depth comparison function
     * @return true if successful, false on failure
     */
    public static native boolean setDepthStencilStateParams(long depthStencilStateHandle, boolean depthEnable, boolean depthWriteEnable, int depthFunc);

    /**
     * Release depth stencil buffer
     * Properly releases ID3D11Texture2D and ID3D11DepthStencilView
     *
     * @param depthStencilHandle - Depth stencil buffer handle
     */
    public static native void releaseDepthStencilBuffer(long depthStencilHandle);

    /**
     * Release depth stencil state
     * Properly releases ID3D11DepthStencilState
     *
     * @param depthStencilStateHandle - Depth stencil state handle
     */
    public static native void releaseDepthStencilState(long depthStencilStateHandle);

    /**
     * Check if depth stencil buffer is valid
     *
     * @param depthStencilHandle - Depth stencil buffer handle
     * @return true if valid, false if invalid or null
     */
    public static native boolean isDepthStencilBufferValid(long depthStencilHandle);

    /**
     * Resize depth stencil buffer
     * Recreates depth stencil buffer with new dimensions (needed for window resize)
     *
     * @param depthStencilHandle - Current depth stencil buffer handle
     * @param newWidth - New width in pixels
     * @param newHeight - New height in pixels
     * @param format - Depth format (same as createDepthStencilBuffer)
     * @return New depth stencil buffer handle, or 0 on failure
     */
    public static native long resizeDepthStencilBuffer(long depthStencilHandle, int newWidth, int newHeight, int format);

    // Depth stencil format constants
    public static final int DEPTH_FORMAT_D24_UNORM_S8_UINT = 0;  // 24-bit depth, 8-bit stencil
    public static final int DEPTH_FORMAT_D32_FLOAT = 1;          // 32-bit float depth
    public static final int DEPTH_FORMAT_D16_UNORM = 2;          // 16-bit normalized depth

    // Depth comparison function constants (OpenGL-style for compatibility)
    public static final int DEPTH_FUNC_NEVER = 0;     // GL_NEVER
    public static final int DEPTH_FUNC_LESS = 1;      // GL_LESS (most common)
    public static final int DEPTH_FUNC_EQUAL = 2;     // GL_EQUAL
    public static final int DEPTH_FUNC_LEQUAL = 3;    // GL_LEQUAL (recommended)
    public static final int DEPTH_FUNC_GREATER = 4;   // GL_GREATER
    public static final int DEPTH_FUNC_NOTEQUAL = 5;  // GL_NOTEQUAL
    public static final int DEPTH_FUNC_GEQUAL = 6;    // GL_GEQUAL
    public static final int DEPTH_FUNC_ALWAYS = 7;    // GL_ALWAYS

    // Depth stencil clear flags
    public static final int CLEAR_DEPTH = 0x00000001;   // D3D11_CLEAR_DEPTH
    public static final int CLEAR_STENCIL = 0x00000002; // D3D11_CLEAR_STENCIL

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

    // D3D11 DXGI format constants
    public static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
    public static final int DXGI_FORMAT_R8_UNORM = 61;
    public static final int DXGI_FORMAT_R8G8_UNORM = 49;
    public static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;

    // Additional DXGI format constants for VertexFormatMixin
    public static final int DXGI_FORMAT_R32G32B32_FLOAT = 6;
    public static final int DXGI_FORMAT_R32G32B32A32_FLOAT = 2;
    public static final int DXGI_FORMAT_R32G32_FLOAT = 16;
    public static final int DXGI_FORMAT_R16G16B16A16_UNORM = 35;
    public static final int DXGI_FORMAT_R16G16B16A16_SNORM = 36;
    public static final int DXGI_FORMAT_R32G32B32A32_UINT = 3;
    public static final int DXGI_FORMAT_R32G32B32A32_SINT = 4;
    public static final int DXGI_FORMAT_R8G8B8A8_SNORM = 30;

    // Constant buffer slot constants
    public static final int CONSTANT_BUFFER_SLOT_MATRICES = 0;
    public static final int CONSTANT_BUFFER_SLOT_LIGHTING = 1;
    public static final int CONSTANT_BUFFER_SLOT_VECTORS = 2;
    public static final int CONSTANT_BUFFER_SLOT_COLORS = 3;

    // Additional missing methods
    /**
     * Set shader light direction
     * @param lightIndex - Light index (0 or 1)
     * @param x - X component
     * @param y - Y component
     * @param z - Z component
     */
    public static native void setShaderLightDirection(int lightIndex, float x, float y, float z);

    /**
     * Set texture matrix
     * @param matrixData - 4x4 matrix data (16 floats, column-major)
     */
    public static native void setTextureMatrix(float[] matrixData);

    /**
     * Update constant buffer with float data
     * @param slot - Buffer slot
     * @param data - FloatBuffer data
     */
    public static native void updateConstantBuffer(int slot, java.nio.FloatBuffer data);

    // ==================== SAFE WRAPPER METHODS ====================

    // REMOVED: beginFrameSafe() and endFrameSafe() - use beginFrame()/endFrame() directly instead
    // Safe wrappers were redundant since the native methods are already simple

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
        stats.append("=== D3D11 Debug Stats ===\n");
        stats.append("Debug Enabled: ").append(debugEnabled).append("\n");
        stats.append("Safe Mode: ").append(safeModeEnabled).append("\n");
        stats.append("Renderer Initialized: ").append(isInitialized()).append("\n\n");

        // Get native debug layer statistics
        try {
            String nativeStats = nativeGetDebugStats();
            if (nativeStats != null && !nativeStats.isEmpty()) {
                stats.append(nativeStats).append("\n");
            }
        } catch (UnsatisfiedLinkError e) {
            stats.append("Native debug stats unavailable\n");
        }

        // Get Java-side debug utils stats
        stats.append(VitraDebugUtils.getDebugStats());

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
     * Native method to get comprehensive debug statistics from DirectX debug layer
     * Returns formatted string with error counts, warning counts, etc.
     */
    public static native String nativeGetDebugStats();

    /**
     * Native method to process debug messages from ID3D11InfoQueue
     * This directly writes messages to native log file (dx11_native_*.log)
     * Should be called every frame to capture DirectX debug layer output
     */
    public static native void nativeProcessDebugMessages();

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
     * Get D3D11 device information
     * Returns information about the GPU adapter, driver version, and feature level
     */
    public static String getDeviceInfo() {
        try {
            return nativeGetDeviceInfo();
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Native library not available for getDeviceInfo: {}", e.getMessage());
            return "D3D11 device info unavailable (native library error)";
        } catch (Exception e) {
            LOGGER.error("Error getting device info", e);
            return "D3D11 device info unavailable (error: " + e.getMessage() + ")";
        }
    }

    /**
     * Get swap chain information for debugging
     * Returns detailed information about the D3D11 swap chain state
     */
    public static native String getSwapChainInfo();

    /**
     * Verify swap chain is properly bound to window
     * Checks if the swap chain is correctly connected to the window handle
     */
    public static native boolean verifySwapChainBinding();

    /**
     * Validate render target and depth stencil binding
     * Ensures render target view and depth stencil view are properly set
     */
    public static native boolean validateRenderTargets();

    /**
     * Get current viewport information
     * Returns the current D3D11 viewport dimensions
     */
    public static native String getViewportInfo();

    /**
     * Force swap chain recreation (for debugging recovery)
     * Attempts to recreate the swap chain if it's in an invalid state
     */
    public static native boolean recreateSwapChain();

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
     * This synchronizes Minecraft's Matrix4f projection matrix (JOML column-major) to D3D11 (row-major)
     *
     * @param matrixData - Float array containing 16 elements of the 4x4 matrix in column-major order
     */
    public static native void setProjectionMatrix(float[] matrixData);

    /**
     * Set model-view matrix from Minecraft's RenderSystem
     * This synchronizes Minecraft's Matrix4f model-view matrix (JOML column-major) to D3D11 (row-major)
     *
     * @param matrixData - Float array containing 16 elements of the 4x4 matrix in column-major order
     */
    public static native void setModelViewMatrix(float[] matrixData);

    /**
     * Apply model-view matrix from Minecraft's RenderSystem (for RenderSystemMixin)
     * This applies the current model-view matrix stack state to D3D11
     */
    public static native void applyModelViewMatrix();

    /**
     * Set ALL transformation matrices from Minecraft's RenderSystem
     * This is the CORRECT way to sync matrices - we need MVP, ModelView, and Projection!
     *
     * @param mvpData - Model-View-Projection matrix (16 floats, column-major)
     * @param modelViewData - Model-View matrix (16 floats, column-major)
     * @param projectionData - Projection matrix (16 floats, column-major)
     */
    public static native void setTransformMatrices(float[] mvpData, float[] modelViewData, float[] projectionData);

    /**
     * Set shader color for vertex coloring (CRITICAL for correct block/entity colors)
     *
     * @param r - Red component (0.0-1.0)
     * @param g - Green component (0.0-1.0)
     * @param b - Blue component (0.0-1.0)
     * @param a - Alpha component (0.0-1.0)
     */
    public static native void setShaderColor(float r, float g, float b, float a);

    /**
     * Set fog color for atmospheric effects (CRITICAL for proper fog rendering)
     *
     * @param r - Red component (0.0-1.0)
     * @param g - Green component (0.0-1.0)
     * @param b - Blue component (0.0-1.0)
     * @param a - Alpha component (0.0-1.0)
     */
    public static native void setShaderFogColor(float r, float g, float b, float a);

    /**
     * Create main render target for Minecraft 1.21.1 (CRITICAL for MainTargetMixin)
     *
     * @param width - Render target width
     * @param height - Render target height
     * @param useDepth - Whether to include depth attachment
     * @return Render target handle, or 0 on failure
     */
    public static native long createMainRenderTarget(int width, int height, boolean useDepth);

    /**
     * Resize render target (CRITICAL for window resize)
     *
     * @param renderTargetHandle - Render target handle from createMainRenderTarget()
     * @param width - New width
     * @param height - New height
     * @param format - Depth format (from DEPTH_FORMAT_* constants)
     * @return New render target handle, or 0 on failure
     */
    public static native long resizeRenderTarget(long renderTargetHandle, int width, int height, int format);

    /**
     * Bind render target for writing (CRITICAL for MainTargetMixin)
     *
     * @param renderTargetHandle - Render target handle from createMainRenderTarget()
     * @param updateScissor - Whether to update scissor rectangle
     */
    public static native void bindRenderTargetForWriting(long renderTargetHandle, boolean updateScissor);

    /**
     * Bind render target as texture for reading (CRITICAL for MainTargetMixin)
     *
     * @param renderTargetHandle - Render target handle from createMainRenderTarget()
     */
    public static native void bindRenderTargetAsTexture(long renderTargetHandle);

    /**
     * Get color texture from render target (CRITICAL for MainTargetMixin)
     *
     * @param renderTargetHandle - Render target handle from createMainRenderTarget()
     *return Color texture handle, or 0 if not available
     */
    public static native long getRenderTargetColorTexture(long renderTargetHandle);

    /**
     * Release render target (CRITICAL for MainTargetMixin)
     *
     * @param renderTargetHandle - Render target handle from createMainRenderTarget()
     */
    public static native void releaseRenderTarget(long renderTargetHandle);

    /**
     * Present texture using D3D11 (CRITICAL for CommandEncoderMixin)
     *
     * @param textureId - Texture ID from MainTargetMixin.getColorTexture()
     * @return true if successful
     */
    public static native boolean presentTexture(int textureId);

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

    // REMOVED: Conflicting overload causes Java to call wrong method
    // Use bindFramebuffer(int target, int framebuffer) instead (line 3213)

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

    // ==================== 1.21.1 MIXIN SUPPORT METHODS ====================

    // VertexBufferMixin native methods
    /**
     * Create vertex buffer for VertexBufferMixin
     * @param name - Buffer name for debugging
     * @return Buffer ID or -1 on failure
     */
    public static native int createVertexBuffer(String name);

    /**
     * Create index buffer for VertexBufferMixin
     * @return Buffer ID or -1 on failure
     */
    public static native int createIndexBuffer();

    /**
     * Upload vertex data to buffer
     * @param bufferId - Buffer ID
     * @param data - Vertex data
     * @return true if successful
     */
    public static native boolean uploadVertexData(int bufferId, java.nio.ByteBuffer data);

    /**
     * Upload index data to buffer
     * @param bufferId - Buffer ID
     * @param data - Index data
     * @return true if successful
     */
    public static native boolean uploadIndexData(int bufferId, java.nio.ByteBuffer data);

    /**
     * Release vertex buffer
     * @param bufferId - Buffer ID
     */
    public static native void releaseVertexBuffer(int bufferId);

    /**
     * Release index buffer
     * @param bufferId - Buffer ID
     */
    public static native void releaseIndexBuffer(int bufferId);

    /**
     * Draw indexed geometry
     * @param vertexBufferId - Vertex buffer ID
     * @param indexBufferId - Index buffer ID
     * @param mode - Draw mode
     * @return true if successful
     */
    public static native boolean drawIndexed(int vertexBufferId, int indexBufferId, int mode);

    /**
     * Draw non-indexed geometry
     * @param vertexBufferId - Vertex buffer ID
     * @param mode - Draw mode
     * @return true if successful
     */
    public static native boolean drawNonIndexed(int vertexBufferId, int mode);

    // AbstractTextureMixin native methods - Following VulkanMod's approach (no GPU abstraction)
    /**
     * Create texture using traditional OpenGL texture ID approach
     * @param textureId - OpenGL texture ID
     * @param width - Texture width
     * @param height - Texture height
     * @param format - Texture format (GL_RGBA, GL_RGB, etc.)
     * @return true if successful
     */
    public static native boolean createTextureFromId(int textureId, int width, int height, int format);

    /**
     * Upload texture data (following VulkanMod's VkGlTexture approach)
     * @param textureId - OpenGL texture ID
     * @param data - Pixel data
     * @param width - Texture width
     * @param height - Texture height
     * @param format - Pixel format (GL_RGBA, GL_RGB, etc.)
     * @return true if successful
     */
    public static native boolean uploadTextureData(int textureId, byte[] data, int width, int height, int format);

    /**
     * Bind texture for rendering (OpenGL compatibility)
     * @param textureId - OpenGL texture ID
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     */
    public static native void bindTextureId(int textureId, int target);

    /**
     * Set texture parameter (OpenGL compatibility)
     * @param textureId - OpenGL texture ID
     * @param pname - Parameter name (GL_TEXTURE_MIN_FILTER, etc.)
     * @param param - Parameter value
     */
    public static native void setTextureParameterId(int textureId, int pname, int param);

    /**
     * Delete texture (OpenGL compatibility)
     * @param textureId - OpenGL texture ID
     */
    public static native void deleteTexture(int textureId);

    // ShaderInstanceMixin native methods
    /**
     * Create shader pipeline for ShaderInstanceMixin
     * @param name - Shader name
     * @return Pipeline ID or -1 on failure
     */
    public static native int createShaderPipeline(String name);

    /**
     * Initialize shader uniforms
     * @param pipelineId - Pipeline ID
     * @param uniformNames - Array of uniform names
     * @param uniformTypes - Array of uniform types
     * @return true if successful
     */
    public static native boolean initShaderUniforms(int pipelineId, String[] uniformNames, int[] uniformTypes);

    /**
     * Bind shader pipeline for use (DEPRECATED - use long version instead)
     * @param pipelineId - Pipeline ID
     */
    @Deprecated
    public static native void bindShaderPipelineOld(int pipelineId);

    /**
     * Upload shader uniform data
     * @param pipelineId - Pipeline ID
     * @param uniformName - Uniform name
     * @param data - Uniform data
     * @param dataType - Data type (0=float, 1=vec2, 2=vec3, 3=vec4, 4=mat4, 5=int)
     * @return true if successful
     */
    public static native boolean uploadShaderUniforms(int pipelineId, String uniformName, Object data, int dataType);

    /**
     * Clear all shader uniforms
     * @param pipelineId - Pipeline ID
     */
    public static native void clearShaderUniforms(int pipelineId);

    /**
     * Set individual shader uniform
     * @param pipelineId - Pipeline ID
     * @param uniformName - Uniform name
     * @param value - Uniform value
     * @param dataType - Data type
     * @return true if successful
     */
    public static native boolean setShaderUniform(int pipelineId, String uniformName, Object value, int dataType);

    /**
     * Release shader pipeline
     * @param pipelineId - Pipeline ID
     */
    public static native void releaseShaderPipeline(int pipelineId);

    /**
     * Use default shader as fallback
     * @param shaderName - Original shader name (for logging)
     * @return true if successful
     */
    public static native boolean useDefaultShader(String shaderName);

    // GlCommandEncoderMixin native methods
    // Note: presentTexture method is already defined above in MainTargetMixin section

    /**
     * Execute batched draw calls
     * @param vertexFormat - Vertex format name
     * @param drawCalls - Collection of draw calls
     * @param indexType - Index type
     * @param uniformData - Uniform data
     * @return true if successful
     */
    public static native boolean executeBatchedDraws(String vertexFormat, java.util.Collection<?> drawCalls, int indexType, Object uniformData);

    /**
     * Execute indexed draw call
     * @param vertexData - Vertex data array
     * @param baseVertex - Base vertex
     * @param indexData - Index data array
     * @param firstIndex - First index
     * @param count - Number of indices
     * @param vertexFormat - Vertex format name
     * @return true if successful
     */
    public static native boolean executeIndexedDraw(int[] vertexData, int baseVertex, int[] indexData, int firstIndex, int count, String vertexFormat);

    /**
     * Execute non-indexed draw call
     * @param vertexData - Vertex data array
     * @param baseVertex - Base vertex
     * @param count - Number of vertices
     * @param vertexFormat - Vertex format name
     * @return true if successful
     */
    public static native boolean executeNonIndexedDraw(int[] vertexData, int baseVertex, int count, String vertexFormat);

    /**
     * Get texture ID from bound texture (following VulkanMod's approach)
     * @param target - Texture target (GL_TEXTURE_2D, etc.)
     * @return Currently bound texture ID or 0 if none
     */
    public static native int getBoundTextureId(int target);

    // BufferUploaderMixin native methods
    /**
     * Draw with default shader (indexed)
     * @param vertexData - Vertex data array
     * @param vertexCount - Number of vertices
     * @param indexData - Index data array
     * @param indexCount - Number of indices
     * @param formatName - Vertex format name
     * @return true if successful
     */
    public static native boolean drawWithDefaultShaderIndexed(int[] vertexData, int vertexCount, int[] indexData, int indexCount, String formatName);

    /**
     * Draw with default shader (non-indexed)
     * @param vertexData - Vertex data array
     * @param vertexCount - Number of vertices
     * @param formatName - Vertex format name
     * @return true if successful
     */
    public static native boolean drawWithDefaultShaderNonIndexed(int[] vertexData, int vertexCount, String formatName);

    /**
     * Draw immediate indexed geometry
     * @param vertexData - Vertex data array
     * @param vertexCount - Number of vertices
     * @param indexData - Index data array
     * @param indexCount - Number of indices
     * @param formatName - Vertex format name
     * @return true if successful
     */
    public static native boolean drawImmediateIndexed(int[] vertexData, int vertexCount, int[] indexData, int indexCount, String formatName);

    /**
     * Draw immediate non-indexed geometry
     * @param vertexData - Vertex data array
     * @param vertexCount - Number of vertices
     * @param formatName - Vertex format name
     * @return true if successful
     */
    public static native boolean drawImmediateNonIndexed(int[] vertexData, int vertexCount, String formatName);

    /**
     * Enable/disable batching for performance
     * @param enabled - Batching enabled
     */
    public static native void setBatchingEnabled(boolean enabled);

    /**
     * Check if batching is enabled
     * @return true if batching is enabled
     */
    public static native boolean isBatchingEnabled();

    // GpuDeviceMixin native methods
    /**
     * Get D3D11 adapter name
     * @return Adapter name or null on failure
     */
    public static native String getAdapterName();

    /**
     * Check if D3D11 device is available
     * @return true if device is available
     */
    public static native boolean isDeviceAvailable();

    /**
     * Clear D3D11 pipeline cache
     */
    public static native void clearPipelineCache();

    /**
     * Create render pipeline
     * @param name - Pipeline name
     * @return Pipeline ID or -1 on failure
     */
    public static native int createPipeline(String name);

    /**
     * Release render pipeline
     * @param pipelineId - Pipeline ID
     */
    public static native void releasePipeline(int pipelineId);

    // Additional utility methods for 1.21.1 compatibility (following VulkanMod approach)
    /**
     * Get texture information from texture ID
     * @param textureId - Texture ID
     * @return Texture information string or null if not found
     */
    public static native String getTextureInfo(int textureId);

    /**
     * Check if texture exists and is valid
     * @param textureId - Texture ID
     * @return true if texture exists and is valid
     */
    public static native boolean isTextureValid(int textureId);

    /**
     * Create texture with parameters (OpenGL compatibility)
     * @param textureId - Desired texture ID
     * @param width - Texture width
     * @param height - Texture height
     * @param format - Texture format (GL_RGBA, GL_RGB, etc.)
     * @return true if successful
     */
    public static native boolean createTextureWithParams(int textureId, int width, int height, int format);

    /**
     * Validate shader pipeline is ready for use
     * @param pipelineId - Pipeline ID
     * @return true if pipeline is valid
     */
    public static native boolean validateShaderPipeline(int pipelineId);

    /**
     * Get current D3D11 device state
     * @return Device state information
     */
    public static native String getDeviceState();

    /**
     * Force garbage collection of GPU resources
     */
    public static native void forceGarbageCollection();

    // ==================== DIRECTX 11 DEBUG LAYER AND OBJECT NAMING ====================

    /**
     * Set debug object name for D3D11 resource
     * CRUCIAL for debugging - allows identifying objects in graphics debuggers
     * @param resourceHandle - D3D11 resource handle (device, buffer, texture, etc.)
     * @param objectName - Human-readable name for the object
     * @return true if successful
     */
    public static native boolean setDebugObjectName(long resourceHandle, String objectName);

    /**
     * Set debug object name with object type for better identification
     * @param resourceHandle - D3D11 resource handle
     * @param objectType - Type of object ("Device", "Buffer", "Texture", "Shader", etc.)
     * @param objectName - Human-readable name
     * @return true if successful
     */
    public static native boolean setDebugObjectNameTyped(long resourceHandle, String objectType, String objectName);

    /**
     * Enable D3D11 debug layer (must be called before device creation)
     * CRITICAL for debugging - provides detailed validation and error reporting
     * @param enableDebugLayer - Enable debug layer
     * @param enableGpuValidation - Enable GPU-based validation (slower but thorough)
     * @return true if debug layer enabled successfully
     */
    public static native boolean enableDirectXDebugLayer(boolean enableDebugLayer, boolean enableGpuValidation);

    /**
     * Check if D3D11 debug layer is available on system
     * @return true if debug layer is available
     */
    public static native boolean isDebugLayerAvailable();

    /**
     * Report live objects - lists all active D3D11 objects
     * ESSENTIAL for memory leak detection and debugging
     * @return String containing detailed report of live objects
     */
    public static native String reportLiveObjects();

    /**
     * Set debug message severity filtering
     * Controls which debug messages are reported
     * @param severityLevel - Minimum severity level (0=Info, 1=Warning, 2=Error, 3=Critical)
     */
    public static native void setDebugMessageSeverity(int severityLevel);

    /**
     * Enable/disable breaking on debug errors
     * When enabled, the debugger will break on DirectX errors
     * @param breakOnError - Break on DirectX errors
     * @param breakOnWarning - Break on DirectX warnings
     */
    public static native void setDebugBreakOnError(boolean breakOnError, boolean breakOnWarning);

    /**
     * Get debug message queue information
     * Returns statistics about debug messages in the queue
     * @return Debug queue information
     */
    public static native String getDebugMessageQueueInfo();

    /**
     * Clear debug message queue
     * Removes all pending debug messages
     */
    public static native void clearDebugMessageQueue();

    /**
     * Begin D3D11 event annotation
     * Used for grouping operations in graphics debuggers
     * @param eventName - Event name
     * @return Event handle
     */
    public static native long beginDebugEvent(String eventName);

    /**
     * End D3D11 event annotation
     * @param eventHandle - Event handle from beginDebugEvent
     */
    public static native void endDebugEvent(long eventHandle);

    /**
     * Set debug marker for current location
     * Marks a specific point in the command stream
     * @param markerName - Marker name
     */
    public static native void setDebugMarker(String markerName);

    /**
     * Create debug annotation interface
     * Enables advanced annotation features
     * @return true if annotation interface created successfully
     */
    public static native boolean createDebugAnnotationInterface();

    /**
     * Release debug annotation interface
     */
    public static native void releaseDebugAnnotationInterface();

    /**
     * Enable shader debugging information
     * Compiles shaders with debug information for better shader debugging
     * @param enableDebugInfo - Enable debug info in shaders
     */
    public static native void enableShaderDebugInfo(boolean enableDebugInfo);

    /**
     * Validate current device state
     * Checks for common device state issues
     * @return Validation report
     */
    public static native String validateDeviceState();

    /**
     * Get memory usage statistics
     * Returns detailed GPU memory usage information
     * @return Memory usage report
     */
    public static native String getMemoryUsageStatistics();

    /**
     * Check for resource leaks
     * Analyzes allocated resources for potential leaks
     * @return Leak detection report
     */
    public static native String checkResourceLeaks();

    /**
     * Force garbage collection and validate cleanup
     * Forces GC and checks that resources are properly cleaned up
     * @return Cleanup validation report
     */
    public static native String forceAndValidateCleanup();

    // Debug object type constants
    public static final String DEBUG_OBJECT_TYPE_DEVICE = "Device";
    public static final String DEBUG_OBJECT_TYPE_BUFFER = "Buffer";
    public static final String DEBUG_OBJECT_TYPE_TEXTURE = "Texture";
    public static final String DEBUG_OBJECT_TYPE_SHADER = "Shader";
    public static final String DEBUG_OBJECT_TYPE_VERTEX_SHADER = "VertexShader";
    public static final String DEBUG_OBJECT_TYPE_PIXEL_SHADER = "PixelShader";
    public static final String DEBUG_OBJECT_TYPE_GEOMETRY_SHADER = "GeometryShader";
    public static final String DEBUG_OBJECT_TYPE_COMPUTE_SHADER = "ComputeShader";
    public static final String DEBUG_OBJECT_TYPE_INPUT_LAYOUT = "InputLayout";
    public static final String DEBUG_OBJECT_TYPE_RENDER_TARGET = "RenderTarget";
    public static final String DEBUG_OBJECT_TYPE_DEPTH_STENCIL = "DepthStencil";
    public static final String DEBUG_OBJECT_TYPE_BLEND_STATE = "BlendState";
    public static final String DEBUG_OBJECT_TYPE_DEPTH_STENCIL_STATE = "DepthStencilState";
    public static final String DEBUG_OBJECT_TYPE_RASTERIZER_STATE = "RasterizerState";
    public static final String DEBUG_OBJECT_TYPE_SAMPLER = "Sampler";
    public static final String DEBUG_OBJECT_TYPE_SWAP_CHAIN = "SwapChain";
    public static final String DEBUG_OBJECT_TYPE_QUERY = "Query";
    public static final String DEBUG_OBJECT_TYPE_FENCE = "Fence";
    public static final String DEBUG_OBJECT_TYPE_PIPELINE = "Pipeline";

    // ==================== ADVANCED TEXTURE METHODS (for MAbstractTexture) ====================

  
    /**
     * Update texture sub-region (for MAbstractTexture)
     * @param textureHandle - Texture handle
     * @param data - Pixel data in RGBA format
     * @param xOffset - X offset in pixels
     * @param yOffset - Y offset in pixels
     * @param width - Region width in pixels
     * @param height - Region height in pixels
     * @param format - OpenGL format constant (GL_RGBA, etc.)
     * @return true if successful
     */
    public static native boolean updateTextureSubRegion(long textureHandle, byte[] data, int xOffset, int yOffset, int width, int height, int format);

    /**
     * Set texture filtering parameters (for MAbstractTexture)
     * @param textureHandle - Texture handle
     * @param blur - Enable blur (linear filtering)
     * @param mipmap - Enable mipmapping
     * @return true if successful
     */
    public static native boolean setTextureFilter(long textureHandle, boolean blur, boolean mipmap);

    /**
     * Set texture wrap mode (for MAbstractTexture)
     * @param textureHandle - Texture handle
     * @param wrapMode - Wrap mode constant
     * @return true if successful
     */
    public static native boolean setTextureWrap(long textureHandle, int wrapMode);

    /**
     * Generate texture mipmaps (for MAbstractTexture)
     * @param textureHandle - Texture handle
     * @param levels - Number of mipmap levels to generate
     * @return true if successful
     */
    public static native boolean generateTextureMipmaps(long textureHandle, int levels);

    // Texture wrap mode constants
    public static final int TEXTURE_WRAP_REPEAT = 0;
    public static final int TEXTURE_WRAP_CLAMP_TO_EDGE = 1;
    public static final int TEXTURE_WRAP_MIRRORED_REPEAT = 2;
    public static final int TEXTURE_WRAP_CLAMP_TO_BORDER = 3;

    // Texture filter constants
    public static final int TEXTURE_FILTER_NEAREST = 0;
    public static final int TEXTURE_FILTER_LINEAR = 1;
    public static final int TEXTURE_FILTER_NEAREST_MIPMAP_NEAREST = 2;
    public static final int TEXTURE_FILTER_LINEAR_MIPMAP_NEAREST = 3;
    public static final int TEXTURE_FILTER_NEAREST_MIPMAP_LINEAR = 4;
    public static final int TEXTURE_FILTER_LINEAR_MIPMAP_LINEAR = 5;

    // ==================== ADDITIONAL MISSING METHODS ====================

    /**
     * Set VSync
     */
    public static native void setVsync(boolean enabled);

    // ==================== TEXTURE MANAGEMENT FOR MTEXTUREUTIL ====================

    /**
     * Create texture for OpenGL texture ID compatibility (for MTextureUtil)
     * @param textureId - OpenGL texture ID to associate with D3D11 texture
     * @param data - Pixel data (can be null for placeholder)
     * @param width - Texture width
     * @param height - Texture height
     * @param mipLevels - Number of mipmap levels
     * @return D3D11 texture handle
     */
    public static native long createTextureWithId(Integer textureId, int width, int height, int mipLevels);

    /**
     * Set texture format (for MTextureUtil)
     * @param textureHandle - D3D11 texture handle
     * @param directXFormat - D3D11 format constant (DXGI_FORMAT_*)
     */
    public static native void setTextureFormat(long textureHandle, int directXFormat);

    /**
     * Check if texture needs recreation (for MTextureUtil)
     * @param textureHandle - D3D11 texture handle
     * @param width - Desired width
     * @param height - Desired height
     * @param directXFormat - Desired D3D11 format
     * @return true if texture needs to be recreated
     */
    public static native boolean needsTextureRecreation(long textureHandle, int width, int height, int directXFormat);

    /**
     * Release texture (for MTextureUtil)
     * @param textureHandle - D3D11 texture handle to release
     */
    public static native void releaseTexture(long textureHandle);

    // ==================== MTEXTUREUTIL SPECIFIC METHODS ====================

    /**
     * Generate OpenGL texture ID (for MTextureUtil compatibility)
     * @return Generated OpenGL texture ID
     */
    public static native int generateGLTextureId();

    /**
     * Prepare texture image with mipmap levels (for MTextureUtil)
     * @param textureHandle - D3D11 texture handle
     * @param directXFormat - D3D11 format constant
     * @param mipmapLevel - Number of mipmap levels
     * @param width - Texture width
     * @param height - Texture height
     * @return true if successful
     */
    public static native boolean prepareTextureImage(long textureHandle, int directXFormat, int mipmapLevel, int width, int height);

    /**
     * Allocate texture level (for MTextureUtil)
     * @param textureHandle - D3D11 texture handle
     * @param level - Mipmap level
     * @param width - Level width
     * @param height - Level height
     * @param directXFormat - D3D11 format constant
     * @return true if successful
     */
    public static native boolean allocateTextureLevel(long textureHandle, int level, int width, int height, int directXFormat);

    /**
     * Create staging texture for readback operations (for MTextureUtil)
     * @param width - Texture width
     * @param height - Texture height
     * @param directXFormat - D3D11 format constant
     * @return Staging texture handle, or 0 on failure
     */
    public static native long createStagingTexture(int width, int height, int directXFormat);

    /**
     * Read texture data from staging texture (for MTextureUtil)
     * @param stagingTexture - Staging texture handle
     * @param width - Texture width
     * @param height - Texture height
     * @return Pixel data in RGBA format, or null on failure
     */
    public static native byte[] readTextureData(long stagingTexture, int width, int height);

    /**
     * Download texture data to byte array (for MNativeImage)
     * Uses staging texture for CPU read access in D3D11
     * @param textureHandle - D3D11 texture handle
     * @param outputData - Output byte array to receive pixel data
     * @param level - Mipmap level to download
     * @return true if download succeeded
     */
    public static native boolean downloadTextureData(long textureHandle, byte[] outputData, int level);

    // OpenGL constants for MTextureUtil compatibility
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    public static final int GL_TEXTURE_BASE_LEVEL = 0x813C;
    public static final int GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE;

    /**
     * Set depth test enabled state
     * @param enabled - Enable/disable depth testing
     */
    public static native void setDepthTestEnabled(boolean enabled);

    /**
     * Set depth write mask
     * @param mask - Enable/disable depth writes
     */
    public static native void setDepthWriteMask(boolean mask);

    /**
     * Set depth function
     * @param func - Depth comparison function (GL_LESS, GL_LEQUAL, etc.)
     */
    public static native void setDepthFunc(int func);

    /**
     * Set blend enabled state
     * @param enabled - Enable/disable blending
     */
    public static native void setBlendEnabled(boolean enabled);

    /**
     * Set cull enabled state
     * @param enabled - Enable/disable face culling
     */
    public static native void setCullEnabled(boolean enabled);

    /**
     * Set polygon mode
     * @param mode - Polygon mode (GL_FILL, GL_LINE, GL_POINT)
     */
    public static native void setPolygonMode(int mode);

    /**
     * Set depth bias (polygon offset)
     * @param constant - Constant depth bias factor
     * @param slope - Slope scaled depth bias factor
     */
    public static native void setDepthBias(float constant, float slope);

    /**
     * Clear render target with depth and stencil options
     * @param clearColor - Clear color buffer
     * @param clearDepth - Clear depth buffer
     * @param clearStencil - Clear stencil buffer
     * @param r - Red component
     * @param g - Green component
     * @param b - Blue component
     * @param a - Alpha component
     */
    public static native void clearRenderTarget(boolean clearColor, boolean clearDepth, boolean clearStencil,
                                               float r, float g, float b, float a);

    // ==================== MISSING METHODS FROM COMPILATION ERRORS ====================

    /**
     * Initialize renderer system (for RenderSystemMixin)
     */
    public static native void initRenderer();

    /**
     * Get maximum supported texture size (for RenderSystemMixin)
     * @return Maximum texture dimension
     */
    public static native int maxSupportedTextureSize();

    /**
     * Add frame operation for deferred execution (for GameRendererMixin)
     * @param operation - Runnable operation to execute next frame
     */
    public static void addFrameOperation(Runnable operation) {
        // For now, execute immediately - proper frame queue can be implemented later
        try {
            operation.run();
        } catch (Exception e) {
            LOGGER.error("Error in frame operation", e);
        }
    }

    /**
     * Get active shader pipeline count (for GameRendererMixin)
     * @return Number of active shader pipelines
     */
    public static native int getActiveShaderPipelineCount();

    /**
     * Get total shader pipeline count (for GameRendererMixin)
     * @return Total number of shader pipelines
     */
    public static native int getTotalShaderPipelineCount();

    /**
     * Upload and bind uniform buffer objects (for BufferUploaderMixin)
     */
    public static native void uploadAndBindUBOs();

    /**
     * Set texture parameter using long handle (for texture management)
     * @param textureHandle - DirectX texture handle
     * @param pname - Parameter name
     * @param param - Parameter value
     */
    public static native void setTextureParameter(long textureHandle, int pname, int param);

    // ==================== MISSING NATIVE METHODS FOR COMPILATION ====================

    /**
     * Set clear depth value for depth buffer clearing
     * @param depth - Depth clear value (0.0 to 1.0)
     */
    public static native void setClearDepth(float depth);

    /**
     * Set render targets for drawing operations
     * @param framebuffer - Framebuffer handle (0 for default)
     */
    public static native void setRenderTargets(int framebuffer);

    /**
     * Set read render target for read operations
     * @param framebuffer - Framebuffer handle (0 for default)
     */
    public static native void setReadRenderTarget(int framebuffer);

    /**
     * Create render target with color and depth attachments
     * @param width - Render target width
     * @param height - Render target height
     * @param hasColor - Create color attachment
     * @param hasDepth - Create depth attachment
     * @return Render target handle, or 0 on failure
     */
    public static native long createRenderTarget(int width, int height, boolean hasColor, boolean hasDepth);

    /**
     * Set active texture unit for texture operations
     * @param textureUnit - Texture unit index (0-31)
     */
    public static native void setActiveTextureUnit(int textureUnit);

    /**
     * Wait for GPU to complete all pending operations
     * Blocks until GPU finishes processing all commands in the command queue
     */
    public static native void waitForIdle();

    /**
     * FIX #1: Submit pending texture/buffer uploads (VulkanMod pattern)
     * Called in preInitFrame() to flush staging buffer uploads
     */
    public static native void submitPendingUploads();

    /**
     * Update texture with pixel data (for GlStateManagerMixin)
     * @param textureId - OpenGL texture ID
     * @param data - Pixel data
     * @param width - Texture width
     * @param height - Texture height
     * @param format - Pixel format
     * @return true if successful
     */
    public static native boolean updateTexture(int textureId, byte[] data, int width, int height, int format);

    /**
     * Create GPU texture (for AbstractTextureMixin)
     * @param name - Texture name for debugging
     * @return D3D11 texture handle
     */
    public static native long createGpuTexture(String name);

    // ==================== DIRECTX 11 TOPOLOGY CONSTANTS ====================

    public static final int D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED = 0;
    public static final int D3D11_PRIMITIVE_TOPOLOGY_POINTLIST = 1;
    public static final int D3D11_PRIMITIVE_TOPOLOGY_LINELIST = 2;
    public static final int D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP = 3;
    public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST = 4;
    public static final int D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP = 5;

    // ==================== INPUT LAYOUT MANAGEMENT ====================

    /**
     * Create D3D11 input layout for vertex format
     * @param elementDescriptions - Array of input layout element descriptions
     * @param elementCount - Number of elements
     * @return Input layout handle, or 0 on failure
     */
    public static native long createInputLayout(int[] elementDescriptions, int elementCount);

    /**
     * Release D3D11 input layout
     * @param inputLayoutHandle - Input layout handle
     */
    public static native void releaseInputLayout(long inputLayoutHandle);

    /**
     * Bind input layout for rendering
     * @param inputLayoutHandle - Input layout handle
     */
    public static native void bindInputLayout(long inputLayoutHandle);

    // ==================== MISSING METHODS FROM COMPILATION ERRORS ====================

    /**
     * Adjust orthographic projection matrix
     * @param left - Left bound
     * @param right - Right bound
     * @param bottom - Bottom bound
     * @param top - Top bound
     * @param zNear - Near plane
     * @param zFar - Far plane
     */
    public static native void adjustOrthographicProjection(float left, float right, float bottom, float top, float zNear, float zFar);

    /**
     * Adjust perspective projection matrix
     * @param fovy - Field of view in y direction
     * @param aspect - Aspect ratio
     * @param zNear - Near plane
     * @param zFar - Far plane
     */
    public static native void adjustPerspectiveProjection(float fovy, float aspect, float zNear, float zFar);

    /**
     * Begin text batch rendering
     */
    public static native void beginTextBatch();

    /**
     * Cleanup render context
     */
    public static native void cleanupRenderContext();

    /**
     * Clear depth buffer
     * @param depth - Depth value
     */
    public static native void clearDepthBuffer(float depth);

    /**
     * Draw mesh with advanced parameters
     * @param vertexBuffer - Vertex buffer
     * @param indexBuffer - Index buffer
     * @param vertexCount - Vertex count
     * @param indexCount - Index count
     * @param primitiveMode - Primitive mode
     * @param vertexSize - Vertex size
     */
    public static native void drawMesh(Object vertexBuffer, Object indexBuffer, int vertexCount, int indexCount, int primitiveMode, int vertexSize);

    /**
     * End text batch rendering
     */
    public static native void endTextBatch();

  
    /**
     * Get optimal framerate limit
     * @return Framerate limit
     */
    public static native int getOptimalFramerateLimit();

    /**
     * Get optimized D3D11 shader
     * @param shaderName - Shader name
     * @return Shader handle
     */
    public static native long getOptimizedDirectX11Shader(String shaderName);

    /**
     * Handle display resize
     * @param width - New width
     * @param height - New height
     */
    public static native void handleDisplayResize(int width, int height);

    /**
     * Initialize debug system
     */
    public static native void initializeDebug();

    /**
     * Initialize DirectX with safety checks
     * @return true if successful
     */
    public static native boolean initializeDirectXSafe();

    
    /**
     * Check if matrix is D3D11 optimized
     * @param matrix - Matrix data
     * @return true if optimized
     */
    public static native boolean isMatrixDirectX11Optimized(float[] matrix);

    /**
     * Check if shader is D3D11 compatible
     * @param shaderData - Shader bytecode
     * @return true if compatible
     */
    public static native boolean isShaderDirectX11Compatible(byte[] shaderData);

    /**
     * Optimize button rendering
     * @param buttonId - Button ID
     */
    public static native void optimizeButtonRendering(int buttonId);

    /**
     * Optimize container background rendering
     * @param containerId - Container ID
     */
    public static native void optimizeContainerBackground(int containerId);

    /**
     * Optimize container labels rendering
     * @param labelId - Label ID
     */
    public static native void optimizeContainerLabels(int labelId);

    /**
     * Optimize crosshair rendering
     */
    public static native void optimizeCrosshairRendering();

    /**
     * Optimize dirt background rendering
     * @param dirtType - Dirt type
     */
    public static native void optimizeDirtBackground(int dirtType);

    /**
     * Optimize fading background rendering
     * @param fadeAmount - Fade amount
     */
    public static native void optimizeFadingBackground(float fadeAmount);

    /**
     * Optimize logo rendering
     * @param logoSize - Logo size
     */
    public static native void optimizeLogoRendering(int logoSize);

    /**
     * Optimize matrix inversion
     * @param matrix - Input matrix
     * @return Inverted matrix
     */
    public static native float[] optimizeMatrixInversion(float[] matrix);

    /**
     * Optimize matrix multiplication
     * @param matrixA - First matrix
     * @param matrixB - Second matrix
     * @return Result matrix
     */
    public static native float[] optimizeMatrixMultiplication(float[] matrixA, float[] matrixB);

    /**
     * Optimize matrix transpose
     * @param matrix - Input matrix
     * @return Transposed matrix
     */
    public static native float[] optimizeMatrixTranspose(float[] matrix);

    /**
     * Optimize panorama rendering
     * @param panoramaId - Panorama ID
     */
    public static native void optimizePanoramaRendering(int panoramaId);

    /**
     * Optimize rotation matrix
     * @param angle - Rotation angle
     * @param axis - Rotation axis
     * @return Rotation matrix
     */
    public static native float[] optimizeRotationMatrix(float angle, float[] axis);

    /**
     * Optimize scale matrix
     * @param scaleX - X scale
     * @param scaleY - Y scale
     * @param scaleZ - Z scale
     * @return Scale matrix
     */
    public static native float[] optimizeScaleMatrix(float scaleX, float scaleY, float scaleZ);

    /**
     * Optimize screen background rendering
     * @param screenType - Screen type
     */
    public static native void optimizeScreenBackground(int screenType);

    /**
     * Optimize slot highlight rendering
     * @param slotId - Slot ID
     */
    public static native void optimizeSlotHighlight(int slotId);

    /**
     * Optimize slot rendering
     * @param slotId - Slot ID
     */
    public static native void optimizeSlotRendering(int slotId);

    /**
     * Optimize tooltip rendering
     * @param tooltipId - Tooltip ID
     */
    public static native void optimizeTooltipRendering(int tooltipId);

    /**
     * Optimize translation matrix
     * @param translateX - X translation
     * @param translateY - Y translation
     * @param translateZ - Z translation
     * @return Translation matrix
     */
    public static native float[] optimizeTranslationMatrix(float translateX, float translateY, float translateZ);

    /**
     * Precompile shader for D3D11
     * @param shaderSource - Shader source code
     * @param shaderType - Shader type
     * @return Compiled shader handle
     */
    public static native long precompileShaderForDirectX11(String shaderSource, int shaderType);

    /**
     * Prepare render context
     */
    public static native void prepareRenderContext();

    /**
     * Present frame to screen
     */
    public static native void presentFrame();

    /**
     * Set window active state
     * @param active - Window active
     */
    public static native void setWindowActiveState(boolean active);

    /**
     * Wait for GPU commands to complete
     */
    public static native void waitForGpuCommands();

    // ==================== GLSTATEMANAGER COMPATIBILITY METHODS ====================

    /**
     * Bind texture (int ID version for GlStateManager compatibility)
     * @param textureId - OpenGL-style texture ID
     */
    public static native void bindTexture(int textureId);

    /**
     * Upload texture data (texImage2D)
     */
    public static native void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels);

    /**
     * Update texture subregion (texSubImage2D)
     */
    public static native void texSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels);

    /**
     * Update texture subregion with explicit row pitch (for GL_UNPACK_ROW_LENGTH support)
     * CRITICAL for font textures
     */
    public static native void texSubImage2DWithPitch(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels, int rowPitch);

    /**
     * Set active texture unit
     */
    public static native void activeTexture(int texture);

    /**
     * Set texture parameter (integer)
     */
    public static native void texParameteri(int target, int pname, int param);

    /**
     * Get texture level parameter
     */
    public static native int getTexLevelParameter(int target, int level, int pname);

    /**
     * Pixel store parameter
     */
    public static native void pixelStore(int pname, int param);

    /**
     * Enable blending
     */
    public static native void enableBlend();

    /**
     * Disable blending
     */
    public static native void disableBlend();

    /**
     * Set blend function
     */
    public static native void blendFunc(int srcFactor, int dstFactor);

    /**
     * Set blend function separate
     */
    public static native void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);

    /**
     * Set blend equation
     */
    public static native void blendEquation(int mode);

    /**
     * Enable depth test
     */
    public static native void enableDepthTest();

    /**
     * Disable depth test
     */
    public static native void disableDepthTest();

    /**
     * Set depth function
     */
    public static native void depthFunc(int func);

    /**
     * Set depth mask
     */
    public static native void depthMask(boolean flag);

    /**
     * Enable backface culling
     */
    public static native void enableCull();

    /**
     * Disable backface culling
     */
    public static native void disableCull();

    /**
     * Reset scissor to full viewport
     */
    public static native void resetScissor();

    /**
     * Set scissor rectangle
     */
    public static native void setScissor(int x, int y, int width, int height);

    /**
     * Clear operation (glClear)
     */
    public static native void clear(int mask);

    /**
     * Set color write mask
     */
    public static native void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    /**
     * Enable polygon offset
     */
    public static native void enablePolygonOffset();

    /**
     * Disable polygon offset
     */
    public static native void disablePolygonOffset();

    /**
     * Set polygon offset
     */
    public static native void polygonOffset(float factor, float units);

    /**
     * Enable color logic op
     */
    public static native void enableColorLogicOp();

    /**
     * Disable color logic op
     */
    public static native void disableColorLogicOp();

    /**
     * Set logic operation
     */
    public static native void logicOp(int opcode);

    /**
     * Bind buffer (int ID version for GlStateManager compatibility)
     */
    public static native void bindBuffer(int target, int buffer);

    /**
     * Upload buffer data (ByteBuffer version)
     */
    public static native void bufferData(int target, java.nio.ByteBuffer data, int usage);

    /**
     * Upload buffer data with stride - ByteBuffer version with explicit stride for D3D11
     * @param target - GL_ARRAY_BUFFER or GL_ELEMENT_ARRAY_BUFFER
     * @param data - Buffer data
     * @param usage - Usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     * @param stride - Vertex stride in bytes (size of one vertex)
     */
    public static native void bufferData(int target, java.nio.ByteBuffer data, int usage, int stride);

    /**
     * Allocate buffer data (size version)
     */
    public static native void bufferData(int target, long size, int usage);

    /**
     * Delete buffer
     */
    public static native void deleteBuffer(int buffer);

    /**
     * Map buffer (int ID version for GlStateManager - returns mapped memory)
     */
    public static native java.nio.ByteBuffer mapBuffer(int target, int access);

    /**
     * Unmap buffer (int target version)
     */
    public static native void unmapBuffer(int target);

    /**
     * Framebuffer texture attachment (int ID version)
     */
    public static native void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level);

    /**
     * Framebuffer renderbuffer attachment (int ID version)
     */
    public static native void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer);

    /**
     * Renderbuffer storage (int ID version)
     */
    public static native void renderbufferStorage(int target, int internalformat, int width, int height);

    /**
     * Bind framebuffer (int ID version)
     */
    public static native void bindFramebuffer(int target, int framebuffer);

    /**
     * Create or resize framebuffer textures (color + depth)
     * @param framebufferId The framebuffer ID
     * @param width Framebuffer width
     * @param height Framebuffer height
     * @param hasColor Whether to create color attachment
     * @param hasDepth Whether to create depth attachment
     * @return true if successful, false otherwise
     */
    public static native boolean createFramebufferTextures(int framebufferId, int width, int height, boolean hasColor, boolean hasDepth);

    /**
     * Destroy framebuffer and free resources
     * @param framebufferId The framebuffer ID to destroy
     */
    public static native void destroyFramebuffer(int framebufferId);

    /**
     * Bind framebuffer texture for reading
     * @param framebufferId The framebuffer ID
     * @param textureUnit The texture unit to bind to
     */
    public static native void bindFramebufferTexture(int framebufferId, int textureUnit);

    /**
     * Bind renderbuffer (int ID version)
     */
    public static native void bindRenderbuffer(int target, int renderbuffer);

    // ==================== MAINTARGET COMPATIBILITY METHODS ====================

    /**
     * Bind main render target (back buffer)
     */
    public static native void bindMainRenderTarget();

    /**
     * Bind main render target texture for reading
     */
    public static native void bindMainRenderTargetTexture();

    /**
     * Get main color texture ID
     */
    public static native int getMainColorTextureId();

    
}
