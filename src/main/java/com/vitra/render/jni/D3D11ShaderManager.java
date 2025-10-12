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
     * Load a shader from HLSL bytecode (Windows DirectX)
     */
    public long loadShader(String name, int type) {
        String cacheKey = name + "_" + type;

        Long cached = shaderCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String resourcePath = "/shaders/dx11/" + name + ((type == VitraNativeRenderer.SHADER_TYPE_VERTEX) ? ".vs" : ".ps") + ".bin";
            InputStream is = getClass().getResourceAsStream(resourcePath);

            if (is == null) {
                LOGGER.error("Shader resource not found: {}", resourcePath);
                return 0;
            }

            byte[] bytecode = is.readAllBytes();
            is.close();

            if (bytecode.length == 0) {
                LOGGER.error("Empty shader file: {}", resourcePath);
                return 0;
            }

            long handle = VitraNativeRenderer.createShader(bytecode, bytecode.length, type);
            if (handle != 0) {
                shaderCache.put(cacheKey, handle);
                LOGGER.debug("Loaded {} shader: {} (handle: 0x{})",
                    type == VitraNativeRenderer.SHADER_TYPE_VERTEX ? "vertex" : "pixel",
                    name, Long.toHexString(handle));
            } else {
                LOGGER.error("Failed to create shader: {}", name);
            }

            return handle;

        } catch (IOException e) {
            LOGGER.error("Failed to load shader: {}", name, e);
            return 0;
        }
    }

    /**
     * Create a shader pipeline from vertex and pixel shaders
     */
    public long createPipeline(String name) {
        Long cached = pipelineCache.get(name);
        if (cached != null) {
            return cached;
        }

        long vertexShader = loadShader("vs_" + name, VitraNativeRenderer.SHADER_TYPE_VERTEX);
        if (vertexShader == 0) {
            LOGGER.error("Failed to load vertex shader for pipeline: {}", name);
            return 0;
        }

        long pixelShader = loadShader("fs_" + name, VitraNativeRenderer.SHADER_TYPE_PIXEL);
        if (pixelShader == 0) {
            LOGGER.warn("Failed to load pixel shader for pipeline: {}, using vertex only", name);
        }

        long pipeline = VitraNativeRenderer.createShaderPipeline(vertexShader, pixelShader);
        if (pipeline != 0) {
            pipelineCache.put(name, pipeline);
            LOGGER.debug("Created shader pipeline: {} (handle: 0x{})", name, Long.toHexString(pipeline));
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
     * Preload common shaders
     */
    public void preloadShaders() {
        LOGGER.info("Preloading DirectX shaders...");

        String[] commonShaders = {
            "basic", "texture", "colored", "debug"
        };

        for (String shader : commonShaders) {
            createPipeline(shader);
        }

        LOGGER.info("Preloaded {} shader pipelines", pipelineCache.size());
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