package com.vitra.render.jni;

/**
 * Common interface for shader managers across different DirectX backends
 */
public interface IShaderManager {

    /**
     * Initialize the shader manager
     */
    void initialize();

    /**
     * Shutdown the shader manager and clean up resources
     */
    void shutdown();

    /**
     * Clear all cached shaders
     */
    void clearCache();

    /**
     * Get statistics about the shader cache
     */
    String getCacheStats();

    /**
     * Check if the shader manager is initialized
     */
    boolean isInitialized();

    /**
     * Get the type of renderer this shader manager belongs to
     */
    String getRendererType();
}