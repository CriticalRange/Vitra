#ifndef VITRA_D3D12_H
#define VITRA_D3D12_H

#include <jni.h>
#include <cstdint>
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <d3dcompiler.h>
#include <d3d12sdklayers.h>
#include <d3d12shader.h>
#include <DirectXMath.h>
#include <wrl/client.h>
#include <vector>
#include <unordered_map>
#include <memory>
#include <array>
#include <mutex>

// DirectX 12 Ultimate Headers
#include <d3d12raytracing.h>
#include <d3d12meshshader.h>
#include <d3d12video.h>

// DirectStorage Headers
#include <dstorage.h>
#include <dstorageerr.h>

// D3D12 Memory Allocator (D3D12MA) Headers
#include <D3D12MemAlloc.h>

// Enhanced resource tracking for leak detection and debugging
struct D3D12ResourceInfo {
    std::string name;
    std::string type;
    size_t size;
    uint64_t creationFrame;
    D3D12_RESOURCE_STATES currentState;
    std::unordered_map<std::string, std::any> metadata;
    uint64_t gpuHandle;
};

// Command list management with frame tracking
struct D3D12CommandListInfo {
    D3D12_COMMAND_LIST_TYPE type;
    int frameIndex;
    bool inUse;
    uint32_t resourceBarrierCount;
    uint32_t drawCallCount;
    uint32_t dispatchCallCount;
};

// Advanced texture management
struct D3D12TextureInfo {
    uint32_t width, height, depth, mipLevels;
    DXGI_FORMAT format;
    D3D12_RESOURCE_STATES currentState;
    std::unordered_map<D3D12_CPU_DESCRIPTOR_HANDLE, D3D12_GPU_DESCRIPTOR_HANDLE> descriptorHandles;
    std::string debugName;
};

// Pipeline state caching
struct D3D12PipelineStateInfo {
    std::string name;
    ComPtr<ID3D12RootSignature> rootSignature;
    ComPtr<ID3D12PipelineState> pipelineState;
    D3D12_PRIMITIVE_TOPOLOGY topology;
    std::vector<D3D12_INPUT_ELEMENT_DESC> inputElements;
    bool isComputable;
    uint32_t creationFrame;
    std::unordered_map<std::string, std::any> metadata;
};

// Performance profiling system
struct D3D12PerformanceCounters {
    // Frame statistics
    uint64_t frameCount = 0;
    uint64_t totalFrameTime = 0;
    uint64_t minFrameTime = LLONG_MAX;
    uint64_t maxFrameTime = 0;
    std::vector<uint64_t> recentFrameTimes;

    // Draw and compute statistics
    uint64_t totalDrawCalls = 0;
    uint64_t totalDispatchCalls = 0;
    uint64_t totalVerticesDrawn = 0;

    // Resource creation statistics
    uint64_t texturesCreated = 0;
    uint64_t buffersCreated = 0;
    uint64_t shadersCompiled = 0;
    uint64_t pipelinesCreated = 0;

    // Memory statistics
    uint64_t totalTextureMemory = 0;
    uint64_t totalBufferMemory = 0;

    // Barrier statistics
    uint64_t totalBarriersIssued = 0;
    uint64_t redundantBarriersAvoided = 0;

    // Timing samples for detailed profiling
    std::vector<D3D12TimingSample> timingSamples;

    D3D12PerformanceCounters() {}

    void recordFrameTime(uint64_t frameTime);
    void recordDrawCall(uint32_t vertexCount, uint32_t instanceCount);
    void recordDispatchCall(uint32_t threadGroupX, uint32_t threadGroupY, uint32_t threadGroupZ);
    void recordTextureCreation(uint32_t width, uint32_t height, DXGI_FORMAT format, uint64_t creationTime);
    void recordBufferCreation(size_t size, uint64_t creationTime);
    void recordShaderCompilation(uint64_t size, bool successful, uint64_t compilationTime);
    void recordPipelineCreation(uint64_t creationTime);
    void recordResourceBarrier(int type);
    void addTimingSample(const D3D12TimingSample& sample);
    std::string getStats() const;
    void reset();
};

