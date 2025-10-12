#include "vitra_d3d12.h"
#include <iostream>
#include <random>

// Global D3D12 resources
D3D12Resources g_d3d12 = {};

// Resource tracking
std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_vertexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_indexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_vertexShaderBlobs;
std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_pixelShaderBlobs;
std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_textures;
std::unordered_map<uint64_t, D3D12_CPU_DESCRIPTOR_HANDLE> g_srvDescriptors;

// Handle generator
std::random_device rd;
std::mt19937_64 gen(rd());
std::uniform_int_distribution<uint64_t> dis;

uint64_t generateHandle() {
    return dis(gen);
}

bool compileShader(const char* source, const char* target, ID3DBlob** blob) {
    UINT flags = D3DCOMPILE_ENABLE_STRICTNESS;
    if (g_d3d12.debugEnabled) {
        flags |= D3DCOMPILE_DEBUG | D3DCOMPILE_SKIP_OPTIMIZATION;
    }

    ID3DBlob* errorBlob = nullptr;
    HRESULT hr = D3DCompile(
        source,
        strlen(source),
        nullptr,
        nullptr,
        nullptr,
        "main",
        target,
        flags,
        0,
        blob,
        &errorBlob
    );

    if (FAILED(hr)) {
        if (errorBlob) {
            std::cerr << "Shader compilation error: "
                      << static_cast<const char*>(errorBlob->GetBufferPointer()) << std::endl;
            errorBlob->Release();
        }
        return false;
    }

    if (errorBlob) {
        errorBlob->Release();
    }
    return true;
}

void createRenderTargetViews() {
    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = g_d3d12.rtvHeap->GetCPUDescriptorHandleForHeapStart();

    for (UINT i = 0; i < D3D12Resources::FRAME_COUNT; i++) {
        HRESULT hr = g_d3d12.swapChain->GetBuffer(i, IID_PPV_ARGS(&g_d3d12.renderTargets[i]));
        if (SUCCEEDED(hr)) {
            g_d3d12.device->CreateRenderTargetView(g_d3d12.renderTargets[i].Get(), nullptr, rtvHandle);
            rtvHandle.ptr += g_d3d12.rtvDescriptorSize;
        }
    }
}

void createDepthStencilView() {
    D3D12_DEPTH_STENCIL_VIEW_DESC dsvDesc = {};
    dsvDesc.Format = DXGI_FORMAT_D32_FLOAT;
    dsvDesc.ViewDimension = D3D12_DSV_DIMENSION_TEXTURE2D;
    dsvDesc.Flags = D3D12_DSV_FLAG_NONE;

    D3D12_CLEAR_VALUE depthOptimizedClearValue = {};
    depthOptimizedClearValue.Format = DXGI_FORMAT_D32_FLOAT;
    depthOptimizedClearValue.DepthStencil.Depth = 1.0f;
    depthOptimizedClearValue.DepthStencil.Stencil = 0;

    D3D12_HEAP_PROPERTIES heapProps = {};
    heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;
    heapProps.CPUPageProperty = D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
    heapProps.MemoryPoolPreference = D3D12_MEMORY_POOL_UNKNOWN;
    heapProps.CreationNodeMask = 1;
    heapProps.VisibleNodeMask = 1;

    D3D12_RESOURCE_DESC resourceDesc = {};
    resourceDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    resourceDesc.Alignment = 0;
    resourceDesc.Width = g_d3d12.width;
    resourceDesc.Height = g_d3d12.height;
    resourceDesc.DepthOrArraySize = 1;
    resourceDesc.MipLevels = 0;
    resourceDesc.Format = DXGI_FORMAT_D32_FLOAT;
    resourceDesc.SampleDesc.Count = 1;
    resourceDesc.SampleDesc.Quality = 0;
    resourceDesc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    resourceDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL;

    HRESULT hr = g_d3d12.device->CreateCommittedResource(
        &heapProps,
        D3D12_HEAP_FLAG_NONE,
        &resourceDesc,
        D3D12_RESOURCE_STATE_DEPTH_WRITE,
        &depthOptimizedClearValue,
        IID_PPV_ARGS(&g_d3d12.depthStencilBuffer)
    );

    if (SUCCEEDED(hr)) {
        g_d3d12.device->CreateDepthStencilView(
            g_d3d12.depthStencilBuffer.Get(),
            &dsvDesc,
            g_d3d12.dsvHeap->GetCPUDescriptorHandleForHeapStart()
        );
    }
}

