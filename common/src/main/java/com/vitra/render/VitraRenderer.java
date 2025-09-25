package com.vitra.render;

import com.vitra.core.config.RendererType;
import com.vitra.render.backend.BgfxRenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main renderer management class for Vitra
 * Handles creation and management of render contexts based on configuration
 */
public class VitraRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraRenderer.class);

    private RenderContext currentContext;
    private boolean initialized = false;

    /**
     * Initialize the renderer with Direct3D 11 as default
     */
    public void initialize() {
        initialize(RendererType.DIRECTX11);
    }

    /**
     * Initialize the renderer with a specific backend
     * @param rendererType The desired renderer backend
     */
    public void initialize(RendererType rendererType) {
        if (initialized) {
            LOGGER.warn("VitraRenderer already initialized");
            return;
        }

        LOGGER.info("Preparing Vitra renderer with backend: {} (deferred initialization)", rendererType.getDisplayName());

        try {
            // Check if the requested renderer is supported
            if (!rendererType.isSupported()) {
                throw new RuntimeException("DirectX 11 is not supported on this platform. Vitra requires Windows.");
            }

            // Create the render context based on the selected backend
            currentContext = createRenderContext(rendererType);

            if (currentContext == null) {
                throw new RuntimeException("Failed to create render context for " + rendererType.getDisplayName());
            }

            // Initialize the context without BGFX initialization
            // BGFX initialization will be deferred until first frame
            boolean success = currentContext.initialize(1920, 1080, 0L);

            if (!success) {
                throw new RuntimeException("Failed to prepare render context");
            }

            initialized = true;
            LOGGER.info("Vitra renderer preparation complete, BGFX will initialize on first frame");

        } catch (Exception e) {
            LOGGER.error("Failed to prepare Vitra renderer", e);
            throw new RuntimeException("Vitra renderer initialization failed", e);
        }
    }

    /**
     * Force immediate BGFX initialization (for replacing OpenGL)
     */
    public boolean initializeImmediately() {
        if (currentContext != null && currentContext instanceof com.vitra.render.backend.BgfxRenderContext) {
            com.vitra.render.backend.BgfxRenderContext bgfxContext = (com.vitra.render.backend.BgfxRenderContext) currentContext;
            return bgfxContext.forceInitialization();
        }
        return false;
    }

    /**
     * Initialize BGFX with a specific window handle
     */
    public boolean initializeWithWindowHandle(long windowHandle) {
        if (currentContext != null && currentContext instanceof com.vitra.render.backend.BgfxRenderContext) {
            com.vitra.render.backend.BgfxRenderContext bgfxContext = (com.vitra.render.backend.BgfxRenderContext) currentContext;
            return bgfxContext.initializeWithWindowHandle(windowHandle);
        }
        return false;
    }

    /**
     * Check if the renderer is fully initialized
     */
    public boolean isFullyInitialized() {
        return initialized && currentContext != null;
    }

    /**
     * Shutdown the renderer and cleanup resources
     */
    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra renderer...");

        if (currentContext != null) {
            currentContext.shutdown();
            currentContext = null;
        }

        initialized = false;
        LOGGER.info("Vitra renderer shutdown complete");
    }

    /**
     * Switch to a different rendering backend at runtime
     * @param newRendererType The new renderer backend to use
     */
    public boolean switchRenderer(RendererType newRendererType) {
        if (!initialized) {
            LOGGER.warn("Cannot switch renderer - not initialized");
            return false;
        }

        if (currentContext != null && currentContext.getRendererType() == newRendererType) {
            LOGGER.info("Already using {} renderer", newRendererType.getDisplayName());
            return true;
        }

        LOGGER.info("Switching renderer from {} to {}",
            currentContext != null ? currentContext.getRendererType().getDisplayName() : "none",
            newRendererType.getDisplayName());

        try {
            // Shutdown current context
            if (currentContext != null) {
                currentContext.shutdown();
            }

            // Create new context
            currentContext = createRenderContext(newRendererType);
            if (currentContext == null) {
                LOGGER.error("Failed to create new render context");
                return false;
            }

            // Initialize new context
            boolean success = currentContext.initialize(1920, 1080, 0L);
            if (!success) {
                LOGGER.error("Failed to initialize new render context");
                currentContext = null;
                return false;
            }

            LOGGER.info("Successfully switched to {} renderer", newRendererType.getDisplayName());
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to switch renderer", e);
            currentContext = null;
            return false;
        }
    }

    /**
     * Create a render context for the specified backend
     */
    private RenderContext createRenderContext(RendererType rendererType) {
        switch (rendererType) {
            case DIRECTX11:
                return new BgfxRenderContext(RendererType.DIRECTX11);
            default:
                LOGGER.error("Unknown renderer type: {}", rendererType);
                return null;
        }
    }

    /**
     * Get the current render context
     */
    public RenderContext getRenderContext() {
        return currentContext;
    }

    /**
     * Check if the renderer is initialized and ready
     */
    public boolean isInitialized() {
        return initialized && currentContext != null && currentContext.isValid();
    }

    /**
     * Begin a new frame for rendering
     */
    public void beginFrame() {
        if (currentContext != null) {
            currentContext.beginFrame();
        }
    }

    /**
     * End the current frame and present to screen
     */
    public void endFrame() {
        if (currentContext != null) {
            currentContext.endFrame();
        }
    }

    /**
     * Handle window resize events
     */
    public void resize(int width, int height) {
        if (currentContext != null) {
            currentContext.resize(width, height);
        }
    }

    /**
     * Clear the framebuffer with the specified color
     */
    public void clear(float r, float g, float b, float a) {
        if (currentContext != null) {
            currentContext.clear(r, g, b, a, 1.0f);
        }
    }
}