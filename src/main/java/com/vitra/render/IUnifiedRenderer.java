package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;

/**
 * Unified renderer interface that supports both DirectX 11 and DirectX 12 backends
 * Provides dynamic backend selection based on configuration
 */
public interface IUnifiedRenderer {

    /**
     * Initialize the renderer with the given window handle and dimensions
     */
    boolean initialize(long windowHandle, int width, int height, boolean debugMode);

    /**
     * Shutdown the renderer and clean up resources
     */
    void shutdown();

    /**
     * Begin a new frame
     */
    void beginFrame();

    /**
     * End the current frame and present
     */
    void endFrame();

    /**
     * Present the frame (swap buffers)
     */
    void present();

    /**
     * Resize the renderer viewport
     */
    void resize(int width, int height);

    /**
     * Clear the render target with the given color
     */
    void clear(float r, float g, float b, float a);

    /**
     * Clear the depth buffer
     */
    void clearDepthBuffer();

    /**
     * Wait for GPU commands to complete
     */
    void waitForGpuCommands();

    /**
     * Recreate the swap chain (for VSync changes, etc.)
     */
    void recreateSwapChain();

    /**
     * Get the renderer type this instance supports
     */
    RendererType getRendererType();

    /**
     * Check if the renderer is initialized
     */
    boolean isInitialized();

    /**
     * Get the native device handle for debugging
     */
    long getNativeDeviceHandle();
}