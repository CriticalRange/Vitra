#ifndef VITRA_D3D11_H
#define VITRA_D3D11_H

#include <jni.h>
#include <cstdint>
#include <windows.h>
#include <d3d11_3.h>
#include <dxgi1_4.h>
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

    // Additional default shaders for different vertex formats
    ComPtr<ID3D11VertexShader> defaultVertexShaderWithUV;
    ComPtr<ID3D11PixelShader> defaultPixelShaderWithUV;
    ComPtr<ID3D11InputLayout> defaultInputLayoutWithUV;
    ComPtr<ID3D11VertexShader> defaultVertexShaderWithoutUV;
    ComPtr<ID3D11PixelShader> defaultPixelShaderWithoutUV;
    ComPtr<ID3D11InputLayout> defaultInputLayoutWithoutUV;
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
    uint64_t boundVertexShader;  // Track current vertex shader for input layout creation
    uint64_t boundPixelShader;   // Track current pixel shader

    // Texture management
    std::vector<ComPtr<ID3D11ShaderResourceView>> shaderResourceViews;
    std::vector<ComPtr<ID3D11SamplerState>> samplerStates;
    ComPtr<ID3D11Buffer> constantBuffers[4]; // b0-b3 registers

    // Rasterizer state
    ComPtr<ID3D11RasterizerState> rasterizerState;
    ComPtr<ID3D11DepthStencilState> depthStencilState;
    ComPtr<ID3D11BlendState> blendState;

    // CRITICAL FIX: Cached depth stencil states (reuse instead of recreating every frame)
    // According to DirectX best practices, create once and reuse
    ComPtr<ID3D11DepthStencilState> depthStencilStateEnabled;   // DepthEnable=TRUE
    ComPtr<ID3D11DepthStencilState> depthStencilStateDisabled;  // DepthEnable=FALSE

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

    // Debug statistics tracking
    struct {
        uint64_t totalMessages;
        uint64_t corruptionCount;
        uint64_t errorCount;
        uint64_t warningCount;
        uint64_t infoCount;
        uint64_t messagesProcessed;
        uint64_t framesWithErrors;
        uint64_t currentFrameErrors;
    } debugStats;

    // Clear color state (FIX: Missing - causes pink background)
    float clearColor[4];

    // Shader color state (ColorModulator uniform - CRITICAL FIX for red screen issue)
    float shaderColor[4];  // RGBA multiplier for all fragments

    // FIX #5: Async fence system for frame pipelining (VulkanMod pattern)
    struct FrameFence {
        ComPtr<ID3D11Query> disjointQuery;
        ComPtr<ID3D11Query> timestampStart;
        ComPtr<ID3D11Query> timestampEnd;
        bool signaled;
    };
    static const int MAX_FRAMES_IN_FLIGHT = 2;
    FrameFence frameFences[2];  // Double buffering
    int currentFrameIndex;

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
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug, jboolean useWarp);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shutdown
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_recreateSwapChain
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_beginFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_endFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blitTextureToBackBuffer
    (JNIEnv* env, jclass clazz, jlong textureHandle, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resetDynamicState
    (JNIEnv* env, jclass clazz);

// REMOVED: Old clear(float, float, float, float) - replaced by clear__I(int mask)

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setClearColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createGLShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createShaderPipeline
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyResource
    (JNIEnv* env, jclass clazz, jlong handle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipeline);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_draw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint baseVertex, jint firstIndex, jint indexCount, jint instanceCount);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawWithVertexFormat
    (JNIEnv* env, jclass clazz, jlong vbHandle, jlong ibHandle, jint baseVertex, jint firstIndex, jint vertexOrIndexCount, jint instanceCount, jintArray vertexFormatDesc);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isInitialized
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTextureFromId
    (JNIEnv* env, jclass clazz, jint textureId, jint width, jint height, jint format);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTextureFromData
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_updateTextureMipLevel
    (JNIEnv* env, jclass clazz, jlong textureHandle, jbyteArray data, jint width, jint height, jint mipLevel);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_updateTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle, jbyteArray data, jint width, jint height, jint mipLevel);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTexture__IJ
    (JNIEnv* env, jclass clazz, jint slot, jlong textureHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setViewport
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDebugMessages
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeClearDebugMessages
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeSetDebugSeverity
    (JNIEnv* env, jclass clazz, jint severity);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeBreakOnError
    (JNIEnv* env, jclass clazz, jboolean enabled);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDeviceInfo
    (JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDebugStats
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeProcessDebugMessages
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeValidateShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size);

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_mapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jint size, jint accessFlags);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_unmapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setInputLayoutFromVertexFormat
    (JNIEnv* env, jclass clazz, jlong vertexShaderHandle, jintArray vertexFormatDesc);

