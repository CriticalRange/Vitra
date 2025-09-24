package com.vitra.render;

import com.vitra.core.config.RendererType;

/**
 * Main rendering context interface - provides platform-independent rendering capabilities
 * This is the core interface that all backend implementations must implement
 */
public interface RenderContext {

    /**
     * Initialize the render context with the given parameters
     * @param width Initial framebuffer width
     * @param height Initial framebuffer height
     * @param windowHandle Platform-specific window handle (HWND on Windows, Window on X11, etc.)
     * @return true if initialization succeeded
     */
    boolean initialize(int width, int height, long windowHandle);

    /**
     * Shutdown the render context and cleanup resources
     */
    void shutdown();

    /**
     * Get the renderer type this context implements
     */
    RendererType getRendererType();

    /**
     * Begin a new frame
     */
    void beginFrame();

    /**
     * End the current frame and present to screen
     */
    void endFrame();

    /**
     * Handle window resize events
     * @param newWidth New framebuffer width
     * @param newHeight New framebuffer height
     */
    void resize(int newWidth, int newHeight);

    /**
     * Set the viewport for rendering
     * @param x Viewport X offset
     * @param y Viewport Y offset
     * @param width Viewport width
     * @param height Viewport height
     */
    void setViewport(int x, int y, int width, int height);

    /**
     * Create a new vertex buffer
     * @param vertices Vertex data
     * @param layout Vertex layout description
     * @return Handle to the created vertex buffer
     */
    int createVertexBuffer(float[] vertices, VertexLayout layout);

    /**
     * Create a new index buffer
     * @param indices Index data
     * @return Handle to the created index buffer
     */
    int createIndexBuffer(int[] indices);

    /**
     * Create a new texture
     * @param width Texture width
     * @param height Texture height
     * @param format Texture format
     * @param data Texture data (can be null for empty texture)
     * @return Handle to the created texture
     */
    int createTexture(int width, int height, TextureFormat format, byte[] data);

    /**
     * Create a new shader program
     * @param vertexShaderCode Vertex shader source code
     * @param fragmentShaderCode Fragment shader source code
     * @return Handle to the created shader program
     */
    int createShaderProgram(String vertexShaderCode, String fragmentShaderCode);

    /**
     * Bind a vertex buffer for rendering
     * @param handle Vertex buffer handle
     */
    void bindVertexBuffer(int handle);

    /**
     * Bind an index buffer for rendering
     * @param handle Index buffer handle
     */
    void bindIndexBuffer(int handle);

    /**
     * Bind a texture for rendering
     * @param handle Texture handle
     * @param slot Texture slot (0-15 typically)
     */
    void bindTexture(int handle, int slot);

    /**
     * Bind a shader program for rendering
     * @param handle Shader program handle
     */
    void bindShaderProgram(int handle);

    /**
     * Set a shader uniform value
     * @param shaderHandle Shader program handle
     * @param uniformName Name of the uniform
     * @param value Uniform value
     */
    void setShaderUniform(int shaderHandle, String uniformName, float[] value);

    /**
     * Draw indexed primitives
     * @param indexCount Number of indices to draw
     * @param indexOffset Offset into index buffer
     * @param vertexOffset Offset into vertex buffer
     */
    void drawIndexed(int indexCount, int indexOffset, int vertexOffset);

    /**
     * Draw non-indexed primitives
     * @param vertexCount Number of vertices to draw
     * @param vertexOffset Offset into vertex buffer
     */
    void draw(int vertexCount, int vertexOffset);

    /**
     * Delete a buffer resource
     * @param handle Buffer handle to delete
     */
    void deleteBuffer(int handle);

    /**
     * Delete a texture resource
     * @param handle Texture handle to delete
     */
    void deleteTexture(int handle);

    /**
     * Delete a shader program resource
     * @param handle Shader program handle to delete
     */
    void deleteShaderProgram(int handle);

    /**
     * Clear the framebuffer
     * @param colorR Red component (0.0-1.0)
     * @param colorG Green component (0.0-1.0)
     * @param colorB Blue component (0.0-1.0)
     * @param colorA Alpha component (0.0-1.0)
     * @param depth Depth value (0.0-1.0)
     */
    void clear(float colorR, float colorG, float colorB, float colorA, float depth);

    /**
     * Check if the render context is valid and ready for rendering
     */
    boolean isValid();

    /**
     * Get debug information about the renderer
     */
    String getDebugInfo();
}