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

    // D3D12 Advanced Configuration (inspired by VulkanMod's comprehensive config)
    private int preferredAdapter = -1; // -1 = auto-select, otherwise adapter index
    private boolean d3d12maEnabled = true; // D3D12 Memory Allocator
    private boolean memoryBudgetingEnabled = true;
    private boolean directStorageEnabled = true; // DirectStorage for fast asset loading
    private boolean hardwareDecompressionEnabled = true;
    private boolean gpuDrivenRenderingEnabled = false; // Advanced GPU-driven pipeline
    private boolean debugLayerEnabled = false; // D3D12 debug layer
    private boolean gpuValidationEnabled = false; // GPU-side validation
    private boolean resourceLeakDetectionEnabled = true;
    private boolean objectNamingEnabled = true;

    // D3D12 Performance Configuration
    private boolean asyncComputeEnabled = false; // Async compute queue usage
    private boolean multiQueueEnabled = true; // Use separate graphics/compute/copy queues
    private int frameQueueSize = 3; // Triple buffering for frame management
    private boolean descriptorHeapCachingEnabled = true;
    private boolean pipelineCachingEnabled = true;
    private boolean shaderCachingEnabled = true;
    private boolean resourceBarrierOptimization = true;

    // D3D12 Memory Configuration
    private long uploadBufferSize = 64 * 1024 * 1024; // 64MB upload buffer
    private int maxTextureUploadBatchSize = 1024; // Maximum textures per upload batch
    private boolean useD3D12MA = true; // Use D3D12 Memory Allocator
    private boolean enableMemoryDefragmentation = true;
    private long memoryBudgetWarningThreshold = 100 * 1024 * 1024; // 100MB

    // D3D12 Shader Configuration
    private String shaderModel = "6_5"; // Default to latest shader model
    private boolean enableShaderDebugging = false;
    private boolean enableShaderOptimization = true;
    private int maxShaderCompilerThreads = 4; // Parallel shader compilation
    private boolean precompileCommonShaders = true;

    // D3D12 Pipeline Configuration
    private boolean enablePipelineCaching = true;
    private int maxCachedPipelines = 1000;
    private boolean enablePipelineCompilationLogging = false;
    private boolean useRootSignature1 = true; // Use enhanced root signatures

    // D3D12 Texture Configuration
    private boolean enableTextureStreaming = true;
    private int maxStreamingTextures = 256;
    private boolean enableMipmapGeneration = true;
    private boolean enableTextureCompression = true;
    private boolean useSRGBFormats = true;

    // D3D12 Synchronization Configuration
    private boolean enableFrameLatencyTracking = true;
    private int maxFrameLatency = 3;
    private boolean enableGpuTimeouts = false;
    private long gpuTimeoutMs = 5000;

    // D3D12 Profiling Configuration
    private boolean enableProfiler = true;
    private boolean enableDetailedProfiling = false;
    private int maxProfilerSamples = 10000;
    private boolean enablePerformanceOverlay = false;
    private boolean enableGpuProfiling = false;

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

        // D3D12 Advanced Configuration
        preferredAdapter = Integer.parseInt(properties.getProperty("d3d12.preferredAdapter", "-1"));
        d3d12maEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.d3d12maEnabled", "true"));
        memoryBudgetingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.memoryBudgetingEnabled", "true"));
        directStorageEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.directStorageEnabled", "true"));
        hardwareDecompressionEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.hardwareDecompressionEnabled", "true"));
        gpuDrivenRenderingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.gpuDrivenRenderingEnabled", "false"));
        debugLayerEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.debugLayerEnabled", "false"));
        gpuValidationEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.gpuValidationEnabled", "false"));
        resourceLeakDetectionEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.resourceLeakDetectionEnabled", "true"));
        objectNamingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.objectNamingEnabled", "true"));

        // D3D12 Performance Configuration
        asyncComputeEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.asyncComputeEnabled", "false"));
        multiQueueEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.multiQueueEnabled", "true"));
        frameQueueSize = Integer.parseInt(properties.getProperty("d3d12.frameQueueSize", "3"));
        descriptorHeapCachingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.descriptorHeapCachingEnabled", "true"));
        pipelineCachingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.pipelineCachingEnabled", "true"));
        shaderCachingEnabled = Boolean.parseBoolean(properties.getProperty("d3d12.shaderCachingEnabled", "true"));
        resourceBarrierOptimization = Boolean.parseBoolean(properties.getProperty("d3d12.resourceBarrierOptimization", "true"));

        // D3D12 Memory Configuration
        uploadBufferSize = Long.parseLong(properties.getProperty("d3d12.uploadBufferSize", String.valueOf(64L * 1024 * 1024)));
        maxTextureUploadBatchSize = Integer.parseInt(properties.getProperty("d3d12.maxTextureUploadBatchSize", "1024"));
        useD3D12MA = Boolean.parseBoolean(properties.getProperty("d3d12.useD3D12MA", "true"));
        enableMemoryDefragmentation = Boolean.parseBoolean(properties.getProperty("d3d12.enableMemoryDefragmentation", "true"));
        memoryBudgetWarningThreshold = Long.parseLong(properties.getProperty("d3d12.memoryBudgetWarningThreshold", String.valueOf(100L * 1024 * 1024)));

        // D3D12 Shader Configuration
        shaderModel = properties.getProperty("d3d12.shaderModel", "6_5");
        enableShaderDebugging = Boolean.parseBoolean(properties.getProperty("d3d12.enableShaderDebugging", "false"));
        enableShaderOptimization = Boolean.parseBoolean(properties.getProperty("d3d12.enableShaderOptimization", "true"));
        maxShaderCompilerThreads = Integer.parseInt(properties.getProperty("d3d12.maxShaderCompilerThreads", "4"));
        precompileCommonShaders = Boolean.parseBoolean(properties.getProperty("d3d12.precompileCommonShaders", "true"));

        // D3D12 Pipeline Configuration
        enablePipelineCaching = Boolean.parseBoolean(properties.getProperty("d3d12.enablePipelineCaching", "true"));
        maxCachedPipelines = Integer.parseInt(properties.getProperty("d3d12.maxCachedPipelines", "1000"));
        enablePipelineCompilationLogging = Boolean.parseBoolean(properties.getProperty("d3d12.enablePipelineCompilationLogging", "false"));
        useRootSignature1 = Boolean.parseBoolean(properties.getProperty("d3d12.useRootSignature1", "true"));

        // D3D12 Texture Configuration
        enableTextureStreaming = Boolean.parseBoolean(properties.getProperty("d3d12.enableTextureStreaming", "true"));
        maxStreamingTextures = Integer.parseInt(properties.getProperty("d3d12.maxStreamingTextures", "256"));
        enableMipmapGeneration = Boolean.parseBoolean(properties.getProperty("d3d12.enableMipmapGeneration", "true"));
        enableTextureCompression = Boolean.parseBoolean(properties.getProperty("d3d12.enableTextureCompression", "true"));
        useSRGBFormats = Boolean.parseBoolean(properties.getProperty("d3d12.useSRGBFormats", "true"));

        // D3D12 Synchronization Configuration
        enableFrameLatencyTracking = Boolean.parseBoolean(properties.getProperty("d3d12.enableFrameLatencyTracking", "true"));
        maxFrameLatency = Integer.parseInt(properties.getProperty("d3d12.maxFrameLatency", "3"));
        enableGpuTimeouts = Boolean.parseBoolean(properties.getProperty("d3d12.enableGpuTimeouts", "false"));
        gpuTimeoutMs = Long.parseLong(properties.getProperty("d3d12.gpuTimeoutMs", "5000"));

        // D3D12 Profiling Configuration
        enableProfiler = Boolean.parseBoolean(properties.getProperty("d3d12.enableProfiler", "true"));
        enableDetailedProfiling = Boolean.parseBoolean(properties.getProperty("d3d12.enableDetailedProfiling", "false"));
        maxProfilerSamples = Integer.parseInt(properties.getProperty("d3d12.maxProfilerSamples", "10000"));
        enablePerformanceOverlay = Boolean.parseBoolean(properties.getProperty("d3d12.enablePerformanceOverlay", "false"));
        enableGpuProfiling = Boolean.parseBoolean(properties.getProperty("d3d12.enableGpuProfiling", "false"));

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

        // D3D12 Advanced Configuration
        properties.setProperty("d3d12.preferredAdapter", String.valueOf(preferredAdapter));
        properties.setProperty("d3d12.d3d12maEnabled", String.valueOf(d3d12maEnabled));
        properties.setProperty("d3d12.memoryBudgetingEnabled", String.valueOf(memoryBudgetingEnabled));
        properties.setProperty("d3d12.directStorageEnabled", String.valueOf(directStorageEnabled));
        properties.setProperty("d3d12.hardwareDecompressionEnabled", String.valueOf(hardwareDecompressionEnabled));
        properties.setProperty("d3d12.gpuDrivenRenderingEnabled", String.valueOf(gpuDrivenRenderingEnabled));
        properties.setProperty("d3d12.debugLayerEnabled", String.valueOf(debugLayerEnabled));
        properties.setProperty("d3d12.gpuValidationEnabled", String.valueOf(gpuValidationEnabled));
        properties.setProperty("d3d12.resourceLeakDetectionEnabled", String.valueOf(resourceLeakDetectionEnabled));
        properties.setProperty("d3d12.objectNamingEnabled", String.valueOf(objectNamingEnabled));

        // D3D12 Performance Configuration
        properties.setProperty("d3d12.asyncComputeEnabled", String.valueOf(asyncComputeEnabled));
        properties.setProperty("d3d12.multiQueueEnabled", String.valueOf(multiQueueEnabled));
        properties.setProperty("d3d12.frameQueueSize", String.valueOf(frameQueueSize));
        properties.setProperty("d3d12.descriptorHeapCachingEnabled", String.valueOf(descriptorHeapCachingEnabled));
        properties.setProperty("d3d12.pipelineCachingEnabled", String.valueOf(pipelineCachingEnabled));
        properties.setProperty("d3d12.shaderCachingEnabled", String.valueOf(shaderCachingEnabled));
        properties.setProperty("d3d12.resourceBarrierOptimization", String.valueOf(resourceBarrierOptimization));

        // D3D12 Memory Configuration
        properties.setProperty("d3d12.uploadBufferSize", String.valueOf(uploadBufferSize));
        properties.setProperty("d3d12.maxTextureUploadBatchSize", String.valueOf(maxTextureUploadBatchSize));
        properties.setProperty("d3d12.useD3D12MA", String.valueOf(useD3D12MA));
        properties.setProperty("d3d12.enableMemoryDefragmentation", String.valueOf(enableMemoryDefragmentation));
        properties.setProperty("d3d12.memoryBudgetWarningThreshold", String.valueOf(memoryBudgetWarningThreshold));

        // D3D12 Shader Configuration
        properties.setProperty("d3d12.shaderModel", shaderModel);
        properties.setProperty("d3d12.enableShaderDebugging", String.valueOf(enableShaderDebugging));
        properties.setProperty("d3d12.enableShaderOptimization", String.valueOf(enableShaderOptimization));
        properties.setProperty("d3d12.maxShaderCompilerThreads", String.valueOf(maxShaderCompilerThreads));
        properties.setProperty("d3d12.precompileCommonShaders", String.valueOf(precompileCommonShaders));

        // D3D12 Pipeline Configuration
        properties.setProperty("d3d12.enablePipelineCaching", String.valueOf(enablePipelineCaching));
        properties.setProperty("d3d12.maxCachedPipelines", String.valueOf(maxCachedPipelines));
        properties.setProperty("d3d12.enablePipelineCompilationLogging", String.valueOf(enablePipelineCompilationLogging));
        properties.setProperty("d3d12.useRootSignature1", String.valueOf(useRootSignature1));

        // D3D12 Texture Configuration
        properties.setProperty("d3d12.enableTextureStreaming", String.valueOf(enableTextureStreaming));
        properties.setProperty("d3d12.maxStreamingTextures", String.valueOf(maxStreamingTextures));
        properties.setProperty("d3d12.enableMipmapGeneration", String.valueOf(enableMipmapGeneration));
        properties.setProperty("d3d12.enableTextureCompression", String.valueOf(enableTextureCompression));
        properties.setProperty("d3d12.useSRGBFormats", String.valueOf(useSRGBFormats));

        // D3D12 Synchronization Configuration
        properties.setProperty("d3d12.enableFrameLatencyTracking", String.valueOf(enableFrameLatencyTracking));
        properties.setProperty("d3d12.maxFrameLatency", String.valueOf(maxFrameLatency));
        properties.setProperty("d3d12.enableGpuTimeouts", String.valueOf(enableGpuTimeouts));
        properties.setProperty("d3d12.gpuTimeoutMs", String.valueOf(gpuTimeoutMs));

        // D3D12 Profiling Configuration
        properties.setProperty("d3d12.enableProfiler", String.valueOf(enableProfiler));
        properties.setProperty("d3d12.enableDetailedProfiling", String.valueOf(enableDetailedProfiling));
        properties.setProperty("d3d12.maxProfilerSamples", String.valueOf(maxProfilerSamples));
        properties.setProperty("d3d12.enablePerformanceOverlay", String.valueOf(enablePerformanceOverlay));
        properties.setProperty("d3d12.enableGpuProfiling", String.valueOf(enableGpuProfiling));
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

    // D3D12 Advanced Configuration getters and setters
    public int getPreferredAdapter() { return preferredAdapter; }
    public void setPreferredAdapter(int preferredAdapter) { this.preferredAdapter = preferredAdapter; }

    public boolean isD3D12MAEnabled() { return d3d12maEnabled; }
    public void setD3D12MAEnabled(boolean d3d12maEnabled) { this.d3d12maEnabled = d3d12maEnabled; }

    public boolean isMemoryBudgetingEnabled() { return memoryBudgetingEnabled; }
    public void setMemoryBudgetingEnabled(boolean memoryBudgetingEnabled) { this.memoryBudgetingEnabled = memoryBudgetingEnabled; }

    public boolean isDirectStorageEnabled() { return directStorageEnabled; }
    public void setDirectStorageEnabled(boolean directStorageEnabled) { this.directStorageEnabled = directStorageEnabled; }

    public boolean isHardwareDecompressionEnabled() { return hardwareDecompressionEnabled; }
    public void setHardwareDecompressionEnabled(boolean hardwareDecompressionEnabled) { this.hardwareDecompressionEnabled = hardwareDecompressionEnabled; }

    public boolean isGpuDrivenRenderingEnabled() { return gpuDrivenRenderingEnabled; }
    public void setGpuDrivenRenderingEnabled(boolean gpuDrivenRenderingEnabled) { this.gpuDrivenRenderingEnabled = gpuDrivenRenderingEnabled; }

    public boolean isDebugLayerEnabled() { return debugLayerEnabled; }
    public void setDebugLayerEnabled(boolean debugLayerEnabled) { this.debugLayerEnabled = debugLayerEnabled; }

    public boolean isGpuValidationEnabled() { return gpuValidationEnabled; }
    public void setGpuValidationEnabled(boolean gpuValidationEnabled) { this.gpuValidationEnabled = gpuValidationEnabled; }

    public boolean isResourceLeakDetectionEnabled() { return resourceLeakDetectionEnabled; }
    public void setResourceLeakDetectionEnabled(boolean resourceLeakDetectionEnabled) { this.resourceLeakDetectionEnabled = resourceLeakDetectionEnabled; }

    public boolean isObjectNamingEnabled() { return objectNamingEnabled; }
    public void setObjectNamingEnabled(boolean objectNamingEnabled) { this.objectNamingEnabled = objectNamingEnabled; }

    // D3D12 Performance Configuration getters and setters
    public boolean isAsyncComputeEnabled() { return asyncComputeEnabled; }
    public void setAsyncComputeEnabled(boolean asyncComputeEnabled) { this.asyncComputeEnabled = asyncComputeEnabled; }

    public boolean isMultiQueueEnabled() { return multiQueueEnabled; }
    public void setMultiQueueEnabled(boolean multiQueueEnabled) { this.multiQueueEnabled = multiQueueEnabled; }

    public int getFrameQueueSize() { return frameQueueSize; }
    public void setFrameQueueSize(int frameQueueSize) { this.frameQueueSize = Math.max(2, Math.min(4, frameQueueSize)); }

    public boolean isDescriptorHeapCachingEnabled() { return descriptorHeapCachingEnabled; }
    public void setDescriptorHeapCachingEnabled(boolean descriptorHeapCachingEnabled) { this.descriptorHeapCachingEnabled = descriptorHeapCachingEnabled; }

    public boolean isPipelineCachingEnabled() { return pipelineCachingEnabled; }
    public void setPipelineCachingEnabled(boolean pipelineCachingEnabled) { this.pipelineCachingEnabled = pipelineCachingEnabled; }

    public boolean isShaderCachingEnabled() { return shaderCachingEnabled; }
    public void setShaderCachingEnabled(boolean shaderCachingEnabled) { this.shaderCachingEnabled = shaderCachingEnabled; }

    public boolean isResourceBarrierOptimizationEnabled() { return resourceBarrierOptimization; }
    public void setResourceBarrierOptimizationEnabled(boolean resourceBarrierOptimization) { this.resourceBarrierOptimization = resourceBarrierOptimization; }

    // D3D12 Memory Configuration getters and setters
    public long getUploadBufferSize() { return uploadBufferSize; }
    public void setUploadBufferSize(long uploadBufferSize) { this.uploadBufferSize = uploadBufferSize; }

    public int getMaxTextureUploadBatchSize() { return maxTextureUploadBatchSize; }
    public void setMaxTextureUploadBatchSize(int maxTextureUploadBatchSize) { this.maxTextureUploadBatchSize = maxTextureUploadBatchSize; }

    public boolean isUseD3D12MA() { return useD3D12MA; }
    public void setUseD3D12MA(boolean useD3D12MA) { this.useD3D12MA = useD3D12MA; }

    public boolean isEnableMemoryDefragmentation() { return enableMemoryDefragmentation; }
    public void setEnableMemoryDefragmentation(boolean enableMemoryDefragmentation) { this.enableMemoryDefragmentation = enableMemoryDefragmentation; }

    public long getMemoryBudgetWarningThreshold() { return memoryBudgetWarningThreshold; }
    public void setMemoryBudgetWarningThreshold(long memoryBudgetWarningThreshold) { this.memoryBudgetWarningThreshold = memoryBudgetWarningThreshold; }

    // D3D12 Shader Configuration getters and setters
    public String getShaderModel() { return shaderModel; }
    public void setShaderModel(String shaderModel) { this.shaderModel = shaderModel; }

    public boolean isEnableShaderDebugging() { return enableShaderDebugging; }
    public void setEnableShaderDebugging(boolean enableShaderDebugging) { this.enableShaderDebugging = enableShaderDebugging; }

    public boolean isEnableShaderOptimization() { return enableShaderOptimization; }
    public void setEnableShaderOptimization(boolean enableShaderOptimization) { this.enableShaderOptimization = enableShaderOptimization; }

    public int getMaxShaderCompilerThreads() { return maxShaderCompilerThreads; }
    public void setMaxShaderCompilerThreads(int maxShaderCompilerThreads) { this.maxShaderCompilerThreads = maxShaderCompilerThreads; }

    public boolean isPrecompileCommonShaders() { return precompileCommonShaders; }
    public void setPrecompileCommonShaders(boolean precompileCommonShaders) { this.precompileCommonShaders = precompileCommonShaders; }

    // D3D12 Pipeline Configuration getters and setters
    public boolean isEnablePipelineCaching() { return enablePipelineCaching; }
    public void setEnablePipelineCaching(boolean enablePipelineCaching) { this.enablePipelineCaching = enablePipelineCaching; }

    public int getMaxCachedPipelines() { return maxCachedPipelines; }
    public void setMaxCachedPipelines(int maxCachedPipelines) { this.maxCachedPipelines = maxCachedPipelines; }

    public boolean isEnablePipelineCompilationLogging() { return enablePipelineCompilationLogging; }
    public void setEnablePipelineCompilationLogging(boolean enablePipelineCompilationLogging) { this.enablePipelineCompilationLogging = enablePipelineCompilationLogging; }

    public boolean isUseRootSignature1() { return useRootSignature1; }
    public void setUseRootSignature1(boolean useRootSignature1) { this.useRootSignature1 = useRootSignature1; }

    // D3D12 Texture Configuration getters and setters
    public boolean isEnableTextureStreaming() { return enableTextureStreaming; }
    public void setEnableTextureStreaming(boolean enableTextureStreaming) { this.enableTextureStreaming = enableTextureStreaming; }

    public int getMaxStreamingTextures() { return maxStreamingTextures; }
    public void setMaxStreamingTextures(int maxStreamingTextures) { this.maxStreamingTextures = maxStreamingTextures; }

    public boolean isEnableMipmapGeneration() { return enableMipmapGeneration; }
    public void setEnableMipmapGeneration(boolean enableMipmapGeneration) { this.enableMipmapGeneration = enableMipmapGeneration; }

    public boolean isEnableTextureCompression() { return enableTextureCompression; }
    public void setEnableTextureCompression(boolean enableTextureCompression) { this.enableTextureCompression = enableTextureCompression; }

    public boolean isUseSRGBFormats() { return useSRGBFormats; }
    public void setUseSRGBFormats(boolean useSRGBFormats) { this.useSRGBFormats = useSRGBFormats; }

    // D3D12 Synchronization Configuration getters and setters
    public boolean isEnableFrameLatencyTracking() { return enableFrameLatencyTracking; }
    public void setEnableFrameLatencyTracking(boolean enableFrameLatencyTracking) { this.enableFrameLatencyTracking = enableFrameLatencyTracking; }

    public int getMaxFrameLatency() { return maxFrameLatency; }
    public void setMaxFrameLatency(int maxFrameLatency) { this.maxFrameLatency = maxFrameLatency; }

    public boolean isEnableGpuTimeouts() { return enableGpuTimeouts; }
    public void setEnableGpuTimeouts(boolean enableGpuTimeouts) { this.enableGpuTimeouts = enableGpuTimeouts; }

    public long getGpuTimeoutMs() { return gpuTimeoutMs; }
    public void setGpuTimeoutMs(long gpuTimeoutMs) { this.gpuTimeoutMs = gpuTimeoutMs; }

    // D3D12 Profiling Configuration getters and setters
    public boolean isEnableProfiler() { return enableProfiler; }
    public void setEnableProfiler(boolean enableProfiler) { this.enableProfiler = enableProfiler; }

    public boolean isEnableDetailedProfiling() { return enableDetailedProfiling; }
    public void setEnableDetailedProfiling(boolean enableDetailedProfiling) { this.enableDetailedProfiling = enableDetailedProfiling; }

    public int getMaxProfilerSamples() { return maxProfilerSamples; }
    public void setMaxProfilerSamples(int maxProfilerSamples) { this.maxProfilerSamples = maxProfilerSamples; }

    public boolean isEnablePerformanceOverlay() { return enablePerformanceOverlay; }
    public void setEnablePerformanceOverlay(boolean enablePerformanceOverlay) { this.enablePerformanceOverlay = enablePerformanceOverlay; }

    public boolean isEnableGpuProfiling() { return enableGpuProfiling; }
    public void setEnableGpuProfiling(boolean enableGpuProfiling) { this.enableGpuProfiling = enableGpuProfiling; }

    /**
     * Get D3D12 configuration summary
     */
    public String getD3D12ConfigurationSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== D3D12 Configuration ===\n");
        summary.append("Preferred Adapter: ").append(preferredAdapter).append("\n");
        summary.append("D3D12MA Enabled: ").append(d3d12maEnabled).append("\n");
        summary.append("Memory Budgeting: ").append(memoryBudgetingEnabled).append("\n");
        summary.append("DirectStorage: ").append(directStorageEnabled).append("\n");
        summary.append("Debug Layer: ").append(debugLayerEnabled).append("\n");
        summary.append("Shader Model: ").append(shaderModel).append("\n");
        summary.append("Async Compute: ").append(asyncComputeEnabled).append("\n");
        summary.append("Multi-Queue: ").append(multiQueueEnabled).append("\n");
        summary.append("Pipeline Caching: ").append(pipelineCachingEnabled).append("\n");
        summary.append("Texture Streaming: ").append(enableTextureStreaming).append("\n");
        summary.append("Profiler Enabled: ").append(enableProfiler).append("\n");
        return summary.toString();
    }
}