// ==================== RENDER STATE MANAGEMENT ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendState
    (JNIEnv* env, jclass clazz, jboolean enabled, jint srcBlend, jint destBlend, jint blendOp);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setDepthState
    (JNIEnv* env, jclass clazz, jboolean depthTestEnabled, jboolean depthWriteEnabled, jint depthFunc);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setRasterizerState
    (JNIEnv* env, jclass clazz, jint cullMode, jint fillMode, jboolean scissorEnabled);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clearDepth
    (JNIEnv* env, jclass clazz, jfloat depth);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setColorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha);

// ==================== DIRECT OPENGL → DIRECTX 11 TRANSLATION (VULKANMOD APPROACH) ====================

// Create shader from precompiled bytecode (bytecode array, size, type)
// This is the ONLY version of createGLProgramShader - stub version removed
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createGLProgramShader___3BII
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shaderSource
    (JNIEnv* env, jclass clazz, jint shader, jstring source);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShader
    (JNIEnv* env, jclass clazz, jint shader);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createProgram
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_attachShader
    (JNIEnv* env, jclass clazz, jint program, jint shader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_linkProgram
    (JNIEnv* env, jclass clazz, jint program);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_validateProgram
    (JNIEnv* env, jclass clazz, jint program);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteShader
    (JNIEnv* env, jclass clazz, jint shader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteProgram
    (JNIEnv* env, jclass clazz, jint program);

// Vertex attribute translation (OpenGL → DirectX 11 input layout)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jlong pointer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribPointer_1
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jobject pointer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribIPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jint stride, jlong pointer);

// Uniform location translation (OpenGL → DirectX 11 constant buffers)
JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetUniformLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetUniformLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetAttribLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetAttribLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name);

// ==================== UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts) ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform4f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2, jfloat v3);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniformMatrix4f
    (JNIEnv* env, jclass clazz, jint location, jfloatArray matrix, jboolean transpose);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform1i
    (JNIEnv* env, jclass clazz, jint location, jint value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform1f
    (JNIEnv* env, jclass clazz, jint location, jfloat value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform2f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform3f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_useProgram
    (JNIEnv* env, jclass clazz, jint program);

// ==================== MATRIX MANAGEMENT ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setProjectionMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setModelViewMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderFogColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderLightDirection
    (JNIEnv* env, jclass clazz, jint index, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderTargetForWriting
    (JNIEnv* env, jclass clazz, jlong renderTargetHandle, jboolean updateScissor);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTransformMatrices
    (JNIEnv* env, jclass clazz, jfloatArray mvpData, jfloatArray modelViewData, jfloatArray projectionData);

// ==================== BUFFER/TEXTURE COPY OPERATIONS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyBuffer
    (JNIEnv* env, jclass clazz, jlong srcBufferHandle, jlong dstBufferHandle,
     jint srcOffset, jint dstOffset, jint size);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTexture
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTextureRegion
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle,
     jint srcX, jint srcY, jint srcZ, jint dstX, jint dstY, jint dstZ,
     jint width, jint height, jint depth, jint mipLevel);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTextureToBuffer
    (JNIEnv* env, jclass clazz, jlong textureHandle, jlong bufferHandle, jint mipLevel);

// ==================== GPU SYNCHRONIZATION (FENCES/QUERIES) ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFence
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_signalFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isFenceSignaled
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle);

// ==================== RENDERDOC INTEGRATION ====================

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocIsAvailable
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocStartFrameCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocEndFrameCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocTriggerCapture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocIsCapturing
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocSetCaptureOption
    (JNIEnv* env, jclass clazz, jint option, jint value);

// ==================== MISSING FRAMEBUFFER AND TEXTURE METHODS ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFramebuffer
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferTexture2D
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint textarget, jlong textureHandle, jint level);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferRenderbuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint renderbuffertarget, jlong renderbufferHandle);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_checkFramebufferStatus
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFramebufferTextures
    (JNIEnv* env, jclass clazz, jint framebufferId, jint width, jint height, jboolean hasColor, jboolean hasDepth);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createRenderbuffer
    (JNIEnv* env, jclass clazz, jint width, jint height, jint format);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderbufferStorage
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target, jint internalformat, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createVertexArray
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle);