void waitForGpu() {
    const UINT64 fenceValue = g_d3d12.fenceValues[g_d3d12.frameIndex];
    HRESULT hr = g_d3d12.commandQueue->Signal(g_d3d12.fence.Get(), fenceValue);

    if (SUCCEEDED(hr)) {
        hr = g_d3d12.fence->SetEventOnCompletion(fenceValue, g_d3d12.fenceEvent);

        if (SUCCEEDED(hr)) {
            WaitForSingleObject(g_d3d12.fenceEvent, INFINITE);
        }
    }
}

void moveToNextFrame() {
    const UINT64 currentFenceValue = g_d3d12.fenceValues[g_d3d12.frameIndex];
    HRESULT hr = g_d3d12.commandQueue->Signal(g_d3d12.fence.Get(), currentFenceValue);

    g_d3d12.frameIndex = g_d3d12.swapChain->GetCurrentBackBufferIndex();

    if (g_d3d12.fence->GetCompletedValue() < g_d3d12.fenceValues[g_d3d12.frameIndex]) {
        hr = g_d3d12.fence->SetEventOnCompletion(g_d3d12.fenceValues[g_d3d12.frameIndex], g_d3d12.fenceEvent);
        if (SUCCEEDED(hr)) {
            WaitForSingleObject(g_d3d12.fenceEvent, INFINITE);
        }
    }

    g_d3d12.fenceValues[g_d3d12.frameIndex] = currentFenceValue + 1;
}

