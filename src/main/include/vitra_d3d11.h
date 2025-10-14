#ifndef VITRA_D3D11_H
#define VITRA_D3D11_H

#include <jni.h>
#include <cstdint>
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <vector>
#include <unordered_map>
#include "renderdoc_app.h"

using Microsoft::WRL::ComPtr;

#ifdef __cplusplus
extern "C" {
#endif

// Structure to hold DirectX 11 resources
struct D3D11Resources {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<IDXGISwapChain> swapChain;
    ComPtr<ID3D11RenderTargetView> renderTargetView;
    ComPtr<ID3D11DepthStencilView> depthStencilView;
    ComPtr<ID3D11Texture2D> depthStencilBuffer;

    // Default shaders
    ComPtr<ID3D11VertexShader> defaultVertexShader;
    ComPtr<ID3D11PixelShader> defaultPixelShader;
    ComPtr<ID3D11InputLayout> defaultInputLayout;
    ComPtr<ID3D11SamplerState> defaultSamplerState;

    // Default shader pipeline handle (for JNI)
    uint64_t defaultShaderPipelineHandle;

    // Default texture for fallback when no texture is bound
    ComPtr<ID3D11Texture2D> defaultTexture;
    ComPtr<ID3D11ShaderResourceView> defaultTextureSRV;

    // Current state
    ComPtr<ID3D11Buffer> currentVertexBuffer;
    ComPtr<ID3D11Buffer> currentIndexBuffer;
    UINT currentVertexStride;
    UINT currentVertexOffset;
    DXGI_FORMAT currentIndexFormat;
    D3D11_PRIMITIVE_TOPOLOGY currentTopology;

    // Texture management
    std::vector<ComPtr<ID3D11ShaderResourceView>> shaderResourceViews;
    std::vector<ComPtr<ID3D11SamplerState>> samplerStates;
    ComPtr<ID3D11Buffer> constantBuffers[4]; // b0-b3 registers

    // Rasterizer state
    ComPtr<ID3D11RasterizerState> rasterizerState;
    ComPtr<ID3D11DepthStencilState> depthStencilState;
    ComPtr<ID3D11BlendState> blendState;

    // Viewport and scissor
    D3D11_VIEWPORT viewport;
    D3D11_RECT scissorRect;
    bool scissorEnabled;

    // Window information
    HWND hwnd;
    int width;
    int height;
    bool debugEnabled;

    // Debug layer support
    ComPtr<ID3D11InfoQueue> infoQueue;

    // Clear color state (FIX: Missing - causes pink background)
    float clearColor[4];

    bool initialized;
};

// Global D3D11 resources
extern D3D11Resources g_d3d11;

// RenderDoc API support
extern RENDERDOC_API_1_6_0* g_renderDocAPI;
extern bool g_renderDocInitialized;

// Resource tracking
extern std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_vertexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_indexBuffers;
extern std::unordered_map<uint64_t, ComPtr<ID3D11VertexShader>> g_vertexShaders;
extern std::unordered_map<uint64_t, ComPtr<ID3D11PixelShader>> g_pixelShaders;
extern std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_inputLayouts;
extern std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> g_textures;
extern std::unordered_map<uint64_t, ComPtr<ID3D11ShaderResourceView>> g_shaderResourceViews;
extern std::unordered_map<uint64_t, ComPtr<ID3D11Query>> g_queries;

// Helper functions
uint64_t generateHandle();
bool compileShader(const char* source, const char* target, ID3DBlob** blob);
bool createInputLayout(ID3DBlob* vertexShaderBlob, ID3D11InputLayout** inputLayout);
void updateRenderTargetView();
void setDefaultShaders();
void createDefaultTexture();
void createDefaultRenderStates();

// RenderDoc helper functions
bool initializeRenderDoc();
void shutdownRenderDoc();
void setRenderDocResourceName(ID3D11DeviceChild* resource, const char* name);

// JNI export functions
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdown
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_endFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clear
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setClearColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderPipeline
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyResource
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipeline);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_draw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint baseVertex, jint firstIndex, jint indexCount, jint instanceCount);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isInitialized
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_updateTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle, jbyteArray data, jint width, jint height, jint mipLevel);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture
    (JNIEnv* env, jclass clazz, jlong texture, jint slot);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setViewport
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDebugMessages
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeClearDebugMessages
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeSetDebugSeverity
    (JNIEnv* env, jclass clazz, jint severity);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeBreakOnError
    (JNIEnv* env, jclass clazz, jboolean enabled);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDeviceInfo
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeValidateShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size);

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_mapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jint size, jint accessFlags);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_unmapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle);

// ==================== RENDER STATE MANAGEMENT ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendState
    (JNIEnv* env, jclass clazz, jboolean enabled, jint srcBlend, jint destBlend, jint blendOp);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setDepthState
    (JNIEnv* env, jclass clazz, jboolean depthTestEnabled, jboolean depthWriteEnabled, jint depthFunc);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setRasterizerState
    (JNIEnv* env, jclass clazz, jint cullMode, jint fillMode, jboolean scissorEnabled);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clearDepth
    (JNIEnv* env, jclass clazz, jfloat depth);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setColorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha);

// ==================== UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts) ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform4f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2, jfloat v3);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniformMatrix4f
    (JNIEnv* env, jclass clazz, jint location, jfloatArray matrix, jboolean transpose);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1i
    (JNIEnv* env, jclass clazz, jint location, jint value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1f
    (JNIEnv* env, jclass clazz, jint location, jfloat value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_useProgram
    (JNIEnv* env, jclass clazz, jint program);

// ==================== MATRIX MANAGEMENT ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setProjectionMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTransformMatrices
    (JNIEnv* env, jclass clazz, jfloatArray mvpData, jfloatArray modelViewData, jfloatArray projectionData);

// ==================== BUFFER/TEXTURE COPY OPERATIONS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyBuffer
    (JNIEnv* env, jclass clazz, jlong srcBufferHandle, jlong dstBufferHandle,
     jint srcOffset, jint dstOffset, jint size);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTexture
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTextureRegion
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle,
     jint srcX, jint srcY, jint srcZ, jint dstX, jint dstY, jint dstZ,
     jint width, jint height, jint depth, jint mipLevel);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTextureToBuffer
    (JNIEnv* env, jclass clazz, jlong textureHandle, jlong bufferHandle, jint mipLevel);

// ==================== GPU SYNCHRONIZATION (FENCES/QUERIES) ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createFence
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_signalFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isFenceSignaled
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

// ==================== RENDERDOC INTEGRATION ====================

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocIsAvailable
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocStartFrameCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocEndFrameCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocTriggerCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocIsCapturing
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocSetCaptureOption
    (JNIEnv* env, jclass clazz, jint option, jint value);

#ifdef __cplusplus
}
#endif

#endif // VITRA_D3D11_H