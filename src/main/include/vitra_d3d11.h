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
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug, jboolean useWarp);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdown
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_recreateSwapChain
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginFrame
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_endFrame
    (JNIEnv* env, jclass clazz);

// REMOVED: Old clear(float, float, float, float) - replaced by clear__I(int mask)

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setClearColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLShader
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawWithVertexFormat
    (JNIEnv* env, jclass clazz, jlong vbHandle, jlong ibHandle, jint baseVertex, jint firstIndex, jint vertexOrIndexCount, jint instanceCount, jintArray vertexFormatDesc);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isInitialized
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTextureFromId
    (JNIEnv* env, jclass clazz, jint textureId, jint width, jint height, jint format);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_updateTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle, jbyteArray data, jint width, jint height, jint mipLevel);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture__IJ
    (JNIEnv* env, jclass clazz, jint slot, jlong textureHandle);

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

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDebugStats
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeProcessDebugMessages
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

// ==================== DIRECT OPENGL → DIRECTX 11 TRANSLATION (VULKANMOD APPROACH) ====================

// Create shader from precompiled bytecode (bytecode array, size, type)
// This is the ONLY version of createGLProgramShader - stub version removed
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLProgramShader___3BII
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shaderSource
    (JNIEnv* env, jclass clazz, jint shader, jstring source);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShader
    (JNIEnv* env, jclass clazz, jint shader);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createProgram
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_attachShader
    (JNIEnv* env, jclass clazz, jint program, jint shader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_linkProgram
    (JNIEnv* env, jclass clazz, jint program);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_validateProgram
    (JNIEnv* env, jclass clazz, jint program);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteShader
    (JNIEnv* env, jclass clazz, jint shader);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteProgram
    (JNIEnv* env, jclass clazz, jint program);

// Vertex attribute translation (OpenGL → DirectX 11 input layout)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jlong pointer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribPointer_1
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jobject pointer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribIPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jint stride, jlong pointer);

// Uniform location translation (OpenGL → DirectX 11 constant buffers)
JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetUniformLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetUniformLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetAttribLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetAttribLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name);

// ==================== UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts) ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform4f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2, jfloat v3);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniformMatrix4f
    (JNIEnv* env, jclass clazz, jint location, jfloatArray matrix, jboolean transpose);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1i
    (JNIEnv* env, jclass clazz, jint location, jint value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1f
    (JNIEnv* env, jclass clazz, jint location, jfloat value);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform2f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform3f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_useProgram
    (JNIEnv* env, jclass clazz, jint program);

// ==================== MATRIX MANAGEMENT ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setProjectionMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setModelViewMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderFogColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderLightDirection
    (JNIEnv* env, jclass clazz, jint index, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderTargetForWriting
    (JNIEnv* env, jclass clazz, jlong renderTargetHandle, jboolean updateScissor);

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

// ==================== MISSING FRAMEBUFFER AND TEXTURE METHODS ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createFramebuffer
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferTexture2D
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint textarget, jlong textureHandle, jint level);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferRenderbuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint renderbuffertarget, jlong renderbufferHandle);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_checkFramebufferStatus
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createRenderbuffer
    (JNIEnv* env, jclass clazz, jint width, jint height, jint format);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderbufferStorage
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target, jint internalformat, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexArray
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle);

// ==================== MISSING UNIFORM AND STATE METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureParameterf
    (JNIEnv* env, jclass clazz, jint target, jint pname, jfloat param);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTextureLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setLineWidth
    (JNIEnv* env, jclass clazz, jfloat width);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPolygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendFunc
    (JNIEnv* env, jclass clazz, jint sfactor, jint dfactor);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendEquation
    (JNIEnv* env, jclass clazz, jint mode, jint modeAlpha);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setDrawBuffers
    (JNIEnv* env, jclass clazz, jintArray buffers);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilOpSeparate
    (JNIEnv* env, jclass clazz, jint face, jint sfail, jint dpfail, jint dppass);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilFuncSeparate
    (JNIEnv* env, jclass clazz, jint face, jint func, jint ref, jint mask);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilMaskSeparate
    (JNIEnv* env, jclass clazz, jint face, jint mask);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getMaxTextureSize
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_finish
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setHint
    (JNIEnv* env, jclass clazz, jint target, jint hint);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTexSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint xoffset, jint yoffset, jint x, jint y, jint width, jint height);

// ==================== PERFORMANCE OPTIMIZATION METHODS ====================
// Based on Direct3D 11 Performance Optimization Documentation

// Multithreading and Command List Support
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createDeferredContext
    (JNIEnv* env, jclass clazz);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createCommandList
    (JNIEnv* env, jclass clazz, jlong deferredContextHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_executeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_closeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle);

// Batching and Optimization
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginTextBatch
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_endTextBatch
    (JNIEnv* env, jclass clazz);

// REMOVED: beginFrameSafe and endFrameSafe declarations

// Performance Profiling and Debugging
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDebugStats
    (JNIEnv* env, jclass clazz);


JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isDebugEnabled
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDebug
    (JNIEnv* env, jclass clazz, jboolean enable);

// Resource Optimization
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_precompileShaderForDirectX11
    (JNIEnv* env, jclass clazz, jbyteArray hlslBytecode, jint size, jstring entryPoint, jstring target);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_discardResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_evictResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isResident
    (JNIEnv* env, jclass clazz, jlong resourceHandle);

// Rendering Optimizations (Minecraft-Specific)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeCrosshairRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeButtonRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeContainerBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeContainerLabels
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeDirtBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeFadingBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeLogoRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizePanoramaRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeScreenBackground
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeSlotHighlight
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeSlotRendering
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeTooltipRendering
    (JNIEnv* env, jclass clazz);

