#ifndef VITRA_D3D12_H
#define VITRA_D3D12_H

#include <jni.h>
#include <cstdint>
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <vector>
#include <unordered_map>

using Microsoft::WRL::ComPtr;

#ifdef __cplusplus
extern "C" {
#endif

// Structure to hold DirectX 12 resources
struct D3D12Resources {
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> commandQueue;
    ComPtr<IDXGISwapChain3> swapChain;
    ComPtr<ID3D12DescriptorHeap> rtvHeap;
    ComPtr<ID3D12DescriptorHeap> dsvHeap;
    ComPtr<ID3D12DescriptorHeap> cbvSrvUavHeap;

    // Frame resources
    static const UINT FRAME_COUNT = 2;
    ComPtr<ID3D12Resource> renderTargets[FRAME_COUNT];
    ComPtr<ID3D12Resource> depthStencilBuffer;
    ComPtr<ID3D12CommandAllocator> commandAllocators[FRAME_COUNT];
    ComPtr<ID3D12GraphicsCommandList> commandList;

    // Root signature and pipeline state
    ComPtr<ID3D12RootSignature> rootSignature;
    ComPtr<ID3D12PipelineState> pipelineState;

    // Synchronization objects
    ComPtr<ID3D12Fence> fence;
    UINT64 fenceValues[FRAME_COUNT];
    HANDLE fenceEvent;
    UINT frameIndex;

    // Descriptor sizes
    UINT rtvDescriptorSize;
    UINT dsvDescriptorSize;
    UINT cbvSrvUavDescriptorSize;

    // Viewport and scissor rect
    D3D12_VIEWPORT viewport;
    D3D12_RECT scissorRect;

    // Window information
    HWND hwnd;
    int width;
    int height;
    bool debugEnabled;
    bool initialized;
};

// Global D3D12 resources
extern D3D12Resources g_d3d12;

// Resource tracking with 64-bit handles
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_vertexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_indexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_vertexShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_pixelShaderBlobs;
extern std::unordered_map<uint64_t, ComPtr<ID3D12Resource>> g_textures;
extern std::unordered_map<uint64_t, D3D12_CPU_DESCRIPTOR_HANDLE> g_srvDescriptors;

// Helper functions
uint64_t generateHandle();
bool compileShader(const char* source, const char* target, ID3DBlob** blob);
void createRenderTargetViews();
void createDepthStencilView();
void waitForGpu();
void moveToNextFrame();

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

#ifdef __cplusplus
}
#endif

#endif // VITRA_D3D12_H