// Debug message system
struct D3D12DebugMessage {
    int severity;
    int category;
    int id;
    std::string description;
    std::chrono::system_clock::time_point timestamp;

    D3D12DebugMessage(int sev, int cat, int id, const std::string& desc)
        : severity(sev), category(cat), id(id), description(desc)
        , timestamp(std::chrono::system_clock::now()) {}
};

struct D3D12DebugManager {
    bool enabled = true;
    bool debugLayerEnabled = false;
    bool gpuValidationEnabled = false;
    bool resourceLeakDetectionEnabled = true;
    bool objectNamingEnabled = true;

    // Debug message queue
    std::vector<D3D12DebugMessage> messageQueue;
    std::vector<D3D12DebugMessage> recentMessages;

    // Resource tracking
    std::unordered_map<uint64_t, D3D12ResourceInfo> trackedResources;
    std::unordered_map<std::string, D3D12ResourceInfo*> namedResources;

    // Statistics
    std::array<std::atomic<uint64_t>, 4> messageCounts = {};
    std::atomic<uint64_t> totalMessages = 0;
    std::atomic<uint64_t> leakDetectedCount = 0;

    D3D12DebugManager() : messageCounts{} {
        messageCounts[static_cast<size_t>(SEVERITY_INFO)] = 0;
        messageCounts[static_cast<size_t>(SEVERITY_WARNING)] = 0;
        messageCounts[static_cast<size_t>(SEVERITY_ERROR)] = 0;
        messageCounts[static_cast<size_t>(SEVERITY_CORRUPTION)] = 0;
    }

    void initialize(bool enableDebugLayer, bool enableGpuValidation, bool enableResourceLeakDetection, bool enableObjectNaming);
    void registerResource(uint64_t handle, const std::string& type, const std::string& name, size_t size);
    void registerResource(uint64_t handle, const std::string& type, const std::string& name, size_t size,
                    const std::unordered_map<std::string, std::any>& metadata);
    void unregisterResource(uint64_t handle);
    void unregisterResource(const std::string& name);
    void addDebugMessage(int severity, int category, int id, const std::string& description);
    void addDebugMessage(int severity, int category, const std::string& description, const std::unordered_map<std::string, std::any>& metadata);
    void processDebugMessages();
    void checkResourceLeaks();
    bool setGpuBreakpoint(uint64_t resourceHandle);
    bool removeGpuBreakpoint(uint64_t resourceHandle);
    bool validateResource(uint64_t resourceHandle);
    D3D12ResourceInfo* getResource(uint64_t handle);
    D3D12ResourceInfo* getResource(const std::string& name);
    std::vector<D3D12ResourceInfo*> getAllResources();
    std::vector<D3D12DebugMessage> getRecentMessages(int count);
    std::string getDebugStats() const;
    bool exportDebugInfo(const std::string& filename);
    void cleanup();
};

// Timing sample for detailed profiling
struct D3D12TimingSample {
    std::chrono::high_resolution_clock::time_point startTime;
    std::chrono::high_resolution_clock::time_point endTime;
    int category;
    int id;
    std::string name;
    std::unordered_map<std::string, std::any> metadata;
    uint64_t duration;

    D3D12TimingSample() : category(0), id(0), duration(0) {}
    D3D12TimingSample(int cat, int sampleId, const std::string& sampleName,
                 const std::unordered_map<std::string, std::any>& meta)
        : category(cat), id(sampleId), name(sampleName), metadata(meta), duration(0) {}

    void endTiming();
    double getDurationMs() const;
};

// Configuration structure for D3D12 features (inspired by VulkanMod)
struct D3D12Configuration {
    // Adapter selection
    int preferredAdapter = -1;
    bool d3d12maEnabled = true;
    bool memoryBudgetingEnabled = true;
    bool directStorageEnabled = true;
    bool hardwareDecompressionEnabled = true;
    bool gpuDrivenRenderingEnabled = false;

    // Advanced features
    bool rayTracingEnabled = false;
    bool variableRateShadingEnabled = false;
    bool meshShadersEnabled = false;
    int rayTracingQuality = 2;
    float vrsTileSize = 8.0f;