// ==================== MISSING UNIFORM AND STATE METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameterf
    (JNIEnv* env, jclass clazz, jint target, jint pname, jfloat param);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameter__JII
    (JNIEnv* env, jclass clazz, jlong textureHandle, jint pname, jint param);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTextureLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setLineWidth
    (JNIEnv* env, jclass clazz, jfloat width);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPolygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendFunc
    (JNIEnv* env, jclass clazz, jint sfactor, jint dfactor);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendEquation
    (JNIEnv* env, jclass clazz, jint mode, jint modeAlpha);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setDrawBuffers
    (JNIEnv* env, jclass clazz, jintArray buffers);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilOpSeparate
    (JNIEnv* env, jclass clazz, jint face, jint sfail, jint dpfail, jint dppass);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilFuncSeparate
    (JNIEnv* env, jclass clazz, jint face, jint func, jint ref, jint mask);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilMaskSeparate
    (JNIEnv* env, jclass clazz, jint face, jint mask);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getMaxTextureSize
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_finish
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setHint
    (JNIEnv* env, jclass clazz, jint target, jint hint);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTexSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint xoffset, jint yoffset, jint x, jint y, jint width, jint height);

// ==================== PERFORMANCE OPTIMIZATION METHODS ====================
// Based on Direct3D 11 Performance Optimization Documentation

// Multithreading and Command List Support
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createDeferredContext
    (JNIEnv* env, jclass clazz);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createCommandList
    (JNIEnv* env, jclass clazz, jlong deferredContextHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_executeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_closeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle);

// Batching and Optimization
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_beginTextBatch
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_endTextBatch
    (JNIEnv* env, jclass clazz);

// REMOVED: beginFrameSafe and endFrameSafe declarations

// Performance Profiling and Debugging
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getDebugStats
    (JNIEnv* env, jclass clazz);


JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isDebugEnabled
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDebug
    (JNIEnv* env, jclass clazz, jboolean enable);

// Resource Optimization
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_precompileShaderForDirectX11
    (JNIEnv* env, jclass clazz, jbyteArray hlslBytecode, jint size, jstring entryPoint, jstring target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_discardResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_evictResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isResident
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

// Rendering Optimizations (Minecraft-Specific)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeCrosshairRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeButtonRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeContainerBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeContainerLabels
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeDirtBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeFadingBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeLogoRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizePanoramaRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeScreenBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeSlotHighlight
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeSlotRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeTooltipRendering
    (JNIEnv* env, jclass clazz);

// Matrix Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isMatrixDirectX11Optimized
    (JNIEnv* env, jclass clazz, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixMultiplication
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrixA, jfloatArray matrixB);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixInversion
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixTranspose
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeTranslationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeRotationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat angle, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeScaleMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z);

// Shader Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isShaderDirectX11Compatible
    (JNIEnv* env, jclass clazz, jlong shaderHandle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getOptimizedDirectX11Shader
    (JNIEnv* env, jclass clazz, jlong originalShaderHandle);

// Frame Synchronization and VSync
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setVsync
    (JNIEnv* env, jclass clazz, jboolean enabled);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getOptimalFramerateLimit
    (JNIEnv* env, jclass clazz);

// GPU Synchronization and Resource Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForGpuCommands
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForIdle
    (JNIEnv* env, jclass clazz);

// FIX #1: Submit pending uploads (VulkanMod pattern)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_submitPendingUploads
    (JNIEnv* env, jclass clazz);

// Display and Window Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_handleDisplayResize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setWindowActiveState
    (JNIEnv* env, jclass clazz, jboolean isActive);

// Initialization and Cleanup
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDirectXSafe
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shutdownSafe
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_prepareRenderContext
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_cleanupRenderContext
    (JNIEnv* env, jclass clazz);

// Buffer Management Optimizations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_adjustOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_adjustPerspectiveProjection
    (JNIEnv* env, jclass clazz, jfloat fovy, jfloat aspect, jfloat zNear, jfloat zFar);

// Rendering Pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawMesh
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clearDepthBuffer
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_presentFrame
    (JNIEnv* env, jclass clazz);

// Shader Pipeline Management
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Resource Resize
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height);

// ==================== GLSTATEMANAGER COMPATIBILITY METHODS ====================

// Texture operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTexture__I
    (JNIEnv* env, jclass clazz, jint textureId);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setActiveTextureUnit
    (JNIEnv* env, jclass clazz, jint textureUnit);

