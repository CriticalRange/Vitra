#include "vitra_d3d11.h"
#include <iostream>
#include <sstream>
#include <random>

// Global D3D11 resources
D3D11Resources g_d3d11 = {};

// RenderDoc API support
RENDERDOC_API_1_6_0* g_renderDocAPI = nullptr;
bool g_renderDocInitialized = false;

// ==================== JNI LOGGING INFRASTRUCTURE ====================
// JavaVM pointer - cached in JNI_OnLoad for logging callbacks
static JavaVM* g_jvm = nullptr;

// Cached logging method IDs (for performance - avoid repeated lookups)
static jclass g_loggerClass = nullptr;
static jmethodID g_logDebugMethod = nullptr;
static jmethodID g_logInfoMethod = nullptr;
static jmethodID g_logWarnMethod = nullptr;
static jmethodID g_logErrorMethod = nullptr;

/**
 * JNI_OnLoad - called when native library is loaded
 * This is where we cache the JavaVM pointer for later logging callbacks
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Find VitraNativeRenderer class and cache logging method IDs
    jclass loggerClassLocal = env->FindClass("com/vitra/render/jni/VitraNativeRenderer");
    if (loggerClassLocal == nullptr) {
        std::cerr << "[FATAL] Failed to find VitraNativeRenderer class in JNI_OnLoad" << std::endl;
        return JNI_ERR;
    }

    // Create global reference to prevent garbage collection
    g_loggerClass = reinterpret_cast<jclass>(env->NewGlobalRef(loggerClassLocal));
    env->DeleteLocalRef(loggerClassLocal);

    // Cache method IDs for logging callbacks
    g_logDebugMethod = env->GetStaticMethodID(g_loggerClass, "nativeLogDebug", "(Ljava/lang/String;)V");
    g_logInfoMethod = env->GetStaticMethodID(g_loggerClass, "nativeLogInfo", "(Ljava/lang/String;)V");
    g_logWarnMethod = env->GetStaticMethodID(g_loggerClass, "nativeLogWarn", "(Ljava/lang/String;)V");
    g_logErrorMethod = env->GetStaticMethodID(g_loggerClass, "nativeLogError", "(Ljava/lang/String;)V");

    if (!g_logDebugMethod || !g_logInfoMethod || !g_logWarnMethod || !g_logErrorMethod) {
        std::cerr << "[FATAL] Failed to find logging methods in JNI_OnLoad" << std::endl;
        return JNI_ERR;
    }

    std::cout << "[JNI] Native library loaded - logging bridge initialized" << std::endl;
    return JNI_VERSION_1_6;
}

/**
 * Helper function to get JNIEnv for the current thread
 * This handles thread attachment if necessary
 */
