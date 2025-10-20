package com.vitra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class VitraConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraConfig.class);
    private static final String CONFIG_FILE_NAME = "vitra.properties";

    private final Properties properties;
    private final Path configPath;

    // Rendering Configuration
    private RendererType rendererType = RendererType.DIRECTX11; // Default to DirectX 11 for stability
    private boolean vsyncEnabled = true;
    private int maxFPS = 144;

    // Debug Mode: Enables DirectX 11 debug layer (requires Windows Graphics Tools)
    // Shows native DirectX performance stats overlay in top-left corner
    // WARNING: Requires "Graphics Tools" optional feature installed on Windows 10+
    private boolean debugMode = false;

    // Verbose Logging: Enables DirectX 11 trace-level logging (very detailed, impacts performance)
    private boolean verboseLogging = false;

    // WARP Mode: Use Windows Advanced Rasterization Platform (CPU software renderer)
    // WARNING: WARP is extremely slow but provides complete debug layer validation
    // Enable this when GPU driver doesn't support debug layer messages
    private boolean useWarp = false;

    // Performance Optimization Configuration
    private boolean frustumCulling = true;
    private boolean asyncMeshBuilding = true;

    // Chunk Rendering Configuration
    private int chunkRenderDistance = 12;
    private boolean chunkBatching = true;
    private int maxChunksPerBatch = 16;

    // Entity Rendering Configuration
    private boolean entityBatching = true;
    private int maxEntitiesPerBatch = 32;
    private boolean entityCulling = true;

    // DirectX 12 Ultimate Configuration (Ray Tracing, VRS, Mesh Shaders)
    private boolean rayTracingEnabled = false;
    private boolean variableRateShadingEnabled = false;
    private boolean meshShadersEnabled = false;
    private int rayTracingQuality = 2; // 1=Low, 2=Medium, 3=High, 4=Ultra
    private float vrsTileSize = 8.0f; // Variable Rate Shading tile size

    public VitraConfig(Path configDirectory) {
        this.configPath = configDirectory.resolve(CONFIG_FILE_NAME);
        this.properties = new Properties();
        loadConfig();
    }

    public void loadConfig() {
        if (!Files.exists(configPath)) {
            LOGGER.info("Config file not found, creating default configuration at: {}", configPath);
            saveConfig();
            return;
        }

        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
            loadFromProperties();
            LOGGER.info("Configuration loaded from: {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to load configuration from: {}", configPath, e);
            saveConfig(); // Create default config
        }
    }

    public void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            saveToProperties();

            try (OutputStream output = Files.newOutputStream(configPath)) {
                properties.store(output, "Vitra Configuration - Multi-Backend Minecraft Optimization Mod");
            }
            LOGGER.info("Configuration saved to: {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration to: {}", configPath, e);
        }
    }

    private void loadFromProperties() {
        // Rendering settings
        rendererType = RendererType.valueOf(properties.getProperty("renderer.type", "DIRECTX11"));
        vsyncEnabled = Boolean.parseBoolean(properties.getProperty("renderer.vsync", "true"));
        maxFPS = Integer.parseInt(properties.getProperty("renderer.maxFPS", "144"));
        debugMode = Boolean.parseBoolean(properties.getProperty("renderer.debug", "false"));
        verboseLogging = Boolean.parseBoolean(properties.getProperty("renderer.verboseLogging", "false"));
        useWarp = Boolean.parseBoolean(properties.getProperty("renderer.useWarp", "false"));

        // Performance optimization settings
        frustumCulling = Boolean.parseBoolean(properties.getProperty("optimization.frustumCulling", "true"));
        asyncMeshBuilding = Boolean.parseBoolean(properties.getProperty("optimization.asyncMeshBuilding", "true"));

        // Chunk rendering settings
        chunkRenderDistance = Integer.parseInt(properties.getProperty("chunk.renderDistance", "12"));
        chunkBatching = Boolean.parseBoolean(properties.getProperty("chunk.batching", "true"));
        maxChunksPerBatch = Integer.parseInt(properties.getProperty("chunk.maxPerBatch", "16"));

        // Entity rendering settings
        entityBatching = Boolean.parseBoolean(properties.getProperty("entity.batching", "true"));
        maxEntitiesPerBatch = Integer.parseInt(properties.getProperty("entity.maxPerBatch", "32"));
        entityCulling = Boolean.parseBoolean(properties.getProperty("entity.culling", "true"));

        // DirectX 12 Ultimate settings
        rayTracingEnabled = Boolean.parseBoolean(properties.getProperty("dx12ultimate.rayTracing", "false"));
        variableRateShadingEnabled = Boolean.parseBoolean(properties.getProperty("dx12ultimate.variableRateShading", "false"));
        meshShadersEnabled = Boolean.parseBoolean(properties.getProperty("dx12ultimate.meshShaders", "false"));
        rayTracingQuality = Integer.parseInt(properties.getProperty("dx12ultimate.rayTracingQuality", "2"));
        vrsTileSize = Float.parseFloat(properties.getProperty("dx12ultimate.vrsTileSize", "8.0"));
    }

    private void saveToProperties() {
        // Rendering settings
        properties.setProperty("renderer.type", rendererType.name());
        properties.setProperty("renderer.vsync", String.valueOf(vsyncEnabled));
        properties.setProperty("renderer.maxFPS", String.valueOf(maxFPS));
        properties.setProperty("renderer.debug", String.valueOf(debugMode));
        properties.setProperty("renderer.verboseLogging", String.valueOf(verboseLogging));
        properties.setProperty("renderer.useWarp", String.valueOf(useWarp));

        // Performance optimization settings
        properties.setProperty("optimization.frustumCulling", String.valueOf(frustumCulling));
        properties.setProperty("optimization.asyncMeshBuilding", String.valueOf(asyncMeshBuilding));

        // Chunk rendering settings
        properties.setProperty("chunk.renderDistance", String.valueOf(chunkRenderDistance));
        properties.setProperty("chunk.batching", String.valueOf(chunkBatching));
        properties.setProperty("chunk.maxPerBatch", String.valueOf(maxChunksPerBatch));

        // Entity rendering settings
        properties.setProperty("entity.batching", String.valueOf(entityBatching));
        properties.setProperty("entity.maxPerBatch", String.valueOf(maxEntitiesPerBatch));
        properties.setProperty("entity.culling", String.valueOf(entityCulling));

        // DirectX 12 Ultimate settings
        properties.setProperty("dx12ultimate.rayTracing", String.valueOf(rayTracingEnabled));
        properties.setProperty("dx12ultimate.variableRateShading", String.valueOf(variableRateShadingEnabled));
        properties.setProperty("dx12ultimate.meshShaders", String.valueOf(meshShadersEnabled));
        properties.setProperty("dx12ultimate.rayTracingQuality", String.valueOf(rayTracingQuality));
        properties.setProperty("dx12ultimate.vrsTileSize", String.valueOf(vrsTileSize));
    }

    public RendererType getRendererType() { return rendererType; }
    public void setRendererType(RendererType rendererType) { this.rendererType = rendererType; }

    public boolean isVsyncEnabled() { return vsyncEnabled; }
    public void setVsyncEnabled(boolean vsyncEnabled) { this.vsyncEnabled = vsyncEnabled; }

    public int getMaxFPS() { return maxFPS; }
    public void setMaxFPS(int maxFPS) { this.maxFPS = maxFPS; }

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

    public boolean isVerboseLogging() { return verboseLogging; }
    public void setVerboseLogging(boolean verboseLogging) { this.verboseLogging = verboseLogging; }

    public boolean isUseWarp() { return useWarp; }
    public void setUseWarp(boolean useWarp) { this.useWarp = useWarp; }

    public boolean isFrustumCulling() { return frustumCulling; }
    public void setFrustumCulling(boolean frustumCulling) { this.frustumCulling = frustumCulling; }

    public boolean isAsyncMeshBuilding() { return asyncMeshBuilding; }
    public void setAsyncMeshBuilding(boolean asyncMeshBuilding) { this.asyncMeshBuilding = asyncMeshBuilding; }

    public int getChunkRenderDistance() { return chunkRenderDistance; }
    public void setChunkRenderDistance(int chunkRenderDistance) { this.chunkRenderDistance = chunkRenderDistance; }

    public boolean isChunkBatching() { return chunkBatching; }
    public void setChunkBatching(boolean chunkBatching) { this.chunkBatching = chunkBatching; }

    public int getMaxChunksPerBatch() { return maxChunksPerBatch; }
    public void setMaxChunksPerBatch(int maxChunksPerBatch) { this.maxChunksPerBatch = maxChunksPerBatch; }

    public boolean isEntityBatching() { return entityBatching; }
    public void setEntityBatching(boolean entityBatching) { this.entityBatching = entityBatching; }

    public int getMaxEntitiesPerBatch() { return maxEntitiesPerBatch; }
    public void setMaxEntitiesPerBatch(int maxEntitiesPerBatch) { this.maxEntitiesPerBatch = maxEntitiesPerBatch; }

    public boolean isEntityCulling() { return entityCulling; }
    public void setEntityCulling(boolean entityCulling) { this.entityCulling = entityCulling; }

    // DirectX 12 Ultimate getters and setters
    public boolean isRayTracingEnabled() { return rayTracingEnabled; }
    public void setRayTracingEnabled(boolean rayTracingEnabled) { this.rayTracingEnabled = rayTracingEnabled; }

    public boolean isVariableRateShadingEnabled() { return variableRateShadingEnabled; }
    public void setVariableRateShadingEnabled(boolean variableRateShadingEnabled) { this.variableRateShadingEnabled = variableRateShadingEnabled; }

    public boolean isMeshShadersEnabled() { return meshShadersEnabled; }
    public void setMeshShadersEnabled(boolean meshShadersEnabled) { this.meshShadersEnabled = meshShadersEnabled; }

    public int getRayTracingQuality() { return rayTracingQuality; }
    public void setRayTracingQuality(int rayTracingQuality) { this.rayTracingQuality = Math.max(1, Math.min(4, rayTracingQuality)); }

    public float getVrsTileSize() { return vrsTileSize; }
    public void setVrsTileSize(float vrsTileSize) { this.vrsTileSize = Math.max(4.0f, Math.min(32.0f, vrsTileSize)); }
}