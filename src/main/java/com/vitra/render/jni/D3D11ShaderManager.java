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
    
    // Cache for include file contents to avoid re-reading from JAR
    private final Map<String, String> includeCache = new ConcurrentHashMap<>();

    public D3D11ShaderManager() {
        super(D3D11ShaderManager.class);
    }

    /**
     * Load a shader, prioritizing precompiled .cso files from build-time compilation.
     * Falls back to runtime HLSL compilation if precompiled shader is not available.
     *
     * @param name Shader name (e.g., "position", "position_color", "gui")
     * @param type Shader type (SHADER_TYPE_VERTEX or SHADER_TYPE_PIXEL)
     * @return Shader handle, or 0 on failure
     */
    public long loadShader(String name, int type) {
        try {
            // Build cache key based on shader name and type
            String typeStr = (type == VitraD3D11Renderer.SHADER_TYPE_VERTEX) ? "vs" : "ps";
            String cacheKey = name + "_" + typeStr;
            
            Long cached = shaderCache.get(cacheKey);
            if (cached != null) {
                logger.debug("[SHADER_CACHE_HIT] Using cached shader '{}' (type: {})", name, typeStr);
                return cached;
            }

            // PRIORITY 1: Try to load precompiled .cso file (build-time compiled)
            String csoPath = "/assets/vitra/shaders/compiled/" + name + "_" + typeStr + ".cso";
            InputStream csoStream = getClass().getResourceAsStream(csoPath);
            
            if (csoStream != null) {
                byte[] bytecode = csoStream.readAllBytes();
                csoStream.close();
                
                if (bytecode != null && bytecode.length > 0) {
                    logger.info("[SHADER_LOAD] Loading precompiled shader '{}' from {} ({} bytes)", 
                        name, csoPath, bytecode.length);
                    
                    // Create shader from precompiled bytecode
                    long handle = VitraD3D11Renderer.createShaderFromBytecode(bytecode, type);
                    
                    if (handle != 0) {
                        shaderCache.put(cacheKey, handle);
                        logger.info("[SHADER_LOAD_OK] {} shader '{}' loaded successfully (handle: 0x{})",
                            type == VitraD3D11Renderer.SHADER_TYPE_VERTEX ? "VERTEX" : "PIXEL",
                            name, Long.toHexString(handle));
                        return handle;
                    } else {
                        // Bytecode shader creation failed - likely corrupted .cso or D3D11 issue
                        logger.error("[SHADER_LOAD_FAIL] Failed to create shader from bytecode: {} - D3D11 CreateShader failed", name);
                        logger.error("[SHADER_LOAD_FAIL] This may be due to corrupted .cso file or D3D11 device state");
                        // DO NOT fall back to runtime compilation - it crashes the JVM!
                        // Just return 0 for now, we need to fix the bytecode loading
                        return 0;
                    }
                }
            }

            // DISABLED: Runtime HLSL compilation (D3DCompile) causes JVM crashes
            // If we get here without a precompiled shader, just fail gracefully
            logger.error("[SHADER_LOAD_FAIL] No precompiled shader found for '{}' and runtime compilation is disabled", name);
            logger.error("[SHADER_LOAD_FAIL] Please ensure shaders are compiled during build (gradlew compileShaders)");
            return 0;

            /* DANGEROUS - DISABLED: Runtime compilation crashes JVM
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

            String rawSource = new String(shaderBytes, java.nio.charset.StandardCharsets.UTF_8);
            
            // CRITICAL FIX: Process #include directives since D3DCompile can't access JAR resources
            String hlslSource = preprocessIncludes(rawSource, "/assets/vitra/shaders/core/");
            
            if (hlslSource.isEmpty()) {
                logger.error("Empty shader source after conversion: {}", resourcePath);
                return 0;
            }
            
            // Log if shader is very large (potential issue)
            if (hlslSource.length() > 50000) {
                logger.warn("Warning: Shader '{}' is very large ({} chars), may cause issues", name, hlslSource.length());
            }

            // Compile HLSL at runtime using D3DCompile
            logger.info("[SHADER_COMPILE] Compiling {} shader '{}' ({} chars) - FALLBACK MODE",
                type == VitraD3D11Renderer.SHADER_TYPE_VERTEX ? "VERTEX" : "PIXEL",
                name, hlslSource.length());

            long handle = VitraD3D11Renderer.precompileShaderForDirectX11(hlslSource, type);

            if (handle != 0) {
                shaderCache.put(cacheKey, handle);
                logger.info("[SHADER_COMPILE_OK] {} shader '{}' compiled successfully (handle: 0x{})",
                    type == VitraD3D11Renderer.SHADER_TYPE_VERTEX ? "VERTEX" : "PIXEL",
                    name, Long.toHexString(handle));
            } else {
                logger.error("Failed to compile DirectX shader from HLSL source: {}", name);
            }

            return handle;
            */

        } catch (IOException e) {
            logger.error("Failed to load shader source: {}", name, e);
            return 0;
        }
    }
    
    /**
     * Preprocesses shader source to resolve #include directives
     * D3DCompile's standard file include handler can't access JAR resources,
     * so we need to inline includes manually.
     * 
     * @param source Shader source code
     * @param basePath Base path for includes (e.g., "/assets/vitra/shaders/core/")
     * @return Preprocessed source with includes inlined
     */
    private String preprocessIncludes(String source, String basePath) {
        StringBuilder result = new StringBuilder();
        String[] lines = source.split("\n");
        
        java.util.Set<String> includedFiles = new java.util.HashSet<>();
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                // Parse #include "filename" or #include <filename>
                int start = trimmed.indexOf('"');
                int end = trimmed.lastIndexOf('"');
                if (start == -1 || end == -1 || start >= end) {
                    start = trimmed.indexOf('<');
                    end = trimmed.lastIndexOf('>');
                }
                
                if (start != -1 && end != -1 && start < end) {
                    String includeFile = trimmed.substring(start + 1, end);
                    
                    // Prevent infinite recursion
                    if (includedFiles.contains(includeFile)) {
                        result.append("// Already included: ").append(includeFile).append("\n");
                        continue;
                    }
                    includedFiles.add(includeFile);
                    
                    // Check include cache first
                    String includeContent = includeCache.get(includeFile);
                    
                    if (includeContent == null) {
                        // Try multiple paths to find the include file
                        String[] searchPaths = {
                            basePath + includeFile,                    // Direct path
                            basePath + "../" + includeFile,            // Parent directory (for common includes)
                            "/assets/vitra/shaders/core/" + includeFile,  // Core shaders
                            "/assets/vitra/shaders/hlsl/" + includeFile   // HLSL folder
                        };
                        
                        for (String searchPath : searchPaths) {
                            try (InputStream includeStream = getClass().getResourceAsStream(searchPath)) {
                                if (includeStream != null) {
                                    byte[] includeBytes = includeStream.readAllBytes();
                                    includeContent = new String(includeBytes, java.nio.charset.StandardCharsets.UTF_8);
                                    // Cache the include content for future use
                                    includeCache.put(includeFile, includeContent);
                                    logger.debug("Loaded and cached include file: {} from {}", includeFile, searchPath);
                                    break;
                                }
                            } catch (IOException e) {
                                // Try next path
                            }
                        }
                    }
                    
                    if (includeContent != null) {
                        // Recursively process includes in the included file
                        String processedInclude = preprocessIncludes(includeContent, basePath);
                        result.append("// BEGIN INCLUDE: ").append(includeFile).append("\n");
                        result.append(processedInclude);
                        result.append("// END INCLUDE: ").append(includeFile).append("\n");
                    } else {
                        logger.warn("Include file not found: {}", includeFile);
                        result.append("// ERROR: Include not found: ").append(includeFile).append("\n");
                    }
                } else {
                    result.append(line).append("\n");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        
        return result.toString();
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