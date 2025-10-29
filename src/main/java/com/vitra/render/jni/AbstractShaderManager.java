package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for shader managers across different DirectX backends
 * Provides common functionality for initialization, shutdown, and lifecycle management
 */
public abstract class AbstractShaderManager implements IShaderManager {
    protected final Logger logger;
    protected volatile boolean initialized = false;

    protected AbstractShaderManager(Class<?> loggerClass) {
        this.logger = LoggerFactory.getLogger(loggerClass);
    }

    @Override
    public void initialize() {
        logger.info("Initializing {} shader manager", getRendererType());
        initialized = true;
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down {} shader manager", getRendererType());
        clearCache();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Clear all cached shaders - implementation specific to each DirectX version
     */
    @Override
    public abstract void clearCache();

    /**
     * Get cache statistics - implementation specific to each DirectX version
     */
    @Override
    public abstract String getCacheStats();

    /**
     * Get the renderer type name (e.g., "DirectX", "DirectX 12 Ultimate")
     */
    @Override
    public abstract String getRendererType();
}