    // Performance configuration
    bool asyncComputeEnabled = false;
    bool multiQueueEnabled = true;
    int frameQueueSize = 3;
    bool descriptorHeapCachingEnabled = true;
    bool pipelineCachingEnabled = true;
    bool shaderCachingEnabled = true;
    bool resourceBarrierOptimization = true;

    // Memory configuration
    uint64_t uploadBufferSize = 64 * 1024 * 1024; // 64MB
    int maxTextureUploadBatchSize = 1024;
    bool useD3D12MA = true;
    bool enableMemoryDefragmentation = true;
    uint64_t memoryBudgetWarningThreshold = 100 * 1024 * 1024; // 100MB

    // Shader configuration
    std::string shaderModel = "6_5";
    bool enableShaderDebugging = false;
    bool enableShaderOptimization = true;
    int maxShaderCompilerThreads = 4;
    bool precompileCommonShaders = true;

    // Pipeline configuration
    bool enablePipelineCaching = true;
    int maxCachedPipelines = 1000;
    bool enablePipelineCompilationLogging = false;
    bool useRootSignature1 = true;

    // Texture configuration
    bool enableTextureStreaming = true;
    int maxStreamingTextures = 256;
    bool enableMipmapGeneration = true;
    bool enableTextureCompression = true;
    bool useSRGBFormats = true;

    // Synchronization configuration
    bool enableFrameLatencyTracking = true;
    int maxFrameLatency = 3;
    bool enableGpuTimeouts = false;
    uint64_t gpuTimeoutMs = 5000;

    // Profiling configuration
    bool enableProfiler = true;
    bool enableDetailedProfiling = false;
    int maxProfilerSamples = 10000;
    bool enablePerformanceOverlay = false;
    bool enableGpuProfiling = false;

    D3D12Configuration() {
        // Default VulkanMod-inspired configuration
        std::ifstream configFile("config/vitra.properties");
        loadFromConfigFile(configFile);
    }

    void loadFromConfigFile(std::ifstream& configFile);
    void saveToConfigFile() const;
};

// D3D12.2 Headers
#if defined(NTDDI_WIN10_CO) && (NTDDI_VERSION >= NTDDI_WIN10_CO)
#include <d3d12.h>
#endif

using Microsoft::WRL::ComPtr;
using namespace DirectX;