static JNIEnv* getJNIEnv() {
    if (g_jvm == nullptr) {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    jint result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (result == JNI_EDETACHED) {
        // Thread not attached - attach it
        if (g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return nullptr;
        }
    } else if (result != JNI_OK) {
        return nullptr;
    }

    return env;
}

/**
 * Native logging helpers - call Java logging methods via JNI
 * These replace std::cout/std::cerr to integrate with Java's SLF4J logging
 */
static void nativeLogDebug(const char* message) {
    JNIEnv* env = getJNIEnv();
    if (env && g_loggerClass && g_logDebugMethod) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallStaticVoidMethod(g_loggerClass, g_logDebugMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static void nativeLogInfo(const char* message) {
    JNIEnv* env = getJNIEnv();
    if (env && g_loggerClass && g_logInfoMethod) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallStaticVoidMethod(g_loggerClass, g_logInfoMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static void nativeLogWarn(const char* message) {
    JNIEnv* env = getJNIEnv();
    if (env && g_loggerClass && g_logWarnMethod) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallStaticVoidMethod(g_loggerClass, g_logWarnMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static void nativeLogError(const char* message) {
    JNIEnv* env = getJNIEnv();
    if (env && g_loggerClass && g_logErrorMethod) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallStaticVoidMethod(g_loggerClass, g_logErrorMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

// Convenience overloads for std::string
static void nativeLogDebug(const std::string& message) { nativeLogDebug(message.c_str()); }
static void nativeLogInfo(const std::string& message) { nativeLogInfo(message.c_str()); }
static void nativeLogWarn(const std::string& message) { nativeLogWarn(message.c_str()); }
static void nativeLogError(const std::string& message) { nativeLogError(message.c_str()); }

// Resource tracking
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_vertexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_indexBuffers;
std::unordered_map<uint64_t, UINT> g_vertexBufferStrides; // Track stride for each vertex buffer
std::unordered_map<uint64_t, ComPtr<ID3D11VertexShader>> g_vertexShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11PixelShader>> g_pixelShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_inputLayouts;
std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> g_textures;
std::unordered_map<uint64_t, ComPtr<ID3D11ShaderResourceView>> g_shaderResourceViews;
std::unordered_map<uint64_t, ComPtr<ID3D11Query>> g_queries;

// Handle generator
std::random_device rd;
std::mt19937_64 gen(rd());
std::uniform_int_distribution<uint64_t> dis;

uint64_t generateHandle() {
    return dis(gen);
}

bool compileShader(const char* source, const char* target, ID3DBlob** blob) {
    UINT flags = D3DCOMPILE_ENABLE_STRICTNESS;
    if (g_d3d11.debugEnabled) {
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
            std::ostringstream oss;
            oss << "Shader compilation error: "
                << static_cast<const char*>(errorBlob->GetBufferPointer());
            nativeLogError(oss.str());
            errorBlob->Release();
        }
        return false;
    }

    if (errorBlob) {
        errorBlob->Release();
    }
    return true;
}

bool createInputLayout(ID3DBlob* vertexShaderBlob, ID3D11InputLayout** inputLayout) {
    // Define vertex input layout matching Minecraft 1.21.8's 32-byte vertex format
    // Based on DirectX 11 documentation: proper alignment is critical for correct rendering
    // Format: position (12 bytes) + padding (4 bytes) + texcoord (8 bytes) + color (4 bytes) + padding (4 bytes) = 32 bytes
    D3D11_INPUT_ELEMENT_DESC layout[] = {
        { "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },   // Offset 0, 12 bytes
        { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 16, D3D11_INPUT_PER_VERTEX_DATA, 0 },     // Offset 16 (after 4-byte padding), 8 bytes
        { "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 24, D3D11_INPUT_PER_VERTEX_DATA, 0 }       // Offset 24, 4 bytes
        // Implicit 4-byte padding at end to reach 32 bytes total
    };

    std::ostringstream debugMsg;
    debugMsg << "Creating input layout with " << ARRAYSIZE(layout) << " elements (32-byte stride):";
    nativeLogDebug(debugMsg.str());

    for (int i = 0; i < ARRAYSIZE(layout); i++) {
        std::ostringstream elementMsg;
        elementMsg << "  " << layout[i].SemanticName << " format=" << layout[i].Format
                   << " offset=" << layout[i].AlignedByteOffset;
        nativeLogDebug(elementMsg.str());
    }

    HRESULT hr = g_d3d11.device->CreateInputLayout(
        layout,
        ARRAYSIZE(layout),
        vertexShaderBlob->GetBufferPointer(),
        vertexShaderBlob->GetBufferSize(),
        inputLayout
    );

    if (FAILED(hr)) {
        std::ostringstream errMsg;
        errMsg << "Failed to create input layout: HRESULT=0x" << std::hex << hr << std::dec;
        nativeLogError(errMsg.str());
    } else {
        nativeLogDebug("Input layout created successfully (32-byte vertex format)");
    }

    return SUCCEEDED(hr);
}

void updateRenderTargetView() {
    if (!g_d3d11.swapChain) return;

    ComPtr<ID3D11Texture2D> backBuffer;
    HRESULT hr = g_d3d11.swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), &backBuffer);
    if (FAILED(hr)) {
        std::cerr << "Failed to get back buffer" << std::endl;
        return;
    }

    hr = g_d3d11.device->CreateRenderTargetView(backBuffer.Get(), nullptr, &g_d3d11.renderTargetView);
    if (FAILED(hr)) {
        std::cerr << "Failed to create render target view" << std::endl;
        return;
    }

    std::cout << "[DEBUG] Render target view created successfully (size: "
              << g_d3d11.width << "x" << g_d3d11.height << ", handle: "
              << g_d3d11.renderTargetView.Get() << ")" << std::endl;

    // Create depth stencil buffer
    D3D11_TEXTURE2D_DESC depthDesc = {};
    depthDesc.Width = g_d3d11.width;
    depthDesc.Height = g_d3d11.height;
    depthDesc.MipLevels = 1;
    depthDesc.ArraySize = 1;
    depthDesc.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
    depthDesc.SampleDesc.Count = 1;
    depthDesc.SampleDesc.Quality = 0;
    depthDesc.Usage = D3D11_USAGE_DEFAULT;
    depthDesc.BindFlags = D3D11_BIND_DEPTH_STENCIL;

    hr = g_d3d11.device->CreateTexture2D(&depthDesc, nullptr, &g_d3d11.depthStencilBuffer);
    if (FAILED(hr)) {
        std::cerr << "Failed to create depth stencil buffer" << std::endl;
        return;
    }

    hr = g_d3d11.device->CreateDepthStencilView(g_d3d11.depthStencilBuffer.Get(), nullptr, &g_d3d11.depthStencilView);
    if (FAILED(hr)) {
        std::cerr << "Failed to create depth stencil view" << std::endl;
        return;
    }

    std::cout << "[DEBUG] Depth stencil view created successfully" << std::endl;

    // Set viewport
    g_d3d11.viewport = {};
    g_d3d11.viewport.Width = static_cast<float>(g_d3d11.width);
    g_d3d11.viewport.Height = static_cast<float>(g_d3d11.height);
    g_d3d11.viewport.MinDepth = 0.0f;
    g_d3d11.viewport.MaxDepth = 1.0f;
    g_d3d11.viewport.TopLeftX = 0.0f;
    g_d3d11.viewport.TopLeftY = 0.0f;
}

void setDefaultShaders() {
    // Vertex shader with constant buffer support for MVP matrices
    // Based on DirectX 11 documentation: constant buffers provide uniform data to shaders
    const char* vertexShaderSource = R"(
        // Constant buffer for transformation matrices (register b0)
        // DirectX 11 requires 16-byte alignment for constant buffer members
        cbuffer TransformBuffer : register(b0) {
            float4x4 ModelViewProjection;  // Combined MVP matrix from Minecraft
            float4x4 ModelView;             // Model-view matrix (for lighting calculations)
            float4x4 Projection;            // Projection matrix
        };

        struct VS_INPUT {
            float3 pos : POSITION;
            float2 tex : TEXCOORD0;
            float4 color : COLOR0;
        };

        struct VS_OUTPUT {
            float4 pos : SV_POSITION;
            float2 tex : TEXCOORD0;
            float4 color : COLOR0;
        };

        VS_OUTPUT main(VS_INPUT input) {
            VS_OUTPUT output;

            // Transform vertex position using MVP matrix from constant buffer
            // CRITICAL FIX: Use column-major multiplication for OpenGL/Minecraft compatibility
            // DirectX uses row-major by default, but Minecraft uses column-major (OpenGL style)
            float4 worldPos = float4(input.pos, 1.0);

            // Apply model-view-projection transformation from Minecraft
            // CORRECTED: mul(matrix, vector) for column-major (OpenGL) matrices
            output.pos = mul(ModelViewProjection, worldPos);

            // Pass through texture coordinates and vertex color
            output.tex = input.tex;
            output.color = input.color;

            return output;
        }
    )";

    // Pixel shader with proper texture sampling
    // Based on DirectX 11 documentation: use Texture2D and SamplerState with Sample() method
    const char* pixelShaderSource = R"(
        // Texture and sampler state declarations (registers t0 and s0)
        // DirectX 11 standard pattern for texture sampling in HLSL
        Texture2D texture0 : register(t0);
        SamplerState sampler0 : register(s0);

        struct PS_INPUT {
            float4 pos : SV_POSITION;
            float2 tex : TEXCOORD0;
            float4 color : COLOR0;
        };

        float4 main(PS_INPUT input) : SV_TARGET {
            // Sample texture using the standard DirectX 11 Sample() method
            // This retrieves the texture color at the interpolated UV coordinates
            float4 texColor = texture0.Sample(sampler0, input.tex);

            // Multiply texture color by vertex color for lighting/tinting
            // This is the standard Minecraft rendering equation
            float4 finalColor = texColor * input.color;

            // Ensure alpha is valid (clamp to [0,1] range)
            finalColor.a = saturate(finalColor.a);

            return finalColor;
        }
    )";

    ID3DBlob* vertexBlob = nullptr;
    if (compileShader(vertexShaderSource, "vs_4_0", &vertexBlob)) {
        HRESULT hr = g_d3d11.device->CreateVertexShader(
            vertexBlob->GetBufferPointer(),
            vertexBlob->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultVertexShader
        );

        if (SUCCEEDED(hr)) {
            createInputLayout(vertexBlob, &g_d3d11.defaultInputLayout);
        }
        vertexBlob->Release();
    }

    ID3DBlob* pixelBlob = nullptr;
    if (compileShader(pixelShaderSource, "ps_4_0", &pixelBlob)) {
        g_d3d11.device->CreatePixelShader(
            pixelBlob->GetBufferPointer(),
            pixelBlob->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultPixelShader
        );
        pixelBlob->Release();
    }

    // Create default sampler state for texture sampling
    D3D11_SAMPLER_DESC samplerDesc = {};
    samplerDesc.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    samplerDesc.AddressU = D3D11_TEXTURE_ADDRESS_WRAP;
    samplerDesc.AddressV = D3D11_TEXTURE_ADDRESS_WRAP;
    samplerDesc.AddressW = D3D11_TEXTURE_ADDRESS_WRAP;
    samplerDesc.MipLODBias = 0.0f;
    samplerDesc.MaxAnisotropy = 1;
    samplerDesc.ComparisonFunc = D3D11_COMPARISON_ALWAYS;
    samplerDesc.BorderColor[0] = 0;
    samplerDesc.BorderColor[1] = 0;
    samplerDesc.BorderColor[2] = 0;
    samplerDesc.BorderColor[3] = 0;
    samplerDesc.MinLOD = 0;
    samplerDesc.MaxLOD = D3D11_FLOAT32_MAX;

    g_d3d11.device->CreateSamplerState(&samplerDesc, &g_d3d11.defaultSamplerState);

    // CRITICAL FIX: Store default shader pipeline handle for JNI access
    // This ensures getDefaultShaderPipeline() returns a valid handle
    g_d3d11.defaultShaderPipelineHandle = generateHandle();
    g_vertexShaders[g_d3d11.defaultShaderPipelineHandle] = g_d3d11.defaultVertexShader;
    g_pixelShaders[g_d3d11.defaultShaderPipelineHandle] = g_d3d11.defaultPixelShader;

    std::cout << "[DEBUG] Default shader pipeline created with handle: 0x"
              << std::hex << g_d3d11.defaultShaderPipelineHandle << std::dec << std::endl;

    // Create a default white 1x1 texture for fallback when no texture is bound
    createDefaultTexture();
}

void createDefaultTexture() {
    // Create a 1x1 white texture as fallback
    D3D11_TEXTURE2D_DESC texDesc = {};
    texDesc.Width = 1;
    texDesc.Height = 1;
    texDesc.MipLevels = 1;
    texDesc.ArraySize = 1;
    texDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    texDesc.SampleDesc.Count = 1;
    texDesc.Usage = D3D11_USAGE_DEFAULT;
    texDesc.BindFlags = D3D11_BIND_SHADER_RESOURCE;

    // White pixel data (RGBA = 255,255,255,255)
    unsigned char whitePixel[4] = {255, 255, 255, 255};
    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = whitePixel;
    initData.SysMemPitch = 4;

    HRESULT hr = g_d3d11.device->CreateTexture2D(&texDesc, &initData, &g_d3d11.defaultTexture);
    if (SUCCEEDED(hr)) {
        // Create shader resource view for the default texture
        D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
        srvDesc.Format = texDesc.Format;
        srvDesc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
        srvDesc.Texture2D.MipLevels = 1;

        hr = g_d3d11.device->CreateShaderResourceView(g_d3d11.defaultTexture.Get(), &srvDesc, &g_d3d11.defaultTextureSRV);
        if (SUCCEEDED(hr)) {
            std::cout << "[DEBUG] Default white texture created successfully" << std::endl;
        } else {
            std::cerr << "Failed to create default texture SRV" << std::endl;
        }
    } else {
        std::cerr << "Failed to create default texture" << std::endl;
    }
}

void createDefaultRenderStates() {
    HRESULT hr;

    // Create default blend state (alpha blending enabled for transparency)
    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.AlphaToCoverageEnable = FALSE;
    blendDesc.IndependentBlendEnable = FALSE;
    blendDesc.RenderTarget[0].BlendEnable = TRUE;
    blendDesc.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
    blendDesc.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    blendDesc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    hr = g_d3d11.device->CreateBlendState(&blendDesc, &g_d3d11.blendState);
    if (SUCCEEDED(hr)) {
        float blendFactor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
        std::cout << "[DEBUG] Default blend state created (alpha blending enabled)" << std::endl;
    } else {
        std::cerr << "Failed to create default blend state" << std::endl;
    }

    // Create default depth-stencil state (depth testing enabled)
    D3D11_DEPTH_STENCIL_DESC depthStencilDesc = {};
    depthStencilDesc.DepthEnable = TRUE;
    depthStencilDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depthStencilDesc.DepthFunc = D3D11_COMPARISON_LESS_EQUAL; // Minecraft uses LEQUAL
    depthStencilDesc.StencilEnable = FALSE;
    depthStencilDesc.StencilReadMask = D3D11_DEFAULT_STENCIL_READ_MASK;
    depthStencilDesc.StencilWriteMask = D3D11_DEFAULT_STENCIL_WRITE_MASK;

    hr = g_d3d11.device->CreateDepthStencilState(&depthStencilDesc, &g_d3d11.depthStencilState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilState.Get(), 0);
        std::cout << "[DEBUG] Default depth-stencil state created (depth test enabled, LEQUAL)" << std::endl;
    } else {
        std::cerr << "Failed to create default depth-stencil state" << std::endl;
    }

    // Create default rasterizer state (backface culling enabled)
    D3D11_RASTERIZER_DESC rasterizerDesc = {};
    rasterizerDesc.FillMode = D3D11_FILL_SOLID;
    rasterizerDesc.CullMode = D3D11_CULL_BACK; // Minecraft uses backface culling by default
    rasterizerDesc.FrontCounterClockwise = FALSE;
    rasterizerDesc.DepthBias = 0;
    rasterizerDesc.DepthBiasClamp = 0.0f;
    rasterizerDesc.SlopeScaledDepthBias = 0.0f;
    rasterizerDesc.DepthClipEnable = TRUE;
    rasterizerDesc.ScissorEnable = FALSE;
    rasterizerDesc.MultisampleEnable = FALSE;
    rasterizerDesc.AntialiasedLineEnable = FALSE;

    hr = g_d3d11.device->CreateRasterizerState(&rasterizerDesc, &g_d3d11.rasterizerState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(g_d3d11.rasterizerState.Get());
        std::cout << "[DEBUG] Default rasterizer state created (backface culling enabled)" << std::endl;
    } else {
        std::cerr << "Failed to create default rasterizer state" << std::endl;
    }

    std::cout << "[DEBUG] Default render states initialized successfully" << std::endl;
}

// ==================== RENDERDOC HELPER FUNCTIONS ====================

bool initializeRenderDoc() {
    if (g_renderDocInitialized) {
        return true;
    }

    // Try to get renderdoc.dll if it's already loaded (injected by RenderDoc UI)
    HMODULE renderDocModule = GetModuleHandleA("renderdoc.dll");

    // If not already loaded, try to explicitly load it from common locations
    if (!renderDocModule) {
        std::cout << "[RenderDoc] renderdoc.dll not injected, attempting explicit load..." << std::endl;

        // Try common RenderDoc installation paths
        const char* possiblePaths[] = {
            "renderdoc.dll",  // System PATH or current directory
            "C:\\Program Files\\RenderDoc\\renderdoc.dll",
            "C:\\Program Files (x86)\\RenderDoc\\renderdoc.dll",
        };

        for (const char* path : possiblePaths) {
            renderDocModule = LoadLibraryA(path);
            if (renderDocModule) {
                std::cout << "[RenderDoc] Successfully loaded from: " << path << std::endl;
                break;
            }
        }
    } else {
        std::cout << "[RenderDoc] renderdoc.dll already injected by RenderDoc UI" << std::endl;
    }

    if (!renderDocModule) {
        std::cout << "[RenderDoc] renderdoc.dll not found - RenderDoc features disabled" << std::endl;
        std::cout << "[RenderDoc] To use RenderDoc, either:" << std::endl;
        std::cout << "[RenderDoc]   1. Launch through RenderDoc UI with 'Inject into Process' or" << std::endl;
        std::cout << "[RenderDoc]   2. Enable global process injection in RenderDoc settings or" << std::endl;
        std::cout << "[RenderDoc]   3. Copy renderdoc.dll to the game directory" << std::endl;
        return false;
    }

    // Get the RENDERDOC_GetAPI function
    pRENDERDOC_GetAPI RENDERDOC_GetAPI = (pRENDERDOC_GetAPI)GetProcAddress(renderDocModule, "RENDERDOC_GetAPI");
    if (!RENDERDOC_GetAPI) {
        std::cerr << "[RenderDoc] Failed to find RENDERDOC_GetAPI function" << std::endl;
        return false;
    }

    // Get the API
    int ret = RENDERDOC_GetAPI(eRENDERDOC_API_Version_1_6_0, (void**)&g_renderDocAPI);
    if (ret != 1) {
        std::cerr << "[RenderDoc] Failed to get RenderDoc API (version 1.6.0)" << std::endl;
        g_renderDocAPI = nullptr;
        return false;
    }

    g_renderDocInitialized = true;
    std::cout << "[RenderDoc] ✓ RenderDoc API 1.6.0 initialized successfully" << std::endl;

    // Set useful capture options for better debugging
    if (g_renderDocAPI) {
        // Capture all command lists (helps with deferred rendering)
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_CaptureAllCmdLists, 1);

        // Verify buffer access for better debugging
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_VerifyBufferAccess, 1);

        // Include all resources in captures
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_RefAllResources, 1);

        std::cout << "[RenderDoc] ✓ Capture options configured for debugging" << std::endl;
    }

    return true;
}

void shutdownRenderDoc() {
    if (g_renderDocInitialized && g_renderDocAPI) {
        g_renderDocAPI = nullptr;
        g_renderDocInitialized = false;
        std::cout << "[RenderDoc] RenderDoc API shutdown" << std::endl;
    }
}

void setRenderDocResourceName(ID3D11DeviceChild* resource, const char* name) {
    if (!g_renderDocInitialized || !resource || !name) {
        return;
    }

    // Use DirectX debug naming (works with RenderDoc)
    HRESULT hr = resource->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(name), name);
    if (SUCCEEDED(hr)) {
        std::cout << "[RenderDoc] Named resource: " << name << std::endl;
    }
}

// JNI Implementation
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug) {

    // ✅ CRITICAL: Initialize RenderDoc BEFORE creating D3D11 device
    // RenderDoc must hook the device creation to work properly
    nativeLogInfo("Attempting to initialize RenderDoc API...");
    initializeRenderDoc();

    g_d3d11.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d11.width = width;
    g_d3d11.height = height;
    g_d3d11.debugEnabled = enableDebug;

    // Create device and swap chain
    UINT createDeviceFlags = 0;
    if (g_d3d11.debugEnabled) {
        createDeviceFlags |= D3D11_CREATE_DEVICE_DEBUG;
    }

    // ✅ RenderDoc and graphics debugger compatibility flags
    // D3D11_CREATE_DEVICE_BGRA_SUPPORT: Required for some capture tools and Direct2D interop
    createDeviceFlags |= D3D11_CREATE_DEVICE_BGRA_SUPPORT;

    // D3D11_CREATE_DEVICE_SINGLETHREADED: Better RenderDoc compatibility (we're single-threaded anyway)
    // This can improve capture stability and performance
    createDeviceFlags |= D3D11_CREATE_DEVICE_SINGLETHREADED;

    D3D_FEATURE_LEVEL featureLevels[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
    };

    DXGI_SWAP_CHAIN_DESC swapChainDesc = {};
    swapChainDesc.BufferCount = 2;
    swapChainDesc.BufferDesc.Width = width;
    swapChainDesc.BufferDesc.Height = height;
    swapChainDesc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swapChainDesc.BufferDesc.RefreshRate.Numerator = 60;
    swapChainDesc.BufferDesc.RefreshRate.Denominator = 1;
    swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapChainDesc.OutputWindow = g_d3d11.hwnd;
    swapChainDesc.SampleDesc.Count = 1;
    swapChainDesc.SampleDesc.Quality = 0;
    swapChainDesc.Windowed = TRUE;

    // ✅ CRITICAL FIX: Use flip model for better RenderDoc compatibility
    // FLIP_SEQUENTIAL is recommended for Windows 10+ capture tools
    // Falls back gracefully to DISCARD on older systems
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
    swapChainDesc.Flags = DXGI_SWAP_CHAIN_FLAG_ALLOW_MODE_SWITCH;

    // ✅ CRITICAL: Two-step device creation for RenderDoc compatibility
    // Step 1: Create D3D11 device first (RenderDoc can hook this reliably)
    D3D_FEATURE_LEVEL selectedFeatureLevel;
    HRESULT hr = D3D11CreateDevice(
        nullptr,                    // Use default adapter
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr,                    // No software rasterizer
        createDeviceFlags,
        featureLevels,
        ARRAYSIZE(featureLevels),
        D3D11_SDK_VERSION,
        &g_d3d11.device,
        &selectedFeatureLevel,
        &g_d3d11.context
    );

    if (FAILED(hr)) {
        std::ostringstream errMsg;
        errMsg << "Failed to create D3D11 device: 0x" << std::hex << hr;
        nativeLogError(errMsg.str());
        return JNI_FALSE;
    }

    std::ostringstream deviceMsg;
    deviceMsg << "Device created with feature level: "
              << ((selectedFeatureLevel == D3D_FEATURE_LEVEL_11_1) ? "11.1" : "11.0");
    nativeLogInfo(deviceMsg.str());

    // Step 2: Create swap chain separately using DXGI factory
    ComPtr<IDXGIDevice> dxgiDevice;
    hr = g_d3d11.device->QueryInterface(__uuidof(IDXGIDevice), (void**)&dxgiDevice);
    if (FAILED(hr)) {
        std::ostringstream errMsg;
        errMsg << "Failed to get DXGI device: 0x" << std::hex << hr;
        nativeLogError(errMsg.str());
        return JNI_FALSE;
    }

    ComPtr<IDXGIAdapter> dxgiAdapter;
    hr = dxgiDevice->GetAdapter(&dxgiAdapter);
    if (FAILED(hr)) {
        std::ostringstream errMsg;
        errMsg << "Failed to get DXGI adapter: 0x" << std::hex << hr;
        nativeLogError(errMsg.str());
        return JNI_FALSE;
    }

    ComPtr<IDXGIFactory> dxgiFactory;
    hr = dxgiAdapter->GetParent(__uuidof(IDXGIFactory), (void**)&dxgiFactory);
    if (FAILED(hr)) {
        std::ostringstream errMsg;
        errMsg << "Failed to get DXGI factory: 0x" << std::hex << hr;
        nativeLogError(errMsg.str());
        return JNI_FALSE;
    }

    // Create the swap chain through DXGI factory
    hr = dxgiFactory->CreateSwapChain(g_d3d11.device.Get(), &swapChainDesc, &g_d3d11.swapChain);
    if (FAILED(hr)) {
        std::cerr << "Failed to create swap chain: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    std::cout << "[D3D11] Swap chain created successfully" << std::endl;

    updateRenderTargetView();

    // IMPORTANT: Ensure render target is bound immediately after creation
    // This prevents render target mismatch during first frame
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    setDefaultShaders();

    // Initialize default render states for Minecraft rendering
    createDefaultRenderStates();

    // Initialize constant buffer with identity matrices
    // This prevents undefined behavior when shaders first execute
    // Based on DirectX 11 best practices: always initialize constant buffers
    struct TransformMatrices {
        float mvp[16];  // Model-View-Projection matrix (identity)
        float mv[16];   // Model-View matrix (identity)
        float proj[16]; // Projection matrix (identity)
    };

    TransformMatrices identityMatrices = {};
    // Set identity matrices (1.0 on diagonal, 0.0 elsewhere)
    for (int i = 0; i < 3; i++) {
        identityMatrices.mvp[i * 4 + i] = 1.0f;
        identityMatrices.mv[i * 4 + i] = 1.0f;
        identityMatrices.proj[i * 4 + i] = 1.0f;
    }
    identityMatrices.mvp[15] = 1.0f;
    identityMatrices.mv[15] = 1.0f;
    identityMatrices.proj[15] = 1.0f;

    // Create constant buffer for transform matrices (slot 0 / register b0)
    D3D11_BUFFER_DESC cbDesc = {};
    cbDesc.Usage = D3D11_USAGE_DYNAMIC;
    cbDesc.ByteWidth = sizeof(TransformMatrices);
    cbDesc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    cbDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    hr = g_d3d11.device->CreateBuffer(&cbDesc, nullptr, &g_d3d11.constantBuffers[0]);
    if (SUCCEEDED(hr)) {
        // Upload identity matrices to constant buffer
        D3D11_MAPPED_SUBRESOURCE mappedResource;
        hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
        if (SUCCEEDED(hr)) {
            memcpy(mappedResource.pData, &identityMatrices, sizeof(TransformMatrices));
            g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);

            // Bind constant buffer to vertex shader stage
            g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());

            std::cout << "[DEBUG] Transform constant buffer initialized with identity matrices" << std::endl;
        } else {
            std::cerr << "Failed to map transform constant buffer for initialization" << std::endl;
        }
    } else {
        std::cerr << "Failed to create transform constant buffer" << std::endl;
    }

    // Initialize clear color state (FIX: Missing - causes pink background)
    g_d3d11.clearColor[0] = 0.0f; // R
    g_d3d11.clearColor[1] = 0.0f; // G
    g_d3d11.clearColor[2] = 0.0f; // B
    g_d3d11.clearColor[3] = 1.0f; // A

    g_d3d11.initialized = true;
    std::cout << "DirectX 11 initialized successfully" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdown
    (JNIEnv* env, jclass clazz) {

    // Clear all resources
    g_vertexBuffers.clear();
    g_indexBuffers.clear();
    g_vertexBufferStrides.clear();
    g_vertexShaders.clear();
    g_pixelShaders.clear();
    g_inputLayouts.clear();
    g_textures.clear();
    g_shaderResourceViews.clear();

    // Release DirectX resources
    g_d3d11.defaultInputLayout.Reset();
    g_d3d11.defaultPixelShader.Reset();
    g_d3d11.defaultVertexShader.Reset();
    g_d3d11.defaultSamplerState.Reset();
    g_d3d11.depthStencilView.Reset();
    g_d3d11.depthStencilBuffer.Reset();
    g_d3d11.renderTargetView.Reset();
    g_d3d11.infoQueue.Reset(); // Release debug info queue
    g_d3d11.context.Reset();
    g_d3d11.swapChain.Reset();
    g_d3d11.device.Reset();

    g_d3d11.initialized = false;
    std::cout << "DirectX 11 shutdown completed" << std::endl;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resize
    (JNIEnv* env, jclass clazz, jint width, jint height) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;

    g_d3d11.width = width;
    g_d3d11.height = height;

    // Release existing render target view
    g_d3d11.renderTargetView.Reset();
    g_d3d11.depthStencilView.Reset();
    g_d3d11.depthStencilBuffer.Reset();

    // Resize swap chain
    HRESULT hr = g_d3d11.swapChain->ResizeBuffers(
        2, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0);

    if (SUCCEEDED(hr)) {
        updateRenderTargetView();

        // IMPORTANT: Re-bind render targets after resize to ensure consistency
        // This fixes the render target mismatch issue where drawing uses old RTV
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

        std::cout << "[DEBUG] Render target updated and rebound after resize" << std::endl;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;

    // Set render targets
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    // Set viewport
    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);

    // Use the clear color set by Minecraft (default: black or sky color)
    // Changed from debug magenta (1,0,1) to stored clear color for proper rendering
    g_d3d11.context->ClearRenderTargetView(g_d3d11.renderTargetView.Get(), g_d3d11.clearColor);

    // Also clear depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);

    // CRITICAL FIX: Bind default shaders every frame!
    // DirectX 11 REQUIRES a vertex shader and pixel shader to be bound before any draw operations
    // Without this, all draw calls fail silently, causing black screen
    if (g_d3d11.defaultVertexShader && g_d3d11.defaultPixelShader) {
        g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
        g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);

        // Set default input layout for the vertex shader
        if (g_d3d11.defaultInputLayout) {
            g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
        }

        // Set default primitive topology
        g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

        // Set default sampler state
        if (g_d3d11.defaultSamplerState) {
            g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
        }

        // Bind default white texture to slot 0 to prevent undefined texture sampling
        if (g_d3d11.defaultTextureSRV) {
            g_d3d11.context->PSSetShaderResources(0, 1, g_d3d11.defaultTextureSRV.GetAddressOf());
        }
    }

    // NOTE: Projection matrix should be set by Minecraft's RenderSystem
    // The projection matrix will be synchronized from RenderSystem via setProjectionMatrix JNI method
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_endFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;


    // Present the frame
    g_d3d11.swapChain->Present(1, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clear
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_d3d11.initialized) return;

    float clearColor[4] = { r, g, b, a };
    g_d3d11.context->ClearRenderTargetView(g_d3d11.renderTargetView.Get(), clearColor);
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setClearColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_d3d11.initialized) return;

    // Store clear color state (FIX: This was missing - causes pink background)
    g_d3d11.clearColor[0] = r;
    g_d3d11.clearColor[1] = g;
    g_d3d11.clearColor[2] = b;
    g_d3d11.clearColor[3] = a;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride) {

    if (!g_d3d11.initialized) {
        std::cerr << "createVertexBuffer: DirectX not initialized" << std::endl;
        return 0;
    }

    // Validate size parameter FIRST
    if (size <= 0) {
        std::cerr << "createVertexBuffer: invalid size=" << size << " (must be > 0)" << std::endl;
        return 0;
    }

    // Null check for data array
    if (data == nullptr) {
        std::cerr << "createVertexBuffer: data array is null" << std::endl;
        return 0;
    }

    // Get Java array length for validation
    jsize arrayLength = env->GetArrayLength(data);
    if (arrayLength < size) {
        std::cerr << "createVertexBuffer: array length " << arrayLength
                  << " is less than requested size " << size << std::endl;
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "createVertexBuffer: failed to get array elements (size=" << size
                  << ", arrayLength=" << arrayLength << ")" << std::endl;
        return 0;
    }

    std::cout << "[DEBUG] Creating vertex buffer: size=" << size
              << ", stride=" << stride
              << ", arrayLength=" << arrayLength << std::endl;

    D3D11_BUFFER_DESC desc = {};
    desc.Usage = D3D11_USAGE_DYNAMIC; // Changed from DEFAULT to DYNAMIC for CPU access
    desc.ByteWidth = size;
    desc.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE; // Added CPU write access for mapping

    ComPtr<ID3D11Buffer> buffer;

    // For DYNAMIC buffers, we MUST pass nullptr as initial data (cannot use D3D11_SUBRESOURCE_DATA)
    // Then immediately Map/Unmap to initialize the buffer data
    // This is required by DirectX 11 spec for DYNAMIC resources
    HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &buffer);

    if (FAILED(hr)) {
        std::cerr << "Failed to create vertex buffer: HRESULT=0x" << std::hex << hr << std::dec
                  << ", size=" << size << ", stride=" << stride << std::endl;

        // Check if it's a validation layer error - try to get more info
        if (g_d3d11.debugEnabled && g_d3d11.infoQueue) {
            UINT64 numMessages = g_d3d11.infoQueue->GetNumStoredMessages();
            std::cerr << "Debug layer has " << numMessages << " messages" << std::endl;
            for (UINT64 i = 0; i < numMessages && i < 5; i++) {
                SIZE_T messageLength = 0;
                g_d3d11.infoQueue->GetMessage(i, nullptr, &messageLength);
                if (messageLength > 0) {
                    D3D11_MESSAGE* message = (D3D11_MESSAGE*)malloc(messageLength);
                    if (message && SUCCEEDED(g_d3d11.infoQueue->GetMessage(i, message, &messageLength))) {
                        std::cerr << "  [D3D11] " << message->pDescription << std::endl;
                    }
                    free(message);
                }
            }
            g_d3d11.infoQueue->ClearStoredMessages();
        }

        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return 0;
    }

    // CRITICAL: Immediately map and initialize the buffer with data
    // This must be done before the buffer is ever used for rendering
    if (bytes != nullptr && size > 0) {
        D3D11_MAPPED_SUBRESOURCE mappedResource;
        hr = g_d3d11.context->Map(buffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
        if (SUCCEEDED(hr)) {
            memcpy(mappedResource.pData, bytes, size);
            g_d3d11.context->Unmap(buffer.Get(), 0);
            std::cout << "[DEBUG] Buffer data copied successfully via Map/Unmap" << std::endl;
        } else {
            std::cerr << "Failed to map vertex buffer for initialization: HRESULT=0x" << std::hex << hr << std::dec << std::endl;
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    uint64_t handle = generateHandle();
    g_vertexBuffers[handle] = buffer;
    g_vertexBufferStrides[handle] = static_cast<UINT>(stride); // Store the stride

    // Name the resource for RenderDoc debugging
    char bufferName[64];
    snprintf(bufferName, sizeof(bufferName), "VitraVertexBuffer_%llu", handle);
    setRenderDocResourceName(buffer.Get(), bufferName);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format) {

    if (!g_d3d11.initialized) {
        std::cerr << "createIndexBuffer: DirectX not initialized" << std::endl;
        return 0;
    }

    // Validate size parameter FIRST
    if (size <= 0) {
        std::cerr << "createIndexBuffer: invalid size=" << size << " (must be > 0)" << std::endl;
        return 0;
    }

    // Null check for data array
    if (data == nullptr) {
        std::cerr << "createIndexBuffer: data array is null" << std::endl;
        return 0;
    }

    // Get Java array length for validation
    jsize arrayLength = env->GetArrayLength(data);
    if (arrayLength < size) {
        std::cerr << "createIndexBuffer: array length " << arrayLength
                  << " is less than requested size " << size << std::endl;
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "createIndexBuffer: failed to get array elements (size=" << size
                  << ", arrayLength=" << arrayLength << ")" << std::endl;
        return 0;
    }

    std::cout << "[DEBUG] Creating index buffer: size=" << size
              << ", format=" << format
              << ", arrayLength=" << arrayLength << std::endl;

    D3D11_BUFFER_DESC desc = {};
    desc.Usage = D3D11_USAGE_DYNAMIC; // Changed from DEFAULT to DYNAMIC for CPU access
    desc.ByteWidth = size;
    desc.BindFlags = D3D11_BIND_INDEX_BUFFER;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE; // Added CPU write access for mapping
    desc.MiscFlags = 0;
    desc.StructureByteStride = 0;

    ComPtr<ID3D11Buffer> buffer;
    // IMPORTANT: DYNAMIC buffers should be created with nullptr for initial data
    // Then use Map/Unmap to fill them with data
    HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &buffer);

    if (FAILED(hr)) {
        std::cerr << "Failed to create index buffer: HRESULT=0x" << std::hex << hr << std::dec
                  << ", size=" << size << ", format=" << format << std::endl;
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return 0;
    }

    // If initial data was provided, map the buffer and copy the data
    if (data != nullptr && bytes != nullptr) {
        D3D11_MAPPED_SUBRESOURCE mappedResource;
        hr = g_d3d11.context->Map(buffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
        if (SUCCEEDED(hr)) {
            memcpy(mappedResource.pData, bytes, size);
            g_d3d11.context->Unmap(buffer.Get(), 0);
        }
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    uint64_t handle = generateHandle();
    g_indexBuffers[handle] = buffer;

    // Name the resource for RenderDoc debugging
    char bufferName[64];
    snprintf(bufferName, sizeof(bufferName), "VitraIndexBuffer_%llu", handle);
    setRenderDocResourceName(buffer.Get(), bufferName);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type) {

    if (!g_d3d11.initialized) return 0;

    jbyte* bytes = env->GetByteArrayElements(bytecode, nullptr);
    if (!bytes) return 0;

    uint64_t handle = generateHandle();

    if (type == 0) { // Vertex shader
        ComPtr<ID3D11VertexShader> shader;
        HRESULT hr = g_d3d11.device->CreateVertexShader(
            bytes, size, nullptr, &shader);

        if (SUCCEEDED(hr)) {
            g_vertexShaders[handle] = shader;

            // Name the resource for RenderDoc debugging
            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraVertexShader_%llu", handle);
            setRenderDocResourceName(shader.Get(), shaderName);
        } else {
            handle = 0;
        }
    } else if (type == 1) { // Pixel shader
        ComPtr<ID3D11PixelShader> shader;
        HRESULT hr = g_d3d11.device->CreatePixelShader(
            bytes, size, nullptr, &shader);

        if (SUCCEEDED(hr)) {
            g_pixelShaders[handle] = shader;

            // Name the resource for RenderDoc debugging
            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraPixelShader_%llu", handle);
            setRenderDocResourceName(shader.Get(), shaderName);
        } else {
            handle = 0;
        }
    }

    env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLShaderPipeline
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader) {

    if (!g_d3d11.initialized) return 0;

    // For simplicity, we'll just return the vertex shader handle as the pipeline handle
    // In a more complete implementation, you'd create a pipeline state object
    return vertexShader;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyResource
    (JNIEnv* env, jclass clazz, jlong handle) {

    uint64_t h = static_cast<uint64_t>(handle);

    g_vertexBuffers.erase(h);
    g_indexBuffers.erase(h);
    g_vertexBufferStrides.erase(h);
    g_vertexShaders.erase(h);
    g_pixelShaders.erase(h);
    g_inputLayouts.erase(h);
    g_textures.erase(h);
    g_shaderResourceViews.erase(h);
    g_queries.erase(h);  // Clean up queries (fences)
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipeline) {

    if (!g_d3d11.initialized) return;

    uint64_t vsHandle = static_cast<uint64_t>(pipeline);

    auto vsIt = g_vertexShaders.find(vsHandle);
    if (vsIt != g_vertexShaders.end()) {
        g_d3d11.context->VSSetShader(vsIt->second.Get(), nullptr, 0);
    }

    auto psIt = g_pixelShaders.find(vsHandle);
    if (psIt != g_pixelShaders.end()) {
        g_d3d11.context->PSSetShader(psIt->second.Get(), nullptr, 0);
    }

    // Set default input layout
    g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Set default sampler state for texture sampling
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

    // CRITICAL: Always bind the default white texture to slot 0 as fallback
    // This ensures texture sampling never returns undefined values
    if (g_d3d11.defaultTextureSRV) {
        g_d3d11.context->PSSetShaderResources(0, 1, g_d3d11.defaultTextureSRV.GetAddressOf());
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.defaultVertexShader) {
        std::cerr << "getDefaultShaderPipeline: DirectX not initialized or default shader missing" << std::endl;
        return 0;
    }

    // Return the stored default shader pipeline handle
    // This handle was created during initialization in setDefaultShaders()
    return static_cast<jlong>(g_d3d11.defaultShaderPipelineHandle);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_draw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint baseVertex, jint firstIndex, jint indexCount, jint instanceCount) {

    if (!g_d3d11.initialized) return;

    // CRITICAL FIX: Ensure render target is bound before every draw call
    // This prevents the "no render target bound" DirectX debug warning
    if (g_d3d11.renderTargetView && g_d3d11.depthStencilView) {
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());
    }

    // CRITICAL: Ensure shaders are bound before every draw call
    // This is the fix for black screen - shaders MUST be set!
    if (g_d3d11.defaultVertexShader) {
        g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    }
    if (g_d3d11.defaultPixelShader) {
        g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    }

    // CRITICAL: Ensure input layout is bound (defines vertex format)
    if (g_d3d11.defaultInputLayout) {
        g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    }

    // CRITICAL: Ensure constant buffer (transform matrices) is bound for this draw call
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.constantBuffers[0]) {
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    }

    // CRITICAL: Ensure sampler state is bound for texture sampling
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

    // CRITICAL: Ensure default white texture is bound to prevent undefined texture sampling
    if (g_d3d11.defaultTextureSRV) {
        g_d3d11.context->PSSetShaderResources(0, 1, g_d3d11.defaultTextureSRV.GetAddressOf());
    }


    // Set vertex buffer
    uint64_t vbHandle = static_cast<uint64_t>(vertexBuffer);
    auto vbIt = g_vertexBuffers.find(vbHandle);
    if (vbIt != g_vertexBuffers.end()) {
        // Get the actual stride for this vertex buffer
        // Minecraft 1.21.8 uses 32-byte vertex format (position=12 + padding=4 + texcoord=8 + color=4 + padding=4)
        UINT stride = 32; // Default fallback - fixed to match Minecraft's actual vertex format
        auto strideIt = g_vertexBufferStrides.find(vbHandle);
        if (strideIt != g_vertexBufferStrides.end()) {
            stride = strideIt->second;
            std::cout << "[DEBUG] Using stored stride " << stride << " for vertex buffer 0x" << std::hex << vbHandle << std::dec << std::endl;
        } else {
            std::cout << "[DEBUG] Using default stride 32 for vertex buffer 0x" << std::hex << vbHandle << std::dec << std::endl;
        }
        UINT offset = 0;
        g_d3d11.context->IASetVertexBuffers(0, 1, vbIt->second.GetAddressOf(), &stride, &offset);

        std::cout << "[DEBUG] Drawing with vertex buffer 0x" << std::hex << vbHandle
                  << ", stride=" << std::dec << stride << " bytes" << std::endl;

        // DEBUG: Log input layout and vertex format info
        std::cout << "[DEBUG] Using input layout: " << (g_d3d11.defaultInputLayout ? "valid" : "NULL") << std::endl;
        std::cout << "[DEBUG] Draw params: baseVertex=" << baseVertex << ", firstIndex=" << firstIndex
                  << ", indexCount=" << indexCount << ", instanceCount=" << instanceCount << std::endl;
    }

    // Set index buffer if provided
    if (indexBuffer != 0) {
        uint64_t ibHandle = static_cast<uint64_t>(indexBuffer);
        auto ibIt = g_indexBuffers.find(ibHandle);
        if (ibIt != g_indexBuffers.end()) {
            DXGI_FORMAT format = DXGI_FORMAT_R16_UINT;
            g_d3d11.context->IASetIndexBuffer(ibIt->second.Get(), format, 0);
        }

        if (indexCount > 0) {
            std::cout << "[DEBUG] DrawIndexed: count=" << indexCount << ", firstIndex=" << firstIndex
                      << ", baseVertex=" << baseVertex << std::endl;

            // CRITICAL FIX: Pass baseVertex and firstIndex to DrawIndexed!
            // DrawIndexed(IndexCount, StartIndexLocation, BaseVertexLocation)
            if (instanceCount > 1) {
                // Instanced draw call
                g_d3d11.context->DrawIndexedInstanced(indexCount, instanceCount, firstIndex, baseVertex, 0);
            } else {
                // Single instance draw call
                g_d3d11.context->DrawIndexed(indexCount, firstIndex, baseVertex);
            }
        }
    } else {
        if (indexCount > 0) {
            std::cout << "[DEBUG] Draw (non-indexed): count=" << indexCount << ", offset=" << firstIndex << std::endl;
            // Non-indexed draw call
            if (instanceCount > 1) {
                g_d3d11.context->DrawInstanced(indexCount, instanceCount, firstIndex, 0);
            } else {
                g_d3d11.context->Draw(indexCount, firstIndex);
            }
        }
    }

}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isInitialized
    (JNIEnv* env, jclass clazz) {

    return g_d3d11.initialized ? JNI_TRUE : JNI_FALSE;
}

// OpenGL to DirectX 11 topology mapping
D3D11_PRIMITIVE_TOPOLOGY glTopologyToD3D11(int glTopology) {
    switch (glTopology) {
        case 0x0000: // GL_POINTS
            return D3D11_PRIMITIVE_TOPOLOGY_POINTLIST;
        case 0x0001: // GL_LINES
            return D3D11_PRIMITIVE_TOPOLOGY_LINELIST;
        case 0x0002: // GL_LINE_STRIP
            return D3D11_PRIMITIVE_TOPOLOGY_LINESTRIP;
        case 0x0004: // GL_TRIANGLES
            return D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
        case 0x0005: // GL_TRIANGLE_STRIP
            return D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP;
        case 0x0006: // GL_TRIANGLE_FAN
            return D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED; // Not directly supported
        default:
            return D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology) {

    if (!g_d3d11.initialized) return;

    g_d3d11.currentTopology = glTopologyToD3D11(topology);
    g_d3d11.context->IASetPrimitiveTopology(g_d3d11.currentTopology);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize) {

    if (!g_d3d11.initialized || vertexBuffer == nullptr) return;

    // CRITICAL FIX: Ensure render target is bound before every draw call
    // This prevents the "no render target bound" DirectX debug warning
    if (g_d3d11.renderTargetView && g_d3d11.depthStencilView) {
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());
    }

    // CRITICAL: Ensure shaders are bound before every draw call
    // This is the fix for black screen - shaders MUST be set!
    if (g_d3d11.defaultVertexShader) {
        g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    }
    if (g_d3d11.defaultPixelShader) {
        g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    }

    // CRITICAL: Ensure input layout is bound (defines vertex format)
    if (g_d3d11.defaultInputLayout) {
        g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    }

    // CRITICAL: Ensure constant buffer (transform matrices) is bound for this draw call
    if (g_d3d11.constantBuffers[0]) {
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    }

    // CRITICAL: Ensure sampler state is bound for texture sampling
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

    // CRITICAL: Ensure default white texture is bound to prevent undefined texture sampling
    if (g_d3d11.defaultTextureSRV) {
        g_d3d11.context->PSSetShaderResources(0, 1, g_d3d11.defaultTextureSRV.GetAddressOf());
    }

    // Get direct buffer address
    void* vertexData = env->GetDirectBufferAddress(vertexBuffer);
    if (!vertexData) {
        std::cerr << "Failed to get vertex buffer address" << std::endl;
        return;
    }

    // Create temporary vertex buffer
    D3D11_BUFFER_DESC vbDesc = {};
    vbDesc.Usage = D3D11_USAGE_DYNAMIC;
    vbDesc.ByteWidth = vertexCount * vertexSize;
    vbDesc.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    vbDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    ComPtr<ID3D11Buffer> tempVertexBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&vbDesc, nullptr, &tempVertexBuffer);
    if (FAILED(hr)) {
        std::cerr << "Failed to create temporary vertex buffer: " << std::hex << hr << std::endl;
        return;
    }

    // Upload vertex data
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    hr = g_d3d11.context->Map(tempVertexBuffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        memcpy(mappedResource.pData, vertexData, vertexCount * vertexSize);
        g_d3d11.context->Unmap(tempVertexBuffer.Get(), 0);
    }

    // Set vertex buffer
    UINT stride = vertexSize;
    UINT offset = 0;
    g_d3d11.context->IASetVertexBuffers(0, 1, tempVertexBuffer.GetAddressOf(), &stride, &offset);

    // Set primitive topology
    g_d3d11.currentTopology = glTopologyToD3D11(primitiveMode);
    g_d3d11.context->IASetPrimitiveTopology(g_d3d11.currentTopology);

    // Handle index buffer if provided
    if (indexBuffer != nullptr && indexCount > 0) {
        void* indexData = env->GetDirectBufferAddress(indexBuffer);
        if (indexData) {
            // Create temporary index buffer
            D3D11_BUFFER_DESC ibDesc = {};
            ibDesc.Usage = D3D11_USAGE_DYNAMIC;
            ibDesc.ByteWidth = indexCount * 2; // Assuming 16-bit indices
            ibDesc.BindFlags = D3D11_BIND_INDEX_BUFFER;
            ibDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

            ComPtr<ID3D11Buffer> tempIndexBuffer;
            hr = g_d3d11.device->CreateBuffer(&ibDesc, nullptr, &tempIndexBuffer);
            if (SUCCEEDED(hr)) {
                // Upload index data
                hr = g_d3d11.context->Map(tempIndexBuffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
                if (SUCCEEDED(hr)) {
                    memcpy(mappedResource.pData, indexData, indexCount * 2);
                    g_d3d11.context->Unmap(tempIndexBuffer.Get(), 0);
                }

                // Set and draw indexed
                g_d3d11.context->IASetIndexBuffer(tempIndexBuffer.Get(), DXGI_FORMAT_R16_UINT, 0);
                std::cout << "[DEBUG] drawMeshData - Drawing indexed: " << indexCount << " indices" << std::endl;
                g_d3d11.context->DrawIndexed(indexCount, 0, 0);
            }
        }
    } else {
        // Draw non-indexed
        std::cout << "[DEBUG] drawMeshData - Drawing non-indexed: " << vertexCount << " vertices" << std::endl;
        g_d3d11.context->Draw(vertexCount, 0);
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Null check for data array
    if (data == nullptr) {
        std::cerr << "createTexture: data array is null" << std::endl;
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "createTexture: failed to get array elements" << std::endl;
        return 0;
    }

    // Calculate number of mipmap levels: 1 + floor(log2(max(width, height)))
    // For example: 512x512 → 10 levels (512, 256, 128, 64, 32, 16, 8, 4, 2, 1)
    UINT mipLevels = 1;
    UINT maxDimension = (width > height) ? width : height;
    UINT size = maxDimension;
    while (size > 1) {
        size >>= 1;  // Divide by 2
        mipLevels++;
    }

    std::cout << "Creating texture " << width << "x" << height << " with " << mipLevels << " mip levels" << std::endl;

    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = width;
    desc.Height = height;
    desc.MipLevels = mipLevels;  // ✅ CRITICAL FIX: Support multiple mipmap levels!
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; // Default to RGBA8
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;

    // Create texture WITHOUT initial data - Minecraft will upload all mip levels separately
    // This allows UpdateSubresource to work correctly for each mip level
    ComPtr<ID3D11Texture2D> texture;
    HRESULT hr = g_d3d11.device->CreateTexture2D(&desc, nullptr, &texture);

    if (FAILED(hr)) {
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        std::cerr << "Failed to create texture: " << std::hex << hr << std::endl;
        return 0;
    }

    // Now upload the initial data for mip level 0 using UpdateSubresource
    D3D11_BOX srcBox = {};
    srcBox.left = 0;
    srcBox.top = 0;
    srcBox.front = 0;
    srcBox.right = width;
    srcBox.bottom = height;
    srcBox.back = 1;

    UINT rowPitch = width * 4; // RGBA = 4 bytes per pixel
    g_d3d11.context->UpdateSubresource(
        texture.Get(),
        0,                  // Subresource 0 = mip level 0
        nullptr,            // Update entire texture
        bytes,
        rowPitch,
        0                   // 2D texture, no depth pitch
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    // Create shader resource view
    ComPtr<ID3D11ShaderResourceView> srv;
    D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
    srvDesc.Format = desc.Format;
    srvDesc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    srvDesc.Texture2D.MipLevels = 1;

    hr = g_d3d11.device->CreateShaderResourceView(texture.Get(), &srvDesc, &srv);
    if (FAILED(hr)) {
        std::cerr << "Failed to create shader resource view: " << std::hex << hr << std::endl;
        return 0;
    }

    uint64_t handle = generateHandle();
    g_textures[handle] = texture;
    g_shaderResourceViews[handle] = srv;

    // Name the resources for RenderDoc debugging
    char textureName[64];
    char srvName[64];
    snprintf(textureName, sizeof(textureName), "VitraTexture_%llu", handle);
    snprintf(srvName, sizeof(srvName), "VitraTextureSRV_%llu", handle);
    setRenderDocResourceName(texture.Get(), textureName);
    setRenderDocResourceName(srv.Get(), srvName);

    return static_cast<jlong>(handle);
}

/**
 * Update existing texture data using ID3D11DeviceContext::UpdateSubresource
 * This is MUCH more efficient than destroying and recreating the texture!
 *
 * Uses UpdateSubresource instead of Map/Unmap because textures are created with D3D11_USAGE_DEFAULT
 * (not DYNAMIC), which is the correct usage for shader resource views.
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_updateTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle, jbyteArray data, jint width, jint height, jint mipLevel) {

    if (!g_d3d11.initialized) {
        std::cerr << "updateTexture: DirectX not initialized" << std::endl;
        return JNI_FALSE;
    }

    // Validate handle
    if (textureHandle == 0) {
        std::cerr << "updateTexture: invalid texture handle (0)" << std::endl;
        return JNI_FALSE;
    }

    // Validate data
    if (data == nullptr) {
        std::cerr << "updateTexture: data array is null" << std::endl;
        return JNI_FALSE;
    }

    // Find texture in tracking map
    uint64_t handle = static_cast<uint64_t>(textureHandle);
    auto it = g_textures.find(handle);
    if (it == g_textures.end()) {
        std::cerr << "updateTexture: texture handle not found: 0x" << std::hex << handle << std::endl;
        return JNI_FALSE;
    }

    ComPtr<ID3D11Texture2D> texture = it->second;
    if (!texture) {
        std::cerr << "updateTexture: texture object is null for handle 0x" << std::hex << handle << std::endl;
        return JNI_FALSE;
    }

    // Get texture description to verify dimensions
    D3D11_TEXTURE2D_DESC desc;
    texture->GetDesc(&desc);

    // Validate mip level first
    if (static_cast<UINT>(mipLevel) >= desc.MipLevels) {
        std::cerr << "updateTexture: invalid mip level " << mipLevel << " (texture has " << desc.MipLevels << " mips)" << std::endl;
        return JNI_FALSE;
    }

    // Calculate EXPECTED dimensions for this mip level
    // Each mip level is half the size: mip0=256x256, mip1=128x128, mip2=64x64, etc.
    UINT expectedWidth = desc.Width >> mipLevel;   // Right shift = divide by 2^mipLevel
    UINT expectedHeight = desc.Height >> mipLevel;

    // Ensure we don't go below 1x1
    if (expectedWidth == 0) expectedWidth = 1;
    if (expectedHeight == 0) expectedHeight = 1;

    // Now check if update data matches the EXPECTED size for this mip level
    if (static_cast<UINT>(width) != expectedWidth || static_cast<UINT>(height) != expectedHeight) {
        std::cerr << "updateTexture: dimension mismatch! Texture mip " << mipLevel
                  << " expects " << expectedWidth << "x" << expectedHeight
                  << ", but update data is " << width << "x" << height << std::endl;
        return JNI_FALSE;
    }

    // Get pixel data from Java
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "updateTexture: failed to get array elements" << std::endl;
        return JNI_FALSE;
    }

    // Calculate row pitch based on format
    // For RGBA8 (R8G8B8A8_UNORM): 4 bytes per pixel
    UINT bytesPerPixel = 4; // Default to RGBA8
    switch (desc.Format) {
        case DXGI_FORMAT_R8G8B8A8_UNORM:
        case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
        case DXGI_FORMAT_B8G8R8A8_UNORM:
        case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
            bytesPerPixel = 4;
            break;
        case DXGI_FORMAT_R8_UNORM:
            bytesPerPixel = 1;
            break;
        case DXGI_FORMAT_R16G16B16A16_FLOAT:
            bytesPerPixel = 8;
            break;
        default:
            std::cerr << "updateTexture: unsupported format " << desc.Format << std::endl;
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return JNI_FALSE;
    }

    // Calculate pitch: Source Row Pitch = [bytes per pixel] * [width]
    UINT rowPitch = bytesPerPixel * width;

    // Calculate subresource index: D3D11CalcSubresource(mipLevel, 0, mipLevels)
    // For single-layer 2D textures: subresource = mipLevel
    UINT subresource = mipLevel;

    // Update the texture using UpdateSubresource
    // pDstBox = nullptr means update the entire subresource
    // SrcDepthPitch = 0 for 2D textures
    g_d3d11.context->UpdateSubresource(
        texture.Get(),        // Destination resource
        subresource,          // Subresource index (mip level)
        nullptr,              // Update entire texture (no partial update)
        bytes,                // Source pixel data
        rowPitch,             // Source row pitch
        0                     // Source depth pitch (0 for 2D)
    );

    // Release Java array (JNI_ABORT = don't copy back changes)
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    std::cout << "✓ Updated texture 0x" << std::hex << handle << " (" << width << "x" << height
              << ", mip " << std::dec << mipLevel << ") using UpdateSubresource" << std::endl;

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture
    (JNIEnv* env, jclass clazz, jlong texture, jint slot) {

    if (!g_d3d11.initialized) return;

    if (texture == 0) {
        // Unbind texture
        ID3D11ShaderResourceView* nullSRV = nullptr;
        g_d3d11.context->PSSetShaderResources(slot, 1, &nullSRV);
        return;
    }

    uint64_t handle = static_cast<uint64_t>(texture);
    auto it = g_shaderResourceViews.find(handle);
    if (it != g_shaderResourceViews.end()) {
        g_d3d11.context->PSSetShaderResources(slot, 1, it->second.GetAddressOf());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot) {

    if (!g_d3d11.initialized || slot < 0 || slot >= 4) return;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;

    // Create or update constant buffer
    if (!g_d3d11.constantBuffers[slot]) {
        D3D11_BUFFER_DESC desc = {};
        desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.ByteWidth = ((size + 15) / 16) * 16; // Align to 16 bytes
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[slot]);
        if (FAILED(hr)) {
            std::cerr << "Failed to create constant buffer: " << std::hex << hr << std::endl;
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
    }

    // Update constant buffer data
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(g_d3d11.constantBuffers[slot].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        memcpy(mappedResource.pData, bytes, size);
        g_d3d11.context->Unmap(g_d3d11.constantBuffers[slot].Get(), 0);
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    // Bind constant buffer to vertex and pixel shader
    g_d3d11.context->VSSetConstantBuffers(slot, 1, g_d3d11.constantBuffers[slot].GetAddressOf());
    g_d3d11.context->PSSetConstantBuffers(slot, 1, g_d3d11.constantBuffers[slot].GetAddressOf());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setViewport
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    g_d3d11.viewport.TopLeftX = static_cast<float>(x);
    g_d3d11.viewport.TopLeftY = static_cast<float>(y);
    g_d3d11.viewport.Width = static_cast<float>(width);
    g_d3d11.viewport.Height = static_cast<float>(height);
    g_d3d11.viewport.MinDepth = 0.0f;
    g_d3d11.viewport.MaxDepth = 1.0f;

    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    g_d3d11.scissorRect.left = x;
    g_d3d11.scissorRect.top = y;
    g_d3d11.scissorRect.right = x + width;
    g_d3d11.scissorRect.bottom = y + height;
    g_d3d11.scissorEnabled = true;

    g_d3d11.context->RSSetScissorRects(1, &g_d3d11.scissorRect);
}

// ==================== DEBUG LAYER IMPLEMENTATION ====================

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDebugMessages
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.debugEnabled || !g_d3d11.device) {
        return env->NewStringUTF("");
    }

    // Query for ID3D11InfoQueue interface if not already cached
    if (!g_d3d11.infoQueue) {
        HRESULT hr = g_d3d11.device->QueryInterface(__uuidof(ID3D11InfoQueue), &g_d3d11.infoQueue);
        if (FAILED(hr)) {
            std::cerr << "Failed to query ID3D11InfoQueue - debug layer may not be enabled" << std::endl;
            return env->NewStringUTF("");
        }
    }

    // Get number of messages (DirectX 11 uses no category parameter)
    UINT64 numMessages = g_d3d11.infoQueue->GetNumStoredMessages();
    if (numMessages == 0) {
        return env->NewStringUTF("");
    }

    // Collect all messages into a single string
    std::string allMessages;
    for (UINT64 i = 0; i < numMessages; i++) {
        // Get message length
        SIZE_T messageLength = 0;
        g_d3d11.infoQueue->GetMessage(i, nullptr, &messageLength);

        if (messageLength == 0) continue;

        // Allocate and retrieve message
        D3D11_MESSAGE* message = reinterpret_cast<D3D11_MESSAGE*>(malloc(messageLength));
        if (!message) continue;

        HRESULT hr = g_d3d11.infoQueue->GetMessage(i, message, &messageLength);
        if (SUCCEEDED(hr) && message->pDescription) {
            // Add severity prefix
            const char* severityStr = "";
            switch (message->Severity) {
                case D3D11_MESSAGE_SEVERITY_CORRUPTION:
                    severityStr = "[CORRUPTION] ";
                    break;
                case D3D11_MESSAGE_SEVERITY_ERROR:
                    severityStr = "[ERROR] ";
                    break;
                case D3D11_MESSAGE_SEVERITY_WARNING:
                    severityStr = "[WARNING] ";
                    break;
                case D3D11_MESSAGE_SEVERITY_INFO:
                    severityStr = "[INFO] ";
                    break;
                case D3D11_MESSAGE_SEVERITY_MESSAGE:
                    severityStr = "[MESSAGE] ";
                    break;
            }

            allMessages += severityStr;
            allMessages += message->pDescription;
            allMessages += "\n";
        }

        free(message);
    }

    // Clear stored messages after retrieval
    g_d3d11.infoQueue->ClearStoredMessages();

    return env->NewStringUTF(allMessages.c_str());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeClearDebugMessages
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.infoQueue) return;

    g_d3d11.infoQueue->ClearStoredMessages();
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeSetDebugSeverity
    (JNIEnv* env, jclass clazz, jint severity) {

    if (!g_d3d11.infoQueue) return;

    // Set message severity filter
    D3D11_INFO_QUEUE_FILTER filter = {};
    D3D11_MESSAGE_SEVERITY severities[] = {
        D3D11_MESSAGE_SEVERITY_INFO,
        D3D11_MESSAGE_SEVERITY_WARNING,
        D3D11_MESSAGE_SEVERITY_ERROR,
        D3D11_MESSAGE_SEVERITY_CORRUPTION
    };

    // Filter out messages below the specified severity
    if (severity > 0) {
        filter.DenyList.NumSeverities = severity;
        filter.DenyList.pSeverityList = severities;
    }

    g_d3d11.infoQueue->PushStorageFilter(&filter);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeBreakOnError
    (JNIEnv* env, jclass clazz, jboolean enabled) {

    if (!g_d3d11.infoQueue) return;

    // Break on error severity messages
    g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_ERROR, enabled ? TRUE : FALSE);
    g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_CORRUPTION, enabled ? TRUE : FALSE);
}

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDeviceInfo
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.device) {
        return env->NewStringUTF("DirectX 11 - Not Initialized");
    }

    // Get adapter information via DXGI
    ComPtr<IDXGIDevice> dxgiDevice;
    HRESULT hr = g_d3d11.device->QueryInterface(__uuidof(IDXGIDevice), &dxgiDevice);
    if (FAILED(hr)) {
        return env->NewStringUTF("DirectX 11 - Unknown Adapter");
    }

    ComPtr<IDXGIAdapter> adapter;
    hr = dxgiDevice->GetAdapter(&adapter);
    if (FAILED(hr)) {
        return env->NewStringUTF("DirectX 11 - Unknown Adapter");
    }

    DXGI_ADAPTER_DESC adapterDesc;
    hr = adapter->GetDesc(&adapterDesc);
    if (FAILED(hr)) {
        return env->NewStringUTF("DirectX 11 - Unknown Adapter");
    }

    // Convert wide string description to UTF-8
    char description[256] = {};
    wcstombs(description, adapterDesc.Description, sizeof(description) - 1);

    // Format device information
    char deviceInfo[512];
    snprintf(deviceInfo, sizeof(deviceInfo),
        "DirectX 11.0 - %s (VID: 0x%04X, PID: 0x%04X, VRAM: %llu MB)",
        description,
        adapterDesc.VendorId,
        adapterDesc.DeviceId,
        adapterDesc.DedicatedVideoMemory / (1024 * 1024));

    return env->NewStringUTF(deviceInfo);
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeValidateShader
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size) {

    if (!g_d3d11.initialized || !bytecode) return JNI_FALSE;

    jbyte* bytes = env->GetByteArrayElements(bytecode, nullptr);
    if (!bytes) return JNI_FALSE;

    // Try to create a temporary vertex shader to validate bytecode
    ComPtr<ID3D11VertexShader> tempShader;
    HRESULT hr = g_d3d11.device->CreateVertexShader(bytes, size, nullptr, &tempShader);

    env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);

    return SUCCEEDED(hr) ? JNI_TRUE : JNI_FALSE;
}

// ==================== RENDER STATE MANAGEMENT IMPLEMENTATION ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendState
    (JNIEnv* env, jclass clazz, jboolean enabled, jint srcBlend, jint destBlend, jint blendOp) {

    if (!g_d3d11.initialized) return;

    // Map blend factors from OpenGL to DirectX 11
    auto mapBlendFactor = [](jint gl) -> D3D11_BLEND {
        switch (gl) {
            case 0:    // GL_ZERO
                return D3D11_BLEND_ZERO;
            case 1:    // GL_ONE
                return D3D11_BLEND_ONE;
            case 0x0300: // GL_SRC_COLOR
                return D3D11_BLEND_SRC_COLOR;
            case 0x0301: // GL_ONE_MINUS_SRC_COLOR
                return D3D11_BLEND_INV_SRC_COLOR;
            case 0x0302: // GL_SRC_ALPHA
                return D3D11_BLEND_SRC_ALPHA;
            case 0x0303: // GL_ONE_MINUS_SRC_ALPHA
                return D3D11_BLEND_INV_SRC_ALPHA;
            case 0x0304: // GL_DST_ALPHA
                return D3D11_BLEND_DEST_ALPHA;
            case 0x0305: // GL_ONE_MINUS_DST_ALPHA
                return D3D11_BLEND_INV_DEST_ALPHA;
            case 0x0306: // GL_DST_COLOR
                return D3D11_BLEND_DEST_COLOR;
            case 0x0307: // GL_ONE_MINUS_DST_COLOR
                return D3D11_BLEND_INV_DEST_COLOR;
            default:
                return D3D11_BLEND_ONE;
        }
    };

    // Map blend operation from OpenGL to DirectX 11
    auto mapBlendOp = [](jint gl) -> D3D11_BLEND_OP {
        switch (gl) {
            case 0x8006: // GL_FUNC_ADD
                return D3D11_BLEND_OP_ADD;
            case 0x800A: // GL_FUNC_SUBTRACT
                return D3D11_BLEND_OP_SUBTRACT;
            case 0x800B: // GL_FUNC_REVERSE_SUBTRACT
                return D3D11_BLEND_OP_REV_SUBTRACT;
            case 0x8007: // GL_MIN
                return D3D11_BLEND_OP_MIN;
            case 0x8008: // GL_MAX
                return D3D11_BLEND_OP_MAX;
            default:
                return D3D11_BLEND_OP_ADD;
        }
    };

    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.AlphaToCoverageEnable = FALSE;
    blendDesc.IndependentBlendEnable = FALSE;
    blendDesc.RenderTarget[0].BlendEnable = enabled ? TRUE : FALSE;
    blendDesc.RenderTarget[0].SrcBlend = mapBlendFactor(srcBlend);
    blendDesc.RenderTarget[0].DestBlend = mapBlendFactor(destBlend);
    blendDesc.RenderTarget[0].BlendOp = mapBlendOp(blendOp);
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    // Create blend state
    ComPtr<ID3D11BlendState> newBlendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &newBlendState);

    if (SUCCEEDED(hr)) {
        g_d3d11.blendState = newBlendState;

        // Apply the blend state
        float blendFactor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);

        std::cout << "[DEBUG] Blend state set: enabled=" << (enabled ? "true" : "false")
                  << ", src=" << srcBlend << ", dest=" << destBlend << std::endl;
    } else {
        std::cerr << "Failed to create blend state: HRESULT=0x" << std::hex << hr << std::dec << std::endl;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setDepthState
    (JNIEnv* env, jclass clazz, jboolean depthTestEnabled, jboolean depthWriteEnabled, jint depthFunc) {

    if (!g_d3d11.initialized) return;

    // Map depth comparison function from OpenGL to DirectX 11
    auto mapDepthFunc = [](jint gl) -> D3D11_COMPARISON_FUNC {
        switch (gl) {
            case 0x0200: // GL_NEVER
                return D3D11_COMPARISON_NEVER;
            case 0x0201: // GL_LESS
                return D3D11_COMPARISON_LESS;
            case 0x0202: // GL_EQUAL
                return D3D11_COMPARISON_EQUAL;
            case 0x0203: // GL_LEQUAL
                return D3D11_COMPARISON_LESS_EQUAL;
            case 0x0204: // GL_GREATER
                return D3D11_COMPARISON_GREATER;
            case 0x0205: // GL_NOTEQUAL
                return D3D11_COMPARISON_NOT_EQUAL;
            case 0x0206: // GL_GEQUAL
                return D3D11_COMPARISON_GREATER_EQUAL;
            case 0x0207: // GL_ALWAYS
                return D3D11_COMPARISON_ALWAYS;
            default:
                return D3D11_COMPARISON_LESS_EQUAL;
        }
    };

    D3D11_DEPTH_STENCIL_DESC depthStencilDesc = {};
    depthStencilDesc.DepthEnable = depthTestEnabled ? TRUE : FALSE;
    depthStencilDesc.DepthWriteMask = depthWriteEnabled ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
    depthStencilDesc.DepthFunc = mapDepthFunc(depthFunc);
    depthStencilDesc.StencilEnable = FALSE;
    depthStencilDesc.StencilReadMask = D3D11_DEFAULT_STENCIL_READ_MASK;
    depthStencilDesc.StencilWriteMask = D3D11_DEFAULT_STENCIL_WRITE_MASK;

    // Create depth stencil state
    ComPtr<ID3D11DepthStencilState> newDepthState;
    HRESULT hr = g_d3d11.device->CreateDepthStencilState(&depthStencilDesc, &newDepthState);

    if (SUCCEEDED(hr)) {
        g_d3d11.depthStencilState = newDepthState;

        // Apply the depth stencil state
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilState.Get(), 0);

        std::cout << "[DEBUG] Depth state set: test=" << (depthTestEnabled ? "enabled" : "disabled")
                  << ", write=" << (depthWriteEnabled ? "enabled" : "disabled")
                  << ", func=" << depthFunc << std::endl;
    } else {
        std::cerr << "Failed to create depth stencil state: HRESULT=0x" << std::hex << hr << std::dec << std::endl;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setRasterizerState
    (JNIEnv* env, jclass clazz, jint cullMode, jint fillMode, jboolean scissorEnabled) {

    if (!g_d3d11.initialized) return;

    // Map cull mode from OpenGL to DirectX 11
    auto mapCullMode = [](jint gl) -> D3D11_CULL_MODE {
        switch (gl) {
            case 0:      // No culling
                return D3D11_CULL_NONE;
            case 0x0404: // GL_FRONT
                return D3D11_CULL_FRONT;
            case 0x0405: // GL_BACK
                return D3D11_CULL_BACK;
            default:
                return D3D11_CULL_BACK;
        }
    };

    // Map fill mode from OpenGL to DirectX 11
    auto mapFillMode = [](jint gl) -> D3D11_FILL_MODE {
        switch (gl) {
            case 0x1B00: // GL_POINT
                return D3D11_FILL_SOLID; // D3D11 doesn't support point fill
            case 0x1B01: // GL_LINE
                return D3D11_FILL_WIREFRAME;
            case 0x1B02: // GL_FILL
                return D3D11_FILL_SOLID;
            default:
                return D3D11_FILL_SOLID;
        }
    };

    D3D11_RASTERIZER_DESC rasterizerDesc = {};
    rasterizerDesc.FillMode = mapFillMode(fillMode);
    rasterizerDesc.CullMode = mapCullMode(cullMode);
    rasterizerDesc.FrontCounterClockwise = FALSE;
    rasterizerDesc.DepthBias = 0;
    rasterizerDesc.DepthBiasClamp = 0.0f;
    rasterizerDesc.SlopeScaledDepthBias = 0.0f;
    rasterizerDesc.DepthClipEnable = TRUE;
    rasterizerDesc.ScissorEnable = scissorEnabled ? TRUE : FALSE;
    rasterizerDesc.MultisampleEnable = FALSE;
    rasterizerDesc.AntialiasedLineEnable = FALSE;

    // Create rasterizer state
    ComPtr<ID3D11RasterizerState> newRasterizerState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rasterizerDesc, &newRasterizerState);

    if (SUCCEEDED(hr)) {
        g_d3d11.rasterizerState = newRasterizerState;

        // Apply the rasterizer state
        g_d3d11.context->RSSetState(g_d3d11.rasterizerState.Get());

        std::cout << "[DEBUG] Rasterizer state set: cull=" << cullMode
                  << ", fill=" << fillMode
                  << ", scissor=" << (scissorEnabled ? "enabled" : "disabled") << std::endl;
    } else {
        std::cerr << "Failed to create rasterizer state: HRESULT=0x" << std::hex << hr << std::dec << std::endl;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clearDepth
    (JNIEnv* env, jclass clazz, jfloat depth) {

    if (!g_d3d11.initialized || !g_d3d11.depthStencilView) return;

    // Clear only the depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, depth, 0);

    std::cout << "[DEBUG] Depth cleared: depth=" << depth << std::endl;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setColorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha) {

    if (!g_d3d11.initialized || !g_d3d11.blendState) return;

    // Get current blend state descriptor
    D3D11_BLEND_DESC currentBlendDesc;
    g_d3d11.blendState->GetDesc(&currentBlendDesc);

    // Update color write mask
    UINT8 writeMask = 0;
    if (red) writeMask |= D3D11_COLOR_WRITE_ENABLE_RED;
    if (green) writeMask |= D3D11_COLOR_WRITE_ENABLE_GREEN;
    if (blue) writeMask |= D3D11_COLOR_WRITE_ENABLE_BLUE;
    if (alpha) writeMask |= D3D11_COLOR_WRITE_ENABLE_ALPHA;

    currentBlendDesc.RenderTarget[0].RenderTargetWriteMask = writeMask;

    // Recreate blend state with new write mask
    ComPtr<ID3D11BlendState> newBlendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&currentBlendDesc, &newBlendState);

    if (SUCCEEDED(hr)) {
        g_d3d11.blendState = newBlendState;

        // Apply the updated blend state
        float blendFactor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);

        std::cout << "[DEBUG] Color mask set: R=" << (red ? "1" : "0")
                  << " G=" << (green ? "1" : "0")
                  << " B=" << (blue ? "1" : "0")
                  << " A=" << (alpha ? "1" : "0") << std::endl;
    } else {
        std::cerr << "Failed to create blend state for color mask: HRESULT=0x" << std::hex << hr << std::dec << std::endl;
    }
}

// ==================== MATRIX MANAGEMENT ====================

/**
 * Set orthographic projection matrix for 2D rendering (GUI, menus, etc.)
 * This creates a matrix that maps screen coordinates to NDC space [-1, 1]
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {

    if (!g_d3d11.initialized || !g_d3d11.constantBuffers[0]) return;

    // Create orthographic projection matrix
    // DirectX uses row-major matrices, so we need to set them correctly
    struct TransformMatrices {
        float mvp[16];  // Model-View-Projection matrix
        float mv[16];   // Model-View matrix
        float proj[16]; // Projection matrix
    };

    TransformMatrices matrices = {};

    // Orthographic projection matrix formula:
    // https://docs.microsoft.com/en-us/windows/win32/direct3d9/d3dxmatrixorthooffcenterlh
    float width = right - left;
    float height = bottom - top; // Note: DirectX Y goes down, so bottom > top
    float depth = zFar - zNear;

    // MVP matrix (orthographic projection only for 2D)
    matrices.mvp[0] = 2.0f / width;
    matrices.mvp[5] = 2.0f / height;
    matrices.mvp[10] = 1.0f / depth;
    matrices.mvp[12] = -(right + left) / width;
    matrices.mvp[13] = -(top + bottom) / height;
    matrices.mvp[14] = -zNear / depth;
    matrices.mvp[15] = 1.0f;

    // Model-view matrix (identity for 2D)
    matrices.mv[0] = 1.0f;
    matrices.mv[5] = 1.0f;
    matrices.mv[10] = 1.0f;
    matrices.mv[15] = 1.0f;

    // Projection matrix (same as MVP for 2D orthographic)
    memcpy(matrices.proj, matrices.mvp, sizeof(matrices.proj));

    // Upload to constant buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        memcpy(mappedResource.pData, &matrices, sizeof(TransformMatrices));
        g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);

        // Re-bind constant buffer to vertex shader
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());

        // Log via JNI to integrate with Java logging
        std::ostringstream oss;
        oss << "Orthographic projection set: " << width << "x" << height
            << " (left=" << left << ", right=" << right
            << ", bottom=" << bottom << ", top=" << top << ")";
        nativeLogDebug(oss.str());
    } else {
        nativeLogError("Failed to map constant buffer for orthographic projection");
    }
}

/**
 * Set projection matrix from Minecraft's RenderSystem
 * This synchronizes Minecraft's Matrix4f projection matrix to DirectX 11 constant buffer
 *
 * @param matrixData Float array containing 16 elements (4x4 matrix in row-major order)
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setProjectionMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData) {

    if (!g_d3d11.initialized || !g_d3d11.constantBuffers[0]) return;

    if (matrixData == nullptr) {
        std::cerr << "setProjectionMatrix: matrix data is null" << std::endl;
        return;
    }

    // Get array length for validation
    jsize arrayLength = env->GetArrayLength(matrixData);
    if (arrayLength != 16) {
        std::cerr << "setProjectionMatrix: invalid matrix size " << arrayLength << " (expected 16)" << std::endl;
        return;
    }

    // Get matrix elements from Java
    jfloat* matrixElements = env->GetFloatArrayElements(matrixData, nullptr);
    if (!matrixElements) {
        std::cerr << "setProjectionMatrix: failed to get matrix elements" << std::endl;
        return;
    }

    // Prepare transformation matrices structure
    struct TransformMatrices {
        float mvp[16];  // Model-View-Projection matrix
        float mv[16];   // Model-View matrix
        float proj[16]; // Projection matrix
    };

    TransformMatrices matrices = {};

    // Copy projection matrix from Minecraft (JOML Matrix4f is column-major)
    // DirectX uses row-major, so we need to transpose
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            matrices.proj[row * 4 + col] = matrixElements[col * 4 + row]; // Transpose
        }
    }

    // For now, use projection matrix as MVP (assuming model-view is identity)
    // Minecraft will multiply model-view separately on CPU side
    memcpy(matrices.mvp, matrices.proj, sizeof(matrices.proj));

    // Model-view matrix (identity for now)
    matrices.mv[0] = 1.0f;
    matrices.mv[5] = 1.0f;
    matrices.mv[10] = 1.0f;
    matrices.mv[15] = 1.0f;

    env->ReleaseFloatArrayElements(matrixData, matrixElements, JNI_ABORT);

    // Upload to constant buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        memcpy(mappedResource.pData, &matrices, sizeof(TransformMatrices));
        g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);

        // Re-bind constant buffer to vertex shader
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());

        std::cout << "[DEBUG] Projection matrix synchronized from Minecraft RenderSystem" << std::endl;
    } else {
        std::cerr << "Failed to map constant buffer for projection matrix" << std::endl;
    }
}

/**
 * Set ALL transformation matrices from Minecraft's RenderSystem
 * This is the CORRECT way - we get MVP, ModelView, and Projection separately
 *
 * CRITICAL: This fixes the "cosmic blue triangles" issue!
 * The problem was that we were only using Projection matrix with identity ModelView,
 * which meant camera transformations were not applied to vertices.
 *
 * @param mvpData - Pre-calculated MVP matrix from Java (column-major, 16 floats)
 * @param modelViewData - Model-View matrix (column-major, 16 floats)
 * @param projectionData - Projection matrix (column-major, 16 floats)
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTransformMatrices
    (JNIEnv* env, jclass clazz, jfloatArray mvpData, jfloatArray modelViewData, jfloatArray projectionData) {

    if (!g_d3d11.initialized || !g_d3d11.constantBuffers[0]) return;

    // Validate all inputs
    if (mvpData == nullptr || modelViewData == nullptr || projectionData == nullptr) {
        std::cerr << "setTransformMatrices: one or more matrix arrays are null" << std::endl;
        return;
    }

    // Validate array sizes
    if (env->GetArrayLength(mvpData) != 16 ||
        env->GetArrayLength(modelViewData) != 16 ||
        env->GetArrayLength(projectionData) != 16) {
        std::cerr << "setTransformMatrices: invalid matrix sizes (expected 16 elements each)" << std::endl;
        return;
    }

    // Get all matrix elements
    jfloat* mvpElements = env->GetFloatArrayElements(mvpData, nullptr);
    jfloat* mvElements = env->GetFloatArrayElements(modelViewData, nullptr);
    jfloat* projElements = env->GetFloatArrayElements(projectionData, nullptr);

    if (!mvpElements || !mvElements || !projElements) {
        std::cerr << "setTransformMatrices: failed to get matrix elements" << std::endl;
        if (mvpElements) env->ReleaseFloatArrayElements(mvpData, mvpElements, JNI_ABORT);
        if (mvElements) env->ReleaseFloatArrayElements(modelViewData, mvElements, JNI_ABORT);
        if (projElements) env->ReleaseFloatArrayElements(projectionData, projElements, JNI_ABORT);
        return;
    }

    // Prepare transformation matrices structure
    struct TransformMatrices {
        float mvp[16];  // Model-View-Projection matrix
        float mv[16];   // Model-View matrix
        float proj[16]; // Projection matrix
    };

    TransformMatrices matrices = {};

    // Transpose all matrices from JOML column-major to DirectX row-major
    // JOML (column-major): mat[col * 4 + row]
    // DirectX (row-major): mat[row * 4 + col]
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            matrices.mvp[row * 4 + col] = mvpElements[col * 4 + row];   // Transpose MVP
            matrices.mv[row * 4 + col] = mvElements[col * 4 + row];     // Transpose ModelView
            matrices.proj[row * 4 + col] = projElements[col * 4 + row]; // Transpose Projection
        }
    }

    // Release Java arrays
    env->ReleaseFloatArrayElements(mvpData, mvpElements, JNI_ABORT);
    env->ReleaseFloatArrayElements(modelViewData, mvElements, JNI_ABORT);
    env->ReleaseFloatArrayElements(projectionData, projElements, JNI_ABORT);

    // Upload to constant buffer using WRITE_DISCARD (best performance for per-frame updates)
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        // Copy all matrices to GPU
        memcpy(mappedResource.pData, &matrices, sizeof(TransformMatrices));
        g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);

        // CRITICAL: Re-bind constant buffer to vertex shader slot b0
        // DirectX state can be overwritten, so we must bind before each frame
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());

        std::cout << "[DEBUG] ✓ ALL transformation matrices synced: MVP + ModelView + Projection" << std::endl;
    } else {
        std::cerr << "✗ Failed to map constant buffer for transformation matrices: HRESULT=0x"
                  << std::hex << hr << std::dec << std::endl;
    }
}

// ==================== BUFFER MAPPING IMPLEMENTATION ====================

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_mapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jint size, jint accessFlags) {

    if (!g_d3d11.initialized) return nullptr;

    uint64_t handle = static_cast<uint64_t>(bufferHandle);

    // Try to find the buffer in vertex or index buffer maps
    ComPtr<ID3D11Buffer> buffer;
    auto vbIt = g_vertexBuffers.find(handle);
    if (vbIt != g_vertexBuffers.end()) {
        buffer = vbIt->second;
    } else {
        auto ibIt = g_indexBuffers.find(handle);
        if (ibIt != g_indexBuffers.end()) {
            buffer = ibIt->second;
        } else {
            std::cerr << "Buffer handle not found: " << handle << std::endl;
            return nullptr;
        }
    }

    // Determine D3D11_MAP type based on access flags
    // accessFlags: 1=read, 2=write, 3=read+write
    D3D11_MAP mapType;
    if (accessFlags == 1) {
        mapType = D3D11_MAP_READ;
    } else if (accessFlags == 2) {
        mapType = D3D11_MAP_WRITE_DISCARD;
    } else if (accessFlags == 3) {
        mapType = D3D11_MAP_READ_WRITE;
    } else {
        std::cerr << "Invalid access flags: " << accessFlags << std::endl;
        return nullptr;
    }

    // Map the buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(buffer.Get(), 0, mapType, 0, &mappedResource);

    if (FAILED(hr)) {
        std::cerr << "Failed to map buffer: " << std::hex << hr << std::endl;
        return nullptr;
    }

    // Create a direct ByteBuffer pointing to the mapped memory
    jobject byteBuffer = env->NewDirectByteBuffer(mappedResource.pData, size);

    if (!byteBuffer) {
        std::cerr << "Failed to create direct ByteBuffer" << std::endl;
        g_d3d11.context->Unmap(buffer.Get(), 0);
        return nullptr;
    }

    return byteBuffer;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_unmapBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle) {

    if (!g_d3d11.initialized) return;

    uint64_t handle = static_cast<uint64_t>(bufferHandle);

    // Try to find the buffer in vertex or index buffer maps
    ComPtr<ID3D11Buffer> buffer;
    auto vbIt = g_vertexBuffers.find(handle);
    if (vbIt != g_vertexBuffers.end()) {
        buffer = vbIt->second;
    } else {
        auto ibIt = g_indexBuffers.find(handle);
        if (ibIt != g_indexBuffers.end()) {
            buffer = ibIt->second;
        } else {
            std::cerr << "Buffer handle not found for unmap: " << handle << std::endl;
            return;
        }
    }

    // Unmap the buffer
    g_d3d11.context->Unmap(buffer.Get(), 0);
}

// ==================== BUFFER/TEXTURE COPY OPERATIONS ====================

/**
 * Copy data from one buffer to another using CopySubresourceRegion
 * This is essential for buffer-to-buffer data transfers without CPU involvement
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyBuffer
    (JNIEnv* env, jclass clazz, jlong srcBufferHandle, jlong dstBufferHandle,
     jint srcOffset, jint dstOffset, jint size) {

    if (!g_d3d11.initialized) {
        std::cerr << "copyBuffer: DirectX not initialized" << std::endl;
        return;
    }

    uint64_t srcHandle = static_cast<uint64_t>(srcBufferHandle);
    uint64_t dstHandle = static_cast<uint64_t>(dstBufferHandle);

    // Find source buffer
    ComPtr<ID3D11Buffer> srcBuffer;
    auto srcVbIt = g_vertexBuffers.find(srcHandle);
    if (srcVbIt != g_vertexBuffers.end()) {
        srcBuffer = srcVbIt->second;
    } else {
        auto srcIbIt = g_indexBuffers.find(srcHandle);
        if (srcIbIt != g_indexBuffers.end()) {
            srcBuffer = srcIbIt->second;
        } else {
            std::cerr << "copyBuffer: source buffer not found: " << srcHandle << std::endl;
            return;
        }
    }

    // Find destination buffer
    ComPtr<ID3D11Buffer> dstBuffer;
    auto dstVbIt = g_vertexBuffers.find(dstHandle);
    if (dstVbIt != g_vertexBuffers.end()) {
        dstBuffer = dstVbIt->second;
    } else {
        auto dstIbIt = g_indexBuffers.find(dstHandle);
        if (dstIbIt != g_indexBuffers.end()) {
            dstBuffer = dstIbIt->second;
        } else {
            std::cerr << "copyBuffer: destination buffer not found: " << dstHandle << std::endl;
            return;
        }
    }

    // Setup source box for partial copy
    D3D11_BOX srcBox;
    srcBox.left = srcOffset;
    srcBox.right = srcOffset + size;
    srcBox.top = 0;
    srcBox.bottom = 1;
    srcBox.front = 0;
    srcBox.back = 1;

    // Copy the buffer region
    g_d3d11.context->CopySubresourceRegion(
        dstBuffer.Get(),  // Destination
        0,                // Destination subresource
        dstOffset,        // Destination X
        0,                // Destination Y
        0,                // Destination Z
        srcBuffer.Get(),  // Source
        0,                // Source subresource
        &srcBox           // Source box
    );

    std::cout << "[DEBUG] Buffer copy: " << size << " bytes from offset " << srcOffset
              << " to offset " << dstOffset << std::endl;
}

/**
 * Copy entire texture using CopyResource
 * Requires textures to have identical dimensions and formats
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTexture
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle) {

    if (!g_d3d11.initialized) {
        std::cerr << "copyTexture: DirectX not initialized" << std::endl;
        return;
    }

    uint64_t srcHandle = static_cast<uint64_t>(srcTextureHandle);
    uint64_t dstHandle = static_cast<uint64_t>(dstTextureHandle);

    // Find source texture
    auto srcIt = g_textures.find(srcHandle);
    if (srcIt == g_textures.end()) {
        std::cerr << "copyTexture: source texture not found: " << srcHandle << std::endl;
        return;
    }

    // Find destination texture
    auto dstIt = g_textures.find(dstHandle);
    if (dstIt == g_textures.end()) {
        std::cerr << "copyTexture: destination texture not found: " << dstHandle << std::endl;
        return;
    }

    // Copy entire texture (all mip levels, array slices)
    g_d3d11.context->CopyResource(dstIt->second.Get(), srcIt->second.Get());

    std::cout << "[DEBUG] Texture copy: full resource copy completed" << std::endl;
}

/**
 * Copy a specific region of a texture using CopySubresourceRegion
 * Allows partial texture copies with offset control
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTextureRegion
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle,
     jint srcX, jint srcY, jint srcZ, jint dstX, jint dstY, jint dstZ,
     jint width, jint height, jint depth, jint mipLevel) {

    if (!g_d3d11.initialized) {
        std::cerr << "copyTextureRegion: DirectX not initialized" << std::endl;
        return;
    }

    uint64_t srcHandle = static_cast<uint64_t>(srcTextureHandle);
    uint64_t dstHandle = static_cast<uint64_t>(dstTextureHandle);

    // Find source texture
    auto srcIt = g_textures.find(srcHandle);
    if (srcIt == g_textures.end()) {
        std::cerr << "copyTextureRegion: source texture not found: " << srcHandle << std::endl;
        return;
    }

    // Find destination texture
    auto dstIt = g_textures.find(dstHandle);
    if (dstIt == g_textures.end()) {
        std::cerr << "copyTextureRegion: destination texture not found: " << dstHandle << std::endl;
        return;
    }

    // Setup source box for region copy
    D3D11_BOX srcBox;
    srcBox.left = srcX;
    srcBox.right = srcX + width;
    srcBox.top = srcY;
    srcBox.bottom = srcY + height;
    srcBox.front = srcZ;
    srcBox.back = srcZ + depth;

    // Calculate subresource index for the mip level
    UINT srcSubresource = D3D11CalcSubresource(mipLevel, 0, 1);
    UINT dstSubresource = D3D11CalcSubresource(mipLevel, 0, 1);

    // Copy the texture region
    g_d3d11.context->CopySubresourceRegion(
        dstIt->second.Get(),  // Destination
        dstSubresource,       // Destination subresource (mip level)
        dstX,                 // Destination X
        dstY,                 // Destination Y
        dstZ,                 // Destination Z
        srcIt->second.Get(),  // Source
        srcSubresource,       // Source subresource (mip level)
        &srcBox               // Source box
    );

    std::cout << "[DEBUG] Texture region copy: " << width << "x" << height << "x" << depth
              << " at mip level " << mipLevel << std::endl;
}

/**
 * Copy texture data to a buffer (for readback/download operations)
 * Uses a staging texture as intermediate step since textures can't be mapped directly
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTextureToBuffer
    (JNIEnv* env, jclass clazz, jlong textureHandle, jlong bufferHandle, jint mipLevel) {

    if (!g_d3d11.initialized) {
        std::cerr << "copyTextureToBuffer: DirectX not initialized" << std::endl;
        return;
    }

    uint64_t texHandle = static_cast<uint64_t>(textureHandle);
    uint64_t bufHandle = static_cast<uint64_t>(bufferHandle);

    // Find source texture
    auto texIt = g_textures.find(texHandle);
    if (texIt == g_textures.end()) {
        std::cerr << "copyTextureToBuffer: texture not found: " << texHandle << std::endl;
        return;
    }

    // Find destination buffer
    ComPtr<ID3D11Buffer> dstBuffer;
    auto vbIt = g_vertexBuffers.find(bufHandle);
    if (vbIt != g_vertexBuffers.end()) {
        dstBuffer = vbIt->second;
    } else {
        auto ibIt = g_indexBuffers.find(bufHandle);
        if (ibIt != g_indexBuffers.end()) {
            dstBuffer = ibIt->second;
        } else {
            std::cerr << "copyTextureToBuffer: buffer not found: " << bufHandle << std::endl;
            return;
        }
    }

    // Get texture description
    D3D11_TEXTURE2D_DESC texDesc;
    texIt->second->GetDesc(&texDesc);

    // Calculate mip level dimensions
    UINT mipWidth = (std::max)(1u, texDesc.Width >> mipLevel);
    UINT mipHeight = (std::max)(1u, texDesc.Height >> mipLevel);
    UINT rowPitch = mipWidth * 4; // Assuming RGBA8 format
    UINT dataSize = rowPitch * mipHeight;

    // Create staging texture for readback
    D3D11_TEXTURE2D_DESC stagingDesc = texDesc;
    stagingDesc.Width = mipWidth;
    stagingDesc.Height = mipHeight;
    stagingDesc.MipLevels = 1;
    stagingDesc.ArraySize = 1;
    stagingDesc.Usage = D3D11_USAGE_STAGING;
    stagingDesc.BindFlags = 0;
    stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    stagingDesc.MiscFlags = 0;

    ComPtr<ID3D11Texture2D> stagingTexture;
    HRESULT hr = g_d3d11.device->CreateTexture2D(&stagingDesc, nullptr, &stagingTexture);
    if (FAILED(hr)) {
        std::cerr << "copyTextureToBuffer: failed to create staging texture: " << std::hex << hr << std::endl;
        return;
    }

    // Copy texture to staging texture
    UINT srcSubresource = D3D11CalcSubresource(mipLevel, 0, texDesc.MipLevels);
    g_d3d11.context->CopySubresourceRegion(
        stagingTexture.Get(), 0, 0, 0, 0,
        texIt->second.Get(), srcSubresource, nullptr
    );

    // Map staging texture to read data
    D3D11_MAPPED_SUBRESOURCE mappedTex;
    hr = g_d3d11.context->Map(stagingTexture.Get(), 0, D3D11_MAP_READ, 0, &mappedTex);
    if (SUCCEEDED(hr)) {
        // Map destination buffer
        D3D11_MAPPED_SUBRESOURCE mappedBuf;
        hr = g_d3d11.context->Map(dstBuffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedBuf);
        if (SUCCEEDED(hr)) {
            // Copy texture data to buffer
            if (mappedTex.RowPitch == rowPitch) {
                // Direct copy if row pitch matches
                memcpy(mappedBuf.pData, mappedTex.pData, dataSize);
            } else {
                // Row-by-row copy if row pitch differs (due to alignment)
                uint8_t* src = static_cast<uint8_t*>(mappedTex.pData);
                uint8_t* dst = static_cast<uint8_t*>(mappedBuf.pData);
                for (UINT y = 0; y < mipHeight; y++) {
                    memcpy(dst, src, rowPitch);
                    src += mappedTex.RowPitch;
                    dst += rowPitch;
                }
            }
            g_d3d11.context->Unmap(dstBuffer.Get(), 0);
            std::cout << "[DEBUG] Texture copied to buffer: " << dataSize << " bytes" << std::endl;
        } else {
            std::cerr << "copyTextureToBuffer: failed to map buffer: " << std::hex << hr << std::endl;
        }
        g_d3d11.context->Unmap(stagingTexture.Get(), 0);
    } else {
        std::cerr << "copyTextureToBuffer: failed to map staging texture: " << std::hex << hr << std::endl;
    }
}

// ==================== GPU SYNCHRONIZATION (FENCES/QUERIES) ====================

/**
 * Create a GPU fence (implemented as D3D11 Event Query)
 * Used for CPU-GPU synchronization and command buffer completion detection
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createFence
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) {
        std::cerr << "createFence: DirectX not initialized" << std::endl;
        return 0;
    }

    // Create Event Query (acts as a fence in D3D11)
    D3D11_QUERY_DESC queryDesc = {};
    queryDesc.Query = D3D11_QUERY_EVENT;
    queryDesc.MiscFlags = 0;

    ComPtr<ID3D11Query> query;
    HRESULT hr = g_d3d11.device->CreateQuery(&queryDesc, &query);

    if (FAILED(hr)) {
        std::cerr << "createFence: failed to create query: " << std::hex << hr << std::endl;
        return 0;
    }

    // Generate handle and store query
    uint64_t handle = generateHandle();
    g_queries[handle] = query;

    std::cout << "[DEBUG] Created GPU fence (query): handle=0x" << std::hex << handle << std::dec << std::endl;
    return static_cast<jlong>(handle);
}

/**
 * Signal a fence by issuing an End() command
 * The GPU will signal this fence when all previous commands complete
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_signalFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle) {

    if (!g_d3d11.initialized) {
        std::cerr << "signalFence: DirectX not initialized" << std::endl;
        return;
    }

    uint64_t handle = static_cast<uint64_t>(fenceHandle);
    auto it = g_queries.find(handle);
    if (it == g_queries.end()) {
        std::cerr << "signalFence: fence not found: " << handle << std::endl;
        return;
    }

    // Issue End() to signal the query
    // GPU will complete this when all previous commands finish
    g_d3d11.context->End(it->second.Get());

    std::cout << "[DEBUG] Fence signaled: handle=0x" << std::hex << handle << std::dec << std::endl;
}

/**
 * Check if a fence has been signaled (non-blocking)
 * Returns true if GPU has completed all commands up to this fence
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isFenceSignaled
    (JNIEnv* env, jclass clazz, jlong fenceHandle) {

    if (!g_d3d11.initialized) {
        return JNI_FALSE;
    }

    uint64_t handle = static_cast<uint64_t>(fenceHandle);
    auto it = g_queries.find(handle);
    if (it == g_queries.end()) {
        std::cerr << "isFenceSignaled: fence not found: " << handle << std::endl;
        return JNI_FALSE;
    }

    // Check query data (non-blocking with D3D11_ASYNC_GETDATA_DONOTFLUSH)
    BOOL queryData = FALSE;
    HRESULT hr = g_d3d11.context->GetData(
        it->second.Get(),
        &queryData,
        sizeof(BOOL),
        D3D11_ASYNC_GETDATA_DONOTFLUSH  // Don't flush, just check
    );

    if (hr == S_OK) {
        // Query data is available, fence is signaled
        return JNI_TRUE;
    } else if (hr == S_FALSE) {
        // Query data not yet available
        return JNI_FALSE;
    } else {
        std::cerr << "isFenceSignaled: GetData failed: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }
}

/**
 * Wait for a fence to be signaled (blocking)
 * Spins until GPU completes all commands up to this fence
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle) {

    if (!g_d3d11.initialized) {
        return;
    }

    uint64_t handle = static_cast<uint64_t>(fenceHandle);
    auto it = g_queries.find(handle);
    if (it == g_queries.end()) {
        std::cerr << "waitForFence: fence not found: " << handle << std::endl;
        return;
    }

    std::cout << "[DEBUG] Waiting for fence: handle=0x" << std::hex << handle << std::dec << std::endl;

    // Blocking wait for query completion
    BOOL queryData = FALSE;
    while (g_d3d11.context->GetData(it->second.Get(), &queryData, sizeof(BOOL), 0) != S_OK) {
        // Spin wait (could add Sleep(0) to yield CPU)
    }

    std::cout << "[DEBUG] Fence signaled (wait complete): handle=0x" << std::hex << handle << std::dec << std::endl;
}

// ==================== RENDERDOC JNI IMPLEMENTATION ====================

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocIsAvailable
    (JNIEnv* env, jclass clazz) {
    // Try to initialize RenderDoc if not already done
    if (!g_renderDocInitialized) {
        initializeRenderDoc();
    }
    return g_renderDocInitialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocStartFrameCapture
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        std::cerr << "[RenderDoc] RenderDoc not available - cannot start capture" << std::endl;
        return JNI_FALSE;
    }

    if (!g_d3d11.initialized || !g_d3d11.device) {
        std::cerr << "[RenderDoc] DirectX not initialized - cannot start capture" << std::endl;
        return JNI_FALSE;
    }

    // Start frame capture with the D3D11 device and window
    g_renderDocAPI->StartFrameCapture(g_d3d11.device.Get(), g_d3d11.hwnd);
    std::cout << "[RenderDoc] ✓ Frame capture started" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocEndFrameCapture
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        std::cerr << "[RenderDoc] RenderDoc not available - cannot end capture" << std::endl;
        return JNI_FALSE;
    }

    if (!g_d3d11.initialized || !g_d3d11.device) {
        std::cerr << "[RenderDoc] DirectX not initialized - cannot end capture" << std::endl;
        return JNI_FALSE;
    }

    // End frame capture
    uint32_t result = g_renderDocAPI->EndFrameCapture(g_d3d11.device.Get(), g_d3d11.hwnd);
    if (result == 1) {
        std::cout << "[RenderDoc] ✓ Frame capture ended successfully" << std::endl;
    } else {
        std::cerr << "[RenderDoc] ✗ Failed to end frame capture (no capture was in progress)" << std::endl;
    }
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocTriggerCapture
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        std::cerr << "[RenderDoc] RenderDoc not available - cannot trigger capture" << std::endl;
        return;
    }

    // Trigger a capture for the next frame
    g_renderDocAPI->TriggerCapture();
    std::cout << "[RenderDoc] ✓ Capture triggered for next frame" << std::endl;
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocIsCapturing
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        return JNI_FALSE;
    }

    uint32_t capturing = g_renderDocAPI->IsFrameCapturing();
    return capturing == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocSetCaptureOption
    (JNIEnv* env, jclass clazz, jint option, jint value) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        std::cerr << "[RenderDoc] RenderDoc not available - cannot set capture option" << std::endl;
        return;
    }

    RENDERDOC_CaptureOption opt = static_cast<RENDERDOC_CaptureOption>(option);
    uint32_t val = static_cast<uint32_t>(value);

    uint32_t result = g_renderDocAPI->SetCaptureOptionU32(opt, val);
    if (result == 1) {
        std::cout << "[RenderDoc] ✓ Set capture option " << option << " = " << value << std::endl;
    } else {
        std::cerr << "[RenderDoc] ✗ Failed to set capture option " << option << " = " << value << std::endl;
    }
}

// ============================================================================
// UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts)
// ============================================================================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform4f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2, jfloat v3) {

    if (!g_d3d11.initialized) return;

    // Map uniform location to constant buffer slot and offset
    // For simplicity, use slot 0 with basic layout
    if (location >= 0 && location < 16) { // Support 16 uniform slots
        float uniformData[4] = { v0, v1, v2, v3 };

        // Create or update constant buffer if needed
        if (!g_d3d11.constantBuffers[0]) {
            D3D11_BUFFER_DESC desc = {};
            desc.ByteWidth = 256; // 16 uniforms * 4 floats * 4 bytes
            desc.Usage = D3D11_USAGE_DYNAMIC;
            desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
            desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

            HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[0]);
            if (FAILED(hr)) {
                std::cerr << "Failed to create constant buffer for uniforms" << std::endl;
                return;
            }
        }

        // Update subregion of constant buffer
        D3D11_BOX box = {};
        box.left = location * 16; // 4 floats * 4 bytes = 16 bytes per uniform
        box.right = box.left + 16;
        box.top = 0;
        box.bottom = 1;
        box.front = 0;
        box.back = 1;

        g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[0].Get(), 0, &box, uniformData, 16, 0);
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
        g_d3d11.context->PSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniformMatrix4f
    (JNIEnv* env, jclass clazz, jint location, jfloatArray matrix, jboolean transpose) {

    if (!g_d3d11.initialized || !matrix) return;

    jsize length = env->GetArrayLength(matrix);
    if (length < 16) return;

    float matrixData[16];
    env->GetFloatArrayRegion(matrix, 0, 16, matrixData);

    // Use slot 1 for matrices (different from regular uniforms)
    if (!g_d3d11.constantBuffers[1]) {
        D3D11_BUFFER_DESC desc = {};
        desc.ByteWidth = 4096; // Large buffer for matrices
        desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[1]);
        if (FAILED(hr)) {
            std::cerr << "Failed to create matrix constant buffer" << std::endl;
            return;
        }
    }

    // Calculate offset (16 floats * 4 bytes = 64 bytes per matrix)
    UINT offset = (location % 16) * 64;

    D3D11_BOX box = {};
    box.left = offset;
    box.right = offset + 64;
    box.top = 0;
    box.bottom = 1;
    box.front = 0;
    box.back = 1;

    g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[1].Get(), 0, &box, matrixData, 64, 0);
    g_d3d11.context->VSSetConstantBuffers(1, 1, g_d3d11.constantBuffers[1].GetAddressOf());
    g_d3d11.context->PSSetConstantBuffers(1, 1, g_d3d11.constantBuffers[1].GetAddressOf());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1i
    (JNIEnv* env, jclass clazz, jint location, jint value) {

    if (!g_d3d11.initialized) return;

    // Convert integer to float for storage in constant buffer
    float uniformData[4] = { static_cast<float>(value), 0.0f, 0.0f, 0.0f };

    // Use slot 2 for integer uniforms
    if (!g_d3d11.constantBuffers[2]) {
        D3D11_BUFFER_DESC desc = {};
        desc.ByteWidth = 256;
        desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[2]);
        if (FAILED(hr)) {
            std::cerr << "Failed to create integer uniform constant buffer" << std::endl;
            return;
        }
    }

    UINT offset = (location % 16) * 16;
    D3D11_BOX box = {};
    box.left = offset;
    box.right = offset + 16;
    box.top = 0;
    box.bottom = 1;
    box.front = 0;
    box.back = 1;

    g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[2].Get(), 0, &box, uniformData, 16, 0);
    g_d3d11.context->VSSetConstantBuffers(2, 1, g_d3d11.constantBuffers[2].GetAddressOf());
    g_d3d11.context->PSSetConstantBuffers(2, 1, g_d3d11.constantBuffers[2].GetAddressOf());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform1f
    (JNIEnv* env, jclass clazz, jint location, jfloat value) {

    if (!g_d3d11.initialized) return;

    float uniformData[4] = { value, 0.0f, 0.0f, 0.0f };

    // Use slot 0 for float uniforms (same as setUniform4f)
    if (!g_d3d11.constantBuffers[0]) {
        D3D11_BUFFER_DESC desc = {};
        desc.ByteWidth = 256;
        desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[0]);
        if (FAILED(hr)) {
            std::cerr << "Failed to create float uniform constant buffer" << std::endl;
            return;
        }
    }

    UINT offset = (location % 16) * 16;
    D3D11_BOX box = {};
    box.left = offset;
    box.right = offset + 16;
    box.top = 0;
    box.bottom = 1;
    box.front = 0;
    box.back = 1;

    g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[0].Get(), 0, &box, uniformData, 16, 0);
    g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    g_d3d11.context->PSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_useProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized) return;

    // For DirectX 11, programs are mapped to shader pipelines
    // This is a simplified implementation - in a real scenario, you'd need to track
    // vertex/pixel shader pairs and switch between them

    // For now, we'll use the default shader pipeline when any program is set
    if (program != 0) {
        // Use default shaders (simplified approach)
        g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
        g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
        g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    } else {
        // No program bound - clear shaders
        g_d3d11.context->VSSetShader(nullptr, nullptr, 0);
        g_d3d11.context->PSSetShader(nullptr, nullptr, 0);
        g_d3d11.context->IASetInputLayout(nullptr);
    }
}

// ==================== DIRECT OPENGL → DIRECTX 11 TRANSLATION (VULKANMOD APPROACH) ====================

// Direct shader compilation from GLSL source to DirectX 11 HLSL shader
JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLProgramShader
    (JNIEnv* env, jclass clazz, jint type) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique DirectX 11 shader handle (treated as OpenGL shader ID for compatibility)
    uint64_t shaderHandle = generateHandle();

    // VULKANMOD APPROACH: Return handle directly, no OpenGL tracking
    // In real implementation, would immediately compile GLSL→HLSL and create DirectX shader
    return (jint)shaderHandle;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderPipeline
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique DirectX 11 shader pipeline handle
    uint64_t pipelineHandle = generateHandle();

    // VULKANMOD APPROACH: Return handle directly
    // In real implementation, would create DirectX 11 pipeline state object
    // For now, just return the handle for compatibility
    std::cout << "[Native] Created shader pipeline: VS=0x" << std::hex << vertexShader
              << ", PS=0x" << std::hex << pixelShader
              << " -> Pipeline=0x" << std::hex << pipelineHandle << std::dec << std::endl;

    return pipelineHandle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shaderSource
    (JNIEnv* env, jclass clazz, jint shader, jstring source) {

    if (!g_d3d11.initialized || shader == 0) return;

    // VULKANMOD APPROACH: GLSL source is stored for translation to HLSL
    const char* sourceStr = env->GetStringUTFChars(source, nullptr);
    if (sourceStr) {
        // TODO: Store GLSL source for GLSL→HLSL translation
        // For now, just process the string
        env->ReleaseStringUTFChars(source, sourceStr);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShader
    (JNIEnv* env, jclass clazz, jint shader) {

    if (!g_d3d11.initialized || shader == 0) return;

    // VULKANMOD APPROACH: Immediately compile GLSL to DirectX 11 HLSL shader
    // Real implementation would:
    // 1. Translate GLSL → HLSL using glslang or similar
    // 2. Compile HLSL using D3DCompile API
    // 3. Create ID3D11VertexShader or ID3D11PixelShader
    // 4. Store in existing DirectX shader maps (g_vertexShaders/g_pixelShaders)

    // For demonstration, use default shaders
    // In production, this does actual GLSL→HLSL translation and compilation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createProgram
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique DirectX 11 program handle (shader pipeline)
    uint64_t programHandle = generateHandle();

    // VULKANMOD APPROACH: Return handle as OpenGL program ID
    // Real implementation would create DirectX 11 shader pipeline
    return (jint)programHandle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_attachShader
    (JNIEnv* env, jclass clazz, jint program, jint shader) {

    if (!g_d3d11.initialized || program == 0 || shader == 0) return;

    // VULKANMOD APPROACH: Associate DirectX shader with program pipeline
    // Real implementation would update shader pipeline with attached shaders
    // For now, this is a no-op - handles are managed by the caller
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_linkProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized || program == 0) return;

    // VULKANMOD APPROACH: Finalize DirectX 11 shader pipeline
    // Real implementation would create complete shader pipeline from attached shaders
    // For now, this is a no-op - pipeline creation happens when needed
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_validateProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized || program == 0) return;

    // VULKANMOD APPROACH: Validate DirectX 11 shader pipeline
    // Real implementation would validate pipeline completeness
    // For now, always return success
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteShader
    (JNIEnv* env, jclass clazz, jint shader) {

    if (shader == 0) return;

    // VULKANMOD APPROACH: Destroy DirectX 11 shader
    // Real implementation would convert shader ID to DirectX handle and destroy
    // For now, this is a no-op - handles are managed by Java garbage collection
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (program == 0) return;

    // VULKANMOD APPROACH: Destroy DirectX 11 shader pipeline
    // Real implementation would convert program ID to DirectX pipeline handle and destroy
    // For now, this is a no-op
}

// Vertex attribute management (VulkanMod approach - direct DirectX calls)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Enable vertex attribute in DirectX input layout
    // Real implementation would update input layout state
    // For now, this is a no-op - input layout is created with shaders
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Disable vertex attribute
    // Real implementation would update input layout state
    // For now, this is a no-op
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jlong pointer) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Define vertex attribute format for DirectX input layout
    // Real implementation would store for input layout creation
    // For now, this is a no-op - input layout comes from shader reflection
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribPointer_1
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jobject pointer) {

    // ByteBuffer version - delegate to long pointer version
    Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribPointer(env, clazz, index, size, type, normalized, stride, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glVertexAttribIPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jint stride, jlong pointer) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Define integer vertex attribute format
    // Real implementation would store for input layout creation
    // For now, this is a no-op
}

// Uniform location management (VulkanMod approach - direct DirectX constant buffers)
JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetUniformLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name) {

    if (!g_d3d11.initialized || program == 0) return -1;

    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (!nameStr) return -1;

    // VULKANMOD APPROACH: Return DirectX constant buffer slot/offset
    // Real implementation would:
    // 1. Parse HLSL shader bytecode for constant buffer parameters
    // 2. Map uniform names to constant buffer slots and offsets
    // 3. Return proper location indices

    // Simplified: return sequential numbers for common uniforms
    jint location = -1;
    if (strcmp(nameStr, "ModelViewMatrix") == 0) location = 0;
    else if (strcmp(nameStr, "ProjectionMatrix") == 0) location = 1;
    else if (strcmp(nameStr, "MVPMatrix") == 0) location = 2;
    else if (strcmp(nameStr, "Color") == 0) location = 3;
    else if (strcmp(nameStr, "Texture") == 0) location = 4;
    else location = 5 + (strlen(nameStr) % 10); // Pseudo-random for other uniforms

    env->ReleaseStringUTFChars(name, nameStr);
    return location;
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetUniformLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name) {

    // ByteBuffer version - simplified
    return -1;
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetAttribLocation
    (JNIEnv* env, jclass clazz, jint program, jstring name) {

    if (!g_d3d11.initialized || program == 0) return -1;

    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (!nameStr) return -1;

    // VULKANMOD APPROACH: Return DirectX input layout slot index
    // Real implementation would:
    // 1. Parse vertex shader input signature
    // 2. Map attribute names to input layout slot indices
    // 3. Return proper attribute locations

    // Simplified: return sequential numbers for common attributes
    jint location = -1;
    if (strcmp(nameStr, "position") == 0) location = 0;
    else if (strcmp(nameStr, "texCoord") == 0) location = 1;
    else if (strcmp(nameStr, "color") == 0) location = 2;
    else if (strcmp(nameStr, "normal") == 0) location = 3;
    else location = 4 + (strlen(nameStr) % 8); // Pseudo-random for other attributes

    env->ReleaseStringUTFChars(name, nameStr);
    return location;
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_glGetAttribLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name) {

    // ByteBuffer version - simplified
    return -1;
}

// Additional uniform methods (VulkanMod approach)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform2f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1) {

    if (!g_d3d11.initialized || location < 0) return;

    // Pack into 4-float uniform for DirectX constant buffer
    float data[4] = { v0, v1, 0.0f, 0.0f };
    Java_com_vitra_render_jni_VitraNativeRenderer_setUniform4f(env, clazz, location, data[0], data[1], data[2], data[3]);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setUniform3f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2) {

    if (!g_d3d11.initialized || location < 0) return;

    // Pack into 4-float uniform for DirectX constant buffer
    float data[4] = { v0, v1, v2, 0.0f };
    Java_com_vitra_render_jni_VitraNativeRenderer_setUniform4f(env, clazz, location, data[0], data[1], data[2], data[3]);
}

// ==================== MISSING FRAMEBUFFER AND TEXTURE METHODS ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createFramebuffer
    (JNIEnv* env, jclass clazz, jint width, jint height) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();

    // For simplicity, return the handle directly (framebuffer creation is implicit in DirectX 11)
    return (jlong)handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, framebuffer binding is handled through render target views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferTexture2D
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint textarget, jlong textureHandle, jint level) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, this maps to setting render target views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferRenderbuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint renderbuffertarget, jlong renderbufferHandle) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, this maps to setting depth/stencil views
    // This is a placeholder implementation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_checkFramebufferStatus
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target) {

    if (!g_d3d11.initialized) return 0;

    // Always return framebuffer complete for simplicity
    return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle) {

    if (!g_d3d11.initialized) return;

    // Remove from tracking map
    // This is handled by the destroyResource method
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createRenderbuffer
    (JNIEnv* env, jclass clazz, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();
    return (jlong)handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target) {

    if (!g_d3d11.initialized) return;

    // Renderbuffer binding in DirectX 11 is handled through depth/stencil views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderbufferStorage
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target, jint internalformat, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, this is handled through texture/surface creation
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle) {

    if (!g_d3d11.initialized) return;

    // Remove from tracking map
    // This is handled by the destroyResource method
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexArray
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();
    return (jlong)handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle) {

    if (!g_d3d11.initialized) return;

    // Vertex array binding in DirectX 11 is handled through input layouts
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle) {

    if (!g_d3d11.initialized) return;

    // Remove from tracking map
    // This is handled by the destroyResource method
}

// ==================== MISSING UNIFORM AND STATE METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param) {

    if (!g_d3d11.initialized) return;

    // Texture parameter setting in DirectX 11 is handled through sampler states
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureParameterf
    (JNIEnv* env, jclass clazz, jint target, jint pname, jfloat param) {

    if (!g_d3d11.initialized) return;

    // Texture parameter setting in DirectX 11 is handled through sampler states
    // This is a placeholder implementation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname) {

    if (!g_d3d11.initialized) return 0;

    // Return default values for common texture parameters
    switch (pname) {
        case 0x1004: // GL_TEXTURE_WIDTH
            return 1024;
        case 0x1005: // GL_TEXTURE_HEIGHT
            return 1024;
        default:
            return 0;
    }
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTextureLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname) {

    if (!g_d3d11.initialized) return 0;

    // Return default values for texture level parameters
    switch (pname) {
        case 0x8D3B: // GL_TEXTURE_WIDTH
            return 1024;
        case 0x8D3A: // GL_TEXTURE_HEIGHT
            return 1024;
        default:
            return 0;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param) {

    if (!g_d3d11.initialized) return;

    // Pixel store mode in DirectX 11 is handled through texture upload parameters
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setLineWidth
    (JNIEnv* env, jclass clazz, jfloat width) {

    if (!g_d3d11.initialized) return;

    // Line width in DirectX 11 is handled through rasterizer state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPolygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units) {

    if (!g_d3d11.initialized) return;

    // Polygon offset in DirectX 11 is handled through rasterizer state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendFunc
    (JNIEnv* env, jclass clazz, jint sfactor, jint dfactor) {

    if (!g_d3d11.initialized) return;

    // Blend function in DirectX 11 is handled through blend state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setBlendEquation
    (JNIEnv* env, jclass clazz, jint mode, jint modeAlpha) {

    if (!g_d3d11.initialized) return;

    // Blend equation in DirectX 11 is handled through blend state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setDrawBuffers
    (JNIEnv* env, jclass clazz, jintArray buffers) {

    if (!g_d3d11.initialized) return;

    // Draw buffers in DirectX 11 are handled through render target views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilOpSeparate
    (JNIEnv* env, jclass clazz, jint face, jint sfail, jint dpfail, jint dppass) {

    if (!g_d3d11.initialized) return;

    // Stencil operations in DirectX 11 are handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilFuncSeparate
    (JNIEnv* env, jclass clazz, jint face, jint func, jint ref, jint mask) {

    if (!g_d3d11.initialized) return;

    // Stencil function in DirectX 11 is handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setStencilMaskSeparate
    (JNIEnv* env, jclass clazz, jint face, jint mask) {

    if (!g_d3d11.initialized) return;

    // Stencil mask in DirectX 11 is handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getMaxTextureSize
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 1024;

    // Return a reasonable default texture size for DirectX 11
    return 4096; // 4K textures are common
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_finish
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;

    // Finish in DirectX 11 - flush all commands
    g_d3d11.context->Flush();
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setHint
    (JNIEnv* env, jclass clazz, jint target, jint hint) {

    if (!g_d3d11.initialized) return;

    // Hints in DirectX 11 are mostly ignored or handled through driver settings
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTexSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint xoffset, jint yoffset, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    // Texture sub-image copying in DirectX 11 is handled through CopySubresourceRegion
    // This is a placeholder implementation
}