// JNI Implementation
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeInitializeDirectX12
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug) {

    g_d3d12.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d12.width = width;
    g_d3d12.height = height;
    g_d3d12.debugEnabled = enableDebug;
    g_d3d12.frameIndex = 0;

    // Enable debug layer if requested
    if (g_d3d12.debugEnabled) {
        ComPtr<ID3D12Debug> debugController;
        if (SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&debugController)))) {
            debugController->EnableDebugLayer();
            std::cout << "DirectX 12 debug layer enabled" << std::endl;
        }
    }

    // Create DXGI factory
    ComPtr<IDXGIFactory4> factory;
    UINT dxgiFactoryFlags = g_d3d12.debugEnabled ? DXGI_CREATE_FACTORY_DEBUG : 0;
    HRESULT hr = CreateDXGIFactory2(dxgiFactoryFlags, IID_PPV_ARGS(&factory));
    if (FAILED(hr)) {
        std::cerr << "Failed to create DXGI factory: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    // Find hardware adapter
    ComPtr<IDXGIAdapter1> hardwareAdapter;
    for (UINT adapterIndex = 0; ; ++adapterIndex) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(adapterIndex, &adapter) == DXGI_ERROR_NOT_FOUND) {
            break;
        }

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);

        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            continue; // Skip software adapter
        }

        if (SUCCEEDED(D3D12CreateDevice(adapter.Get(), D3D_FEATURE_LEVEL_12_0, _uuidof(ID3D12Device), nullptr))) {
            hardwareAdapter = adapter;
            break;
        }
    }

    if (!hardwareAdapter) {
        std::cerr << "No compatible DirectX 12 hardware adapter found" << std::endl;
        return JNI_FALSE;
    }

    // Create D3D12 device
    hr = D3D12CreateDevice(
        hardwareAdapter.Get(),
        D3D_FEATURE_LEVEL_12_0,
        IID_PPV_ARGS(&g_d3d12.device)
    );

    if (FAILED(hr)) {
        std::cerr << "Failed to create D3D12 device: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    // Create command queue
    D3D12_COMMAND_QUEUE_DESC queueDesc = {};
    queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;

    hr = g_d3d12.device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&g_d3d12.commandQueue));
    if (FAILED(hr)) {
        std::cerr << "Failed to create command queue: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    // Create swap chain
    DXGI_SWAP_CHAIN_DESC1 swapChainDesc = {};
    swapChainDesc.BufferCount = D3D12Resources::FRAME_COUNT;
    swapChainDesc.Width = width;
    swapChainDesc.Height = height;
    swapChainDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    swapChainDesc.SampleDesc.Count = 1;

    ComPtr<IDXGISwapChain1> swapChain;
    hr = factory->CreateSwapChainForHwnd(
        g_d3d12.commandQueue.Get(),
        g_d3d12.hwnd,
        &swapChainDesc,
        nullptr,
        nullptr,
        &swapChain
    );

    if (FAILED(hr)) {
        std::cerr << "Failed to create swap chain: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    hr = swapChain.As(&g_d3d12.swapChain);
    if (FAILED(hr)) {
        std::cerr << "Failed to query IDXGISwapChain3: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    g_d3d12.frameIndex = g_d3d12.swapChain->GetCurrentBackBufferIndex();

    // Disable Alt+Enter fullscreen toggle
    factory->MakeWindowAssociation(g_d3d12.hwnd, DXGI_MWA_NO_ALT_ENTER);

    // Create descriptor heaps
    D3D12_DESCRIPTOR_HEAP_DESC rtvHeapDesc = {};
    rtvHeapDesc.NumDescriptors = D3D12Resources::FRAME_COUNT;
    rtvHeapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    rtvHeapDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    hr = g_d3d12.device->CreateDescriptorHeap(&rtvHeapDesc, IID_PPV_ARGS(&g_d3d12.rtvHeap));
    if (FAILED(hr)) {
        std::cerr << "Failed to create RTV descriptor heap: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    g_d3d12.rtvDescriptorSize = g_d3d12.device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);

    D3D12_DESCRIPTOR_HEAP_DESC dsvHeapDesc = {};
    dsvHeapDesc.NumDescriptors = 1;
    dsvHeapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_DSV;
    dsvHeapDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    hr = g_d3d12.device->CreateDescriptorHeap(&dsvHeapDesc, IID_PPV_ARGS(&g_d3d12.dsvHeap));
    if (FAILED(hr)) {
        std::cerr << "Failed to create DSV descriptor heap: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    g_d3d12.dsvDescriptorSize = g_d3d12.device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_DSV);

    D3D12_DESCRIPTOR_HEAP_DESC cbvHeapDesc = {};
    cbvHeapDesc.NumDescriptors = 1000; // Reserve space for CBV/SRV/UAV descriptors
    cbvHeapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    cbvHeapDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    hr = g_d3d12.device->CreateDescriptorHeap(&cbvHeapDesc, IID_PPV_ARGS(&g_d3d12.cbvSrvUavHeap));
    if (FAILED(hr)) {
        std::cerr << "Failed to create CBV/SRV/UAV descriptor heap: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    g_d3d12.cbvSrvUavDescriptorSize = g_d3d12.device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);

    // Create frame resources
    createRenderTargetViews();
    createDepthStencilView();

    // Create command allocators
    for (UINT i = 0; i < D3D12Resources::FRAME_COUNT; i++) {
        hr = g_d3d12.device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&g_d3d12.commandAllocators[i]));
        if (FAILED(hr)) {
            std::cerr << "Failed to create command allocator " << i << ": " << std::hex << hr << std::endl;
            return JNI_FALSE;
        }
        g_d3d12.fenceValues[i] = 0;
    }

    // Create command list
    hr = g_d3d12.device->CreateCommandList(
        0,
        D3D12_COMMAND_LIST_TYPE_DIRECT,
        g_d3d12.commandAllocators[g_d3d12.frameIndex].Get(),
        nullptr,
        IID_PPV_ARGS(&g_d3d12.commandList)
    );

    if (FAILED(hr)) {
        std::cerr << "Failed to create command list: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    // Close the command list for now
    g_d3d12.commandList->Close();

    // Create synchronization objects
    hr = g_d3d12.device->CreateFence(g_d3d12.fenceValues[g_d3d12.frameIndex], D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&g_d3d12.fence));
    if (FAILED(hr)) {
        std::cerr << "Failed to create fence: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    g_d3d12.fenceValues[g_d3d12.frameIndex]++;

    g_d3d12.fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (g_d3d12.fenceEvent == nullptr) {
        std::cerr << "Failed to create fence event" << std::endl;
        return JNI_FALSE;
    }

    // Set viewport and scissor rect
    g_d3d12.viewport.TopLeftX = 0.0f;
    g_d3d12.viewport.TopLeftY = 0.0f;
    g_d3d12.viewport.Width = static_cast<float>(width);
    g_d3d12.viewport.Height = static_cast<float>(height);
    g_d3d12.viewport.MinDepth = 0.0f;
    g_d3d12.viewport.MaxDepth = 1.0f;

    g_d3d12.scissorRect.left = 0;
    g_d3d12.scissorRect.top = 0;
    g_d3d12.scissorRect.right = width;
    g_d3d12.scissorRect.bottom = height;

    g_d3d12.initialized = true;
    std::cout << "DirectX 12 Ultimate initialized successfully" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeShutdown
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d12.initialized) return;

    // Wait for GPU to finish
    waitForGpu();

    // Clean up resources
    g_vertexBuffers.clear();
    g_indexBuffers.clear();
    g_vertexShaderBlobs.clear();
    g_pixelShaderBlobs.clear();
    g_textures.clear();
    g_srvDescriptors.clear();

    if (g_d3d12.fenceEvent != nullptr) {
        CloseHandle(g_d3d12.fenceEvent);
        g_d3d12.fenceEvent = nullptr;
    }

    g_d3d12.fence.Reset();
    g_d3d12.commandList.Reset();

    for (UINT i = 0; i < D3D12Resources::FRAME_COUNT; i++) {
        g_d3d12.commandAllocators[i].Reset();
        g_d3d12.renderTargets[i].Reset();
    }

    g_d3d12.depthStencilBuffer.Reset();
    g_d3d12.cbvSrvUavHeap.Reset();
    g_d3d12.dsvHeap.Reset();
    g_d3d12.rtvHeap.Reset();
    g_d3d12.swapChain.Reset();
    g_d3d12.commandQueue.Reset();
    g_d3d12.pipelineState.Reset();
    g_d3d12.rootSignature.Reset();
    g_d3d12.device.Reset();

    g_d3d12.initialized = false;
    std::cout << "DirectX 12 Ultimate shutdown completed" << std::endl;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeResize
    (JNIEnv* env, jclass clazz, jint width, jint height) {

    if (!g_d3d12.initialized || !g_d3d12.swapChain) return;

    waitForGpu();

    for (UINT i = 0; i < D3D12Resources::FRAME_COUNT; i++) {
        g_d3d12.renderTargets[i].Reset();
        g_d3d12.fenceValues[i] = g_d3d12.fenceValues[g_d3d12.frameIndex];
    }

    g_d3d12.depthStencilBuffer.Reset();

    HRESULT hr = g_d3d12.swapChain->ResizeBuffers(
        D3D12Resources::FRAME_COUNT,
        width,
        height,
        DXGI_FORMAT_R8G8B8A8_UNORM,
        0
    );

    if (SUCCEEDED(hr)) {
        g_d3d12.width = width;
        g_d3d12.height = height;
        g_d3d12.frameIndex = g_d3d12.swapChain->GetCurrentBackBufferIndex();

        createRenderTargetViews();
        createDepthStencilView();

        g_d3d12.viewport.Width = static_cast<float>(width);
        g_d3d12.viewport.Height = static_cast<float>(height);
        g_d3d12.scissorRect.right = width;
        g_d3d12.scissorRect.bottom = height;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBeginFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d12.initialized) return;

    HRESULT hr = g_d3d12.commandAllocators[g_d3d12.frameIndex]->Reset();
    if (FAILED(hr)) {
        std::cerr << "Failed to reset command allocator" << std::endl;
        return;
    }

    hr = g_d3d12.commandList->Reset(g_d3d12.commandAllocators[g_d3d12.frameIndex].Get(), g_d3d12.pipelineState.Get());
    if (FAILED(hr)) {
        std::cerr << "Failed to reset command list" << std::endl;
        return;
    }

    // Transition render target from present to render target state
    D3D12_RESOURCE_BARRIER barrier = {};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;
    barrier.Transition.pResource = g_d3d12.renderTargets[g_d3d12.frameIndex].Get();
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_PRESENT;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_RENDER_TARGET;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_d3d12.commandList->ResourceBarrier(1, &barrier);

    // Set render target
    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = g_d3d12.rtvHeap->GetCPUDescriptorHandleForHeapStart();
    rtvHandle.ptr += g_d3d12.frameIndex * g_d3d12.rtvDescriptorSize;

    D3D12_CPU_DESCRIPTOR_HANDLE dsvHandle = g_d3d12.dsvHeap->GetCPUDescriptorHandleForHeapStart();
    g_d3d12.commandList->OMSetRenderTargets(1, &rtvHandle, FALSE, &dsvHandle);

    // Set viewport and scissor rect
    g_d3d12.commandList->RSSetViewports(1, &g_d3d12.viewport);
    g_d3d12.commandList->RSSetScissorRects(1, &g_d3d12.scissorRect);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeEndFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d12.initialized || !g_d3d12.swapChain) return;

    // Transition render target from render target to present state
    D3D12_RESOURCE_BARRIER barrier = {};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;
    barrier.Transition.pResource = g_d3d12.renderTargets[g_d3d12.frameIndex].Get();
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_PRESENT;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_d3d12.commandList->ResourceBarrier(1, &barrier);

    HRESULT hr = g_d3d12.commandList->Close();
    if (FAILED(hr)) {
        std::cerr << "Failed to close command list" << std::endl;
        return;
    }

    // Execute command list
    ID3D12CommandList* commandLists[] = { g_d3d12.commandList.Get() };
    g_d3d12.commandQueue->ExecuteCommandLists(_countof(commandLists), commandLists);

    // Present
    hr = g_d3d12.swapChain->Present(1, 0);
    if (FAILED(hr)) {
        std::cerr << "Failed to present: " << std::hex << hr << std::endl;
    }

    moveToNextFrame();
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeClear
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_d3d12.initialized) return;

    float clearColor[4] = { r, g, b, a };

    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = g_d3d12.rtvHeap->GetCPUDescriptorHandleForHeapStart();
    rtvHandle.ptr += g_d3d12.frameIndex * g_d3d12.rtvDescriptorSize;

    D3D12_CPU_DESCRIPTOR_HANDLE dsvHandle = g_d3d12.dsvHeap->GetCPUDescriptorHandleForHeapStart();

    g_d3d12.commandList->ClearRenderTargetView(rtvHandle, clearColor, 0, nullptr);
    g_d3d12.commandList->ClearDepthStencilView(dsvHandle, D3D12_CLEAR_FLAG_DEPTH, 1.0f, 0, 0, nullptr);
}

// Stub implementations for other JNI methods
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride) {
    // TODO: Implement D3D12 vertex buffer creation
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format) {
    // TODO: Implement D3D12 index buffer creation
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type) {
    // TODO: Implement D3D12 shader creation
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreatePipelineState
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader) {
    // TODO: Implement D3D12 pipeline state creation
    return 0;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDestroyResource
    (JNIEnv* env, jclass clazz, jlong handle) {
    uint64_t h = static_cast<uint64_t>(handle);
    g_vertexBuffers.erase(h);
    g_indexBuffers.erase(h);
    g_vertexShaderBlobs.erase(h);
    g_pixelShaderBlobs.erase(h);
    g_textures.erase(h);
    g_srvDescriptors.erase(h);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetPipelineState
    (JNIEnv* env, jclass clazz, jlong pipeline) {
    // TODO: Implement D3D12 pipeline state setting
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDraw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint vertexCount, jint indexCount) {
    // TODO: Implement D3D12 draw call
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeIsInitialized
    (JNIEnv* env, jclass clazz) {
    return g_d3d12.initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology) {
    // TODO: Implement D3D12 primitive topology setting
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeDrawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize) {
    // TODO: Implement D3D12 mesh data drawing
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeCreateTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format) {
    // TODO: Implement D3D12 texture creation
    return 0;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeBindTexture
    (JNIEnv* env, jclass clazz, jlong texture, jint slot) {
    // TODO: Implement D3D12 texture binding
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot) {
    // TODO: Implement D3D12 constant buffer setting
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetViewport
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {
    g_d3d12.viewport.TopLeftX = static_cast<float>(x);
    g_d3d12.viewport.TopLeftY = static_cast<float>(y);
    g_d3d12.viewport.Width = static_cast<float>(width);
    g_d3d12.viewport.Height = static_cast<float>(height);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D12Renderer_nativeSetScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {
    g_d3d12.scissorRect.left = x;
    g_d3d12.scissorRect.top = y;
    g_d3d12.scissorRect.right = x + width;
    g_d3d12.scissorRect.bottom = y + height;
}
