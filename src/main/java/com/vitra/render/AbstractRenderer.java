package com.vitra.render;

import com.vitra.config.RendererType;
import com.vitra.config.VitraConfig;
import com.vitra.core.VitraCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for all Vitra renderers
 * Provides common functionality for initialization, shutdown, and lifecycle management
 */
public abstract class AbstractRenderer implements IVitraRenderer {
    protected final Logger logger;
    protected volatile boolean initialized = false;
    protected long windowHandle = 0L;
    protected VitraConfig config = null;
    protected VitraCore core = null;

    protected AbstractRenderer(Class<?> loggerClass) {
        this.logger = LoggerFactory.getLogger(loggerClass);
    }

    @Override
    public void setConfig(VitraConfig config) {
        this.config = config;
    }

    @Override
    public void setCore(VitraCore core) {
        this.core = core;
        // Set this renderer reference in the core for shader loading
        if (core != null) {
            core.setRenderer(this);
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized && windowHandle != 0L;
    }

    /**
     * Get the renderer type - must be implemented by subclasses
     */
    @Override
    public abstract RendererType getRendererType();

    /**
     * Initialize with window handle - must be implemented by subclasses
     */
    @Override
    public abstract boolean initializeWithWindowHandle(long windowHandle);

    /**
     * Shutdown the renderer - must be implemented by subclasses
     */
    @Override
    public abstract void shutdown();

    /**
     * Get shader manager - must be implemented by subclasses
     */
    @Override
    public abstract Object getShaderManager();

    /**
     * Get buffer manager - must be implemented by subclasses
     */
    @Override
    public abstract Object getBufferManager();

    /**
     * Get native device handle
     */
    @Override
    public long getNativeHandle() {
        return 0L; // Override in subclasses if needed
    }

    /**
     * Common helper method for checking debug mode
     */
    protected boolean isDebugMode() {
        return config != null && config.isDebugMode();
    }

    /**
     * Common helper method for checking verbose mode
     */
    protected boolean isVerboseMode() {
        return config != null && config.isVerboseLogging();
    }
}
