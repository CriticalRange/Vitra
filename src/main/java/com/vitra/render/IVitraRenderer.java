package com.vitra.render;

import com.vitra.config.VitraConfig;
import com.vitra.config.RendererType;

/**
 * Unified renderer interface for Vitra
 * Supports both DirectX 11 and DirectX 12 Ultimate backends
 */
public interface IVitraRenderer {

    /**
     * Initialize the renderer with default settings
     */
    void initialize();

    /**
     * Initialize the renderer with specified renderer type
     */
    void initialize(RendererType rendererType);

    /**
     * Set configuration for the renderer
     */
    void setConfig(VitraConfig config);

    /**
     * Set the Vitra core reference for shader loading
     */
    void setCore(com.vitra.core.VitraCore core);

    /**
     * Initialize with window handle for actual rendering setup
     */
    boolean initializeWithWindowHandle(long windowHandle);

    /**
     * Check if the renderer is fully initialized
     */
    boolean isInitialized();

    /**
     * Get the renderer type
     */
    RendererType getRendererType();

    /**
     * Begin a new frame
     */
    void beginFrame();

    /**
     * End current frame
     */
    void endFrame();

    /**
     * Present the frame to the screen
     */
    void present();

    /**
     * Resize the render target
     */
    void resize(int width, int height);

    /**
     * Clear the render target with specified color
     */
    void clear(float r, float g, float b, float a);

    /**
     * Shutdown the renderer and clean up resources
     */
    void shutdown();

    // Feature support queries

    /**
     * Check if ray tracing is supported
     */
    boolean isRayTracingSupported();

    /**
     * Check if variable rate shading is supported
     */
    boolean isVariableRateShadingSupported();

    /**
     * Check if mesh shaders are supported
     */
    boolean isMeshShadersSupported();

    /**
     * Check if GPU-driven rendering is supported
     */
    boolean isGpuDrivenRenderingSupported();

    // Performance monitoring

    /**
     * Get current GPU utilization percentage
     */
    float getGpuUtilization();

    /**
     * Get frame time in milliseconds
     */
    long getFrameTime();

    /**
     * Get number of draw calls in current frame
     */
    int getDrawCallsPerFrame();

    /**
     * Reset performance counters
     */
    void resetPerformanceCounters();

    // Debug functionality

    /**
     * Enable/disable debug mode
     */
    void setDebugMode(boolean enabled);

    /**
     * Capture current frame for debugging
     */
    void captureFrame(String filename);

    /**
     * Get renderer statistics
     */
    String getRendererStats();

    // Feature access for advanced usage

    /**
     * Get DirectX 11 specific renderer (if available)
     */
    VitraRenderer getDirectX11Renderer();

    /**
     * Get DirectX 12 specific renderer (if available)
     */
    com.vitra.render.jni.VitraD3D12Renderer getDirectX12Renderer();

    /**
     * Get shader manager for this renderer
     */
    Object getShaderManager();

    /**
     * Get buffer manager for this renderer
     */
    Object getBufferManager();

    /**
     * Get native device handle for debugging and verification
     */
    long getNativeHandle();

    // Additional methods needed by mixins

    /**
     * Draw mesh with provided buffers
     */
    void drawMesh(Object vertexBuffer, Object indexBuffer, Object mode, Object format, int vertexCount);

    /**
     * Clear depth buffer
     */
    void clearDepthBuffer();

    /**
     * Wait for GPU commands to complete
     */
    void waitForGpuCommands();
}