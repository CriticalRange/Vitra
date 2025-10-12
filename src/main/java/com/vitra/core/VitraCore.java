package com.vitra.core;

import com.vitra.config.VitraConfig;
import com.vitra.config.RendererType;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.render.DirectX12Renderer;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.D3D12ShaderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * Core configuration and management system for Vitra
 */
public class VitraCore {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraCore.class);

    private VitraConfig config;
    private IVitraRenderer renderer;
    private boolean initialized = false;
    private boolean shadersLoaded = false;

    public void initialize() {
        if (initialized) {
            LOGGER.warn("VitraCore already initialized");
            return;
        }

        LOGGER.info("Initializing Vitra core systems...");

        try {
            config = new VitraConfig(Paths.get("config"));
            LOGGER.info("Configuration loaded: {}", config.getRendererType().getDisplayName());

            // Initialize the appropriate renderer based on configuration
            initializeRenderer(config.getRendererType());

            initialized = true;
            LOGGER.info("Vitra core initialization complete - Ready for {} renderer",
                config.getRendererType().getDisplayName());

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra core", e);
            throw new RuntimeException("Vitra core initialization failed", e);
        }
    }

    /**
     * Initialize the appropriate renderer based on renderer type
     */
    private void initializeRenderer(RendererType rendererType) {
        LOGGER.info("Initializing {} renderer...", rendererType.getDisplayName());

        switch (rendererType) {
            case DIRECTX11:
                renderer = new VitraRenderer();
                renderer.setConfig(config);
                renderer.initialize(rendererType);
                break;

            case DIRECTX12:
            case DIRECTX12_ULTIMATE:
                // Initialize DirectX 12 renderer
                renderer = new DirectX12Renderer();
                renderer.setConfig(config);
                renderer.setCore(this);
                renderer.initialize(rendererType);
                break;

            default:
                throw new RuntimeException("Unsupported renderer type: " + rendererType.getDisplayName());
        }

        if (renderer == null) {
            throw new RuntimeException("Failed to initialize renderer: " + rendererType.getDisplayName());
        }

        LOGGER.info("Successfully initialized {} renderer", rendererType.getDisplayName());
    }

    /**
     * Set the renderer reference for shader loading
     */
    public void setRenderer(IVitraRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Load all shaders from resources based on the active renderer type.
     * This should be called after the renderer is initialized.
     */
    public void loadShaders() {
        if (shadersLoaded) {
            LOGGER.warn("Shaders already loaded");
            return;
        }

        if (renderer == null || !renderer.isInitialized()) {
            LOGGER.error("Cannot load shaders: Renderer not initialized");
            return;
        }

        RendererType rendererType = renderer.getRendererType();
        LOGGER.info("Loading {} shaders from resources...", rendererType.getDisplayName());

        try {
            switch (rendererType) {
                case DIRECTX11:
                    loadDirectX11Shaders();
                    break;
                case DIRECTX12:
                case DIRECTX12_ULTIMATE:
                    loadDirectX12Shaders();
                    break;
                default:
                    LOGGER.error("Unsupported renderer type for shader loading: {}", rendererType);
                    return;
            }

            shadersLoaded = true;
            LOGGER.info("Shader loading complete for {} renderer", rendererType.getDisplayName());

        } catch (Exception e) {
            LOGGER.error("Failed to load shaders", e);
        }
    }

    /**
     * Load DirectX 11 shaders
     */
    private void loadDirectX11Shaders() {
        LOGGER.debug("Loading DirectX 11 shaders...");

        D3D11ShaderManager shaderManager = (D3D11ShaderManager) renderer.getShaderManager();
        if (shaderManager == null) {
            LOGGER.error("DirectX 11 shader manager not available");
            return;
        }

        // Load basic shaders for common Minecraft rendering
        loadAndRegisterShader("basic");              // Basic position shader
        loadAndRegisterShader("position");           // Position only
        loadAndRegisterShader("position_color");     // Position + color
        loadAndRegisterShader("position_tex");       // Position + texture
        loadAndRegisterShader("position_tex_color"); // Position + texture + color
        loadAndRegisterShader("position_color_lightmap");      // With lightmap
        loadAndRegisterShader("position_color_tex_lightmap");  // Full featured
        loadAndRegisterShader("gui");                // GUI rendering
        loadAndRegisterShader("particle");           // Particle effects
        loadAndRegisterShader("terrain");            // Terrain rendering
        loadAndRegisterShader("rendertype_text");    // Text rendering
        loadAndRegisterShader("glint");              // Enchantment glint

        LOGGER.info("DirectX 11 shader loading complete: {}", shaderManager.getCacheStats());
    }

    /**
     * Load DirectX 12 shaders
     */
    private void loadDirectX12Shaders() {
        LOGGER.debug("Loading DirectX 12 shaders...");

        D3D12ShaderManager shaderManager = (D3D12ShaderManager) renderer.getShaderManager();
        if (shaderManager == null) {
            LOGGER.error("DirectX 12 shader manager not available");
            return;
        }

        // Preload all shader types
        shaderManager.preloadMinecraftShaders();

        LOGGER.info("DirectX 12 shader loading complete: {}", shaderManager.getCacheStats());
    }

    /**
     * Load and register a single shader pipeline.
     * Works with both DirectX 11 and DirectX 12 shader managers.
     */
    private void loadAndRegisterShader(String shaderName) {
        try {
            if (renderer == null) {
                LOGGER.error("Renderer not available for shader loading");
                return;
            }

            Object shaderManager = renderer.getShaderManager();
            if (shaderManager == null) {
                LOGGER.error("Shader manager not available");
                return;
            }

            // Handle different shader managers based on renderer type
            RendererType rendererType = renderer.getRendererType();
            long pipelineHandle = 0;

            switch (rendererType) {
                case DIRECTX11:
                    D3D11ShaderManager dx11ShaderManager = (D3D11ShaderManager) shaderManager;
                    pipelineHandle = dx11ShaderManager.createPipeline(shaderName);
                    break;
                case DIRECTX12:
                case DIRECTX12_ULTIMATE:
                    // DirectX 12 shaders are loaded differently - this method is mainly for DirectX 11
                    LOGGER.debug("DirectX 12 shader loading handled by preloadMinecraftShaders()");
                    return;
                default:
                    LOGGER.error("Unsupported renderer type for shader loading: {}", rendererType);
                    return;
            }

            if (pipelineHandle == 0) {
                LOGGER.warn("Failed to load shader pipeline: {}", shaderName);
                return;
            }

            LOGGER.debug("Loaded shader pipeline: {} (handle: 0x{})", shaderName, Long.toHexString(pipelineHandle));

        } catch (Exception e) {
            LOGGER.error("Exception loading shader: {}", shaderName, e);
        }
    }

    public void shutdown() {
        if (!initialized) return;

        LOGGER.info("Shutting down Vitra core...");

        if (config != null) {
            config.saveConfig();
        }

        initialized = false;
        LOGGER.info("Vitra core shutdown complete");
    }

    public VitraConfig getConfig() {
        return config;
    }

    public IVitraRenderer getRenderer() {
        return renderer;
    }

    public boolean isInitialized() {
        return initialized;
    }
}