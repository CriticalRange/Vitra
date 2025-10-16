package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for buffer managers across different DirectX backends
 * Provides common functionality for initialization, shutdown, and lifecycle management
 */
public abstract class AbstractBufferManager implements IBufferManager {
    protected final Logger logger;
    protected volatile boolean initialized = false;

    protected AbstractBufferManager(Class<?> loggerClass) {
        this.logger = LoggerFactory.getLogger(loggerClass);
    }

    @Override
    public void initialize() {
        logger.info("Initializing {} buffer manager", getRendererType());
        initialized = true;
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down {} buffer manager", getRendererType());
        clearAll();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Clear all cached buffers - implementation specific to each DirectX version
     */
    @Override
    public abstract void clearAll();

    /**
     * Get buffer statistics - implementation specific to each DirectX version
     */
    @Override
    public abstract String getBufferStats();

    /**
     * Get the renderer type name (e.g., "DirectX 11", "DirectX 12 Ultimate")
     */
    @Override
    public abstract String getRendererType();
}
