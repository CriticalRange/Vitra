package com.vitra.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration management for Vitra mod
 * Handles loading/saving configuration from file and runtime changes
 */
public class VitraConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraConfig.class);
    private static final String CONFIG_FILE_NAME = "vitra.properties";

    private final Properties properties;
    private final Path configPath;

    // Rendering Configuration
    private RendererType rendererType = RendererType.DIRECTX11;
    private boolean vsyncEnabled = true;
    private int maxFPS = 144;
    private boolean debugMode = false;

    // Performance Optimization Configuration
    private boolean frustumCulling = true;
    private boolean asyncMeshBuilding = true;
    private boolean lodEnabled = true;
    private float lodDistance = 64.0f;
    private boolean memoryPooling = true;
    private int maxPoolSize = 1024;

    // Chunk Rendering Configuration
    private int chunkRenderDistance = 12;
    private boolean chunkBatching = true;
    private int maxChunksPerBatch = 16;

    // Entity Rendering Configuration
    private boolean entityBatching = true;
    private int maxEntitiesPerBatch = 32;
    private boolean entityCulling = true;

    public VitraConfig(Path configDirectory) {
        this.configPath = configDirectory.resolve(CONFIG_FILE_NAME);
        this.properties = new Properties();
        loadConfig();
    }

    /**
     * Load configuration from file
     */
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

    /**
     * Save configuration to file
     */
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

        // Performance optimization settings
        frustumCulling = Boolean.parseBoolean(properties.getProperty("optimization.frustumCulling", "true"));
        asyncMeshBuilding = Boolean.parseBoolean(properties.getProperty("optimization.asyncMeshBuilding", "true"));
        lodEnabled = Boolean.parseBoolean(properties.getProperty("optimization.lodEnabled", "true"));
        lodDistance = Float.parseFloat(properties.getProperty("optimization.lodDistance", "64.0"));
        memoryPooling = Boolean.parseBoolean(properties.getProperty("optimization.memoryPooling", "true"));
        maxPoolSize = Integer.parseInt(properties.getProperty("optimization.maxPoolSize", "1024"));

        // Chunk rendering settings
        chunkRenderDistance = Integer.parseInt(properties.getProperty("chunk.renderDistance", "12"));
        chunkBatching = Boolean.parseBoolean(properties.getProperty("chunk.batching", "true"));
        maxChunksPerBatch = Integer.parseInt(properties.getProperty("chunk.maxPerBatch", "16"));

        // Entity rendering settings
        entityBatching = Boolean.parseBoolean(properties.getProperty("entity.batching", "true"));
        maxEntitiesPerBatch = Integer.parseInt(properties.getProperty("entity.maxPerBatch", "32"));
        entityCulling = Boolean.parseBoolean(properties.getProperty("entity.culling", "true"));
    }

    private void saveToProperties() {
        // Rendering settings
        properties.setProperty("renderer.type", rendererType.name());
        properties.setProperty("renderer.vsync", String.valueOf(vsyncEnabled));
        properties.setProperty("renderer.maxFPS", String.valueOf(maxFPS));
        properties.setProperty("renderer.debug", String.valueOf(debugMode));

        // Performance optimization settings
        properties.setProperty("optimization.frustumCulling", String.valueOf(frustumCulling));
        properties.setProperty("optimization.asyncMeshBuilding", String.valueOf(asyncMeshBuilding));
        properties.setProperty("optimization.lodEnabled", String.valueOf(lodEnabled));
        properties.setProperty("optimization.lodDistance", String.valueOf(lodDistance));
        properties.setProperty("optimization.memoryPooling", String.valueOf(memoryPooling));
        properties.setProperty("optimization.maxPoolSize", String.valueOf(maxPoolSize));

        // Chunk rendering settings
        properties.setProperty("chunk.renderDistance", String.valueOf(chunkRenderDistance));
        properties.setProperty("chunk.batching", String.valueOf(chunkBatching));
        properties.setProperty("chunk.maxPerBatch", String.valueOf(maxChunksPerBatch));

        // Entity rendering settings
        properties.setProperty("entity.batching", String.valueOf(entityBatching));
        properties.setProperty("entity.maxPerBatch", String.valueOf(maxEntitiesPerBatch));
        properties.setProperty("entity.culling", String.valueOf(entityCulling));
    }

    // Getters and setters for all configuration properties

    public RendererType getRendererType() { return rendererType; }
    public void setRendererType(RendererType rendererType) { this.rendererType = rendererType; }

    public boolean isVsyncEnabled() { return vsyncEnabled; }
    public void setVsyncEnabled(boolean vsyncEnabled) { this.vsyncEnabled = vsyncEnabled; }

    public int getMaxFPS() { return maxFPS; }
    public void setMaxFPS(int maxFPS) { this.maxFPS = maxFPS; }

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

    public boolean isFrustumCulling() { return frustumCulling; }
    public void setFrustumCulling(boolean frustumCulling) { this.frustumCulling = frustumCulling; }

    public boolean isAsyncMeshBuilding() { return asyncMeshBuilding; }
    public void setAsyncMeshBuilding(boolean asyncMeshBuilding) { this.asyncMeshBuilding = asyncMeshBuilding; }

    public boolean isLodEnabled() { return lodEnabled; }
    public void setLodEnabled(boolean lodEnabled) { this.lodEnabled = lodEnabled; }

    public float getLodDistance() { return lodDistance; }
    public void setLodDistance(float lodDistance) { this.lodDistance = lodDistance; }

    public boolean isMemoryPooling() { return memoryPooling; }
    public void setMemoryPooling(boolean memoryPooling) { this.memoryPooling = memoryPooling; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

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
}