package com.vitra.core;

import com.vitra.config.VitraConfig;
import com.vitra.render.bgfx.BgfxManagers;
import com.vitra.render.bgfx.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * Core configuration and management system for Vitra
 */
public class VitraCore {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraCore.class);

    private VitraConfig config;
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
            LOGGER.info("Configuration loaded");

            initialized = true;
            LOGGER.info("Vitra core initialization complete");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vitra core", e);
            throw new RuntimeException("Vitra core initialization failed", e);
        }
    }

    /**
     * Load all BGFX shaders from resources.
     * This should be called after BGFX is initialized.
     *
     * Uses ONLY BGFX native methods via Util:
     * - Util.loadProgram() -> bgfx_create_program() -> bgfx_create_shader()
     */
    public void loadShaders() {
        if (shadersLoaded) {
            LOGGER.warn("Shaders already loaded");
            return;
        }

        if (!Util.isInitialized()) {
            LOGGER.error("Cannot load shaders: BGFX not initialized");
            return;
        }

        LOGGER.info("Loading BGFX shaders from resources...");

        try {
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

            shadersLoaded = true;
            LOGGER.info("Shader loading complete");

        } catch (Exception e) {
            LOGGER.error("Failed to load shaders", e);
        }
    }

    /**
     * Load and register a single shader program.
     * Uses Util.loadProgram() which calls BGFX native methods directly.
     */
    private void loadAndRegisterShader(String shaderName) {
        try {
            // Load program using BGFX native methods
            // Util.loadProgram() -> loads vs_ and fs_ shaders -> bgfx_create_program()
            short programHandle = Util.loadProgram(shaderName);

            if (!Util.isValidHandle(programHandle)) {
                LOGGER.warn("Failed to load shader program: {}", shaderName);
                return;
            }

            // Register with shader manager
            BgfxManagers.getShaderManager().registerProgram(shaderName, programHandle);

            LOGGER.debug("Loaded shader: {} (handle: {})", shaderName, programHandle);

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

    public boolean isInitialized() {
        return initialized;
    }
}