#ifdef __cplusplus
extern "C" {
#endif

// Modern D3D12 resource structure with DirectX 12 Ultimate support
struct D3D12Resources {
    // Core device and queue
    ComPtr<ID3D12Device5> device5;                    // D3D12.5 device for Ultimate features
    ComPtr<ID3D12Device12> device12;                  // D3D12.2 device for latest features
    ComPtr<ID3D12CommandQueue> commandQueue;
    ComPtr<ID3D12CommandQueue> computeQueue;          // Separate compute queue
    ComPtr<ID3D12CommandQueue> copyQueue;             // Dedicated copy queue
    ComPtr<IDXGISwapChain4> swapChain4;               // DXGI 1.6 swap chain

    // Enhanced descriptor heaps
    ComPtr<ID3D12DescriptorHeap> rtvHeap;
    ComPtr<ID3D12DescriptorHeap> dsvHeap;
    ComPtr<ID3D12DescriptorHeap> cbvSrvUavHeap;
    ComPtr<ID3D12DescriptorHeap> samplerHeap;

    // GPU upload heap for modern resource management
    ComPtr<ID3D12Resource> gpuUploadHeap;

    // Frame resources with triple buffering
    static const UINT FRAME_COUNT = 3;
    ComPtr<ID3D12Resource> renderTargets[FRAME_COUNT];
    ComPtr<ID3D12Resource> depthStencilBuffer;
    ComPtr<ID3D12CommandAllocator> commandAllocators[FRAME_COUNT];
    ComPtr<ID3D12CommandAllocator> computeAllocators[FRAME_COUNT];
    ComPtr<ID3D12GraphicsCommandList4> commandList4;   // D3D12.4 command list
    ComPtr<ID3D12GraphicsCommandList4> computeCommandList;

    // Root signature and pipeline state objects
    ComPtr<ID3D12RootSignature> rootSignature;
    ComPtr<ID3D12RootSignature> computeRootSignature;
    std::unordered_map<uint64_t, ComPtr<ID3D12PipelineState>> pipelineStates;
    ComPtr<ID3D12PipelineState> currentPipelineState;

    // DirectX 12 Ultimate Resources
    ComPtr<ID3D12Device5> raytracingDevice;           // DXR 1.1 support
    ComPtr<ID3D12StateObject> raytracingStateObject;
    ComPtr<ID3D12Resource> raytracingAccelerationStructure;
    ComPtr<ID3D12Resource> shaderTable;

    // Mesh Shader Resources
    ComPtr<ID3D12PipelineState> meshShaderPipeline;

    // Variable Rate Shading
    ComPtr<ID3D12Resource> vrsShadingRateImage;
    D3D12_SHADING_RATE Combiners[D3D12_RS_SET_SHADING_RATE_COMBINER_COUNT];

    // Sampler Feedback
    ComPtr<ID3D12Resource> samplerFeedbackResource;
    ComPtr<ID3D12Resource> samplerFeedbackMinMipResource;

    // DirectStorage components
    ComPtr<IDStorageFactoryX> storageFactory;              // DirectStorage factory
    ComPtr<IDStorageQueueX> storageQueue;                  // High-speed storage queue
    ComPtr<IDStorageFileX> storageFiles[1024];              // File handles for assets
    UINT32 storageFileCount;                                // Number of open files
    ComPtr<IDStorageCompletionEvent> storageCompletion;     // Completion event
    DSTORAGE_REQUEST_STATUS storageStatus[1024];            // Request status array
    bool storageInitialized;                                 // DirectStorage initialization status

    // D3D12 Memory Allocator (D3D12MA) components
    D3D12MA::Allocator* allocator;                         // Main memory allocator
    D3D12MA::Pool* defaultPool;                            // Default memory pool for buffers
    D3D12MA::Pool* texturePool;                            // Dedicated pool for textures
    D3D12MA::Pool* uploadPool;                             // Upload heap pool
    D3D12MA::Pool* readbackPool;                           // Readback heap pool
    bool allocatorInitialized;                              // D3D12MA initialization status

    // Enhanced Debug Layer components
    ComPtr<ID3D12Debug1> debugController1;               // D3D12 Debug Controller with GPU validation
    ComPtr<ID3D12InfoQueue1> infoQueue1;                   // Info Queue for message callbacks
    D3D12_MESSAGE_SEVERITY debugSeverityFilter;          // Debug message severity filter
    bool debugCallbackRegistered;                         // Flag indicating callback registration status
    std::ofstream debugLogFile;                            // Debug log file stream
    std::string debugLogPath;                             // Debug log file path
    bool debugLoggingToFileEnabled;                        // Flag for file logging

    // Synchronization objects with enhanced features
    ComPtr<ID3D12Fence> fence;
    ComPtr<ID3D12Fence> computeFence;
    ComPtr<ID3D12Fence> copyFence;
    UINT64 fenceValues[FRAME_COUNT];
    UINT64 computeFenceValues[FRAME_COUNT];
    UINT64 copyFenceValues[FRAME_COUNT];
    HANDLE fenceEvent;
    HANDLE computeFenceEvent;
    HANDLE copyFenceEvent;
    UINT frameIndex;

    // Enhanced barriers support
    bool supportsEnhancedBarriers;
    D3D12_FEATURE_DATA_D3D12_OPTIONS12 options12;
    D3D12_FEATURE_DATA_D3D12_OPTIONS13 options13;

    // Descriptor sizes
    UINT rtvDescriptorSize;
    UINT dsvDescriptorSize;
    UINT cbvSrvUavDescriptorSize;
    UINT samplerDescriptorSize;

    // Viewport and scissor rect
    D3D12_VIEWPORT viewport;
    D3D12_RECT scissorRect;

    // Render Pass support
    bool supportsRenderPasses;
    ComPtr<ID3D12Resource> renderPassTarget;

    // GPU Upload Heap support
    bool supportsGPUUploadHeaps;

    // Shader Model support
    D3D_SHADER_MODEL highestShaderModel;

    // Window and configuration
    HWND hwnd;
    int width;
    int height;
    bool debugEnabled;
    bool debugUIEnabled;
    bool initialized;

    // Threading support
    std::mutex resourceMutex;
    std::mutex commandListMutex;

    // Feature support flags
    struct {
        bool raytracingSupported;
        bool meshShadersSupported;
        bool variableRateShadingSupported;
        bool samplerFeedbackSupported;
        bool shaderModel6_7Supported;
        bool workGraphsSupported;
        bool gpuUploadHeapsSupported;
        bool enhancedBarriersSupported;
        bool renderPassesSupported;
        bool directStorageSupported;                          // DirectStorage NVMe support
        bool hardwareDecompressionSupported;                    // BCPACK/DEFLATE decompression
    } features;
};

// Global D3D12 resources
extern D3D12Resources g_d3d12;

// Enhanced resource tracking with modern D3D12 resource management
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_vertexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_indexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_constantBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_structuredBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_rwBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_textures;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_rwTextures;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_vertexShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_pixelShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_computeShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_meshShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_amplificationShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_raytracingShaderBlobs;
extern std::unordered_map<uint64_t, D3D12_CPU_DESCRIPTOR_HANDLE> g_srvDescriptors;
extern std::unordered_map<uint64_t, D3D12_CPU_DESCRIPTOR_HANDLE> g_uavDescriptors;
extern std::unordered_map<uint64_t, D3D12_CPU_DESCRIPTOR_HANDLE> g_cbvDescriptors;
extern std::unordered_map<uint64_t, D3D12_GPU_DESCRIPTOR_HANDLE> g_gpuDescriptorHandles;

// DirectX 12 Ultimate resource tracking
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_raytracingBLAS;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_raytracingTLAS;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_vrsResources;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_samplerFeedbackResources;

// DirectStorage resource tracking
extern std::unordered_map<uint64_t, ComPtr<IDStorageFileX>> g_storageFiles;
extern std::unordered_map<uint64_t, DSTORAGE_REQUEST> g_pendingRequests;

// D3D12MA resource tracking
extern std::unordered_map<uint64_t, std::unique_ptr<D3D12ManagedResource>> g_managedResources;
extern std::unordered_map<D3D12MA::Allocation*, uint64_t> g_allocationToResource;

// Modern resource management structures
struct D3D12BufferDesc {
    D3D12_RESOURCE_DESC resourceDesc;
    D3D12_HEAP_PROPERTIES heapProps;
    D3D12_HEAP_FLAGS heapFlags;
    D3D12_RESOURCE_STATES initialState;
    UINT64 size;
    UINT stride;
    D3D12_RESOURCE_FLAGS flags;
    // D3D12MA allocation parameters
    D3D12MA::ALLOCATION_FLAGS allocationFlags;
    D3D12MA::Pool* customPool;
    const char* debugName;
};

struct D3D12TextureDesc {
    D3D12_RESOURCE_DESC resourceDesc;
    D3D12_HEAP_PROPERTIES heapProps;
    D3D12_HEAP_FLAGS heapFlags;
    D3D12_RESOURCE_STATES initialState;
    DXGI_FORMAT format;
    UINT width, height, depth;
    UINT mipLevels;
    D3D12_RESOURCE_FLAGS flags;
    // D3D12MA allocation parameters
    D3D12MA::ALLOCATION_FLAGS allocationFlags;
    D3D12MA::Pool* customPool;
    const char* debugName;
};

// D3D12MA-enabled resource wrapper
struct D3D12ManagedResource {
    ComPtr<ID3D12Resource> resource;
    D3D12MA::Allocation* allocation;
    D3D12MA::ALLOCATION_INFO allocationInfo;
    uint64_t handle;
    std::string debugName;
    D3D12_RESOURCE_STATES currentState;
    bool isMapped;

    D3D12ManagedResource() : allocation(nullptr), handle(0), currentState(D3D12_RESOURCE_STATE_COMMON), isMapped(false) {}

    ~D3D12ManagedResource() {
        if (allocation) {
            allocation->Release();
            allocation = nullptr;
        }
    }

    ID3D12Resource* GetResource() const { return resource.Get(); }
    D3D12MA::Allocation* GetAllocation() const { return allocation; }
    uint64_t GetHandle() const { return handle; }
    void SetDebugName(const char* name) { debugName = name ? name : "Unnamed"; }
};

struct D3D12PipelineStateDesc {
    ComPtr<ID3D12RootSignature> rootSignature;
    D3D12_RASTERIZER_DESC rasterizerDesc;
    D3D12_BLEND_DESC blendDesc;
    D3D12_DEPTH_STENCIL_DESC depthStencilDesc;
    D3D12_INPUT_LAYOUT_DESC inputLayout;
    D3D12_PRIMITIVE_TOPOLOGY_TYPE primitiveTopologyType;
    UINT nodeMask;
    D3D12_CACHED_PIPELINE_STATE cachedPSO;
    D3D12_PIPELINE_STATE_FLAGS flags;
};

// Enhanced helper functions for modern D3D12
uint64_t generateHandle();
bool compileShader(const char* source, const char* target, ID3DBlob** blob);
bool compileShaderLibrary(const char* source, const char* target, ID3DBlob** blob);
bool compileDXCShader(const char* source, const char* target, const char* entryPoint, ID3DBlob** blob);

// Resource creation functions
void createRenderTargetViews();
void createDepthStencilView();
HRESULT createBuffer(const D3D12BufferDesc& desc, ID3D12Resource** resource);
HRESULT createTexture(const D3D12TextureDesc& desc, ID3D12Resource** resource);
HRESULT createGPUUploadHeap(UINT64 size, ID3D12Resource** uploadHeap);

// Enhanced synchronization
void waitForGpu();
void waitForComputeGPU();
void waitForCopyGPU();
void moveToNextFrame();
void synchronizeAllQueues();

// DirectX 12 Ultimate functions
bool initializeRaytracing();
bool initializeMeshShaders();
bool initializeVariableRateShading();
bool initializeSamplerFeedback();
bool initializeEnhancedBarriers();

// Pipeline state management
HRESULT createGraphicsPipelineState(const D3D12PipelineStateDesc& desc, const D3D12_SHADER_BYTECODE& VS,
                                   const D3D12_SHADER_BYTECODE& PS, ID3D12PipelineState** pipelineState);
HRESULT createComputePipelineState(const D3D12_SHADER_BYTECODE& CS, ID3D12RootSignature* rootSignature,
                                   ID3D12PipelineState** pipelineState);
HRESULT createMeshShaderPipelineState(const D3D12_SHADER_BYTECODE& AS, const D3D12_SHADER_BYTECODE& MS,
                                      const D3D12_SHADER_BYTECODE& PS, ID3D12PipelineState** pipelineState);

// Enhanced barrier functions
void transitionResource(ID3D12GraphicsCommandList4* commandList, ID3D12Resource* resource,
                        D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after,
                        UINT subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES);
void UAVBarrier(ID3D12GraphicsCommandList4* commandList, ID3D12Resource* resource = nullptr);
void aliasingBarrier(ID3D12GraphicsCommandList4* commandList, ID3D12Resource* before, ID3D12Resource* after);

// DirectX Raytracing functions
HRESULT createRaytracingPipelineState(D3D12_STATE_OBJECT_DESC* desc, ID3D12StateObject** stateObject);
HRESULT buildBottomLevelAS(ID3D12GraphicsCommandList4* commandList, const D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_DESC& desc);
HRESULT buildTopLevelAS(ID3D12GraphicsCommandList4* commandList, const D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_DESC& desc);

// Variable Rate Shading functions
void setVariableRateShading(D3D12_SHADING_RATE baseRate, const D3D12_SHADING_RATE_COMBINER combiners[2]);
HRESULT createShadingRateImage(UINT width, UINT height, ID3D12Resource** shadingRateImage);

// Mesh Shader functions
void dispatchMesh(UINT threadGroupCountX, UINT threadGroupCountY, UINT threadGroupCountZ);

// Render Pass functions
void beginRenderPass(const D3D12_RENDER_PASS_BEGIN_DESC& renderPassBegin);
void endRenderPass();

// DirectStorage functions
bool initializeDirectStorage();
bool checkDirectStorageSupport();
HRESULT createStorageQueue();
HRESULT openStorageFile(const wchar_t* filename, IDStorageFileX** file);
HRESULT enqueueReadRequest(IDStorageFileX* file, UINT64 offset, UINT64 size, void* destination, UINT64 requestTag);
HRESULT enqueueDecompressionRead(IDStorageFileX* file, UINT64 compressedOffset, UINT64 compressedSize,
                                void* compressedDestination, UINT64 decompressedSize,
                                void* decompressedDestination, UINT64 requestTag);
void processStorageQueue();
void waitForStorageRequests(UINT64 timeoutMs);
void shutdownDirectStorage();

// Enhanced Debug Layer functions
bool initializeDebugLayer();
bool setupDebugMessageCallback();
void shutdownDebugLayer();
void setDebugSeverityFilter(D3D12_MESSAGE_SEVERITY minSeverity);
bool initializeDebugLogFile(const std::string& logPath);
void closeDebugLogFile();
void writeDebugMessage(D3D12_MESSAGE_CATEGORY category, D3D12_MESSAGE_SEVERITY severity, D3D12_MESSAGE_ID id, const char* message);
void processDebugMessages();
void enableGPUValidation(bool enable);
void enableSynchronizedCommandQueueValidation(bool enable);

// DirectStorage performance functions
float getStorageThroughput();
UINT getPendingRequestCount();
bool isStorageQueueIdle();

// D3D12 Memory Allocator (D3D12MA) functions
bool initializeD3D12MA();
bool createMemoryPools();
void shutdownD3D12MA();
HRESULT createBufferWithD3D12MA(const D3D12BufferDesc& desc, ID3D12Resource** resource, D3D12MA::Allocation** allocation);
HRESULT createTextureWithD3D12MA(const D3D12TextureDesc& desc, ID3D12Resource** resource, D3D12MA::Allocation** allocation);
HRESULT createUploadBufferWithD3D12MA(UINT64 size, ID3D12Resource** resource, D3D12MA::Allocation** allocation);
HRESULT createReadbackBufferWithD3D12MA(UINT64 size, ID3D12Resource** resource, D3D12MA::Allocation** allocation);
void releaseResource(D3D12MA::Allocation* allocation);
bool checkMemoryBudget(UINT64 requiredSize, D3D12_HEAP_TYPE heapType);
void getMemoryStatistics(D3D12MA::Statistics* stats);
void getPoolStatistics(D3D12MA::Pool* pool, D3D12MA::PoolStatistics* poolStats);
bool beginDefragmentation();
void enableBudgeting(bool enable);
void dumpMemoryStatisticsToJson();
bool validateAllocation(D3D12MA::Allocation* allocation);

// Feature detection functions
bool checkRaytracingSupport();
bool checkMeshShaderSupport();
bool checkVariableRateShadingSupport();
bool checkSamplerFeedbackSupport();
bool checkShaderModelSupport(D3D_SHADER_MODEL requiredModel);
bool checkEnhancedBarriersSupport();
bool checkGPUUploadHeapSupport();
D3D_SHADER_MODEL getHighestSupportedShaderModel();

// JNI export functions for DirectX 12 Ultimate
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeInitializeDirectX12
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeShutdown
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeResize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBeginFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEndFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeClear
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreatePipelineState
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDestroyResource
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetPipelineState
    (JNIEnv* env, jclass clazz, jlong pipeline);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDraw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint vertexCount, jint indexCount);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsInitialized
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDrawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBindTexture
    (JNIEnv* env, jclass clazz, jlong texture, jint slot);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetViewport
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

