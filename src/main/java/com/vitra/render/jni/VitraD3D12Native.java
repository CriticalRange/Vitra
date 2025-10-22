package com.vitra.render.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 12 native interface matching DirectX 11 pattern
 * Provides DirectX 12 backend methods without duplicates
 */
public class VitraD3D12Native {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12Native");

    // Core initialization methods
    public static native boolean initializeDirectX12(long windowHandle, int width, int height, boolean debugMode);

    // Core device management
    public static native boolean isInitialized();
    public static native void shutdown();
    public static native void beginFrame();
    public static native void endFrame();
    public static native void presentFrame();
    public static native void resize(int width, int height);
    public static native void clear(float r, float g, float b, float a);
    public static native void clearDepthBuffer(float depth);
    public static native void waitForIdle();
    public static native void waitForGpuCommands();

    // Device and context access
    public static native long getDevice();
    public static native long getContext();
    public static native long getSwapChain();
    public static native int getMaxTextureSize();

    // Shader management
    public static native long createShader(byte[] shaderBytecode, int type);
    public static native void setShader(long shaderHandle);
    public static native void setShaderPipeline(long pipelineHandle);
    public static native long getDefaultShaderPipeline();
    public static native boolean isShaderCompiled(long shaderHandle);
    public static native String getShaderCompileLog(long shaderHandle);

    // Texture management
    public static native long createTexture(int width, int height, int format, int type, byte[] data);
    public static native void bindTexture(long textureHandle, int unit);
    public static native void setTextureData(long textureHandle, int width, int height, int format, byte[] data);
    public static native void generateMipmaps(long textureHandle);

    // Buffer management
    public static native long createVertexBuffer(int size, int usage);
    public static native long createIndexBuffer(int size, int usage);
    public static native void setBufferData(long bufferHandle, byte[] data);

    // Performance and debug methods
    public static native boolean enableDebugLayer(boolean enabled);
    public static native void setDebugMode(boolean enabled);
    public static native boolean isDebugEnabled();
    public static native void processDebugMessages();
    public static native String getDebugStats();
    public static native void captureFrame(String filename);
    public static native float getGpuUtilization();
    public static native long getFrameTime();
    public static native int getDrawCallsPerFrame();
    public static native void resetPerformanceCounters();

    // VSync and display management
    public static native void setVsync(boolean enabled);
    public static native void recreateSwapChain();
    public static native void handleDisplayResize(int width, int height);
    public static native void setWindowActiveState(boolean active);

    // DirectX 12 Ultimate features
    public static native boolean isRaytracingSupported();
    public static native boolean isVariableRateShadingSupported();
    public static native boolean isMeshShadersSupported();
    public static native boolean isGpuDrivenRenderingSupported();

    // Enhanced features for DirectX 12 Ultimate
    public static native void enableRaytracing(boolean enabled);
    public static native void setVariableRateShading(int tileSize);
    public static native void enableMeshShaders(boolean enabled);
    public static native void enableGpuDrivenRendering(boolean enabled);

    // DirectStorage methods
    public static native boolean isDirectStorageSupported();
    public static native boolean isHardwareDecompressionSupported();
    public static native long openStorageFile(String filename);
    public static native long enqueueRead(long fileHandle, long offset, long size, long destination, long requestTag);
    public static native void processStorageQueue();
    public static native boolean isStorageQueueEmpty();
    public static native void closeStorageFile(long fileHandle);
    public static native void setStoragePriority(boolean realtimePriority);
    public static native float getStorageUtilization();
    public static native long getStorageThroughput();

    // Advanced features and configuration methods
    public static native boolean initializeWithConfig(long windowHandle, String configJson);
    public static native void present();

    // DirectStorage advanced methods
    public static native String getDirectStorageStats();

    // D3D12MA (Direct3D 12 Memory Allocator) methods
    public static native boolean isD3D12MASupported();
    public static native boolean enableMemoryBudgeting(boolean enable);
    public static native long createManagedBuffer(byte[] data, int size, int stride, int heapType, int allocationFlags);
    public static native long createManagedTexture(byte[] data, int width, int height, int format, int heapType, int allocationFlags);
    public static native long createManagedUploadBuffer(int size);
    public static native void releaseManagedResource(long handle);
    public static native String getMemoryStatistics();
    public static native String getPoolStatistics(int poolType);
    public static native boolean beginDefragmentation();
    public static native boolean validateAllocation(long handle);
    public static native String getAllocationInfo(long handle);
    public static native void setResourceDebugName(long handle, String name);
    public static native boolean checkMemoryBudget(long requiredSize, int heapType);
    public static native void dumpMemoryToJson();
    public static native String getD3D12MAStats();

    // Raytracing advanced methods
    public static native boolean enableRayTracing(int quality);
    public static native void disableRayTracing();

