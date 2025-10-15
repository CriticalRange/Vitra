package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX 11 shader manager that replaces BGFX shader loading
 */
public class D3D11ShaderManager implements IShaderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11ShaderManager.class);

    private final Map<String, Long> shaderCache = new ConcurrentHashMap<>();
    private final Map<String, Long> pipelineCache = new ConcurrentHashMap<>();

    /**
     * Load a shader from compiled HLSL bytecode (.cso files)
     *
     * @param name Shader name (e.g., "position", "position_color")
     * @param type Shader type (SHADER_TYPE_VERTEX or SHADER_TYPE_PIXEL)
     * @return Shader handle, or 0 on failure
     */
    public long loadShader(String name, int type) {
        String cacheKey = name + "_" + type;

        Long cached = shaderCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            // Construct resource path for compiled shaders
            // Format: /shaders/compiled/<name>_vs.cso or /shaders/compiled/<name>_ps.cso
            String shaderTypeExt = (type == VitraNativeRenderer.SHADER_TYPE_VERTEX) ? "vs" : "ps";
            String resourcePath = "/shaders/compiled/" + name + "_" + shaderTypeExt + ".cso";

            InputStream is = getClass().getResourceAsStream(resourcePath);

            if (is == null) {
                LOGGER.error("Compiled shader not found: {}", resourcePath);
                return 0;
            }

            byte[] bytecode = is.readAllBytes();
            is.close();

            if (bytecode.length == 0) {
                LOGGER.error("Empty shader bytecode: {}", resourcePath);
                return 0;
            }

            long handle = VitraNativeRenderer.createGLProgramShader(bytecode, bytecode.length, type);
            if (handle != 0) {
                shaderCache.put(cacheKey, handle);
                LOGGER.debug("Loaded {} shader: {} (handle: 0x{}, size: {} bytes)",
                    type == VitraNativeRenderer.SHADER_TYPE_VERTEX ? "vertex" : "pixel",
                    name, Long.toHexString(handle), bytecode.length);
            } else {
                LOGGER.error("Failed to create DirectX shader from bytecode: {}", name);
            }

            return handle;

        } catch (IOException e) {
            LOGGER.error("Failed to load compiled shader: {}", name, e);
            return 0;
        }
    }

    /**
     * Create a shader pipeline from vertex and pixel shaders
     *
     * @param name Shader pipeline name (e.g., "position", "position_color", "position_tex")
     * @return Pipeline handle, or 0 on failure
     */
    public long createPipeline(String name) {
        Long cached = pipelineCache.get(name);
        if (cached != null) {
            return cached;
        }

        // Load vertex shader (e.g., position_vs.cso)
        long vertexShader = loadShader(name, VitraNativeRenderer.SHADER_TYPE_VERTEX);
        if (vertexShader == 0) {
            LOGGER.error("Failed to load vertex shader for pipeline: {}", name);
            return 0;
        }

        // Load pixel shader (e.g., position_ps.cso)
        long pixelShader = loadShader(name, VitraNativeRenderer.SHADER_TYPE_PIXEL);
        if (pixelShader == 0) {
            LOGGER.warn("Failed to load pixel shader for pipeline: {}, using vertex only", name);
        }

        long pipeline = VitraNativeRenderer.createShaderPipeline(vertexShader, pixelShader);
        if (pipeline != 0) {
            pipelineCache.put(name, pipeline);
            LOGGER.info("Created shader pipeline: {} (VS: 0x{}, PS: 0x{}, Pipeline: 0x{})",
                name,
                Long.toHexString(vertexShader),
                Long.toHexString(pixelShader),
                Long.toHexString(pipeline));
        } else {
            LOGGER.error("Failed to create shader pipeline: {}", name);
        }

        return pipeline;
    }

    /**
     * Get a cached shader pipeline
     */
    public long getPipeline(String name) {
        return pipelineCache.getOrDefault(name, 0L);
    }

    /**
     * Preload ALL Minecraft 1.21.8 shaders
     * Total: 36 shader pairs (72 compiled binaries)
     */
    public void preloadShaders() {
        LOGGER.info("Preloading ALL Minecraft DirectX 11 shaders...");

        // ALL 36 Minecraft 1.21.8 core shaders
        String[] allShaders = {
            // Basic rendering
            "position", "position_color", "position_tex", "position_tex_color",
            "position_color_lightmap", "position_color_tex_lightmap",

            // Particles and effects
            "particle", "glint",

            // GUI and menus
            "gui", "blit_screen", "panorama",

            // Text rendering
            "rendertype_text", "rendertype_text_background", "rendertype_text_see_through",
            "rendertype_text_background_see_through", "rendertype_text_intensity",
            "rendertype_text_intensity_see_through",

            // Entity rendering
            "entity", "rendertype_entity_alpha", "rendertype_entity_decal", "rendertype_entity_shadow",
            "rendertype_item_entity_translucent_cull",

            // Special effects
            "rendertype_beacon_beam", "rendertype_clouds", "rendertype_end_portal",
            "rendertype_crumbling", "rendertype_outline", "rendertype_lightning",
            "rendertype_lines", "rendertype_leash",

            // World rendering
            "terrain", "sky", "stars", "rendertype_translucent_moving_block",
            "rendertype_water_mask", "rendertype_world_border"
        };

        int successCount = 0;
        int failCount = 0;

        for (String shader : allShaders) {
            long pipeline = createPipeline(shader);
            if (pipeline != 0) {
                successCount++;
            } else {
                failCount++;
                LOGGER.error("Failed to preload shader: {}", shader);
            }
        }

        LOGGER.info("Preloaded {} shader pipelines ({} succeeded, {} failed)",
            allShaders.length, successCount, failCount);

        if (failCount > 0) {
            LOGGER.warn("Some shaders failed to load. Rendering may be incomplete.");
        } else {
            LOGGER.info("All Minecraft shaders loaded successfully!");
        }
    }

  
    /**
     * Get cache statistics
     */
    public String getCacheStats() {
        return String.format("Shaders: %d, Pipelines: %d", shaderCache.size(), pipelineCache.size());
    }

    // IShaderManager interface implementation

    @Override
    public void initialize() {
        LOGGER.info("Initializing DirectX 11 shader manager");
        // No specific initialization needed for DirectX 11 JNI
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down DirectX 11 shader manager");
        clearCache();
    }

    @Override
    public void clearCache() {
        // Destroy all pipelines
        for (Long pipeline : pipelineCache.values()) {
            VitraNativeRenderer.destroyResource(pipeline);
        }
        pipelineCache.clear();

        // Destroy all shaders
        for (Long shader : shaderCache.values()) {
            VitraNativeRenderer.destroyResource(shader);
        }
        shaderCache.clear();

        LOGGER.info("Cleared shader cache");
    }

    @Override
    public boolean isInitialized() {
        return true; // DirectX 11 shader manager is always ready
    }

    @Override
    public String getRendererType() {
        return "DirectX 11";
    }
}