// Matrix Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isMatrixDirectX11Optimized
    (JNIEnv* env, jclass clazz, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixMultiplication
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrixA, jfloatArray matrixB);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixInversion
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixTranspose
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeTranslationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeRotationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat angle, jfloat x, jfloat y, jfloat z);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeScaleMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z);

// Shader Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isShaderDirectX11Compatible
    (JNIEnv* env, jclass clazz, jlong shaderHandle);

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getOptimizedDirectX11Shader
    (JNIEnv* env, jclass clazz, jlong originalShaderHandle);

// Frame Synchronization and VSync
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setVsync
    (JNIEnv* env, jclass clazz, jboolean enabled);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getOptimalFramerateLimit
    (JNIEnv* env, jclass clazz);

// GPU Synchronization and Resource Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForGpuCommands
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForIdle
    (JNIEnv* env, jclass clazz);

// Display and Window Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_handleDisplayResize
    (JNIEnv* env, jclass clazz, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setWindowActiveState
    (JNIEnv* env, jclass clazz, jboolean isActive);

// Initialization and Cleanup
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectXSafe
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdownSafe
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_prepareRenderContext
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_cleanupRenderContext
    (JNIEnv* env, jclass clazz);

// Buffer Management Optimizations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_adjustOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_adjustPerspectiveProjection
    (JNIEnv* env, jclass clazz, jfloat fovy, jfloat aspect, jfloat zNear, jfloat zFar);

// Rendering Pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawMesh
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clearDepthBuffer
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_presentFrame
    (JNIEnv* env, jclass clazz);

// Shader Pipeline Management
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Resource Resize
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height);

// ==================== GLSTATEMANAGER COMPATIBILITY METHODS ====================

// Texture operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture__I
    (JNIEnv* env, jclass clazz, jint textureId);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteTexture__I
    (JNIEnv* env, jclass clazz, jint textureId);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_releaseTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint internalFormat,
     jint width, jint height, jint border, jint format, jint type, jobject pixels);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texSubImage2DWithPitch
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels, jint rowPitch);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_activeTexture
    (JNIEnv* env, jclass clazz, jint texture);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texParameteri
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTexLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_pixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param);

// Blend state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableBlend
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableBlend
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendFunc
    (JNIEnv* env, jclass clazz, jint srcFactor, jint dstFactor);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendFuncSeparate
    (JNIEnv* env, jclass clazz, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendEquation
    (JNIEnv* env, jclass clazz, jint mode);

// Depth state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableDepthTest
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableDepthTest
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_depthFunc
    (JNIEnv* env, jclass clazz, jint func);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_depthMask
    (JNIEnv* env, jclass clazz, jboolean flag);

// Cull state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableCull
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableCull
    (JNIEnv* env, jclass clazz);

// Scissor/viewport
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resetScissor
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setScissor
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height);

// Clear operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clear
    (JNIEnv* env, jclass clazz, jint mask);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_colorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha);

// Polygon operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPolygonMode
    (JNIEnv* env, jclass clazz, jint face, jint mode);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enablePolygonOffset
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disablePolygonOffset
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_polygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units);

// Color logic operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableColorLogicOp
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableColorLogicOp
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_logicOp
    (JNIEnv* env, jclass clazz, jint opcode);

// Buffer operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint buffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__ILjava_nio_ByteBuffer_2I
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__ILjava_nio_ByteBuffer_2II
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage, jint stride);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__IJI
    (JNIEnv* env, jclass clazz, jint target, jlong size, jint usage);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteBuffer__I
    (JNIEnv* env, jclass clazz, jint buffer);

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_mapBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint access);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_unmapBuffer__I
    (JNIEnv* env, jclass clazz, jint target);

// Framebuffer operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferTexture2D__IIIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint textarget, jint texture, jint level);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferRenderbuffer__IIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderbufferStorage__IIII
    (JNIEnv* env, jclass clazz, jint target, jint internalformat, jint width, jint height);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindFramebuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint framebuffer);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderbuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint renderbuffer);

// ==================== MAINTARGET COMPATIBILITY METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindMainRenderTarget
    (JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindMainRenderTargetTexture
    (JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getMainColorTextureId
    (JNIEnv* env, jclass clazz);

// ==================== HLSL SHADER COMPILATION (PHASE 2 - SHADER SYSTEM) ====================

// Runtime HLSL shader compilation
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShader__Ljava_nio_ByteBuffer_2ILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jobject sourceBuffer, jint sourceLength, jstring target, jstring debugName);

// HLSL shader compilation from file
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShaderFromFile
    (JNIEnv* env, jclass clazz, jstring filePath, jstring target, jstring debugName);

// Create shader from precompiled bytecode
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderFromBytecode
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint bytecodeLength, jstring shaderType);

// Pipeline creation from vertex + pixel shaders (overload for new shader system)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderPipeline__JJ
    (JNIEnv* env, jclass clazz, jlong vertexShaderHandle, jlong pixelShaderHandle);

// Bind shader pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Destroy shader pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle);

// Constant buffer creation
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createConstantBuffer
    (JNIEnv* env, jclass clazz, jint size);

// Update constant buffer data
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_updateConstantBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jbyteArray data);

// Bind constant buffer to vertex shader stage
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindConstantBufferVS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle);

// Bind constant buffer to pixel shader stage
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindConstantBufferPS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle);

// Retrieve last shader compilation error
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getLastShaderError
    (JNIEnv* env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif // VITRA_D3D11_H