// FIX: Bind texture to specific slot (VulkanMod VTextureSelector pattern)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTextureToSlot__IJ
    (JNIEnv* env, jclass clazz, jint slot, jlong d3d11Handle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteTexture__I
    (JNIEnv* env, jclass clazz, jint textureId);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_releaseTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint internalFormat,
     jint width, jint height, jint border, jint format, jint type, jobject pixels);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texSubImage2DWithPitch
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels, jint rowPitch);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_activeTexture
    (JNIEnv* env, jclass clazz, jint texture);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texParameteri
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTexLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_pixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param);

// Blend state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableBlend
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableBlend
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendFunc
    (JNIEnv* env, jclass clazz, jint srcFactor, jint dstFactor);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendFuncSeparate
    (JNIEnv* env, jclass clazz, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendEquation
    (JNIEnv* env, jclass clazz, jint mode);

// Depth state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableDepthTest
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableDepthTest
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_depthFunc
    (JNIEnv* env, jclass clazz, jint func);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_depthMask
    (JNIEnv* env, jclass clazz, jboolean flag);

// Cull state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableCull
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableCull
    (JNIEnv* env, jclass clazz);

// Scissor/viewport
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resetScissor
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setScissor
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

// Clear operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clear
    (JNIEnv* env, jclass clazz, jint mask);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_colorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha);

// Polygon operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPolygonMode
    (JNIEnv* env, jclass clazz, jint face, jint mode);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enablePolygonOffset
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disablePolygonOffset
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_polygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units);

// Color logic operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableColorLogicOp
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableColorLogicOp
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_logicOp
    (JNIEnv* env, jclass clazz, jint opcode);

// Buffer operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint buffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__ILjava_nio_ByteBuffer_2I
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__ILjava_nio_ByteBuffer_2II
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage, jint stride);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__IJI
    (JNIEnv* env, jclass clazz, jint target, jlong size, jint usage);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteBuffer__I
    (JNIEnv* env, jclass clazz, jint buffer);

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_mapBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint access);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_unmapBuffer__I
    (JNIEnv* env, jclass clazz, jint target);

// Framebuffer operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferTexture2D__IIIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint textarget, jint texture, jint level);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferRenderbuffer__IIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderbufferStorage__IIII
    (JNIEnv* env, jclass clazz, jint target, jint internalformat, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindFramebuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint framebuffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderbuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint renderbuffer);

// ==================== MAINTARGET COMPATIBILITY METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindMainRenderTarget
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindMainRenderTargetTexture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getMainColorTextureId
    (JNIEnv* env, jclass clazz);

// ==================== HLSL SHADER COMPILATION (PHASE 2 - SHADER SYSTEM) ====================

// Runtime HLSL shader compilation (ByteBuffer version)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShader__Ljava_nio_ByteBuffer_2ILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jobject sourceBuffer, jint sourceLength, jstring target, jstring debugName);

// Runtime HLSL shader compilation (byte array version) - RENAMED FOR DIAGNOSTIC
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShaderBYTEARRAY___3BILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jbyteArray sourceArray, jint sourceLength, jstring target, jstring debugName);

// HLSL shader compilation from file
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShaderFromFile
    (JNIEnv* env, jclass clazz, jstring filePath, jstring target, jstring debugName);

// Get bytecode from compiled shader blob
JNIEXPORT jbyteArray JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getBlobBytecode
    (JNIEnv* env, jclass clazz, jlong blobHandle);

// Create shader from precompiled bytecode
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createShaderFromBytecode
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint bytecodeLength, jstring shaderType);

// Pipeline creation from vertex + pixel shaders (overload for new shader system)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createShaderPipeline__JJ
    (JNIEnv* env, jclass clazz, jlong vertexShaderHandle, jlong pixelShaderHandle);

// Bind shader pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Destroy shader pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Constant buffer creation
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createConstantBuffer
    (JNIEnv* env, jclass clazz, jint size);

// Update constant buffer data
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_updateConstantBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jbyteArray data);

// Bind constant buffer to vertex shader stage
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindConstantBufferVS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle);

// Bind constant buffer to pixel shader stage
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindConstantBufferPS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle);

// Upload and bind all UBOs (Uniform Buffer Objects / Constant Buffers)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_uploadAndBindUBOs
    (JNIEnv* env, jclass clazz);

// Retrieve last shader compilation error
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getLastShaderError
    (JNIEnv* env, jclass clazz);

// ==================== RENDER TARGET MANAGEMENT ====================

// Set render target to a specific texture
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setRenderTarget
    (JNIEnv* env, jclass clazz, jlong textureHandle);

// Set render target to the swap chain back buffer
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setRenderTargetToBackBuffer
    (JNIEnv* env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif // VITRA_D3D11_H