    // Variable Rate Shading advanced methods
    public static native boolean enableVariableRateShading(int tileSize);
    public static native void disableVariableRateShading();

    // Device access methods
    public static native long getNativeDeviceHandle();

    // Adapter selection methods
    public static native int getAdapterCount();
    public static native String getAdapterInfo(int adapterIndex);
    public static native boolean createDeviceWithAdapter(int adapterIndex, boolean enableDebugLayer);
    public static native String getAdapterFeatureSupport(int adapterIndex);

    // Root signature methods
    public static native long createRootSignature(byte[] serializedDefinition, String name);

    // Command queue and allocator methods
    public static native long createCommandQueue(int commandListType, String debugName);
    public static native long createCommandAllocator(int commandListType, String debugName);
    public static native long createCommandList(int commandListType, long allocatorHandle, String debugName);
    public static native void resetCommandList(long commandListHandle, long allocatorHandle);
    public static native boolean executeCommandList(long queueHandle, long commandListHandle);
    public static native boolean executeCommandLists(long queueHandle, long[] commandListHandles);

    // Descriptor heap methods
    public static native long createDescriptorHeap(int heapType, int numDescriptors, int flags, String name);
    public static native long getDescriptorHeapCPUStart(long heapHandle);
    public static native long getDescriptorHeapGPUStart(long heapHandle);
    public static native int getDescriptorIncrementSize(int heapType);
    public static native boolean copyDescriptors(long srcCpuHandle, long destCpuHandle, int count, int srcIncrement, int destIncrement);

    // Pipeline state methods
    public static native long createGraphicsPipelineState(byte[] serializedDesc, String name);

    // Render state methods
    public static native void setViewport(int x, int y, int width, int height, float minDepth, float maxDepth);
    public static native void setRasterizerState(int fillMode, int cullMode, boolean frontCounterClockwise,
                                              int depthBias, float depthBiasClamp, float slopeScaledDepthBias,
                                              boolean depthClipEnable, boolean multisampleEnable,
                                              boolean antialiasedLineEnable, int forcedSampleCount,
                                              boolean conservativeRaster);
    public static native void setDepthStencilState(boolean depthEnable, int depthWriteMask, int depthFunc,
                                                boolean stencilEnable, byte stencilReadMask, byte stencilWriteMask,
                                                int frontFaceFailOp, int frontFaceDepthFailOp, int frontFacePassOp, int frontFaceFunc,
                                                int backFaceFailOp, int backFaceDepthFailOp, int backFacePassOp, int backFaceFunc);
    public static native void setBlendState(boolean alphaToCoverageEnable, boolean independentBlendEnable,
                                         int srcBlend, int destBlend, int blendOp,
                                         int srcBlendAlpha, int destBlendAlpha, int blendOpAlpha,
                                         int renderTargetWriteMask);

    // Shader constant methods
    public static native void setProjectionMatrix(float m00, float m01, float m02, float m03,
                                             float m10, float m11, float m12, float m13,
                                             float m20, float m21, float m22, float m23,
                                             float m30, float m31, float m32, float m33);
    public static native void setModelViewMatrix(float m00, float m01, float m02, float m03,
                                            float m10, float m11, float m12, float m13,
                                            float m20, float m21, float m22, float m23,
                                            float m30, float m31, float m32, float m33);
    public static native void setTextureMatrix(float m00, float m01, float m02, float m03,
                                          float m10, float m11, float m12, float m13,
                                          float m20, float m21, float m22, float m23,
                                          float m30, float m31, float m32, float m33);
    public static native void setShaderColor(float r, float g, float b, float a);
    public static native void setShaderLightDirection(int index, float x, float y, float z);
    public static native void setShaderFogColor(float r, float g, float b);

    // Buffer methods
    public static native long mapBuffer(long bufferHandle);
    public static native void unmapBuffer(long bufferHandle);
    public static native boolean copyBuffer(long srcHandle, long dstHandle, int dstOffset, int size);
    public static native void clearDepthBuffer();

    // Resource barrier methods
    public static native boolean executeBarriers(long commandListHandle, int[] barrierTypes, long[] resourceHandles,
                                             int[] statesBefore, int[] statesAfter, int[] flags, int count);

    // Debug layer methods
    public static native void setDebugLayerConfiguration(boolean enableDebugLayer, boolean enableGpuValidation,
                                                   boolean enableResourceLeakDetection, boolean enableObjectNaming);
    public static native int getDebugMessageCount();
    public static native String getDebugMessage(int index);
    public static native void clearDebugMessages();
    public static native boolean setGpuBreakpoint(long resourceHandle);
    public static native boolean removeGpuBreakpoint(long resourceHandle);
    public static native boolean validateResource(long resourceHandle);

