package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DirectX shader manager that replaces BGFX shader loading
 */
public class D3D11ShaderManager extends AbstractShaderManager {
    private final Map<String, Long> shaderCache = new ConcurrentHashMap<>();
    private final Map<String, Long> pipelineCache = new ConcurrentHashMap<>();

    public D3D11ShaderManager() {
        super(D3D11ShaderManager.class);
    }

    /**
     * Load a shader using runtime HLSL compilation from GLSL source
     *
     * @param name Shader name (e.g., "position", "position_color", "gui")
     * @param type Shader type (SHADER_TYPE_VERTEX or SHADER_TYPE_PIXEL)
     * @return Shader handle, or 0 on failure
     */
    public long loadShader(String name, int type) {
        try {
            // NEW: Load HLSL source and compile at runtime (NO MORE .cso files!)
            // Path: /assets/vitra/shaders/core/<name>/<name>.vsh or .fsh
            // This matches VulkanMod's structure where each shader has its own directory
            String shaderTypeExt = (type == VitraD3D11Renderer.SHADER_TYPE_VERTEX) ? ".vsh" : ".fsh";
            String resourcePath = "/assets/vitra/shaders/core/" + name + "/" + name + shaderTypeExt;

            InputStream is = getClass().getResourceAsStream(resourcePath);

            if (is == null) {
                // FALLBACK: Try to use position_tex_color as default shader (VulkanMod pattern)
                if (!name.equals("position_tex_color")) {
                    logger.warn("Shader source not found: {}, falling back to position_tex_color shader", resourcePath);
                    return loadShader("position_tex_color", type);
                }

                logger.error("Shader source not found: {}", resourcePath);
                return 0;
            }

            byte[] shaderBytes = is.readAllBytes();
            is.close();

            if (shaderBytes == null || shaderBytes.length == 0) {
                logger.error("Empty shader source: {}", resourcePath);
                return 0;
            }

            // CRITICAL FIX: Include shader source hash in cache key to detect changes
            // This ensures shaders are recompiled when source code changes
            int sourceHash = java.util.Arrays.hashCode(shaderBytes);
            String cacheKey = name + "_" + type + "_" + Integer.toHexString(sourceHash);

            Long cached = shaderCache.get(cacheKey);
            if (cached != null) {
                logger.info("[SHADER_CACHE_HIT] Using cached shader '{}' (hash: {})", name, Integer.toHexString(sourceHash));
                return cached;
            }

            logger.info("[SHADER_COMPILE] Loaded {} shader bytes for '{}' from {} (hash: {})",
                shaderBytes.length, name, resourcePath, Integer.toHexString(sourceHash));

            // CRITICAL FIX: Use intern() to ensure string is in string pool and prevent JVM corruption
            // JBR (JetBrains Runtime) has issues with dynamically created strings in JNI calls
            String hlslSource = new String(shaderBytes, java.nio.charset.StandardCharsets.UTF_8).intern();

            if (hlslSource.isEmpty()) {
                logger.error("Empty shader source after conversion: {}", resourcePath);
                return 0;
            }

            // Compile HLSL at runtime using D3DCompile
            logger.info("[SHADER_COMPILE] Compiling {} shader '{}' ({} chars)",
                type == VitraD3D11Renderer.SHADER_TYPE_VERTEX ? "VERTEX" : "PIXEL",
                name, hlslSource.length());

            long handle = VitraD3D11Renderer.precompileShaderForDirectX11(hlslSource, type);

            if (handle != 0) {
                shaderCache.put(cacheKey, handle);
                logger.info("[SHADER_COMPILE_OK] {} shader '{}' compiled successfully (handle: 0x{}, hash: {})",
                    type == VitraD3D11Renderer.SHADER_TYPE_VERTEX ? "VERTEX" : "PIXEL",
                    name, Long.toHexString(handle), Integer.toHexString(sourceHash));
            } else {
                logger.error("Failed to compile DirectX shader from HLSL source: {}", name);
            }

            return handle;

        } catch (IOException e) {
            logger.error("Failed to load shader source: {}", name, e);
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
        long vertexShader = loadShader(name, VitraD3D11Renderer.SHADER_TYPE_VERTEX);
        if (vertexShader == 0) {
            logger.error("Failed to load vertex shader for pipeline: {}", name);
            return 0;
        }

        // Load pixel shader (e.g., position_ps.cso)
        long pixelShader = loadShader(name, VitraD3D11Renderer.SHADER_TYPE_PIXEL);
        if (pixelShader == 0) {
            logger.warn("Failed to load pixel shader for pipeline: {}, using vertex only", name);
        }

        long pipeline = VitraD3D11Renderer.createShaderPipeline(vertexShader, pixelShader);
        if (pipeline != 0) {
            pipelineCache.put(name, pipeline);
            logger.info("Created shader pipeline: {} (VS: 0x{}, PS: 0x{}, Pipeline: 0x{})",
                name,
                Long.toHexString(vertexShader),
                Long.toHexString(pixelShader),
                Long.toHexString(pipeline));
        } else {
            logger.error("Failed to create shader pipeline: {}", name);
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
     * Preload ALL Minecraft 1.21.1 shaders
     * Total: 36 shader pairs (72 compiled binaries)
     */
    public void preloadShaders() {
        logger.info("Preloading ALL Minecraft DirectX shaders...");

        // ALL 36 Minecraft 1.21.1 core shaders
        String[] allShaders = {
            // Basic rendering
            "position", "position_color", "position_tex", "position_tex_color",
            "position_color_lightmap", "position_color_tex_lightmap",

            // Particles and effects
            "particle", "glint",

            // GUI and menus
            "gui", "blit_screen",

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
                logger.error("Failed to preload shader: {}", shader);
            }
        }

        logger.info("Preloaded {} shader pipelines ({} succeeded, {} failed)",
            allShaders.length, successCount, failCount);

        if (failCount > 0) {
            logger.warn("Some shaders failed to load. Rendering may be incomplete.");
        } else {
            logger.info("All Minecraft shaders loaded successfully!");
        }
    }


    /**
     * Get cache statistics
     */
    @Override
    public String getCacheStats() {
        return String.format("Shaders: %d, Pipelines: %d", shaderCache.size(), pipelineCache.size());
    }

    @Override
    public void clearCache() {
        // Destroy all pipelines
        for (Long pipeline : pipelineCache.values()) {
            VitraD3D11Renderer.destroyResource(pipeline);
        }
        pipelineCache.clear();

        // Destroy all shaders
        for (Long shader : shaderCache.values()) {
            VitraD3D11Renderer.destroyResource(shader);
        }
        shaderCache.clear();

        logger.info("Cleared shader cache");
    }

    @Override
    public String getRendererType() {
        return "DirectX";
    }
}