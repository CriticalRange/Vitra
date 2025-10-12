package com.vitra.render.jni;

/**
 * Common interface for buffer managers across different DirectX backends
 */
public interface IBufferManager {

    /**
     * Initialize the buffer manager
     */
    void initialize();

    /**
     * Shutdown the buffer manager and clean up resources
     */
    void shutdown();

    /**
     * Clear all cached buffers
     */
    void clearAll();

    /**
     * Get statistics about buffer usage
     */
    String getBufferStats();

    /**
     * Check if the buffer manager is initialized
     */
    boolean isInitialized();

    /**
     * Get the type of renderer this buffer manager belongs to
     */
    String getRendererType();
}