    // ============================================================================
    // ADVANCED FEATURES - Complete implementations from vitra_d3d12.cpp
    // All features from vitra_d3d12_enhanced.cpp now fully implemented
    // Note: Some methods like checkMemoryBudget, enableMemoryBudgeting,
    // getMemoryStatistics, and generateMipmaps are already declared above
    // ============================================================================

    // Multi-Queue Support (Compute + Copy Queues)
    public static native void submitComputeCommandList(long commandListHandle);
    public static native void submitCopyCommandList(long commandListHandle);
    public static native void waitForComputeQueue();
    public static native void waitForCopyQueue();
    public static native void synchronizeAllQueues();

    // Staged Upload Buffer System (64MB Ring Buffer)
    public static native void uploadBufferDataStaged(long dstBufferHandle, byte[] data, int size, int dstOffset);
    public static native void uploadBufferDataImmediate(long dstBufferHandle, byte[] data, int size, int dstOffset);

    // Texture Streaming System
    public static native boolean enableTextureStreaming(String texturePath);
    public static native void processTextureStream();

    // Parallel Shader Precompilation
    public static native void precompileMinecraftShaders();

    // Frame Latency Configuration
    public static native void setFrameLatencyMode(int maxLatency);

    // Pipeline State Caching
    public static native long getCachedPipelineState(String name);
    public static native void cachePipelineState(String name, long pipelineHandle);

    // Advanced Features Initialization
    public static native boolean initializeAdvancedFeatures();

    // Static initialization - Load native library
    static {
        try {
            // Verify Windows platform
            String osName = System.getProperty("os.name").toLowerCase();
            if (!osName.contains("win")) {
                throw new RuntimeException("Vitra DirectX 12 requires Windows 10+. Current OS: " + osName);
            }

            // CRITICAL: Load dxcompiler.dll dependency first (following JNA best practices)
            // This ensures Windows can resolve the dependency when loading vitra-d3d12.dll
            boolean dxcompilerLoaded = false;

            // Try to pre-load dxcompiler.dll from Windows SDK or resources
            try {
                // First attempt: Load from Windows SDK path
                String windowsKits = System.getenv("ProgramFiles(x86)") + "\\Windows Kits\\10\\bin\\10.0.26100.0\\x64\\dxcompiler.dll";
                if (new java.io.File(windowsKits).exists()) {
                    System.load(windowsKits);
                    LOGGER.info("✓ Loaded dxcompiler.dll dependency from Windows SDK");
                    dxcompilerLoaded = true;
                } else {
                    // Second attempt: Try loading from resources (copied by Gradle)
                    String dxcompilerPath = VitraD3D12Native.class.getResource("/native/windows/dxcompiler.dll").getPath();
                    if (dxcompilerPath != null) {
                        System.load(dxcompilerPath);
                        LOGGER.info("✓ Loaded dxcompiler.dll from resources");
                        dxcompilerLoaded = true;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not pre-load dxcompiler.dll: {}", e.getMessage());
                LOGGER.warn("vitra-d3d12.dll will attempt to load it from PATH at runtime");
            }

            // Now load the main DLL (matching DirectX 11 pattern)
            try {
                // Try loading from JAR resources or system library path first
                System.loadLibrary("vitra-d3d12");
                LOGGER.info("✓ Loaded DirectX 12 native library");
            } catch (UnsatisfiedLinkError e) {
                try {
                    // Fallback: Load from resources directory
                    String nativePath = VitraD3D12Native.class.getResource("/native/windows/vitra-d3d12.dll").getPath();
                    if (nativePath != null) {
                        System.load(nativePath);
                        LOGGER.info("✓ Loaded DirectX 12 native library from resources");
                    } else {
                        throw new RuntimeException("DirectX 12 native library (vitra-d3d12.dll) not found");
                    }
                } catch (Exception ex) {
                    LOGGER.error("Failed to load DirectX 12 native library");
                    LOGGER.error("Missing dependencies - check that DirectX 12 SDK is installed");
                    LOGGER.error("System dependencies: d3d12.dll, dxgi.dll, D3DCOMPILER_47.dll");
                    LOGGER.error("Manual dependency: dxcompiler.dll (pre-loaded: {})", dxcompilerLoaded);
                    throw new RuntimeException("Failed to load DirectX 12 native library. Ensure you are running on Windows 10+ with DirectX 12 support.", ex);
                }
            }

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Failed to load DirectX 12 native library (vitra-d3d12.dll)");
            LOGGER.error("Make sure DirectX 12 native library is compiled and available");
            throw new RuntimeException("DirectX 12 native library not found. Run './gradlew compileNativeDX12' to build it.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DirectX 12 native library", e);
        }
    }
}