// DirectX 12 Ultimate JNI functions
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsRaytracingSupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsMeshShadingSupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsVariableRateShadingSupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsSamplerFeedbackSupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateRaytracingPipeline
    (JNIEnv* env, jclass clazz, jbyteArray shaderData);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateBottomLevelAS
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateTopLevelAS
    (JNIEnv* env, jclass clazz, jlongArray instanceDescs);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDispatchRays
    (JNIEnv* env, jclass clazz, jlong raytracingPipeline, jlong raygenTable,
     jlong missTable, jlong hitGroupTable, jint width, jint height, jint depth);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateMeshShaderPipeline
    (JNIEnv* env, jclass clazz, jbyteArray amplificationShader, jbyteArray meshShader, jbyteArray pixelShader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDispatchMesh
    (JNIEnv* env, jclass clazz, jlong meshPipeline, jint threadGroupX, jint threadGroupY, jint threadGroupZ);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetVariableRateShading
    (JNIEnv* env, jclass clazz, jint baseRate, jint combinerX, jint combinerY);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateShadingRateImage
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateSamplerFeedbackResource
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBeginRenderPass
    (JNIEnv* env, jclass clazz, jlongArray renderTargets, jlong depthStencil, jint numRenderTargets);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEndRenderPass
    (JNIEnv* env, jclass clazz);

// DirectStorage JNI functions
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsDirectStorageSupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeOpenStorageFile
    (JNIEnv* env, jclass clazz, jstring filename);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEnqueueRead
    (JNIEnv* env, jclass clazz, jlong fileHandle, jlong offset, jlong size, jbyteArray destination, jlong requestTag);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEnqueueDecompressionRead
    (JNIEnv* env, jclass clazz, jlong fileHandle, jlong compressedOffset, jlong compressedSize,
                     jbyteArray compressedDestination, jbyteArray decompressedDestination, jlong requestTag);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeProcessStorageQueue
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeWaitForStorageRequests
    (JNIEnv* env, jclass clazz, jlong timeoutMs);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetStorageInfo
    (JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetDeviceInfo
    (JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetHighestShaderModel
    (JNIEnv* env, jclass clazz);

// Enhanced buffer and texture management
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateStructuredBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateRWBuffer
    (JNIEnv* env, jclass clazz, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateRWTexture
    (JNIEnv* env, jclass clazz, jint width, jint height, jint format);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeUpdateBuffer
    (JNIEnv* env, jclass clazz, jlong buffer, jbyteArray data, jint offset, jint size);

// Compute shader support
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateComputePipeline
    (JNIEnv* env, jclass clazz, jbyteArray computeShader, jlong rootSignature);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDispatchCompute
    (JNIEnv* env, jclass clazz, jlong computePipeline, jint threadGroupX, jint threadGroupY, jint threadGroupZ);

// Enhanced synchronization
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSynchronizeAllQueues
    (JNIEnv* env, jclass clazz);

// Modern resource binding
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetGraphicsRootSignature
    (JNIEnv* env, jclass clazz, jlong rootSignature);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetComputeRootSignature
    (JNIEnv* env, jclass clazz, jlong rootSignature);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateRootSignature
    (JNIEnv* env, jclass clazz, jbyteArray rootSignatureDesc);

// D3D12 Memory Allocator (D3D12MA) JNI functions
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsD3D12MASupported
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEnableMemoryBudgeting
    (JNIEnv* env, jclass clazz, jboolean enable);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateManagedBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride, jint heapType, jint allocationFlags);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateManagedTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format, jint heapType, jint allocationFlags);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateManagedUploadBuffer
    (JNIEnv* env, jclass clazz, jint size);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeReleaseManagedResource
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetMemoryStatistics
    (JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetPoolStatistics
    (JNIEnv* env, jclass clazz, jint poolType);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBeginDefragmentation
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeValidateAllocation
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeGetAllocationInfo
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetResourceDebugName
    (JNIEnv* env, jclass clazz, jlong handle, jstring name);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCheckMemoryBudget
    (JNIEnv* env, jclass clazz, jlong requiredSize, jint heapType);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDumpMemoryToJson
    (JNIEnv* env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif // VITRA_D3D12_H
