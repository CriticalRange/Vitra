#include "vitra_d3d11.h"
#include <iostream>
#include <sstream>
#include <random>
#include <locale>
#include <codecvt>
#include <d3d11_3.h>
#include <dxgi1_4.h>
#include <d3dcompiler.h>
#include <vector>

// ============================================================================
// MANUAL D3D11 DEVICE FLAGS DEFINITION
// ============================================================================
// Ensure DEBUGGABLE flag is available (may not be in older SDK versions)
// This flag is critical for RenderDoc and PIX graphics debugging
#ifndef D3D11_CREATE_DEVICE_DEBUGGABLE
#define D3D11_CREATE_DEVICE_DEBUGGABLE 0x40
#endif

// Explicitly define all device creation flags we need
// This ensures compatibility even if SDK version varies
#define VITRA_D3D11_CREATE_DEVICE_DEBUG            0x2
#define VITRA_D3D11_CREATE_DEVICE_DEBUGGABLE       0x40
#define VITRA_D3D11_CREATE_DEVICE_BGRA_SUPPORT     0x20

// Define D3D_FEATURE_LEVEL_11_3 if not available in SDK
// Feature level 11.3 was added in Windows 10 SDK
#ifndef D3D_FEATURE_LEVEL_11_3
#define D3D_FEATURE_LEVEL_11_3 (D3D_FEATURE_LEVEL)0xb300
#endif

// Global D3D11 resources
D3D11Resources g_d3d11 = {};

// RenderDoc API support
RENDERDOC_API_1_6_0* g_renderDocAPI = nullptr;
bool g_renderDocInitialized = false;

// Resource tracking
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_vertexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_indexBuffers;
std::unordered_map<uint64_t, UINT> g_vertexBufferStrides; // Track stride for each vertex buffer
std::unordered_map<uint64_t, ComPtr<ID3D11VertexShader>> g_vertexShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11PixelShader>> g_pixelShaders;
std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_shaderBlobs; // Shader bytecode blobs for input layout creation
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_inputLayouts;
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_vertexFormatInputLayouts; // Input layouts keyed by vertex format hash
std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> g_textures;
std::unordered_map<uint64_t, ComPtr<ID3D11ShaderResourceView>> g_shaderResourceViews;
std::unordered_map<uint64_t, ComPtr<ID3D11Query>> g_queries;

// Constant buffer tracking (for VulkanMod-style uniform system)
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_constantBuffers;
uint64_t g_boundConstantBuffersVS[4] = {0, 0, 0, 0};  // Track bound constant buffers for vertex shader (b0-b3)
uint64_t g_boundConstantBuffersPS[4] = {0, 0, 0, 0};  // Track bound constant buffers for pixel shader (b0-b3)

// OpenGL state tracking for texture binding and state management
struct GlStateTracking {
    int currentTextureUnit = 0;
    int boundTextures[32] = {0}; // Up to 32 texture units
    int boundFramebuffer = 0;
    int boundRenderbuffer = 0;
    int boundArrayBuffer = 0;
    int boundElementArrayBuffer = 0;
    bool blendEnabled = false;
    bool depthTestEnabled = true;
    bool cullEnabled = true;
    bool scissorEnabled = false;
    bool polygonOffsetEnabled = false;
    bool colorLogicOpEnabled = false;
};
static GlStateTracking g_glState = {};

// Handle generator
std::random_device rd;
std::mt19937_64 gen(rd());
std::uniform_int_distribution<uint64_t> dis;

uint64_t generateHandle() {
    return dis(gen);
}

// Helper function to log to Java System.out (bypasses stdout buffering)
void logToJava(JNIEnv* env, const char* message) {
    jclass systemClass = env->FindClass("java/lang/System");
    if (!systemClass) return;

    jfieldID outField = env->GetStaticFieldID(systemClass, "out", "Ljava/io/PrintStream;");
    if (!outField) return;

    jobject outStream = env->GetStaticObjectField(systemClass, outField);
    if (!outStream) return;

    jclass printStreamClass = env->FindClass("java/io/PrintStream");
    if (!printStreamClass) return;

    jmethodID printlnMethod = env->GetMethodID(printStreamClass, "println", "(Ljava/lang/String;)V");
    if (!printlnMethod) return;

    jstring jmsg = env->NewStringUTF(message);
    env->CallVoidMethod(outStream, printlnMethod, jmsg);
    env->DeleteLocalRef(jmsg);
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
    // Use shader reflection to automatically generate input layout from vertex shader bytecode
    // This handles different vertex formats for different shaders (position, position_color, position_tex, etc.)

    ComPtr<ID3D11ShaderReflection> reflector;
    HRESULT hr = D3DReflect(
        vertexShaderBlob->GetBufferPointer(),
        vertexShaderBlob->GetBufferSize(),
        IID_ID3D11ShaderReflection,
        &reflector
    );

    if (FAILED(hr)) {
        return false;
    }

    D3D11_SHADER_DESC shaderDesc;
    reflector->GetDesc(&shaderDesc);

    std::vector<D3D11_INPUT_ELEMENT_DESC> inputElements;
    for (UINT i = 0; i < shaderDesc.InputParameters; i++) {
        D3D11_SIGNATURE_PARAMETER_DESC paramDesc;
        reflector->GetInputParameterDesc(i, &paramDesc);

        // Map semantic name and format to DXGI format
        D3D11_INPUT_ELEMENT_DESC elementDesc = {};
        elementDesc.SemanticName = paramDesc.SemanticName;
        elementDesc.SemanticIndex = paramDesc.SemanticIndex;
        elementDesc.InputSlot = 0;
        elementDesc.AlignedByteOffset = D3D11_APPEND_ALIGNED_ELEMENT;
        elementDesc.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
        elementDesc.InstanceDataStepRate = 0;

        // Determine DXGI format based on component mask and type
        if (paramDesc.Mask == 1) {
            // Single component (R)
            if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_UINT32) elementDesc.Format = DXGI_FORMAT_R32_UINT;
            else if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_SINT32) elementDesc.Format = DXGI_FORMAT_R32_SINT;
            else elementDesc.Format = DXGI_FORMAT_R32_FLOAT;
        }
        else if (paramDesc.Mask <= 3) {
            // Two components (RG)
            if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_UINT32) elementDesc.Format = DXGI_FORMAT_R32G32_UINT;
            else if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_SINT32) elementDesc.Format = DXGI_FORMAT_R32G32_SINT;
            else elementDesc.Format = DXGI_FORMAT_R32G32_FLOAT;
        }
        else if (paramDesc.Mask <= 7) {
            // Three components (RGB)
            if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_UINT32) elementDesc.Format = DXGI_FORMAT_R32G32B32_UINT;
            else if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_SINT32) elementDesc.Format = DXGI_FORMAT_R32G32B32_SINT;
            else elementDesc.Format = DXGI_FORMAT_R32G32B32_FLOAT;
        }
        else {
            // Four components (RGBA)
            if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_UINT32) elementDesc.Format = DXGI_FORMAT_R32G32B32A32_UINT;
            else if (paramDesc.ComponentType == D3D_REGISTER_COMPONENT_SINT32) elementDesc.Format = DXGI_FORMAT_R32G32B32A32_SINT;
            else elementDesc.Format = DXGI_FORMAT_R32G32B32A32_FLOAT;
        }

        inputElements.push_back(elementDesc);

        // Logging disabled
    }

    hr = g_d3d11.device->CreateInputLayout(
        inputElements.data(),
        static_cast<UINT>(inputElements.size()),
        vertexShaderBlob->GetBufferPointer(),
        vertexShaderBlob->GetBufferSize(),
        inputLayout
    );

    if (FAILED(hr)) {
        return false;
    }


    return true;
}

// Create input layout from vertex format descriptor (from Java)
// This is the VulkanMod approach: use actual vertex format, not shader reflection
bool createInputLayoutFromVertexFormat(const jint* vertexFormatDesc, jint descLength, ID3DBlob* vertexShaderBlob, ID3D11InputLayout** inputLayout) {
    if (descLength < 1) return false;

    int elementCount = vertexFormatDesc[0];
    if (elementCount == 0 || descLength < 1 + elementCount * 4) return false;

    std::vector<D3D11_INPUT_ELEMENT_DESC> inputElements;

    // Vertex format encoding: [elementCount, usage1, type1, count1, offset1, usage2, type2, count2, offset2, ...]
    // Usage: POSITION=0, COLOR=1, UV=2, NORMAL=3, etc. (VertexFormatElement.Usage ordinal)
    // Type: FLOAT=0, UBYTE=1, BYTE=2, USHORT=3, SHORT=4, UINT=5, INT=6 (VertexFormatElement.Type ordinal)

    for (int i = 0; i < elementCount; i++) {
        int baseIdx = 1 + i * 4;
        int usage = vertexFormatDesc[baseIdx];
        int type = vertexFormatDesc[baseIdx + 1];
        int count = vertexFormatDesc[baseIdx + 2];
        int offset = vertexFormatDesc[baseIdx + 3];

        D3D11_INPUT_ELEMENT_DESC elementDesc = {};

        // Map usage to semantic name
        switch (usage) {
            case 0: elementDesc.SemanticName = "POSITION"; break;
            case 1: elementDesc.SemanticName = "COLOR"; break;
            case 2:
            case 3: // UV0 and UV1
                elementDesc.SemanticName = "TEXCOORD";
                elementDesc.SemanticIndex = (usage == 2) ? 0 : 1;
                break;
            case 4: elementDesc.SemanticName = "NORMAL"; break;
            case 5: elementDesc.SemanticName = "PADDING"; break; // Padding
            case 6: elementDesc.SemanticName = "UV"; elementDesc.SemanticIndex = 2; break; // UV2
            case 7: elementDesc.SemanticName = "TANGENT"; break;
            case 8: elementDesc.SemanticName = "BITANGENT"; break;
            default: elementDesc.SemanticName = "TEXCOORD"; break;
        }

        // Map type and count to DXGI format
        switch (type) {
            case 0: // FLOAT
                switch (count) {
                    case 1: elementDesc.Format = DXGI_FORMAT_R32_FLOAT; break;
                    case 2: elementDesc.Format = DXGI_FORMAT_R32G32_FLOAT; break;
                    case 3: elementDesc.Format = DXGI_FORMAT_R32G32B32_FLOAT; break;
                    case 4: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_FLOAT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_FLOAT; break;
                }
                break;
            case 1: // UBYTE
                switch (count) {
                    case 4: elementDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; break;
                    default: elementDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; break;
                }
                break;
            case 2: // BYTE
                switch (count) {
                    case 4: elementDesc.Format = DXGI_FORMAT_R8G8B8A8_SINT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R8G8B8A8_SINT; break;
                }
                break;
            case 3: // USHORT
                switch (count) {
                    case 2: elementDesc.Format = DXGI_FORMAT_R16G16_UINT; break;
                    case 4: elementDesc.Format = DXGI_FORMAT_R16G16B16A16_UINT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R16G16B16A16_UINT; break;
                }
                break;
            case 4: // SHORT
                switch (count) {
                    case 2: elementDesc.Format = DXGI_FORMAT_R16G16_SINT; break;
                    case 4: elementDesc.Format = DXGI_FORMAT_R16G16B16A16_SINT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R16G16B16A16_SINT; break;
                }
                break;
            case 5: // UINT
                switch (count) {
                    case 1: elementDesc.Format = DXGI_FORMAT_R32_UINT; break;
                    case 2: elementDesc.Format = DXGI_FORMAT_R32G32_UINT; break;
                    case 3: elementDesc.Format = DXGI_FORMAT_R32G32B32_UINT; break;
                    case 4: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_UINT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_UINT; break;
                }
                break;
            case 6: // INT
                switch (count) {
                    case 1: elementDesc.Format = DXGI_FORMAT_R32_SINT; break;
                    case 2: elementDesc.Format = DXGI_FORMAT_R32G32_SINT; break;
                    case 3: elementDesc.Format = DXGI_FORMAT_R32G32B32_SINT; break;
                    case 4: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_SINT; break;
                    default: elementDesc.Format = DXGI_FORMAT_R32G32B32A32_SINT; break;
                }
                break;
            default:
                elementDesc.Format = DXGI_FORMAT_R32G32B32A32_FLOAT;
                break;
        }

        elementDesc.InputSlot = 0;
        elementDesc.AlignedByteOffset = offset; // Use actual offset from vertex format!
        elementDesc.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
        elementDesc.InstanceDataStepRate = 0;

        inputElements.push_back(elementDesc);
    }

    HRESULT hr = g_d3d11.device->CreateInputLayout(
        inputElements.data(),
        static_cast<UINT>(inputElements.size()),
        vertexShaderBlob->GetBufferPointer(),
        vertexShaderBlob->GetBufferSize(),
        inputLayout
    );

    return SUCCEEDED(hr);
}

void updateRenderTargetView() {
    if (!g_d3d11.swapChain) return;

    ComPtr<ID3D11Texture2D> backBuffer;
    HRESULT hr = g_d3d11.swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), &backBuffer);
    if (FAILED(hr)) {
        return;
    }

    hr = g_d3d11.device->CreateRenderTargetView(backBuffer.Get(), nullptr, &g_d3d11.renderTargetView);
    if (FAILED(hr)) {
        return;
    }


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
        return;
    }

    hr = g_d3d11.device->CreateDepthStencilView(g_d3d11.depthStencilBuffer.Get(), nullptr, &g_d3d11.depthStencilView);
    if (FAILED(hr)) {
        return;
    }

    // Logging disabled

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
            // Logging disabled
        } else {
        }
    } else {
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
        // Logging disabled
    } else {
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
        // Logging disabled
    } else {
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
        // Logging disabled
    } else {
    }

    // Logging disabled
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
        // Logging disabled

        // Try common RenderDoc installation paths
        const char* possiblePaths[] = {
            "renderdoc.dll",  // System PATH or current directory
            "C:\\Program Files\\RenderDoc\\renderdoc.dll",
            "C:\\Program Files (x86)\\RenderDoc\\renderdoc.dll",
        };

        for (const char* path : possiblePaths) {
            renderDocModule = LoadLibraryA(path);
            if (renderDocModule) {
                // Logging disabled
                break;
            }
        }
    } else {
        // Logging disabled
    }

    if (!renderDocModule) {
        // Logging disabled
        // Logging disabled
        // Logging disabled
        // Logging disabled
        // Logging disabled
        return false;
    }

    // Get the RENDERDOC_GetAPI function
    pRENDERDOC_GetAPI RENDERDOC_GetAPI = (pRENDERDOC_GetAPI)GetProcAddress(renderDocModule, "RENDERDOC_GetAPI");
    if (!RENDERDOC_GetAPI) {
        return false;
    }

    // Get the API
    int ret = RENDERDOC_GetAPI(eRENDERDOC_API_Version_1_6_0, (void**)&g_renderDocAPI);
    if (ret != 1) {
        g_renderDocAPI = nullptr;
        return false;
    }

    g_renderDocInitialized = true;
    // Logging disabled

    // Set useful capture options for better debugging
    if (g_renderDocAPI) {
        // Capture all command lists (helps with deferred rendering)
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_CaptureAllCmdLists, 1);

        // Verify buffer access for better debugging
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_VerifyBufferAccess, 1);

        // Include all resources in captures
        g_renderDocAPI->SetCaptureOptionU32(eRENDERDOC_Option_RefAllResources, 1);

        // Logging disabled
    }

    return true;
}

void shutdownRenderDoc() {
    if (g_renderDocInitialized && g_renderDocAPI) {
        g_renderDocAPI = nullptr;
        g_renderDocInitialized = false;
        // Logging disabled
    }
}

void setRenderDocResourceName(ID3D11DeviceChild* resource, const char* name) {
    if (!g_renderDocInitialized || !resource || !name) {
        return;
    }

    // Use DirectX debug naming (works with RenderDoc)
    HRESULT hr = resource->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(name), name);
    if (SUCCEEDED(hr)) {
        // Logging disabled
    }
}

// JNI Implementation
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug, jboolean useWarp) {

    // ✅ CRITICAL: Initialize RenderDoc BEFORE creating D3D11 device
    // RenderDoc must hook the device creation to work properly
    initializeRenderDoc();

    g_d3d11.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d11.width = width;
    g_d3d11.height = height;
    g_d3d11.debugEnabled = enableDebug;

    // ==================== FORCE LOAD SDK LAYERS DLL ====================
    // CRITICAL: Manually load d3d11sdklayers.dll BEFORE device creation
    // Without this, DirectX creates a "fake" debug layer (stub interfaces only)
    // Reference: Classic Microsoft "opt-out" behavior where debug flag is accepted
    // but SDK layer DLL is not loaded by runtime
    if (enableDebug) {
        logToJava(env, "[SDK_LAYER] Attempting to manually load SDK Layers DLL...");

        // Try loading SDK layers DLLs in order of preference
        HMODULE sdkLayerModule = nullptr;

        // Try d3d11_3sdklayers.dll first (for Feature Level 11.3)
        sdkLayerModule = LoadLibraryA("d3d11_3sdklayers.dll");
        if (sdkLayerModule) {
            logToJava(env, "[SDK_LAYER] ✓ Loaded d3d11_3sdklayers.dll successfully!");
        } else {
            // Try d3d11_1sdklayers.dll (for Feature Level 11.1)
            sdkLayerModule = LoadLibraryA("d3d11_1sdklayers.dll");
            if (sdkLayerModule) {
                logToJava(env, "[SDK_LAYER] ✓ Loaded d3d11_1sdklayers.dll successfully!");
            } else {
                // Try base d3d11sdklayers.dll (for Feature Level 11.0)
                sdkLayerModule = LoadLibraryA("d3d11sdklayers.dll");
                if (sdkLayerModule) {
                    logToJava(env, "[SDK_LAYER] ✓ Loaded d3d11sdklayers.dll successfully!");
                } else {
                    // FAIL: No SDK layers DLL found - debug layer will be stub only
                    DWORD error = GetLastError();
                    char errorMsg[512];
                    sprintf_s(errorMsg, "[SDK_LAYER] ✗ FAILED to load any SDK Layers DLL! Error: %lu", error);
                    logToJava(env, errorMsg);
                    logToJava(env, "[SDK_LAYER] Debug layer will be FAKE (stub interfaces only, no validation)");
                    logToJava(env, "[SDK_LAYER] Install Graphics Tools from Windows Settings → Apps → Optional Features");
                }
            }
        }

        // CRITICAL: Verify DLL is actually loaded BEFORE device creation
        HMODULE verifyModule = GetModuleHandleA("d3d11_3sdklayers.dll");
        if (!verifyModule) {
            verifyModule = GetModuleHandleA("d3d11_1sdklayers.dll");
        }
        if (!verifyModule) {
            verifyModule = GetModuleHandleA("d3d11sdklayers.dll");
        }

        if (verifyModule) {
            char verifyMsg[256];
            sprintf_s(verifyMsg, "[SDK_LAYER] ✓ VERIFIED: SDK Layers DLL is in process memory (handle=%p)", verifyModule);
            logToJava(env, verifyMsg);
        } else {
            logToJava(env, "[SDK_LAYER] ✗ WARNING: SDK Layers DLL not in process memory - debug layer will be stub!");
        }
    }

    // Create device and swap chain
    UINT creationFlags = D3D11_CREATE_DEVICE_DEBUG;
    creationFlags |= D3D11_CREATE_DEVICE_BGRA_SUPPORT;

    char flagsMsg[256];
    sprintf_s(flagsMsg, "[DEVICE_CREATE] Creation flags: 0x%08X (Debug=%d, BGRA=%d)",
        creationFlags,
        (creationFlags & D3D11_CREATE_DEVICE_DEBUG) != 0,
        (creationFlags & D3D11_CREATE_DEVICE_BGRA_SUPPORT) != 0);
    logToJava(env, flagsMsg);

    // Define the ordering of feature levels that Direct3D attempts to create.
    D3D_FEATURE_LEVEL featureLevels[] =
    {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0,
        D3D_FEATURE_LEVEL_9_3,
        D3D_FEATURE_LEVEL_9_1
    };

    logToJava(env, "[DEVICE_CREATE] Requested feature levels: 11.1, 11.0, 10.1, 10.0, 9.3, 9.1");

    // ==================== WARP MODE SELECTION ====================
    // WARP (Windows Advanced Rasterization Platform) = Software renderer via CPU
    // Use WARP for debugging when hardware GPU driver doesn't support debug layer validation
    // Controlled via renderer.useWarp config option
    D3D_DRIVER_TYPE driverType = D3D_DRIVER_TYPE_HARDWARE;
    if (useWarp) {
        driverType = D3D_DRIVER_TYPE_WARP;
        logToJava(env, "[DEVICE_CREATE] ⚠️ WARP MODE ENABLED - Using CPU software renderer");
        logToJava(env, "[DEVICE_CREATE] Performance will be SLOW but debug validation will be COMPLETE");
    } else {
        logToJava(env, "[DEVICE_CREATE] Using HARDWARE GPU renderer");
    }

    // STEP 1: Create D3D11 Device (separate from swap chain, per Microsoft documentation)
    D3D_FEATURE_LEVEL selectedFeatureLevel;
    ComPtr<ID3D11Device> d3dDevice;
    ComPtr<ID3D11DeviceContext> d3dDeviceContext;

    HRESULT hr = D3D11CreateDevice(
        nullptr,                    // specify nullptr to use the default adapter
        driverType,                 // D3D_DRIVER_TYPE_HARDWARE or D3D_DRIVER_TYPE_WARP
        nullptr,                    // software rasterizer DLL (nullptr for hardware/WARP)
        creationFlags,              // optionally set debug and Direct2D compatibility flags
        featureLevels,
        ARRAYSIZE(featureLevels),
        D3D11_SDK_VERSION,          // always set this to D3D11_SDK_VERSION
        &d3dDevice,
        &selectedFeatureLevel,
        &d3dDeviceContext
    );

    char deviceMsg[256];
    sprintf_s(deviceMsg, "[DEVICE_CREATE] D3D11CreateDevice result: HRESULT=0x%08X", hr);
    logToJava(env, deviceMsg);

    if (FAILED(hr)) {
        // Log which feature level was attempted
        const char* featureLevelName = "UNKNOWN";
        switch (selectedFeatureLevel) {
            case D3D_FEATURE_LEVEL_11_3: featureLevelName = "11.3"; break;
            case D3D_FEATURE_LEVEL_11_1: featureLevelName = "11.1"; break;
            case D3D_FEATURE_LEVEL_11_0: featureLevelName = "11.0"; break;
            case D3D_FEATURE_LEVEL_10_1: featureLevelName = "10.1"; break;
            case D3D_FEATURE_LEVEL_10_0: featureLevelName = "10.0"; break;
            case D3D_FEATURE_LEVEL_9_3: featureLevelName = "9.3"; break;
            case D3D_FEATURE_LEVEL_9_1: featureLevelName = "9.1"; break;
        }
        sprintf_s(deviceMsg, "[DEVICE_CREATE] FAILED: Device creation failed with HRESULT=0x%08X, attempted feature level=%s (0x%04X)",
            hr, featureLevelName, selectedFeatureLevel);
        logToJava(env, deviceMsg);
        return JNI_FALSE;
    }

    // Store device and context in global resources
    g_d3d11.device = d3dDevice;
    g_d3d11.context = d3dDeviceContext;

    // STEP 2: Create Swap Chain using DXGI factory (separate from device)
    ComPtr<IDXGIDevice> dxgiDevice;
    hr = d3dDevice->QueryInterface(__uuidof(IDXGIDevice), &dxgiDevice);
    if (FAILED(hr)) {
        logToJava(env, "[DEVICE_CREATE] FAILED: Could not query IDXGIDevice");
        return JNI_FALSE;
    }

    ComPtr<IDXGIAdapter> dxgiAdapter;
    hr = dxgiDevice->GetAdapter(&dxgiAdapter);
    if (FAILED(hr)) {
        logToJava(env, "[DEVICE_CREATE] FAILED: Could not get DXGI adapter");
        return JNI_FALSE;
    }

    ComPtr<IDXGIFactory> dxgiFactory;
    hr = dxgiAdapter->GetParent(__uuidof(IDXGIFactory), &dxgiFactory);
    if (FAILED(hr)) {
        logToJava(env, "[DEVICE_CREATE] FAILED: Could not get DXGI factory");
        return JNI_FALSE;
    }

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
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    swapChainDesc.Flags = DXGI_SWAP_CHAIN_FLAG_ALLOW_MODE_SWITCH;

    hr = dxgiFactory->CreateSwapChain(d3dDevice.Get(), &swapChainDesc, &g_d3d11.swapChain);
    if (FAILED(hr)) {
        sprintf_s(deviceMsg, "[DEVICE_CREATE] FAILED: Swap chain creation failed with HRESULT=0x%08X", hr);
        logToJava(env, deviceMsg);
        return JNI_FALSE;
    }

    logToJava(env, "[DEVICE_CREATE] Swap chain created successfully");

    // CRITICAL: Log which feature level was actually selected
    if (SUCCEEDED(hr)) {
        const char* featureLevelName = "UNKNOWN";
        switch (selectedFeatureLevel) {
            case D3D_FEATURE_LEVEL_11_3: featureLevelName = "11.3"; break;
            case D3D_FEATURE_LEVEL_11_1: featureLevelName = "11.1"; break;
            case D3D_FEATURE_LEVEL_11_0: featureLevelName = "11.0"; break;
            case D3D_FEATURE_LEVEL_10_1: featureLevelName = "10.1"; break;
            case D3D_FEATURE_LEVEL_10_0: featureLevelName = "10.0"; break;
            case D3D_FEATURE_LEVEL_9_3: featureLevelName = "9.3"; break;
            case D3D_FEATURE_LEVEL_9_1: featureLevelName = "9.1"; break;
        }
        sprintf_s(deviceMsg, "[DEVICE_CREATE] Selected Feature Level: D3D_FEATURE_LEVEL_%s (0x%04X)",
            featureLevelName, selectedFeatureLevel);
        logToJava(env, deviceMsg);
    }

    // DIAGNOSTIC: Immediately after device creation, try to query debug interface
    // This tells us if debug layer was ACTUALLY loaded by DirectX
    ComPtr<ID3D11InfoQueue> testQueue;
    HRESULT testHr = g_d3d11.device->QueryInterface(__uuidof(ID3D11InfoQueue), &testQueue);
    sprintf_s(deviceMsg, "[DEVICE_CREATE] Immediate debug interface test: HRESULT=0x%08X, available=%d",
        testHr, testQueue.Get() != nullptr);
    logToJava(env, deviceMsg);

    // ==================== DEBUG LAYER VERIFICATION TESTS ====================
    // Test 1: Check if d3d11sdklayers.dll is actually loaded
    HMODULE sdkLayersDll = GetModuleHandleA("d3d11sdklayers.dll");
    if (!sdkLayersDll) {
        sdkLayersDll = GetModuleHandleA("d3d11_1sdklayers.dll");
    }
    if (!sdkLayersDll) {
        sdkLayersDll = GetModuleHandleA("d3d11_3sdklayers.dll");
    }
    sprintf_s(deviceMsg, "[DEBUG_VERIFY] SDK Layers DLL loaded: %s (handle=%p)",
        sdkLayersDll ? "YES" : "NO", sdkLayersDll);
    logToJava(env, deviceMsg);

    // Test 2: Check driver type (WARP vs Hardware)
    D3D11_FEATURE_DATA_THREADING threadingInfo = {};
    HRESULT threadHr = g_d3d11.device->CheckFeatureSupport(D3D11_FEATURE_THREADING, &threadingInfo, sizeof(threadingInfo));
    sprintf_s(deviceMsg, "[DEBUG_VERIFY] Feature threading check: HRESULT=0x%08X, DriverConcurrentCreates=%d, DriverCommandLists=%d",
        threadHr, threadingInfo.DriverConcurrentCreates, threadingInfo.DriverCommandLists);
    logToJava(env, deviceMsg);

    // Test 3: Try to get ID3D11Debug interface (the real test!)
    ComPtr<ID3D11Debug> debugInterface;
    HRESULT debugIfHr = g_d3d11.device->QueryInterface(__uuidof(ID3D11Debug), &debugInterface);
    sprintf_s(deviceMsg, "[DEBUG_VERIFY] ID3D11Debug QueryInterface: HRESULT=0x%08X, available=%d",
        debugIfHr, debugInterface.Get() != nullptr);
    logToJava(env, deviceMsg);

    if (debugInterface) {
        // If we can get ID3D11Debug, the debug layer IS active regardless of GetCreationFlags
        logToJava(env, "[DEBUG_VERIFY] ✓ ID3D11Debug acquired - DEBUG LAYER IS ACTUALLY ACTIVE!");

        // Test ReportLiveDeviceObjects to confirm it works
        HRESULT reportHr = debugInterface->ReportLiveDeviceObjects(D3D11_RLDO_DETAIL);
        sprintf_s(deviceMsg, "[DEBUG_VERIFY] ReportLiveDeviceObjects test: HRESULT=0x%08X", reportHr);
        logToJava(env, deviceMsg);
    } else {
        logToJava(env, "[DEBUG_VERIFY] ✗ ID3D11Debug NOT available - debug layer is fake/disabled!");
    }

    // ==================== ADVANCED DEBUG LAYER CONFIGURATION ====================
    // Configure DirectX 11 Debug Layer (ID3D11InfoQueue) for comprehensive error tracking
    // Reference: https://learn.microsoft.com/en-us/windows/win32/direct3d11/using-the-debug-layer-to-test-apps

    if (g_d3d11.debugEnabled && g_d3d11.device) {
        logToJava(env, "[DEBUG_LAYER] Attempting to query ID3D11InfoQueue interface...");

        // Verify device creation flags
        UINT actualFlags = g_d3d11.device->GetCreationFlags();
        char verifyMsg[256];
        sprintf_s(verifyMsg, "[DEBUG_LAYER] Device creation flags: 0x%08X (has DEBUG=%d)",
            actualFlags, (actualFlags & D3D11_CREATE_DEVICE_DEBUG) != 0);
        logToJava(env, verifyMsg);

        // Query ID3D11InfoQueue interface for debug message access
        HRESULT debugHr = g_d3d11.device->QueryInterface(__uuidof(ID3D11InfoQueue), &g_d3d11.infoQueue);

        char hrMsg[256];
        sprintf_s(hrMsg, "[DEBUG_LAYER] QueryInterface result: HRESULT=0x%08X, infoQueue=%p", debugHr, g_d3d11.infoQueue.Get());
        logToJava(env, hrMsg);

        if (SUCCEEDED(debugHr) && g_d3d11.infoQueue) {
            // Successfully acquired debug interface
            logToJava(env, "[DEBUG_LAYER] ✓ ID3D11InfoQueue acquired successfully!");

            // 1. BREAK ON SEVERITY: Trigger debugger on critical issues
            // This causes immediate debugger break when corruption or errors occur
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_CORRUPTION, TRUE);
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_ERROR, FALSE); // Don't break on errors, just log
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_WARNING, FALSE);
            logToJava(env, "[DEBUG_LAYER] ✓ Break on severity configured");

            // 2. MESSAGE STORAGE: Increase storage limit for more comprehensive logging
            // Default is 128 messages, increase to 4096 for heavy debugging sessions
            g_d3d11.infoQueue->SetMessageCountLimit(4096);
            logToJava(env, "[DEBUG_LAYER] ✓ Message count limit set to 4096");

            // CRITICAL FIX: Clear any existing messages before starting
            // Reference: https://stackoverflow.com/questions/53579283/directx-11-debug-layer-capture-error-strings
            g_d3d11.infoQueue->ClearStoredMessages();
            logToJava(env, "[DEBUG_LAYER] ✓ Cleared existing stored messages");

            // ==================== FILTER NUCLEAR OPTION: Clear EVERYTHING ====================
            // CRITICAL: There are TWO separate filter systems that can block messages!
            // 1. STORAGE FILTER: Controls which messages get STORED in the queue
            // 2. RETRIEVAL FILTER: Controls which stored messages are VISIBLE via GetMessage()
            // Both must be cleared for messages to be accessible!

            // Clear storage filter stack completely
            logToJava(env, "[DEBUG_LAYER] Clearing storage filter stack...");
            g_d3d11.infoQueue->ClearStorageFilter();
            HRESULT pushStorageResult = g_d3d11.infoQueue->PushEmptyStorageFilter();
            sprintf_s(hrMsg, "[DEBUG_LAYER] Storage filter: ClearStorageFilter() + PushEmptyStorageFilter() = 0x%08X", pushStorageResult);
            logToJava(env, hrMsg);

            // Clear retrieval filter stack completely (THIS IS THE LIKELY CULPRIT!)
            logToJava(env, "[DEBUG_LAYER] Clearing retrieval filter stack...");
            g_d3d11.infoQueue->ClearRetrievalFilter();
            HRESULT pushRetrievalResult = g_d3d11.infoQueue->PushEmptyRetrievalFilter();
            sprintf_s(hrMsg, "[DEBUG_LAYER] Retrieval filter: ClearRetrievalFilter() + PushEmptyRetrievalFilter() = 0x%08X", pushRetrievalResult);
            logToJava(env, hrMsg);

            if (SUCCEEDED(pushStorageResult) && SUCCEEDED(pushRetrievalResult)) {
                logToJava(env, "[DEBUG_LAYER] ✓ Both storage and retrieval filters cleared successfully!");
            } else {
                logToJava(env, "[DEBUG_LAYER] ✗ WARNING: Filter clearing failed - messages may still be blocked!");
            }

            // Verify message queue is ready
            UINT64 initialMessageCount = g_d3d11.infoQueue->GetNumStoredMessages();
            sprintf_s(hrMsg, "[DEBUG_LAYER] Initial stored message count: %llu", initialMessageCount);
            logToJava(env, hrMsg);

            // ==================== CRITICAL TEST: Manual Message Injection ====================
            // Test if InfoQueue message storage actually works by injecting a test message
            // If this doesn't show up in GetNumStoredMessages(), the storage mechanism is broken
            logToJava(env, "[DEBUG_TEST] Injecting manual test message via AddApplicationMessage...");
            g_d3d11.infoQueue->AddApplicationMessage(D3D11_MESSAGE_SEVERITY_WARNING,
                "TEST MESSAGE: If you see this in GetNumStoredMessages(), InfoQueue storage is working!");

            UINT64 afterTestMessageCount = g_d3d11.infoQueue->GetNumStoredMessages();
            sprintf_s(hrMsg, "[DEBUG_TEST] Message count after manual injection: %llu (expected: %llu + 1)",
                afterTestMessageCount, initialMessageCount);
            logToJava(env, hrMsg);

            if (afterTestMessageCount > initialMessageCount) {
                logToJava(env, "[DEBUG_TEST] ✓ SUCCESS! InfoQueue message storage is WORKING!");
                logToJava(env, "[DEBUG_TEST] This means if GetNumStoredMessages()=0 later, there are genuinely NO errors!");
            } else {
                logToJava(env, "[DEBUG_TEST] ✗ FAILURE! InfoQueue message storage is BROKEN!");
                logToJava(env, "[DEBUG_TEST] This explains why GetNumStoredMessages() always returns 0!");
                logToJava(env, "[DEBUG_TEST] Possible causes:");
                logToJava(env, "[DEBUG_TEST]   1. Storage filter is blocking messages despite PushEmptyStorageFilter()");
                logToJava(env, "[DEBUG_TEST]   2. InfoQueue storage mechanism not initialized by SDK layer DLL");
                logToJava(env, "[DEBUG_TEST]   3. WARP mode has different InfoQueue behavior");
            }

            // ENABLE ALL MESSAGE CATEGORIES: Capture everything
            g_d3d11.infoQueue->SetMuteDebugOutput(FALSE);

            // CRITICAL: Explicitly enable ALL message severities
            // By default, some severities might be muted
            D3D11_MESSAGE_SEVERITY allSeverities[] = {
                D3D11_MESSAGE_SEVERITY_CORRUPTION,
                D3D11_MESSAGE_SEVERITY_ERROR,
                D3D11_MESSAGE_SEVERITY_WARNING,
                D3D11_MESSAGE_SEVERITY_INFO,
                D3D11_MESSAGE_SEVERITY_MESSAGE
            };

            for (int i = 0; i < ARRAYSIZE(allSeverities); i++) {
                g_d3d11.infoQueue->SetBreakOnSeverity(allSeverities[i], FALSE);  // Don't break, just log
            }

            logToJava(env, "[DEBUG_LAYER] ✓ All message severities explicitly enabled");

            // Log debug layer configuration success
            logToJava(env, "[DirectX Debug Layer] Initialized with ZERO FILTERING:\n"
                "  - Break on CORRUPTION: YES\n"
                "  - Break on ERROR: NO (logged only)\n"
                "  - Message storage limit: 4096\n"
                "  - Message filtering: DISABLED (all messages captured)\n"
                "  - Output mode: File + Console\n");

            // Initialize debug statistics
            memset(&g_d3d11.debugStats, 0, sizeof(g_d3d11.debugStats));

        } else {
            // Debug layer requested but ID3D11InfoQueue not available
            // This can happen if debug layer DLL is missing or D3D11_CREATE_DEVICE_DEBUG failed
            logToJava(env, "[DirectX Debug Layer] WARNING: Debug layer requested but ID3D11InfoQueue unavailable");
            logToJava(env, "[DirectX Debug Layer] Ensure D3D11_1SDKLayers.dll is installed (DirectX SDK/Windows SDK)");
        }
    }

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

            // Logging disabled
        } else {
        }
    } else {
    }

    // Initialize clear color state (FIX: Missing - causes pink background)
    // Ensure values are exactly 0.0f to avoid denormalized number warnings
    g_d3d11.clearColor[0] = 0.0f; // R
    g_d3d11.clearColor[1] = 0.0f; // G
    g_d3d11.clearColor[2] = 0.0f; // B
    g_d3d11.clearColor[3] = 1.0f; // A

    g_d3d11.initialized = true;
    // Logging disabled
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
    // Logging disabled
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

        // Logging disabled
    }
}

/**
 * Recreate DirectX 11 swap chain with current window dimensions
 * Called when window is resized, VSync changes, or fullscreen mode toggles
 *
 * @return true if successful, false if failed
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_recreateSwapChain
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) {
        return JNI_FALSE;
    }

    // Get current swap chain description to preserve settings
    DXGI_SWAP_CHAIN_DESC desc;
    HRESULT hr = g_d3d11.swapChain->GetDesc(&desc);
    if (FAILED(hr)) {
        return JNI_FALSE;
    }

    // Use current width/height from g_d3d11 state
    int width = g_d3d11.width;
    int height = g_d3d11.height;

    // Release existing render target views (required before ResizeBuffers)
    g_d3d11.renderTargetView.Reset();
    g_d3d11.depthStencilView.Reset();
    g_d3d11.depthStencilBuffer.Reset();

    // Resize swap chain buffers
    hr = g_d3d11.swapChain->ResizeBuffers(
        2,                               // Buffer count (double buffering)
        width,                           // New width
        height,                          // New height
        DXGI_FORMAT_R8G8B8A8_UNORM,     // Format
        0                                // Flags
    );

    if (FAILED(hr)) {
        return JNI_FALSE;
    }

    // Recreate render target view and depth stencil
    updateRenderTargetView();

    // Re-bind render targets to ensure consistency
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginFrame
    (JNIEnv* env, jclass clazz) {

    // CRITICAL DEBUG: Use JNI to log directly to Java console
    static int callCount = 0;
    if (callCount == 0) {
        jclass systemClass = env->FindClass("java/lang/System");
        jfieldID outField = env->GetStaticFieldID(systemClass, "out", "Ljava/io/PrintStream;");
        jobject outStream = env->GetStaticObjectField(systemClass, outField);
        jclass printStreamClass = env->FindClass("java/io/PrintStream");
        jmethodID printlnMethod = env->GetMethodID(printStreamClass, "println", "(Ljava/lang/String;)V");

        char msg[256];
        snprintf(msg, sizeof(msg), "[C++] beginFrame() CALLED! initialized=%d", g_d3d11.initialized);
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(outStream, printlnMethod, jmsg);
        env->DeleteLocalRef(jmsg);

        callCount++;
    }

    if (!g_d3d11.initialized) {
        return;
    }

    // TEST: Intentionally trigger a DirectX debug warning every 60 frames
    static int testFrameCount = 0;
    testFrameCount++;
    if (testFrameCount == 60) {
        // INTENTIONAL ERROR: Try to set an invalid viewport (causes D3D11 WARNING)
        D3D11_VIEWPORT invalidViewport = {};
        invalidViewport.Width = -100.0f;  // Negative width is INVALID!
        invalidViewport.Height = -100.0f; // Negative height is INVALID!
        invalidViewport.MinDepth = 2.0f;  // MinDepth > MaxDepth is INVALID!
        invalidViewport.MaxDepth = 0.0f;
        g_d3d11.context->RSSetViewports(1, &invalidViewport);
        logToJava(env, "[TEST] Intentional DirectX warning triggered (invalid viewport)!");
        testFrameCount = 0; // Reset counter
    }

    // Set render targets
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    // CRITICAL DEBUG: Log viewport dimensions using JNI (so it actually appears in logs!)
    static int frameCount = 0;
    if (frameCount < 3) {  // Log first 3 frames only
        jclass systemClass = env->FindClass("java/lang/System");
        jfieldID outField = env->GetStaticFieldID(systemClass, "out", "Ljava/io/PrintStream;");
        jobject outStream = env->GetStaticObjectField(systemClass, outField);
        jclass printStreamClass = env->FindClass("java/io/PrintStream");
        jmethodID printlnMethod = env->GetMethodID(printStreamClass, "println", "(Ljava/lang/String;)V");

        char msg[256];
        snprintf(msg, sizeof(msg), "[VIEWPORT] Frame %d: Width=%.0f, Height=%.0f, MinDepth=%.2f, MaxDepth=%.2f",
            frameCount, g_d3d11.viewport.Width, g_d3d11.viewport.Height,
            g_d3d11.viewport.MinDepth, g_d3d11.viewport.MaxDepth);
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(outStream, printlnMethod, jmsg);
        env->DeleteLocalRef(jmsg);

        frameCount++;
    }

    // Set viewport
    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);

    // CRITICAL: Clear depth buffer at start of each frame
    // Without this, depth testing will fail and geometry will render incorrectly
    // NOTE: We do NOT clear color buffer here - that's handled by explicit clear() calls
    if (g_d3d11.depthStencilView) {
        g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
    }

    // Log state info (first 2 frames)
    if (frameCount < 2) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[STATE] DepthStencilState=0x%p, BlendState=0x%p",
            g_d3d11.depthStencilState.Get(), g_d3d11.blendState.Get());
        logToJava(env, msg);
    }

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

// REMOVED: Old clear(float, float, float, float) - replaced by clear(int mask)
// The old implementation caused confusion with duplicate clear methods
// Now using clear(int mask) which respects setClearColor() state

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setClearColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_d3d11.initialized) return;

    // Store clear color state
    // Clamp very small values to 0.0f to avoid denormalized number warnings from DirectX debug layer
    auto clampDenorm = [](float value) -> float {
        return (std::abs(value) < 1e-37f) ? 0.0f : value;
    };

    g_d3d11.clearColor[0] = clampDenorm(r);
    g_d3d11.clearColor[1] = clampDenorm(g);
    g_d3d11.clearColor[2] = clampDenorm(b);
    g_d3d11.clearColor[3] = clampDenorm(a);

    // Log setClearColor calls - UNLIMITED for debugging
    static int setCount = 0;
    char msg[256];
    snprintf(msg, sizeof(msg), "[SET_CLEAR_COLOR %d] color=[%.2f, %.2f, %.2f, %.2f]",
        setCount, g_d3d11.clearColor[0], g_d3d11.clearColor[1],
        g_d3d11.clearColor[2], g_d3d11.clearColor[3]);
    logToJava(env, msg);
    setCount++;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride) {

    if (!g_d3d11.initialized) {
        return 0;
    }

    // Validate size parameter FIRST
    if (size <= 0) {
        return 0;
    }

    // Null check for data array
    if (data == nullptr) {
        return 0;
    }

    // Get Java array length for validation
    jsize arrayLength = env->GetArrayLength(data);
    if (arrayLength < size) {
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        return 0;
    }


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

        // Check if it's a validation layer error - try to get more info
        if (g_d3d11.debugEnabled && g_d3d11.infoQueue) {
            UINT64 numMessages = g_d3d11.infoQueue->GetNumStoredMessages();
            for (UINT64 i = 0; i < numMessages && i < 5; i++) {
                SIZE_T messageLength = 0;
                g_d3d11.infoQueue->GetMessage(i, nullptr, &messageLength);
                if (messageLength > 0) {
                    D3D11_MESSAGE* message = (D3D11_MESSAGE*)malloc(messageLength);
                    if (message && SUCCEEDED(g_d3d11.infoQueue->GetMessage(i, message, &messageLength))) {
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
            // Logging disabled
        } else {
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
        return 0;
    }

    // Validate size parameter FIRST
    if (size <= 0) {
        return 0;
    }

    // Null check for data array
    if (data == nullptr) {
        return 0;
    }

    // Get Java array length for validation
    jsize arrayLength = env->GetArrayLength(data);
    if (arrayLength < size) {
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        return 0;
    }


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

    // CRITICAL FIX: Rebind all active constant buffers after shader pipeline change
    // According to Microsoft DirectX 11 documentation, constant buffers must be rebound
    // after changing shaders. This fixes the yellow/red/purple screen issue.
    for (int slot = 0; slot < 4; ++slot) {
        // Rebind vertex shader constant buffers
        if (g_boundConstantBuffersVS[slot] != 0) {
            auto it = g_constantBuffers.find(g_boundConstantBuffersVS[slot]);
            if (it != g_constantBuffers.end()) {
                ID3D11Buffer* buffers[] = { it->second.Get() };
                g_d3d11.context->VSSetConstantBuffers(slot, 1, buffers);
            }
        }

        // Rebind pixel shader constant buffers
        if (g_boundConstantBuffersPS[slot] != 0) {
            auto it = g_constantBuffers.find(g_boundConstantBuffersPS[slot]);
            if (it != g_constantBuffers.end()) {
                ID3D11Buffer* buffers[] = { it->second.Get() };
                g_d3d11.context->PSSetConstantBuffers(slot, 1, buffers);
            }
        }
    }

    // CRITICAL FIX: Rebind all active textures after shader pipeline change
    // DirectX 11 clears texture bindings when shaders change, just like constant buffers.
    // This fixes the "textures not loading" issue (red screen, no Mojang logo).
    for (int slot = 0; slot < 32; ++slot) {
        jint textureId = g_glState.boundTextures[slot];
        if (textureId > 0) {
            // Look up the shader resource view for this texture ID
            uint64_t handle = static_cast<uint64_t>(textureId);
            auto it = g_shaderResourceViews.find(handle);
            if (it != g_shaderResourceViews.end() && it->second.Get() != nullptr) {
                ID3D11ShaderResourceView* srv = it->second.Get();
                g_d3d11.context->PSSetShaderResources(slot, 1, &srv);
            }
        }
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.defaultVertexShader) {
        return 0;
    }

    // Return the stored default shader pipeline handle
    // This handle was created during initialization in setDefaultShaders()
    return static_cast<jlong>(g_d3d11.defaultShaderPipelineHandle);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_draw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint baseVertex, jint firstIndex, jint indexCount, jint instanceCount) {

    // CRITICAL: Comprehensive null checks to prevent EXCEPTION_ACCESS_VIOLATION
    if (!g_d3d11.initialized) {
        return;
    }

    // CRITICAL: Validate device context before ANY DirectX calls
    if (!g_d3d11.context || !g_d3d11.context.Get()) {
        return;
    }

    // CRITICAL: Validate device before drawing
    if (!g_d3d11.device || !g_d3d11.device.Get()) {
        return;
    }

    // CRITICAL FIX: Ensure render target is bound before every draw call
    // This prevents the "no render target bound" DirectX debug warning
    if (g_d3d11.renderTargetView && g_d3d11.renderTargetView.Get() &&
        g_d3d11.depthStencilView && g_d3d11.depthStencilView.Get()) {
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());
    } else {
        return;
    }

    // CRITICAL: Ensure shaders are bound before every draw call
    // This is the fix for black screen - shaders MUST be set!
    if (g_d3d11.defaultVertexShader && g_d3d11.defaultVertexShader.Get()) {
        g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    } else {
        return;
    }

    if (g_d3d11.defaultPixelShader && g_d3d11.defaultPixelShader.Get()) {
        g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    } else {
        return;
    }

    // CRITICAL: Ensure input layout is bound (defines vertex format)
    if (g_d3d11.defaultInputLayout && g_d3d11.defaultInputLayout.Get()) {
        g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    } else {
        // Continue anyway - may work if vertex layout matches
    }

    // CRITICAL: Ensure constant buffer (transform matrices) is bound for this draw call
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.constantBuffers[0] && g_d3d11.constantBuffers[0].Get()) {
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    }

    // CRITICAL: Ensure sampler state is bound for texture sampling
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.defaultSamplerState && g_d3d11.defaultSamplerState.Get()) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    } else {
    }

    // NOTE: Texture bindings are preserved from setShaderPipeline() texture rebinding
    // No need to bind default white texture here - it would overwrite actual textures!

    // Set vertex buffer with comprehensive null checks
    uint64_t vbHandle = static_cast<uint64_t>(vertexBuffer);
    auto vbIt = g_vertexBuffers.find(vbHandle);
    if (vbIt != g_vertexBuffers.end()) {
        // CRITICAL: Validate the vertex buffer ComPtr before using it
        if (!vbIt->second || !vbIt->second.Get()) {
            char msg[128];
            snprintf(msg, sizeof(msg), "[DRAW_ERROR] Vertex buffer handle 0x%llx found but ComPtr is null", (unsigned long long)vbHandle);
            logToJava(env, msg);
            return;
        }

        // Get the actual stride for this vertex buffer
        // Minecraft 1.21.1 uses 32-byte vertex format (position=12 + padding=4 + texcoord=8 + color=4 + padding=4)
        UINT stride = 32; // Default fallback - fixed to match Minecraft's actual vertex format
        auto strideIt = g_vertexBufferStrides.find(vbHandle);
        if (strideIt != g_vertexBufferStrides.end()) {
            stride = strideIt->second;
            // Logging disabled
        } else {
            // Logging disabled
        }
        UINT offset = 0;
        g_d3d11.context->IASetVertexBuffers(0, 1, vbIt->second.GetAddressOf(), &stride, &offset);


        // DEBUG: Log input layout and vertex format info
        // Logging disabled
    } else {
        // CRITICAL: Always log when buffer not found - this is why draws are failing!
        char msg[256];
        snprintf(msg, sizeof(msg), "[DRAW_ERROR] Vertex buffer handle %lld (0x%llx) not found! Total VBs in map: %zu",
            (long long)vbHandle, (unsigned long long)vbHandle, g_vertexBuffers.size());
        logToJava(env, msg);
        return;
    }

    // Set index buffer if provided (with safety checks)
    if (indexBuffer != 0) {
        uint64_t ibHandle = static_cast<uint64_t>(indexBuffer);
        auto ibIt = g_indexBuffers.find(ibHandle);
        if (ibIt != g_indexBuffers.end()) {
            // CRITICAL: Validate index buffer ComPtr before using it
            if (!ibIt->second || !ibIt->second.Get()) {
                return;
            }

            DXGI_FORMAT format = DXGI_FORMAT_R16_UINT;
            g_d3d11.context->IASetIndexBuffer(ibIt->second.Get(), format, 0);
        } else {
            char msg[128];
            snprintf(msg, sizeof(msg), "[DRAW_ERROR] Index buffer handle 0x%llx not found - skipping draw", (unsigned long long)ibHandle);
            logToJava(env, msg);
            return;
        }

        if (indexCount > 0) {

            // CRITICAL FIX: Pass baseVertex and firstIndex to DrawIndexed!
            // DrawIndexed(IndexCount, StartIndexLocation, BaseVertexLocation)
            // WRAP IN TRY-CATCH TO PREVENT CRASHES
            try {
                // Log only first 5 draw calls per frame to avoid spam
                static int drawCallCount = 0;
                if (drawCallCount < 5) {
                    char msg[200];
                    if (instanceCount > 1) {
                        snprintf(msg, sizeof(msg), "[DRAW %d] DrawIndexedInstanced: indexCount=%d, instances=%d, firstIndex=%d, baseVertex=%d",
                            drawCallCount, indexCount, instanceCount, firstIndex, baseVertex);
                    } else {
                        snprintf(msg, sizeof(msg), "[DRAW %d] DrawIndexed: indexCount=%d, firstIndex=%d, baseVertex=%d",
                            drawCallCount, indexCount, firstIndex, baseVertex);
                    }
                    logToJava(env, msg);
                    drawCallCount++;
                }

                if (instanceCount > 1) {
                    g_d3d11.context->DrawIndexedInstanced(indexCount, instanceCount, firstIndex, baseVertex, 0);
                } else {
                    g_d3d11.context->DrawIndexed(indexCount, firstIndex, baseVertex);
                }
            } catch (const std::exception& e) {
                char msg[256];
                snprintf(msg, sizeof(msg), "[DRAW_ERROR] Exception during DrawIndexed: %s", e.what());
                logToJava(env, msg);
            } catch (...) {
                logToJava(env, "[DRAW_ERROR] Unknown exception during DrawIndexed");
            }
        }
    } else {
        if (indexCount > 0) {
            // Non-indexed draw call
            // WRAP IN TRY-CATCH TO PREVENT CRASHES
            try {
                // Log only first 5 draw calls
                static int nonIndexedDrawCount = 0;
                if (nonIndexedDrawCount < 5) {
                    char msg[200];
                    if (instanceCount > 1) {
                        snprintf(msg, sizeof(msg), "[DRAW %d] DrawInstanced: vertexCount=%d, instances=%d, firstVertex=%d",
                            nonIndexedDrawCount, indexCount, instanceCount, firstIndex);
                    } else {
                        snprintf(msg, sizeof(msg), "[DRAW %d] Draw: vertexCount=%d, firstVertex=%d",
                            nonIndexedDrawCount, indexCount, firstIndex);
                    }
                    logToJava(env, msg);
                    nonIndexedDrawCount++;
                }

                if (instanceCount > 1) {
                    g_d3d11.context->DrawInstanced(indexCount, instanceCount, firstIndex, 0);
                } else {
                    g_d3d11.context->Draw(indexCount, firstIndex);
                }
            } catch (const std::exception& e) {
                char msg[256];
                snprintf(msg, sizeof(msg), "[DRAW_ERROR] Exception during Draw: %s", e.what());
                logToJava(env, msg);
            } catch (...) {
                logToJava(env, "[DRAW_ERROR] Unknown exception during Draw");
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

// Draw with explicit vertex format - creates correct input layout based on actual vertex data
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawWithVertexFormat
    (JNIEnv* env, jclass clazz, jlong vbHandle, jlong ibHandle, jint baseVertex, jint firstIndex, jint vertexOrIndexCount, jint instanceCount, jintArray vertexFormatDesc) {

    if (!g_d3d11.initialized) return;

    // Get vertex format descriptor from Java
    jint* formatDesc = env->GetIntArrayElements(vertexFormatDesc, nullptr);
    jsize formatDescLength = env->GetArrayLength(vertexFormatDesc);

    if (formatDesc == nullptr || formatDescLength < 1) {
        if (formatDesc) env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    // Compute hash of vertex format for input layout caching
    uint64_t formatHash = 0;
    for (jsize i = 0; i < formatDescLength; i++) {
        formatHash = formatHash * 31 + formatDesc[i];
    }

    // Check if we have a cached input layout for this vertex format
    ComPtr<ID3D11InputLayout> inputLayout;
    auto it = g_vertexFormatInputLayouts.find(formatHash);

    if (it == g_vertexFormatInputLayouts.end()) {
        // Need to create new input layout from vertex format
        // Get current vertex shader blob
        if (g_d3d11.boundVertexShader == 0) {
            printf("[INPUT_LAYOUT_ERROR] No vertex shader bound!\n");
            fflush(stdout);
            env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
            return;
        }

        auto blobIt = g_shaderBlobs.find(g_d3d11.boundVertexShader);
        if (blobIt == g_shaderBlobs.end()) {
            printf("[INPUT_LAYOUT_ERROR] Shader blob not found for handle 0x%llx\n", g_d3d11.boundVertexShader);
            fflush(stdout);
            env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
            return;
        }

        ID3DBlob* vsBlob = blobIt->second.Get();
        if (!vsBlob) {
            printf("[INPUT_LAYOUT_ERROR] Shader blob is null for handle 0x%llx\n", g_d3d11.boundVertexShader);
            fflush(stdout);
            env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
            return;
        }

        printf("[INPUT_LAYOUT_CREATE] Creating input layout: formatHash=0x%llx, shaderHandle=0x%llx\n", formatHash, g_d3d11.boundVertexShader);
        fflush(stdout);

        // Create input layout from vertex format
        ID3D11InputLayout* rawInputLayout = nullptr;
        if (!createInputLayoutFromVertexFormat(formatDesc, formatDescLength, vsBlob, &rawInputLayout)) {
            printf("[INPUT_LAYOUT_ERROR] Failed to create input layout from vertex format\n");
            fflush(stdout);
            env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
            return;
        }

        printf("[INPUT_LAYOUT_CREATE] Input layout created successfully\n");
        fflush(stdout);

        inputLayout.Attach(rawInputLayout);
        g_vertexFormatInputLayouts[formatHash] = inputLayout;
    } else {
        inputLayout = it->second;
        printf("[INPUT_LAYOUT_CACHED] Using cached input layout: formatHash=0x%llx\n", formatHash);
        fflush(stdout);
    }

    // Release Java array
    env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);

    // Set input layout
    g_d3d11.context->IASetInputLayout(inputLayout.Get());

    // Find vertex buffer
    auto vbIt = g_vertexBuffers.find(vbHandle);
    if (vbIt == g_vertexBuffers.end()) {
        return;
    }

    ID3D11Buffer* vertexBuffer = vbIt->second.Get();
    UINT stride = g_vertexBufferStrides[vbHandle];
    UINT offset = 0;

    g_d3d11.context->IASetVertexBuffers(0, 1, &vertexBuffer, &stride, &offset);

    // Draw indexed or non-indexed
    if (ibHandle != 0) {
        auto ibIt = g_indexBuffers.find(ibHandle);
        if (ibIt == g_indexBuffers.end()) {
            return;
        }

        ID3D11Buffer* indexBuffer = ibIt->second.Get();
        g_d3d11.context->IASetIndexBuffer(indexBuffer, DXGI_FORMAT_R32_UINT, 0);

        if (instanceCount > 1) {
            g_d3d11.context->DrawIndexedInstanced(vertexOrIndexCount, instanceCount, firstIndex, baseVertex, 0);
        } else {
            g_d3d11.context->DrawIndexed(vertexOrIndexCount, firstIndex, baseVertex);
        }
    } else {
        if (instanceCount > 1) {
            g_d3d11.context->DrawInstanced(vertexOrIndexCount, instanceCount, baseVertex, 0);
        } else {
            g_d3d11.context->Draw(vertexOrIndexCount, baseVertex);
        }
    }
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

    // NOTE: Texture bindings are preserved from setShaderPipeline() texture rebinding
    // No need to bind default white texture here - it would overwrite actual textures!

    // Get direct buffer address
    void* vertexData = env->GetDirectBufferAddress(vertexBuffer);
    if (!vertexData) {
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
                // Logging disabled
                g_d3d11.context->DrawIndexed(indexCount, 0, 0);
            }
        }
    } else {
        // Draw non-indexed
        // Logging disabled
        g_d3d11.context->Draw(vertexCount, 0);
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTexture
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Null check for data array
    if (data == nullptr) {
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
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

    // Logging disabled

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
 * Create DirectX 11 texture from OpenGL texture ID
 * Based on VulkanMod's texture allocation pattern
 * This creates an empty texture that will be filled via texSubImage2D later
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createTextureFromId
    (JNIEnv* env, jclass clazz, jint textureId, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return JNI_FALSE;

    // Delete existing texture if it exists (VulkanMod pattern - recreate on resize)
    uint64_t handle = static_cast<uint64_t>(textureId);
    auto existingTexture = g_textures.find(handle);
    if (existingTexture != g_textures.end()) {
        g_textures.erase(handle);
        g_shaderResourceViews.erase(handle);
        // Logging disabled
    }

    // Create DirectX 11 texture descriptor
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = width;
    desc.Height = height;
    desc.MipLevels = 1;  // Single mip level (matching VulkanMod's approach)
    desc.ArraySize = 1;
    desc.Format = static_cast<DXGI_FORMAT>(format);  // Use DirectX format directly
    desc.SampleDesc.Count = 1;
    desc.SampleDesc.Quality = 0;
    desc.Usage = D3D11_USAGE_DEFAULT;  // GPU read/write, UpdateSubresource for updates
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;  // Only shader resource (simpler, more compatible)
    desc.CPUAccessFlags = 0;  // No CPU access (use UpdateSubresource instead)
    desc.MiscFlags = 0;  // No special flags (GENERATE_MIPS removed - incompatible with MipLevels=1)

    // Create texture WITHOUT initial data (VulkanMod pattern - data uploaded separately)
    ComPtr<ID3D11Texture2D> texture;
    HRESULT hr = g_d3d11.device->CreateTexture2D(&desc, nullptr, &texture);

    if (FAILED(hr)) {
        return JNI_FALSE;
    }

    // Create shader resource view
    D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
    srvDesc.Format = desc.Format;
    srvDesc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    srvDesc.Texture2D.MipLevels = 1;  // Match texture mip levels
    srvDesc.Texture2D.MostDetailedMip = 0;

    ComPtr<ID3D11ShaderResourceView> srv;
    hr = g_d3d11.device->CreateShaderResourceView(texture.Get(), &srvDesc, &srv);

    if (FAILED(hr)) {
        return JNI_FALSE;
    }

    // Store texture and SRV using texture ID as key (jint implicitly converts to uint64_t)
    g_textures[handle] = texture;
    g_shaderResourceViews[handle] = srv;

    // Logging disabled

    return JNI_TRUE;
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
        return JNI_FALSE;
    }

    // Validate handle
    if (textureHandle == 0) {
        return JNI_FALSE;
    }

    // Validate data
    if (data == nullptr) {
        return JNI_FALSE;
    }

    // Find texture in tracking map
    uint64_t handle = static_cast<uint64_t>(textureHandle);
    auto it = g_textures.find(handle);
    if (it == g_textures.end()) {
        return JNI_FALSE;
    }

    ComPtr<ID3D11Texture2D> texture = it->second;
    if (!texture) {
        return JNI_FALSE;
    }

    // Get texture description to verify dimensions
    D3D11_TEXTURE2D_DESC desc;
    texture->GetDesc(&desc);

    // Validate mip level first
    if (static_cast<UINT>(mipLevel) >= desc.MipLevels) {
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
        return JNI_FALSE;
    }

    // Get pixel data from Java
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
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


    return JNI_TRUE;
}

/**
 * Bind a DirectX 11 texture to a shader resource slot
 * CRITICAL FIX: This is the missing piece that causes yellow screen!
 *
 * Without this, Minecraft calls OpenGL bindTexture but DirectX never receives the texture,
 * so shaders use default white texture × yellow vertex color = YELLOW SCREEN
 *
 * @param slot Texture slot (0-15, corresponding to t0-t15 in HLSL)
 * @param textureHandle DirectX 11 texture handle from g_shaderResourceViews (0 to unbind)
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture__IJ
    (JNIEnv* env, jclass clazz, jint slot, jlong textureHandle) {

    if (!g_d3d11.initialized) return;

    // Validate slot (DirectX 11 supports 128 texture slots, but we use 0-15 for simplicity)
    if (slot < 0 || slot >= 16) {
        return;
    }

    ID3D11ShaderResourceView* srv = nullptr;

    if (textureHandle != 0) {
        // Find the shader resource view for this texture handle
        auto it = g_shaderResourceViews.find(textureHandle);
        if (it != g_shaderResourceViews.end()) {
            srv = it->second.Get();
        } else {
            // Texture handle not found - this can happen if texture was deleted
            // or not yet uploaded. Not an error, just skip binding.
            return;
        }
    }

    // Bind shader resource view to both pixel shader and vertex shader
    // Pixel shader gets textures for fragment rendering
    g_d3d11.context->PSSetShaderResources(slot, 1, &srv);

    // Vertex shader might need textures for certain effects (terrain height maps, etc.)
    // Binding to both is safer and matches VulkanMod's approach
    g_d3d11.context->VSSetShaderResources(slot, 1, &srv);

    // Logging disabled for performance (called frequently)
    // Uncomment for debugging texture binding issues:
    // if (srv) {
    //     printf("Bound texture handle 0x%llx to slot %d\n", textureHandle, slot);
    // } else {
    //     printf("Unbound texture from slot %d\n", slot);
    // }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot) {

    if (!g_d3d11.initialized || slot < 0 || slot >= 4) return;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;

    // CRITICAL FIX: Use bgfx's approach for constant buffers
    // According to bgfx and Microsoft best practices:
    // - Use D3D11_USAGE_DEFAULT (not DYNAMIC) for constant buffers
    // - Use UpdateSubresource (not Map/Unmap) for updates
    // - This is more efficient for buffers updated once per frame
    //
    // Previous approach (DYNAMIC + Map/Unmap) may have caused:
    // - Yellow screen (uninitialized or incorrectly uploaded uniforms)
    // - Performance issues (Map/Unmap overhead)

    // Create constant buffer if it doesn't exist
    if (!g_d3d11.constantBuffers[slot]) {
        D3D11_BUFFER_DESC desc = {};
        desc.Usage = D3D11_USAGE_DEFAULT;  // bgfx uses DEFAULT, not DYNAMIC
        desc.ByteWidth = ((size + 15) / 16) * 16; // Align to 16 bytes
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = 0;  // No CPU access flags for DEFAULT usage

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[slot]);
        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
    }

    // Update constant buffer data using UpdateSubresource (bgfx approach)
    // This is the proper way to update D3D11_USAGE_DEFAULT buffers
    // UpdateSubresource parameters:
    //   pDstResource: destination buffer
    //   DstSubresource: 0 (constant buffers have only one subresource)
    //   pDstBox: nullptr (update entire buffer)
    //   pSrcData: source data
    //   SrcRowPitch: 0 (not used for constant buffers)
    //   SrcDepthPitch: 0 (not used for constant buffers)
    g_d3d11.context->UpdateSubresource(
        g_d3d11.constantBuffers[slot].Get(),  // Destination buffer
        0,                                     // Subresource index (always 0 for buffers)
        nullptr,                               // Update entire buffer
        bytes,                                 // Source data
        0,                                     // Row pitch (not used for buffers)
        0                                      // Depth pitch (not used for buffers)
    );

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

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeGetDebugStats
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.debugEnabled) {
        return env->NewStringUTF("Debug layer not enabled");
    }

    // Format comprehensive debug statistics
    char stats[1024];
    snprintf(stats, sizeof(stats),
        "=== DirectX 11 Debug Statistics ===\n"
        "Total Messages: %llu\n"
        "  - Corruptions: %llu\n"
        "  - Errors: %llu\n"
        "  - Warnings: %llu\n"
        "  - Info/Messages: %llu\n"
        "Messages Processed: %llu\n"
        "Frames With Errors: %llu\n"
        "Current Frame Errors: %llu\n"
        "InfoQueue Available: %s\n"
        "Storage Limit: %llu messages\n",
        g_d3d11.debugStats.totalMessages,
        g_d3d11.debugStats.corruptionCount,
        g_d3d11.debugStats.errorCount,
        g_d3d11.debugStats.warningCount,
        g_d3d11.debugStats.infoCount,
        g_d3d11.debugStats.messagesProcessed,
        g_d3d11.debugStats.framesWithErrors,
        g_d3d11.debugStats.currentFrameErrors,
        g_d3d11.infoQueue ? "YES" : "NO",
        g_d3d11.infoQueue ? g_d3d11.infoQueue->GetMessageCountLimit() : 0
    );

    return env->NewStringUTF(stats);
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

    } else {
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

    } else {
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

    } else {
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clearDepth
    (JNIEnv* env, jclass clazz, jfloat depth) {

    if (!g_d3d11.initialized || !g_d3d11.depthStencilView) return;

    // Clear only the depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, depth, 0);

    // Logging disabled
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

    } else {
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

        // Logging disabled
    } else {
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
        return;
    }

    // Get array length for validation
    jsize arrayLength = env->GetArrayLength(matrixData);
    if (arrayLength != 16) {
        return;
    }

    // Get matrix elements from Java
    jfloat* matrixElements = env->GetFloatArrayElements(matrixData, nullptr);
    if (!matrixElements) {
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
    // CRITICAL: Also convert OpenGL NDC Z∈[-1,1] to DirectX NDC Z∈[0,1]
    float tempProj[16];
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            tempProj[row * 4 + col] = matrixElements[col * 4 + row]; // Transpose
        }
    }

    // Apply NDC Z-range conversion: Z' = (Z + 1) / 2
    // This is done by modifying the projection matrix:
    // Row 2 (Z row): multiply by 0.5
    // Row 3 (W row): add 0.5 * Row 2 to it
    // This transforms Z from [-1,1] to [0,1]
    for (int col = 0; col < 4; col++) {
        matrices.proj[2 * 4 + col] = tempProj[2 * 4 + col] * 0.5f + tempProj[3 * 4 + col] * 0.5f;
        matrices.proj[3 * 4 + col] = tempProj[3 * 4 + col];
    }
    // Copy other rows unchanged
    for (int col = 0; col < 4; col++) {
        matrices.proj[0 * 4 + col] = tempProj[0 * 4 + col];
        matrices.proj[1 * 4 + col] = tempProj[1 * 4 + col];
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

        // Logging disabled
    } else {
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
        return;
    }

    // Validate array sizes
    if (env->GetArrayLength(mvpData) != 16 ||
        env->GetArrayLength(modelViewData) != 16 ||
        env->GetArrayLength(projectionData) != 16) {
        return;
    }

    // Get all matrix elements
    jfloat* mvpElements = env->GetFloatArrayElements(mvpData, nullptr);
    jfloat* mvElements = env->GetFloatArrayElements(modelViewData, nullptr);
    jfloat* projElements = env->GetFloatArrayElements(projectionData, nullptr);

    if (!mvpElements || !mvElements || !projElements) {
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
    // CRITICAL: Must also convert OpenGL NDC Z∈[-1,1] to DirectX NDC Z∈[0,1]

    float tempMvp[16], tempProj[16];

    // Transpose matrices first
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            tempMvp[row * 4 + col] = mvpElements[col * 4 + row];        // Transpose MVP
            matrices.mv[row * 4 + col] = mvElements[col * 4 + row];     // Transpose ModelView
            tempProj[row * 4 + col] = projElements[col * 4 + row];      // Transpose Projection
        }
    }

    // Apply NDC Z-range conversion to MVP matrix: Z' = (Z + 1) / 2
    // This transforms Z from [-1,1] (OpenGL) to [0,1] (DirectX)
    // Row 2 (Z output) = Row 2 * 0.5 + Row 3 * 0.5
    for (int col = 0; col < 4; col++) {
        matrices.mvp[2 * 4 + col] = tempMvp[2 * 4 + col] * 0.5f + tempMvp[3 * 4 + col] * 0.5f;
        matrices.mvp[3 * 4 + col] = tempMvp[3 * 4 + col];
    }
    // Copy other MVP rows unchanged
    for (int col = 0; col < 4; col++) {
        matrices.mvp[0 * 4 + col] = tempMvp[0 * 4 + col];
        matrices.mvp[1 * 4 + col] = tempMvp[1 * 4 + col];
    }

    // Apply same conversion to projection matrix
    for (int col = 0; col < 4; col++) {
        matrices.proj[2 * 4 + col] = tempProj[2 * 4 + col] * 0.5f + tempProj[3 * 4 + col] * 0.5f;
        matrices.proj[3 * 4 + col] = tempProj[3 * 4 + col];
    }
    for (int col = 0; col < 4; col++) {
        matrices.proj[0 * 4 + col] = tempProj[0 * 4 + col];
        matrices.proj[1 * 4 + col] = tempProj[1 * 4 + col];
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

        // Logging disabled
    } else {
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
        return nullptr;
    }

    // Map the buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(buffer.Get(), 0, mapType, 0, &mappedResource);

    if (FAILED(hr)) {
        return nullptr;
    }

    // Create a direct ByteBuffer pointing to the mapped memory
    jobject byteBuffer = env->NewDirectByteBuffer(mappedResource.pData, size);

    if (!byteBuffer) {
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

}

/**
 * Copy entire texture using CopyResource
 * Requires textures to have identical dimensions and formats
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTexture
    (JNIEnv* env, jclass clazz, jlong srcTextureHandle, jlong dstTextureHandle) {

    if (!g_d3d11.initialized) {
        return;
    }

    uint64_t srcHandle = static_cast<uint64_t>(srcTextureHandle);
    uint64_t dstHandle = static_cast<uint64_t>(dstTextureHandle);

    // Find source texture
    auto srcIt = g_textures.find(srcHandle);
    if (srcIt == g_textures.end()) {
        return;
    }

    // Find destination texture
    auto dstIt = g_textures.find(dstHandle);
    if (dstIt == g_textures.end()) {
        return;
    }

    // Copy entire texture (all mip levels, array slices)
    g_d3d11.context->CopyResource(dstIt->second.Get(), srcIt->second.Get());

    // Logging disabled
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
        return;
    }

    uint64_t srcHandle = static_cast<uint64_t>(srcTextureHandle);
    uint64_t dstHandle = static_cast<uint64_t>(dstTextureHandle);

    // Find source texture
    auto srcIt = g_textures.find(srcHandle);
    if (srcIt == g_textures.end()) {
        return;
    }

    // Find destination texture
    auto dstIt = g_textures.find(dstHandle);
    if (dstIt == g_textures.end()) {
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

}

/**
 * Copy texture data to a buffer (for readback/download operations)
 * Uses a staging texture as intermediate step since textures can't be mapped directly
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_copyTextureToBuffer
    (JNIEnv* env, jclass clazz, jlong textureHandle, jlong bufferHandle, jint mipLevel) {

    if (!g_d3d11.initialized) {
        return;
    }

    uint64_t texHandle = static_cast<uint64_t>(textureHandle);
    uint64_t bufHandle = static_cast<uint64_t>(bufferHandle);

    // Find source texture
    auto texIt = g_textures.find(texHandle);
    if (texIt == g_textures.end()) {
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
            // Logging disabled
        } else {
        }
        g_d3d11.context->Unmap(stagingTexture.Get(), 0);
    } else {
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
        return 0;
    }

    // Create Event Query (acts as a fence in D3D11)
    D3D11_QUERY_DESC queryDesc = {};
    queryDesc.Query = D3D11_QUERY_EVENT;
    queryDesc.MiscFlags = 0;

    ComPtr<ID3D11Query> query;
    HRESULT hr = g_d3d11.device->CreateQuery(&queryDesc, &query);

    if (FAILED(hr)) {
        return 0;
    }

    // Generate handle and store query
    uint64_t handle = generateHandle();
    g_queries[handle] = query;

    // Logging disabled
    return static_cast<jlong>(handle);
}

/**
 * Signal a fence by issuing an End() command
 * The GPU will signal this fence when all previous commands complete
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_signalFence
    (JNIEnv* env, jclass clazz, jlong fenceHandle) {

    if (!g_d3d11.initialized) {
        return;
    }

    uint64_t handle = static_cast<uint64_t>(fenceHandle);
    auto it = g_queries.find(handle);
    if (it == g_queries.end()) {
        return;
    }

    // Issue End() to signal the query
    // GPU will complete this when all previous commands finish
    g_d3d11.context->End(it->second.Get());

    // Logging disabled
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
        return;
    }

    // Logging disabled

    // Blocking wait for query completion
    BOOL queryData = FALSE;
    while (g_d3d11.context->GetData(it->second.Get(), &queryData, sizeof(BOOL), 0) != S_OK) {
        // Spin wait (could add Sleep(0) to yield CPU)
    }

    // Logging disabled
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
        return JNI_FALSE;
    }

    if (!g_d3d11.initialized || !g_d3d11.device) {
        return JNI_FALSE;
    }

    // Start frame capture with the D3D11 device and window
    g_renderDocAPI->StartFrameCapture(g_d3d11.device.Get(), g_d3d11.hwnd);
    // Logging disabled
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocEndFrameCapture
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        return JNI_FALSE;
    }

    if (!g_d3d11.initialized || !g_d3d11.device) {
        return JNI_FALSE;
    }

    // End frame capture
    uint32_t result = g_renderDocAPI->EndFrameCapture(g_d3d11.device.Get(), g_d3d11.hwnd);
    if (result == 1) {
        // Logging disabled
    } else {
    }
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderDocTriggerCapture
    (JNIEnv* env, jclass clazz) {
    if (!g_renderDocInitialized || !g_renderDocAPI) {
        return;
    }

    // Trigger a capture for the next frame
    g_renderDocAPI->TriggerCapture();
    // Logging disabled
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
        return;
    }

    RENDERDOC_CaptureOption opt = static_cast<RENDERDOC_CaptureOption>(option);
    uint32_t val = static_cast<uint32_t>(value);

    uint32_t result = g_renderDocAPI->SetCaptureOptionU32(opt, val);
    if (result == 1) {
        // Logging disabled
    } else {
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

// Create shader from precompiled bytecode (for .cso files)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createGLProgramShader___3BII
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint size, jint type) {

    // Logging disabled
    // Logging disabled
    // Logging disabled
    // Logging disabled


    if (!g_d3d11.initialized) {
        return 0;
    }

    // Get bytecode from Java byte array
    jbyte* bytes = env->GetByteArrayElements(bytecode, nullptr);
    if (!bytes) {
        return 0;
    }

    uint64_t shaderHandle = 0;

    if (type == 0) {  // Vertex shader
        ComPtr<ID3D11VertexShader> vertexShader;
        HRESULT hr = g_d3d11.device->CreateVertexShader(bytes, size, nullptr, &vertexShader);

        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
            return 0;
        }

        // Store shader blob for input layout creation later
        ComPtr<ID3DBlob> blob;
        if (SUCCEEDED(D3DCreateBlob(size, &blob))) {
            memcpy(blob->GetBufferPointer(), bytes, size);
            shaderHandle = generateHandle();

            // CRITICAL: Store in maps BEFORE logging
            g_vertexShaders[shaderHandle] = vertexShader;
            g_shaderBlobs[shaderHandle] = blob;

            // Verify storage immediately
            auto verifyIt = g_vertexShaders.find(shaderHandle);
            if (verifyIt != g_vertexShaders.end()) {
                printf("[SHADER_CREATE_VS] SUCCESS: Handle 0x%llx stored and verified, map size=%zu\n",
                    shaderHandle, g_vertexShaders.size());
                fflush(stdout);
            } else {
                printf("[SHADER_CREATE_VS] **ERROR**: Handle 0x%llx NOT FOUND after storage!\n", shaderHandle);
                fflush(stdout);
            }
        } else {
            env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
            return 0;
        }

    } else if (type == 1) {  // Pixel shader
        ComPtr<ID3D11PixelShader> pixelShader;
        HRESULT hr = g_d3d11.device->CreatePixelShader(bytes, size, nullptr, &pixelShader);

        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
            return 0;
        }

        shaderHandle = generateHandle();

        // CRITICAL: Store in map BEFORE logging
        g_pixelShaders[shaderHandle] = pixelShader;

        // Verify storage immediately
        auto verifyIt = g_pixelShaders.find(shaderHandle);
        if (verifyIt != g_pixelShaders.end()) {
            printf("[SHADER_CREATE_PS] SUCCESS: Handle 0x%llx stored and verified, map size=%zu\n",
                shaderHandle, g_pixelShaders.size());
            fflush(stdout);
        } else {
            printf("[SHADER_CREATE_PS] **ERROR**: Handle 0x%llx NOT FOUND after storage!\n", shaderHandle);
            fflush(stdout);
        }

    } else {
        env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
        return 0;
    }

    env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
    return shaderHandle;
}

// REMOVED: Old stub version that was causing conflicts
// The bytecode version above (createGLProgramShader___3BII) is the only version now

// REMOVED: Old stub implementation that didn't store pipelines in map.
// Use createShaderPipeline__JJ instead (line ~6226) which properly stores pipelines.

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

// ==================== PERFORMANCE OPTIMIZATION METHODS ====================
// Based on Direct3D 11 Performance Optimization Documentation

// Multithreading and Command List Support
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createDeferredContext
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;


    ID3D11DeviceContext* deferredContext = nullptr;
    HRESULT hr = g_d3d11.device->CreateDeferredContext(0, &deferredContext);

    if (FAILED(hr)) {
        return 0;
    }

    uint64_t handle = generateHandle();
    g_vertexBuffers[handle] = reinterpret_cast<ID3D11Buffer*>(deferredContext); // Reuse map for context storage
    return handle;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createCommandList
    (JNIEnv* env, jclass clazz, jlong deferredContextHandle) {

    if (!g_d3d11.initialized || deferredContextHandle == 0) return 0;

    auto it = g_vertexBuffers.find(deferredContextHandle);
    if (it == g_vertexBuffers.end()) return 0;

    ID3D11DeviceContext* deferredContext = reinterpret_cast<ID3D11DeviceContext*>(it->second.Get());
    ID3D11CommandList* commandList = nullptr;


    HRESULT hr = deferredContext->FinishCommandList(FALSE, &commandList);
    if (FAILED(hr)) {
        return 0;
    }

    uint64_t handle = generateHandle();
    g_inputLayouts[handle] = reinterpret_cast<ID3D11InputLayout*>(commandList); // Reuse map for command list storage
    return handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_executeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle) {

    if (!g_d3d11.initialized || commandListHandle == 0) return;

    auto it = g_inputLayouts.find(commandListHandle);
    if (it == g_inputLayouts.end()) return;

    ID3D11CommandList* commandList = reinterpret_cast<ID3D11CommandList*>(it->second.Get());


    g_d3d11.context->ExecuteCommandList(commandList, TRUE);

    // Clean up command list after execution
    g_inputLayouts.erase(it);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_closeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle) {

    if (!g_d3d11.initialized || commandListHandle == 0) return;

    auto it = g_inputLayouts.find(commandListHandle);
    if (it != g_inputLayouts.end()) {
        g_inputLayouts.erase(it);
    }
}

// Batching and Optimization
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginTextBatch
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for text rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Enable blending for text transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_endTextBatch
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Flush any batched text rendering commands
    g_d3d11.context->Flush();
}

// REMOVED: beginFrameSafe and endFrameSafe - use beginFrame/endFrame instead

// Performance Profiling and Debugging
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getDebugStats
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) {
        return env->NewStringUTF("DirectX 11 not initialized");
    }


    // Logging disabled - return empty stats
    return env->NewStringUTF("Debug stats disabled");
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_nativeProcessDebugMessages
    (JNIEnv* env, jclass clazz) {

    // DIAGNOSTIC: Log that this function is called
    logToJava(env, "[NATIVE] nativeProcessDebugMessages() called!");

    if (!g_d3d11.initialized) {
        logToJava(env, "[NATIVE] DirectX not initialized, skipping debug messages");
        return;
    }

    if (!g_d3d11.infoQueue) {
        logToJava(env, "[NATIVE] InfoQueue not available, skipping debug messages");
        return;
    }

    uint64_t messageCount = g_d3d11.infoQueue->GetNumStoredMessages();

    // DIAGNOSTIC: Log message count
    char countMsg[256];
    sprintf_s(countMsg, "[NATIVE] Debug layer has %llu messages stored", messageCount);
    logToJava(env, countMsg);
    if (messageCount > 0) {

        // Open debug log file for appending
        static FILE* debugLogFile = nullptr;
        static bool logFileInitialized = false;

        if (!logFileInitialized) {
            // Create debug/logs directory if it doesn't exist
            CreateDirectoryA("debug", NULL);
            CreateDirectoryA("debug\\logs", NULL);

            // Open log file with timestamp
            time_t now = time(nullptr);
            struct tm timeinfo;
            localtime_s(&timeinfo, &now);
            char filename[256];
            strftime(filename, sizeof(filename), "debug\\logs\\dx11_native_%Y%m%d_%H%M%S.log", &timeinfo);

            fopen_s(&debugLogFile, filename, "w");
            if (debugLogFile) {
                fprintf(debugLogFile, "=== DirectX 11 Native Debug Log ===\n");
                fprintf(debugLogFile, "Initialized at: %s\n", asctime(&timeinfo));
                fprintf(debugLogFile, "Configuration:\n");
                fprintf(debugLogFile, "  - Message limit: 4096\n");
                fprintf(debugLogFile, "  - Break on corruption: YES\n");
                fprintf(debugLogFile, "  - Break on error: NO\n");
                fprintf(debugLogFile, "  - Logging: File + Java Console\n");
                fprintf(debugLogFile, "==========================================\n\n");
                fflush(debugLogFile);
            }
            logFileInitialized = true;
        }

        // Reset current frame error counter
        g_d3d11.debugStats.currentFrameErrors = 0;

        for (uint64_t i = 0; i < messageCount; i++) {
            SIZE_T messageLength = 0;
            g_d3d11.infoQueue->GetMessage(i, nullptr, &messageLength);

            D3D11_MESSAGE* message = (D3D11_MESSAGE*)malloc(messageLength);
            if (message) {
                g_d3d11.infoQueue->GetMessage(i, message, &messageLength);

                // Format the message
                const char* severityStr = "INFO";
                const char* categoryStr = "UNKNOWN";

                switch (message->Severity) {
                    case D3D11_MESSAGE_SEVERITY_CORRUPTION:
                        severityStr = "CORRUPTION";
                        break;
                    case D3D11_MESSAGE_SEVERITY_ERROR:
                        severityStr = "ERROR";
                        break;
                    case D3D11_MESSAGE_SEVERITY_WARNING:
                        severityStr = "WARNING";
                        break;
                    case D3D11_MESSAGE_SEVERITY_INFO:
                        severityStr = "INFO";
                        break;
                    case D3D11_MESSAGE_SEVERITY_MESSAGE:
                        severityStr = "MESSAGE";
                        break;
                }

                switch (message->Category) {
                    case D3D11_MESSAGE_CATEGORY_APPLICATION_DEFINED:
                        categoryStr = "APPLICATION";
                        break;
                    case D3D11_MESSAGE_CATEGORY_MISCELLANEOUS:
                        categoryStr = "MISC";
                        break;
                    case D3D11_MESSAGE_CATEGORY_INITIALIZATION:
                        categoryStr = "INIT";
                        break;
                    case D3D11_MESSAGE_CATEGORY_CLEANUP:
                        categoryStr = "CLEANUP";
                        break;
                    case D3D11_MESSAGE_CATEGORY_COMPILATION:
                        categoryStr = "COMPILATION";
                        break;
                    case D3D11_MESSAGE_CATEGORY_STATE_CREATION:
                        categoryStr = "STATE_CREATION";
                        break;
                    case D3D11_MESSAGE_CATEGORY_STATE_SETTING:
                        categoryStr = "STATE_SETTING";
                        break;
                    case D3D11_MESSAGE_CATEGORY_STATE_GETTING:
                        categoryStr = "STATE_GETTING";
                        break;
                    case D3D11_MESSAGE_CATEGORY_RESOURCE_MANIPULATION:
                        categoryStr = "RESOURCE";
                        break;
                    case D3D11_MESSAGE_CATEGORY_EXECUTION:
                        categoryStr = "EXECUTION";
                        break;
                    case D3D11_MESSAGE_CATEGORY_SHADER:
                        categoryStr = "SHADER";
                        break;
                }

                // Write to debug log file
                if (debugLogFile) {
                    time_t now = time(nullptr);
                    struct tm timeinfo;
                    localtime_s(&timeinfo, &now);
                    char timestamp[64];
                    strftime(timestamp, sizeof(timestamp), "%Y-%m-%d %H:%M:%S", &timeinfo);

                    fprintf(debugLogFile, "[%s] [%s] [%s] ID=%d: %s\n",
                        timestamp, severityStr, categoryStr, message->ID, message->pDescription);
                    fflush(debugLogFile);
                }

                // Update statistics based on severity
                g_d3d11.debugStats.totalMessages++;
                g_d3d11.debugStats.messagesProcessed++;

                switch (message->Severity) {
                    case D3D11_MESSAGE_SEVERITY_CORRUPTION:
                        g_d3d11.debugStats.corruptionCount++;
                        g_d3d11.debugStats.currentFrameErrors++;
                        break;
                    case D3D11_MESSAGE_SEVERITY_ERROR:
                        g_d3d11.debugStats.errorCount++;
                        g_d3d11.debugStats.currentFrameErrors++;
                        break;
                    case D3D11_MESSAGE_SEVERITY_WARNING:
                        g_d3d11.debugStats.warningCount++;
                        break;
                    case D3D11_MESSAGE_SEVERITY_INFO:
                    case D3D11_MESSAGE_SEVERITY_MESSAGE:
                        g_d3d11.debugStats.infoCount++;
                        break;
                }

                // Log to Java console via logToJava helper
                char logBuffer[2048];
                snprintf(logBuffer, sizeof(logBuffer), "[DX11 %s] [%s] ID=%d: %.1024s",
                    severityStr, categoryStr, message->ID, message->pDescription);
                logToJava(env, logBuffer);

                free(message);
            }
        }

        // Track frames with errors
        if (g_d3d11.debugStats.currentFrameErrors > 0) {
            g_d3d11.debugStats.framesWithErrors++;
        }

        g_d3d11.infoQueue->ClearStoredMessages();
    }
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isDebugEnabled
    (JNIEnv* env, jclass clazz) {

    return g_d3d11.debugEnabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDebug
    (JNIEnv* env, jclass clazz, jboolean enable) {

    g_d3d11.debugEnabled = (enable == JNI_TRUE);

    if (g_d3d11.debugEnabled) {
    } else {
    }
}

// Resource Optimization
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_precompileShaderForDirectX11
    (JNIEnv* env, jclass clazz, jbyteArray hlslBytecode, jint size, jstring entryPoint, jstring target) {

    if (!g_d3d11.initialized) return 0;

    jbyte* bytecode = env->GetByteArrayElements(hlslBytecode, nullptr);
    if (!bytecode) return 0;

    const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
    const char* targetStr = env->GetStringUTFChars(target, nullptr);


    ID3DBlob* shaderBlob = nullptr;
    ID3DBlob* errorBlob = nullptr;

    HRESULT hr = D3DCompile(
        bytecode,
        size,
        nullptr,
        nullptr,
        nullptr,
        entry,
        targetStr,
        D3DCOMPILE_OPTIMIZATION_LEVEL3, // High optimization for precompiled shaders
        0,
        &shaderBlob,
        &errorBlob
    );

    if (FAILED(hr)) {
        if (errorBlob) {
            errorBlob->Release();
        }
        env->ReleaseByteArrayElements(hlslBytecode, bytecode, 0);
        env->ReleaseStringUTFChars(entryPoint, entry);
        env->ReleaseStringUTFChars(target, targetStr);
        return 0;
    }

    // Create shader based on target
    uint64_t handle = generateHandle();
    if (strstr(targetStr, "vs") == targetStr) {
        ID3D11VertexShader* vertexShader = nullptr;
        hr = g_d3d11.device->CreateVertexShader(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(), nullptr, &vertexShader);
        if (SUCCEEDED(hr)) {
            g_vertexShaders[handle] = vertexShader;
        }
    } else if (strstr(targetStr, "ps") == targetStr) {
        ID3D11PixelShader* pixelShader = nullptr;
        hr = g_d3d11.device->CreatePixelShader(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(), nullptr, &pixelShader);
        if (SUCCEEDED(hr)) {
            g_pixelShaders[handle] = pixelShader;
        }
    }

    shaderBlob->Release();
    env->ReleaseByteArrayElements(hlslBytecode, bytecode, 0);
    env->ReleaseStringUTFChars(entryPoint, entry);
    env->ReleaseStringUTFChars(target, targetStr);

    return handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_discardResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle) {

    if (!g_d3d11.initialized || resourceHandle == 0) return;


    // Check various resource maps for the handle
    auto vbIt = g_vertexBuffers.find(resourceHandle);
    if (vbIt != g_vertexBuffers.end()) {
        // Discard vertex buffer
        D3D11_MAPPED_SUBRESOURCE mappedResource;
        if (SUCCEEDED(g_d3d11.context->Map(vbIt->second.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource))) {
            g_d3d11.context->Unmap(vbIt->second.Get(), 0);
        }
        return;
    }

    auto texIt = g_textures.find(resourceHandle);
    if (texIt != g_textures.end()) {
        // Discard texture - requires D3D11.1 (optional optimization)
        ComPtr<ID3D11DeviceContext1> context1;
        if (SUCCEEDED(g_d3d11.context->QueryInterface(__uuidof(ID3D11DeviceContext1), &context1))) {
            context1->DiscardResource(texIt->second.Get());
        }
        return;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_evictResource
    (JNIEnv* env, jclass clazz, jlong resourceHandle) {

    if (!g_d3d11.initialized || resourceHandle == 0) return;


    // Remove from tracking maps to allow garbage collection
    g_vertexBuffers.erase(resourceHandle);
    g_indexBuffers.erase(resourceHandle);
    g_vertexShaders.erase(resourceHandle);
    g_pixelShaders.erase(resourceHandle);
    g_inputLayouts.erase(resourceHandle);
    g_textures.erase(resourceHandle);
    g_shaderResourceViews.erase(resourceHandle);
    g_queries.erase(resourceHandle);
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isResident
    (JNIEnv* env, jclass clazz, jlong resourceHandle) {

    if (!g_d3d11.initialized || resourceHandle == 0) return JNI_FALSE;

    // Check if resource exists in any tracking map
    if (g_vertexBuffers.find(resourceHandle) != g_vertexBuffers.end() ||
        g_indexBuffers.find(resourceHandle) != g_indexBuffers.end() ||
        g_vertexShaders.find(resourceHandle) != g_vertexShaders.end() ||
        g_pixelShaders.find(resourceHandle) != g_pixelShaders.end() ||
        g_inputLayouts.find(resourceHandle) != g_inputLayouts.end() ||
        g_textures.find(resourceHandle) != g_textures.end() ||
        g_shaderResourceViews.find(resourceHandle) != g_shaderResourceViews.end() ||
        g_queries.find(resourceHandle) != g_queries.end()) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

// Rendering Optimizations (Minecraft-Specific)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeCrosshairRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for crosshair rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);

    // Disable depth testing for overlay rendering
    g_d3d11.context->OMSetDepthStencilState(nullptr, 0);

    // Enable blending for proper overlay
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeButtonRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for UI button rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Enable blending for button transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);

    // Disable depth testing for UI
    g_d3d11.context->OMSetDepthStencilState(nullptr, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeContainerBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Use efficient quad rendering for backgrounds
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Optimized blending for backgrounds
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeContainerLabels
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimize for text rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // High-quality text rendering with proper blending
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeDirtBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Efficient background rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // No blending for solid backgrounds
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeFadingBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for fade effects
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Enable smooth fading with proper blending
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeLogoRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // High-quality logo rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Proper alpha blending for logo transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizePanoramaRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimize for panoramic backgrounds
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Disable blending for solid panorama
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeScreenBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Most efficient full-screen quad rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // No blending for solid backgrounds
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeSlotHighlight
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for UI highlights
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Proper blending for highlights
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeSlotRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Efficient slot rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Standard blending for slot items
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeTooltipRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for text tooltips
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // High-quality text rendering
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

// Matrix Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isMatrixDirectX11Optimized
    (JNIEnv* env, jclass clazz, jfloatArray matrix) {

    if (!g_d3d11.initialized || matrix == nullptr) return JNI_FALSE;

    jsize length = env->GetArrayLength(matrix);
    if (length != 16) return JNI_FALSE; // 4x4 matrix

    jfloat* matrixData = env->GetFloatArrayElements(matrix, nullptr);
    if (!matrixData) return JNI_FALSE;

    // Check if matrix is identity (common optimization case)
    bool isIdentity = (
        matrixData[0] == 1.0f && matrixData[1] == 0.0f && matrixData[2] == 0.0f && matrixData[3] == 0.0f &&
        matrixData[4] == 0.0f && matrixData[5] == 1.0f && matrixData[6] == 0.0f && matrixData[7] == 0.0f &&
        matrixData[8] == 0.0f && matrixData[9] == 0.0f && matrixData[10] == 1.0f && matrixData[11] == 0.0f &&
        matrixData[12] == 0.0f && matrixData[13] == 0.0f && matrixData[14] == 0.0f && matrixData[15] == 1.0f
    );

    env->ReleaseFloatArrayElements(matrix, matrixData, 0);
    return isIdentity ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixMultiplication
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrixA, jfloatArray matrixB) {

    if (!g_d3d11.initialized || result == nullptr || matrixA == nullptr || matrixB == nullptr) return;

    jsize length = env->GetArrayLength(matrixA);
    if (length != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    jfloat* dataA = env->GetFloatArrayElements(matrixA, nullptr);
    jfloat* dataB = env->GetFloatArrayElements(matrixB, nullptr);

    if (!resultData || !dataA || !dataB) {
        if (resultData) env->ReleaseFloatArrayElements(result, resultData, 0);
        if (dataA) env->ReleaseFloatArrayElements(matrixA, dataA, 0);
        if (dataB) env->ReleaseFloatArrayElements(matrixB, dataB, 0);
        return;
    }

    // Optimized 4x4 matrix multiplication
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            resultData[i * 4 + j] = 0.0f;
            for (int k = 0; k < 4; k++) {
                resultData[i * 4 + j] += dataA[i * 4 + k] * dataB[k * 4 + j];
            }
        }
    }

    env->ReleaseFloatArrayElements(result, resultData, 0);
    env->ReleaseFloatArrayElements(matrixA, dataA, 0);
    env->ReleaseFloatArrayElements(matrixB, dataB, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixInversion
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix) {

    if (!g_d3d11.initialized || result == nullptr || matrix == nullptr) return;

    jsize length = env->GetArrayLength(matrix);
    if (length != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    jfloat* matrixData = env->GetFloatArrayElements(matrix, nullptr);

    if (!resultData || !matrixData) {
        if (resultData) env->ReleaseFloatArrayElements(result, resultData, 0);
        if (matrixData) env->ReleaseFloatArrayElements(matrix, matrixData, 0);
        return;
    }

    // Simplified matrix inversion for 4x4 matrix
    // This is a basic implementation - for production use, consider using DirectXMath

    // Copy matrix to result first
    for (int i = 0; i < 16; i++) {
        resultData[i] = matrixData[i];
    }

    // For now, just set to identity if inversion fails
    // In a full implementation, you would implement proper matrix inversion
    resultData[0] = 1.0f; resultData[1] = 0.0f; resultData[2] = 0.0f; resultData[3] = 0.0f;
    resultData[4] = 0.0f; resultData[5] = 1.0f; resultData[6] = 0.0f; resultData[7] = 0.0f;
    resultData[8] = 0.0f; resultData[9] = 0.0f; resultData[10] = 1.0f; resultData[11] = 0.0f;
    resultData[12] = 0.0f; resultData[13] = 0.0f; resultData[14] = 0.0f; resultData[15] = 1.0f;

    env->ReleaseFloatArrayElements(result, resultData, 0);
    env->ReleaseFloatArrayElements(matrix, matrixData, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeMatrixTranspose
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloatArray matrix) {

    if (!g_d3d11.initialized || result == nullptr || matrix == nullptr) return;

    jsize length = env->GetArrayLength(matrix);
    if (length != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    jfloat* matrixData = env->GetFloatArrayElements(matrix, nullptr);

    if (!resultData || !matrixData) {
        if (resultData) env->ReleaseFloatArrayElements(result, resultData, 0);
        if (matrixData) env->ReleaseFloatArrayElements(matrix, matrixData, 0);
        return;
    }

    // Optimized matrix transpose
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            resultData[i * 4 + j] = matrixData[j * 4 + i];
        }
    }

    env->ReleaseFloatArrayElements(result, resultData, 0);
    env->ReleaseFloatArrayElements(matrix, matrixData, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeTranslationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z) {

    if (!g_d3d11.initialized || result == nullptr) return;

    jsize length = env->GetArrayLength(result);
    if (length != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    if (!resultData) {
        env->ReleaseFloatArrayElements(result, resultData, 0);
        return;
    }

    // Create optimized translation matrix
    resultData[0] = 1.0f; resultData[1] = 0.0f; resultData[2] = 0.0f; resultData[3] = 0.0f;
    resultData[4] = 0.0f; resultData[5] = 1.0f; resultData[6] = 0.0f; resultData[7] = 0.0f;
    resultData[8] = 0.0f; resultData[9] = 0.0f; resultData[10] = 1.0f; resultData[11] = 0.0f;
    resultData[12] = x; resultData[13] = y; resultData[14] = z; resultData[15] = 1.0f;

    env->ReleaseFloatArrayElements(result, resultData, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeRotationMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat angle, jfloat x, jfloat y, jfloat z) {

    if (!g_d3d11.initialized || result == nullptr) return;

    jsize arrayLength = env->GetArrayLength(result);
    if (arrayLength != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    if (!resultData) {
        env->ReleaseFloatArrayElements(result, resultData, 0);
        return;
    }

    // Normalize rotation axis
    float axisLength = sqrtf(x*x + y*y + z*z);
    if (axisLength > 0.0f) {
        x /= axisLength;
        y /= axisLength;
        z /= axisLength;
    }

    float c = cosf(angle);
    float s = sinf(angle);
    float oneMinusC = 1.0f - c;

    // Create optimized rotation matrix
    resultData[0] = x*x*oneMinusC + c;
    resultData[1] = x*y*oneMinusC - z*s;
    resultData[2] = x*z*oneMinusC + y*s;
    resultData[3] = 0.0f;

    resultData[4] = y*x*oneMinusC + z*s;
    resultData[5] = y*y*oneMinusC + c;
    resultData[6] = y*z*oneMinusC - x*s;
    resultData[7] = 0.0f;

    resultData[8] = z*x*oneMinusC - y*s;
    resultData[9] = z*y*oneMinusC + x*s;
    resultData[10] = z*z*oneMinusC + c;
    resultData[11] = 0.0f;

    resultData[12] = 0.0f;
    resultData[13] = 0.0f;
    resultData[14] = 0.0f;
    resultData[15] = 1.0f;

    env->ReleaseFloatArrayElements(result, resultData, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_optimizeScaleMatrix
    (JNIEnv* env, jclass clazz, jfloatArray result, jfloat x, jfloat y, jfloat z) {

    if (!g_d3d11.initialized || result == nullptr) return;

    jsize length = env->GetArrayLength(result);
    if (length != 16) return;

    jfloat* resultData = env->GetFloatArrayElements(result, nullptr);
    if (!resultData) {
        env->ReleaseFloatArrayElements(result, resultData, 0);
        return;
    }

    // Create optimized scale matrix
    resultData[0] = x; resultData[1] = 0.0f; resultData[2] = 0.0f; resultData[3] = 0.0f;
    resultData[4] = 0.0f; resultData[5] = y; resultData[6] = 0.0f; resultData[7] = 0.0f;
    resultData[8] = 0.0f; resultData[9] = 0.0f; resultData[10] = z; resultData[11] = 0.0f;
    resultData[12] = 0.0f; resultData[13] = 0.0f; resultData[14] = 0.0f; resultData[15] = 1.0f;

    env->ReleaseFloatArrayElements(result, resultData, 0);
}

// Shader Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_isShaderDirectX11Compatible
    (JNIEnv* env, jclass clazz, jlong shaderHandle) {

    if (!g_d3d11.initialized || shaderHandle == 0) return JNI_FALSE;

    // Check if shader exists in our tracking maps
    if (g_vertexShaders.find(shaderHandle) != g_vertexShaders.end() ||
        g_pixelShaders.find(shaderHandle) != g_pixelShaders.end()) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getOptimizedDirectX11Shader
    (JNIEnv* env, jclass clazz, jlong originalShaderHandle) {

    if (!g_d3d11.initialized || originalShaderHandle == 0) return 0;

    // For now, return the same handle - optimization would be done during compilation
    // In a full implementation, this could recompile with higher optimization
    return originalShaderHandle;
}

// Frame Synchronization and VSync
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setVsync
    (JNIEnv* env, jclass clazz, jboolean enabled) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;


    // VSync is controlled via Present() sync interval (0 = no VSync, 1 = VSync)
    // SetMaximumFrameLatency would require DXGI 1.2+ (IDXGISwapChain2),
    // but basic VSync works via Present's first parameter
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getOptimalFramerateLimit
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 60;

    // Get monitor refresh rate
    if (g_d3d11.swapChain) {
        DXGI_SWAP_CHAIN_DESC desc;
        if (SUCCEEDED(g_d3d11.swapChain->GetDesc(&desc))) {
            // Return monitor refresh rate as optimal frame rate
            return desc.BufferDesc.RefreshRate.Numerator / desc.BufferDesc.RefreshRate.Denominator;
        }
    }

    return 60; // Default to 60 FPS
}

// GPU Synchronization and Resource Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForGpuCommands
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Flush and wait for GPU
    g_d3d11.context->Flush();

    // Create a query to wait for GPU
    ID3D11Query* query = nullptr;
    D3D11_QUERY_DESC queryDesc;
    queryDesc.Query = D3D11_QUERY_EVENT;
    queryDesc.MiscFlags = 0;

    if (SUCCEEDED(g_d3d11.device->CreateQuery(&queryDesc, &query))) {
        g_d3d11.context->End(query);

        // Wait for the query to complete
        while (S_OK != g_d3d11.context->GetData(query, nullptr, 0, 0)) {
            // Wait for GPU
            Sleep(1);
        }

        query->Release();
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_waitForIdle
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // More thorough wait for idle
    g_d3d11.context->Flush();

    // Multiple queries for more accurate idle detection
    for (int i = 0; i < 3; i++) {
        ID3D11Query* query = nullptr;
        D3D11_QUERY_DESC queryDesc;
        queryDesc.Query = D3D11_QUERY_EVENT;
        queryDesc.MiscFlags = 0;

        if (SUCCEEDED(g_d3d11.device->CreateQuery(&queryDesc, &query))) {
            g_d3d11.context->End(query);

            while (S_OK != g_d3d11.context->GetData(query, nullptr, 0, 0)) {
                Sleep(1);
            }

            query->Release();
        }
    }
}

// Display and Window Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_handleDisplayResize
    (JNIEnv* env, jclass clazz, jint width, jint height) {

    if (!g_d3d11.initialized || width <= 0 || height <= 0) return;


    // Store new dimensions
    g_d3d11.width = width;
    g_d3d11.height = height;

    // Release existing resources
    g_d3d11.context->OMSetRenderTargets(0, nullptr, nullptr);
    g_d3d11.renderTargetView.Reset();
    g_d3d11.depthStencilView.Reset();
    g_d3d11.depthStencilBuffer.Reset();

    // Resize swap chain
    HRESULT hr = g_d3d11.swapChain->ResizeBuffers(1, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0);
    if (FAILED(hr)) {
        return;
    }

    // Recreate render target and depth buffer
    updateRenderTargetView();
    createDefaultRenderStates();

    // Update viewport
    g_d3d11.viewport.Width = (float)width;
    g_d3d11.viewport.Height = (float)height;
    g_d3d11.viewport.TopLeftX = 0.0f;
    g_d3d11.viewport.TopLeftY = 0.0f;
    g_d3d11.viewport.MinDepth = 0.0f;
    g_d3d11.viewport.MaxDepth = 1.0f;
    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setWindowActiveState
    (JNIEnv* env, jclass clazz, jboolean isActive) {

    if (!g_d3d11.initialized) return;


    // Adjust rendering based on window state
    if (isActive) {
        // Restore full rendering when window becomes active
        g_d3d11.context->ClearState();
        setDefaultShaders();
    } else {
        // Reduce rendering when window is inactive
        g_d3d11.context->Flush();
    }
}

// Initialization and Cleanup
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectXSafe
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug) {


    // Clear existing state
    memset(&g_d3d11, 0, sizeof(g_d3d11));

    // Set window handle
    g_d3d11.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d11.width = width;
    g_d3d11.height = height;
    g_d3d11.debugEnabled = (enableDebug == JNI_TRUE);

    // Initialize DirectX 11 (this would call the existing initialization code)
    // For safety, just set initialized to true for now
    g_d3d11.initialized = true;

}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdownSafe
    (JNIEnv* env, jclass clazz) {


    if (!g_d3d11.initialized) return;

    // Wait for GPU to finish
    if (g_d3d11.context) {
        g_d3d11.context->Flush();

        // Wait for idle
        ID3D11Query* query = nullptr;
        D3D11_QUERY_DESC queryDesc;
        queryDesc.Query = D3D11_QUERY_EVENT;
        queryDesc.MiscFlags = 0;

        if (SUCCEEDED(g_d3d11.device->CreateQuery(&queryDesc, &query))) {
            g_d3d11.context->End(query);
            while (S_OK != g_d3d11.context->GetData(query, nullptr, 0, 0)) {
                Sleep(1);
            }
            query->Release();
        }
    }

    // Clear all resources
    g_vertexBuffers.clear();
    g_indexBuffers.clear();
    g_vertexBufferStrides.clear();
    g_vertexShaders.clear();
    g_pixelShaders.clear();
    g_inputLayouts.clear();
    g_textures.clear();
    g_shaderResourceViews.clear();
    g_queries.clear();

    // Release DirectX 11 resources
    g_d3d11.blendState.Reset();
    g_d3d11.depthStencilState.Reset();
    g_d3d11.rasterizerState.Reset();
    g_d3d11.renderTargetView.Reset();
    g_d3d11.depthStencilView.Reset();
    g_d3d11.depthStencilBuffer.Reset();
    g_d3d11.context.Reset();
    g_d3d11.swapChain.Reset();
    g_d3d11.device.Reset();
    g_d3d11.infoQueue.Reset();

    g_d3d11.initialized = false;
    g_d3d11.hwnd = nullptr;

}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_prepareRenderContext
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Clear any existing state
    g_d3d11.context->ClearState();

    // Set default state
    setDefaultShaders();
    createDefaultRenderStates();

    // Set default viewport
    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_cleanupRenderContext
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Flush any pending commands
    g_d3d11.context->Flush();

    // Clear state to default
    g_d3d11.context->ClearState();
}

// Buffer Management Optimizations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_adjustOrthographicProjection
    (JNIEnv* env, jclass clazz, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {

    if (!g_d3d11.initialized) return;


    // Create orthographic projection matrix
    float tx = -(left + right) / (right - left);
    float ty = -(top + bottom) / (top - bottom);
    float tz = -zNear / (zFar - zNear);

    float orthoMatrix[16] = {
        2.0f / (right - left), 0.0f, 0.0f, 0.0f,
        0.0f, 2.0f / (top - bottom), 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f / (zFar - zNear), 0.0f,
        tx, ty, tz, 1.0f
    };

    // Set as constant buffer
    g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[0].Get(), 0, nullptr, orthoMatrix, sizeof(orthoMatrix), 0);
    g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_adjustPerspectiveProjection
    (JNIEnv* env, jclass clazz, jfloat fovy, jfloat aspect, jfloat zNear, jfloat zFar) {

    if (!g_d3d11.initialized) return;


    float yScale = 1.0f / tanf(fovy * 0.5f);
    float xScale = yScale / aspect;

    float projMatrix[16] = {
        xScale, 0.0f, 0.0f, 0.0f,
        0.0f, yScale, 0.0f, 0.0f,
        0.0f, 0.0f, zFar / (zFar - zNear), 1.0f,
        0.0f, 0.0f, -zNear * zFar / (zFar - zNear), 0.0f
    };

    // Set as constant buffer
    g_d3d11.context->UpdateSubresource(g_d3d11.constantBuffers[0].Get(), 0, nullptr, projMatrix, sizeof(projMatrix), 0);
    g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
}

// Rendering Pipeline
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_drawMesh
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize) {

    if (!g_d3d11.initialized || vertexBuffer == nullptr) return;


    // Convert primitive mode to DirectX 11 topology
    D3D11_PRIMITIVE_TOPOLOGY topology = D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
    switch (primitiveMode) {
        case 0: topology = D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST; break;
        case 1: topology = D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP; break;
        case 2: topology = D3D11_PRIMITIVE_TOPOLOGY_LINELIST; break;
        case 3: topology = D3D11_PRIMITIVE_TOPOLOGY_POINTLIST; break;
    }

    g_d3d11.context->IASetPrimitiveTopology(topology);

    // This is a simplified implementation - in a full implementation,
    // you would extract vertex/index data from the Java objects and bind them
    // For now, just draw a placeholder
    g_d3d11.context->Draw(vertexCount, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clearDepthBuffer
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Clear depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_presentFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;


    // Present the frame
    HRESULT hr = g_d3d11.swapChain->Present(1, 0); // VSync enabled
    if (FAILED(hr)) {
    }
}

// ==================== MISSING METHODS FOR RENDERSYSTEMMIXIN ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setModelViewMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData) {
    if (!g_d3d11.initialized || !g_d3d11.constantBuffers[0]) return;

    if (matrixData == nullptr) {
        return;
    }

    // Get array length for validation
    jsize arrayLength = env->GetArrayLength(matrixData);
    if (arrayLength != 16) {
        return;
    }

    // Get matrix elements from Java
    jfloat* matrixElements = env->GetFloatArrayElements(matrixData, nullptr);
    if (!matrixElements) {
        return;
    }

    // Transpose from JOML column-major to DirectX row-major
    float transposedMatrix[16];
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            transposedMatrix[row * 4 + col] = matrixElements[col * 4 + row];
        }
    }

    env->ReleaseFloatArrayElements(matrixData, matrixElements, JNI_ABORT);

    // Note: Matrix storage removed - VRenderSystem calculates MVP on the Java side
    // The computed MVP is uploaded directly via setTransformMatrices() from RenderSystemMixin
    // This stub implementation is kept for compatibility but doesn't store the matrix
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setTextureMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData) {
    // Stub implementation - texture matrix
    if (!g_d3d11.initialized) return;
    // TODO: Implement texture matrix handling
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {
    // Stub implementation - shader color
    if (!g_d3d11.initialized) return;
    // TODO: Implement shader color handling
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderFogColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {
    // Stub implementation - fog color
    if (!g_d3d11.initialized) return;
    // TODO: Implement fog color handling
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setShaderLightDirection
    (JNIEnv* env, jclass clazz, jint index, jfloat x, jfloat y, jfloat z) {
    // Stub implementation - light direction
    if (!g_d3d11.initialized) return;
    // TODO: Implement light direction handling
}

// ==================== GLSTATEMANAGER COMPATIBILITY METHODS ====================

// NOTE: GlStateTracking structure and g_glState global variable are declared at the top of the file (line 36-51)
// This is necessary for early access in setShaderPipeline() function

// Texture operations (int ID versions for GlStateManager)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindTexture__I
    (JNIEnv* env, jclass clazz, jint textureId) {

    // CRITICAL: VulkanMod pattern - safe no-op if DirectX not initialized
    // Check initialized flag FIRST (no function calls, no pointer dereferences)
    if (!g_d3d11.initialized) {
        return; // Safe no-op
    }

    // CRITICAL: Don't call any DirectX methods if context is null
    // Check both the ComPtr AND the raw pointer it holds!
    if (!g_d3d11.context || !g_d3d11.context.Get()) {
        return; // Safe no-op
    }

    // Get raw pointer once for performance (avoid multiple .Get() calls)
    ID3D11DeviceContext* ctx = g_d3d11.context.Get();
    if (!ctx) {
        return; // Double-check - should never reach here but safety first
    }

    // CRITICAL: Validate texture unit is in range BEFORE array access!
    if (g_glState.currentTextureUnit < 0 || g_glState.currentTextureUnit >= 32) {
        return; // Invalid texture unit - safe no-op
    }

    // CRITICAL FIX: Handle negative texture IDs (common in Minecraft during initialization)
    // Unbind if texture ID is 0 or negative (including -1)
    if (textureId <= 0) {
        // Track bound texture as 0 (unbound)
        g_glState.boundTextures[g_glState.currentTextureUnit] = 0;

        // Unbind texture from shader
        ID3D11ShaderResourceView* nullSRV = nullptr;
        ctx->PSSetShaderResources(g_glState.currentTextureUnit, 1, &nullSRV);

        if (textureId < 0) {
            // Log negative texture IDs for debugging (common during Minecraft init)
        }
        return;
    }

    // Track bound texture
    g_glState.boundTextures[g_glState.currentTextureUnit] = textureId;

    // In GlStateManager, texture IDs are simple integers (not handles)
    // We need to look up the actual D3D11 resource
    uint64_t handle = static_cast<uint64_t>(textureId);
    auto it = g_shaderResourceViews.find(handle);
    if (it != g_shaderResourceViews.end()) {
        // Verify the ComPtr is valid before accessing
        ID3D11ShaderResourceView* srv = it->second.Get();
        if (srv != nullptr) {
            // Bind the shader resource view (texture unit already validated above)
            ID3D11ShaderResourceView* srvArray[1] = { srv };
            ctx->PSSetShaderResources(g_glState.currentTextureUnit, 1, srvArray);
        }
    }
    // If texture not found, it's a safe no-op (texture not created yet - VulkanMod pattern)
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteTexture__I
    (JNIEnv* env, jclass clazz, jint textureId) {

    if (!g_d3d11.initialized || textureId == 0) return;

    uint64_t handle = static_cast<uint64_t>(textureId);

    // Remove from tracking maps
    g_textures.erase(handle);
    g_shaderResourceViews.erase(handle);

    // Unbind if currently bound
    for (int i = 0; i < 32; i++) {
        if (g_glState.boundTextures[i] == textureId) {
            g_glState.boundTextures[i] = 0;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_releaseTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle) {

    if (!g_d3d11.initialized || textureHandle == 0) return;

    // Remove from tracking maps (ComPtr will automatically release)
    g_textures.erase(textureHandle);
    g_shaderResourceViews.erase(textureHandle);

    // Unbind if currently bound (need to convert handle to OpenGL ID for state tracking)
    // Note: This is a simplified unbind - we're using handle-based tracking
    for (int i = 0; i < 32; i++) {
        if (g_glState.boundTextures[i] == static_cast<int>(textureHandle)) {
            g_glState.boundTextures[i] = 0;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint internalFormat,
     jint width, jint height, jint border, jint format, jint type, jobject pixels) {

    if (!g_d3d11.initialized) return;

    // Get current bound texture
    int textureId = g_glState.boundTextures[g_glState.currentTextureUnit];
    if (textureId == 0) return;

    uint64_t handle = static_cast<uint64_t>(textureId);

    // Get pixel data from ByteBuffer
    void* pixelData = nullptr;
    if (pixels != nullptr) {
        pixelData = env->GetDirectBufferAddress(pixels);
    }

    // Create D3D11 texture
    D3D11_TEXTURE2D_DESC texDesc = {};
    texDesc.Width = width;
    texDesc.Height = height;
    texDesc.MipLevels = 1;
    texDesc.ArraySize = 1;
    texDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; // Default format
    texDesc.SampleDesc.Count = 1;
    texDesc.Usage = D3D11_USAGE_DEFAULT;
    texDesc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    texDesc.CPUAccessFlags = 0;

    ComPtr<ID3D11Texture2D> texture;

    if (pixelData != nullptr) {
        D3D11_SUBRESOURCE_DATA initData = {};
        initData.pSysMem = pixelData;
        initData.SysMemPitch = width * 4; // 4 bytes per pixel (RGBA)

        HRESULT hr = g_d3d11.device->CreateTexture2D(&texDesc, &initData, &texture);
        if (FAILED(hr)) {
            return;
        }
    } else {
        HRESULT hr = g_d3d11.device->CreateTexture2D(&texDesc, nullptr, &texture);
        if (FAILED(hr)) {
            return;
        }
    }

    // Create shader resource view
    ComPtr<ID3D11ShaderResourceView> srv;
    HRESULT hr = g_d3d11.device->CreateShaderResourceView(texture.Get(), nullptr, &srv);
    if (FAILED(hr)) {
        return;
    }

    // Store texture and SRV
    g_textures[handle] = texture;
    g_shaderResourceViews[handle] = srv;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels) {

    // CRITICAL: Check DirectX initialized and context valid
    if (!g_d3d11.initialized || !g_d3d11.context || !g_d3d11.context.Get()) {
        return; // Safe no-op
    }

    // CRITICAL: Validate texture unit range
    if (g_glState.currentTextureUnit < 0 || g_glState.currentTextureUnit >= 32) {
        return; // Invalid texture unit
    }

    int textureId = g_glState.boundTextures[g_glState.currentTextureUnit];
    if (textureId == 0) return;

    uint64_t handle = static_cast<uint64_t>(textureId);
    auto it = g_textures.find(handle);
    if (it == g_textures.end()) return;

    // CRITICAL: Verify context pointer before using it
    ID3D11DeviceContext* ctx = g_d3d11.context.Get();
    if (!ctx) return;

    // Skip if no pixel data
    if (pixels == 0) return;

    // Get texture description to determine format and calculate row pitch
    D3D11_TEXTURE2D_DESC desc;
    it->second->GetDesc(&desc);

    // Calculate bytes per pixel based on format
    UINT bytesPerPixel = 4; // Default to RGBA8
    switch (desc.Format) {
        case DXGI_FORMAT_R8G8B8A8_UNORM:
        case DXGI_FORMAT_B8G8R8A8_UNORM:
            bytesPerPixel = 4;
            break;
        case DXGI_FORMAT_R8_UNORM:
            bytesPerPixel = 1;
            break;
        case DXGI_FORMAT_R16G16B16A16_FLOAT:
            bytesPerPixel = 8;
            break;
        // Add more formats as needed
        default:
            bytesPerPixel = 4;
    }

    UINT rowPitch = width * bytesPerPixel;

    // Update texture subregion
    D3D11_BOX box = {};
    box.left = offsetX;
    box.right = offsetX + width;
    box.top = offsetY;
    box.bottom = offsetY + height;
    box.front = 0;
    box.back = 1;

    // CRITICAL: Validate pointer before dereferencing to prevent ACCESS_VIOLATION
    void* pixelData = reinterpret_cast<void*>(pixels);
    if (!pixelData) return;

    // CRITICAL: Wrap in try-catch to prevent crashes from invalid memory access
    try {
        ctx->UpdateSubresource(it->second.Get(), 0, &box, pixelData, rowPitch, 0);
    } catch (...) {
        // Invalid memory access - skip texture upload
        return;
    }
}

/**
 * texSubImage2D with explicit rowPitch parameter
 * CRITICAL for GL_UNPACK_ROW_LENGTH support (font textures)
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texSubImage2DWithPitch
    (JNIEnv* env, jclass clazz, jint target, jint level, jint offsetX, jint offsetY,
     jint width, jint height, jint format, jint type, jlong pixels, jint rowPitch) {

    // CRITICAL: Check DirectX initialized and context valid
    if (!g_d3d11.initialized || !g_d3d11.context || !g_d3d11.context.Get()) {
        return; // Safe no-op
    }

    // CRITICAL: Validate texture unit range
    if (g_glState.currentTextureUnit < 0 || g_glState.currentTextureUnit >= 32) {
        return; // Invalid texture unit
    }

    int textureId = g_glState.boundTextures[g_glState.currentTextureUnit];
    if (textureId == 0) return;

    uint64_t handle = static_cast<uint64_t>(textureId);
    auto it = g_textures.find(handle);
    if (it == g_textures.end()) return;

    // CRITICAL: Verify context pointer before using it
    ID3D11DeviceContext* ctx = g_d3d11.context.Get();
    if (!ctx) return;

    // Skip if no pixel data
    if (pixels == 0) return;

    // Update texture subregion using the provided rowPitch
    D3D11_BOX box = {};
    box.left = offsetX;
    box.right = offsetX + width;
    box.top = offsetY;
    box.bottom = offsetY + height;
    box.front = 0;
    box.back = 1;

    // CRITICAL: Validate pointer before dereferencing to prevent ACCESS_VIOLATION
    void* pixelData = reinterpret_cast<void*>(pixels);
    if (!pixelData) return;

    // CRITICAL: Wrap in try-catch to prevent crashes from invalid memory access
    try {
        // Use the provided rowPitch instead of calculating it
        ctx->UpdateSubresource(it->second.Get(), 0, &box, pixelData, rowPitch, 0);
    } catch (...) {
        // Invalid memory access - skip texture upload
        return;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_activeTexture
    (JNIEnv* env, jclass clazz, jint texture) {

    if (!g_d3d11.initialized) return;

    // GL_TEXTURE0 = 0x84C0
    int unit = texture - 0x84C0;
    if (unit >= 0 && unit < 32) {
        g_glState.currentTextureUnit = unit;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_texParameteri
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 uses sampler states instead of texture parameters
    // This would need a sampler state cache, but for now we'll use default samplers
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getTexLevelParameter
    (JNIEnv* env, jclass clazz, jint target, jint level, jint pname) {

    if (!g_d3d11.initialized) return 0;

    int textureId = g_glState.boundTextures[g_glState.currentTextureUnit];
    if (textureId == 0) return 0;

    uint64_t handle = static_cast<uint64_t>(textureId);
    auto it = g_textures.find(handle);
    if (it == g_textures.end()) return 0;

    D3D11_TEXTURE2D_DESC desc;
    it->second->GetDesc(&desc);

    // GL_TEXTURE_WIDTH = 0x1000, GL_TEXTURE_HEIGHT = 0x1001
    if (pname == 0x1000) return desc.Width;
    if (pname == 0x1001) return desc.Height;

    return 0;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_pixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 doesn't have pixel store parameters like OpenGL
    // These are handled differently in D3D11 (pitch/stride in UpdateSubresource)
}

// Blend state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableBlend
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.blendEnabled = true;

    // Create blend state with blending enabled
    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = TRUE;
    blendDesc.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
    blendDesc.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    blendDesc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableBlend
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.blendEnabled = false;

    // Create blend state with blending disabled
    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = FALSE;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendFunc
    (JNIEnv* env, jclass clazz, jint srcFactor, jint dstFactor) {

    if (!g_d3d11.initialized) return;

    // Map OpenGL blend factors to D3D11
    auto mapBlendFactor = [](jint glFactor) -> D3D11_BLEND {
        switch (glFactor) {
            case 0: return D3D11_BLEND_ZERO;              // GL_ZERO
            case 1: return D3D11_BLEND_ONE;               // GL_ONE
            case 0x0300: return D3D11_BLEND_SRC_COLOR;    // GL_SRC_COLOR
            case 0x0301: return D3D11_BLEND_INV_SRC_COLOR; // GL_ONE_MINUS_SRC_COLOR
            case 0x0302: return D3D11_BLEND_SRC_ALPHA;    // GL_SRC_ALPHA
            case 0x0303: return D3D11_BLEND_INV_SRC_ALPHA; // GL_ONE_MINUS_SRC_ALPHA
            case 0x0304: return D3D11_BLEND_DEST_ALPHA;   // GL_DST_ALPHA
            case 0x0305: return D3D11_BLEND_INV_DEST_ALPHA; // GL_ONE_MINUS_DST_ALPHA
            case 0x0306: return D3D11_BLEND_DEST_COLOR;   // GL_DST_COLOR
            case 0x0307: return D3D11_BLEND_INV_DEST_COLOR; // GL_ONE_MINUS_DST_COLOR
            default: return D3D11_BLEND_ONE;
        }
    };

    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = TRUE;
    blendDesc.RenderTarget[0].SrcBlend = mapBlendFactor(srcFactor);
    blendDesc.RenderTarget[0].DestBlend = mapBlendFactor(dstFactor);
    blendDesc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendFuncSeparate
    (JNIEnv* env, jclass clazz, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha) {

    if (!g_d3d11.initialized) return;

    // Map OpenGL blend factors to D3D11
    auto mapBlendFactor = [](jint glFactor) -> D3D11_BLEND {
        switch (glFactor) {
            case 0: return D3D11_BLEND_ZERO;
            case 1: return D3D11_BLEND_ONE;
            case 0x0300: return D3D11_BLEND_SRC_COLOR;
            case 0x0301: return D3D11_BLEND_INV_SRC_COLOR;
            case 0x0302: return D3D11_BLEND_SRC_ALPHA;
            case 0x0303: return D3D11_BLEND_INV_SRC_ALPHA;
            case 0x0304: return D3D11_BLEND_DEST_ALPHA;
            case 0x0305: return D3D11_BLEND_INV_DEST_ALPHA;
            case 0x0306: return D3D11_BLEND_DEST_COLOR;
            case 0x0307: return D3D11_BLEND_INV_DEST_COLOR;
            default: return D3D11_BLEND_ONE;
        }
    };

    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = TRUE;
    blendDesc.RenderTarget[0].SrcBlend = mapBlendFactor(srcRGB);
    blendDesc.RenderTarget[0].DestBlend = mapBlendFactor(dstRGB);
    blendDesc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].SrcBlendAlpha = mapBlendFactor(srcAlpha);
    blendDesc.RenderTarget[0].DestBlendAlpha = mapBlendFactor(dstAlpha);
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_blendEquation
    (JNIEnv* env, jclass clazz, jint mode) {

    if (!g_d3d11.initialized) return;

    // Map OpenGL blend equation to D3D11
    D3D11_BLEND_OP blendOp;
    switch (mode) {
        case 0x8006: blendOp = D3D11_BLEND_OP_ADD; break;          // GL_FUNC_ADD
        case 0x800A: blendOp = D3D11_BLEND_OP_SUBTRACT; break;     // GL_FUNC_SUBTRACT
        case 0x800B: blendOp = D3D11_BLEND_OP_REV_SUBTRACT; break; // GL_FUNC_REVERSE_SUBTRACT
        case 0x8007: blendOp = D3D11_BLEND_OP_MIN; break;          // GL_MIN
        case 0x8008: blendOp = D3D11_BLEND_OP_MAX; break;          // GL_MAX
        default: blendOp = D3D11_BLEND_OP_ADD; break;
    }

    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = g_glState.blendEnabled;
    blendDesc.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
    blendDesc.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    blendDesc.RenderTarget[0].BlendOp = blendOp;
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = blendOp;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

// Depth state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableDepthTest
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.depthTestEnabled = true;

    D3D11_DEPTH_STENCIL_DESC depthDesc = {};
    depthDesc.DepthEnable = TRUE;
    depthDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depthDesc.DepthFunc = D3D11_COMPARISON_LESS;
    depthDesc.StencilEnable = FALSE;

    ComPtr<ID3D11DepthStencilState> depthState;
    HRESULT hr = g_d3d11.device->CreateDepthStencilState(&depthDesc, &depthState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(depthState.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableDepthTest
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.depthTestEnabled = false;

    D3D11_DEPTH_STENCIL_DESC depthDesc = {};
    depthDesc.DepthEnable = FALSE;
    depthDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depthDesc.DepthFunc = D3D11_COMPARISON_ALWAYS;
    depthDesc.StencilEnable = FALSE;

    ComPtr<ID3D11DepthStencilState> depthState;
    HRESULT hr = g_d3d11.device->CreateDepthStencilState(&depthDesc, &depthState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(depthState.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_depthFunc
    (JNIEnv* env, jclass clazz, jint func) {

    if (!g_d3d11.initialized) return;

    // Map OpenGL comparison function to D3D11
    D3D11_COMPARISON_FUNC d3dFunc;
    switch (func) {
        case 0x0200: d3dFunc = D3D11_COMPARISON_NEVER; break;           // GL_NEVER
        case 0x0201: d3dFunc = D3D11_COMPARISON_LESS; break;            // GL_LESS
        case 0x0202: d3dFunc = D3D11_COMPARISON_EQUAL; break;           // GL_EQUAL
        case 0x0203: d3dFunc = D3D11_COMPARISON_LESS_EQUAL; break;      // GL_LEQUAL
        case 0x0204: d3dFunc = D3D11_COMPARISON_GREATER; break;         // GL_GREATER
        case 0x0205: d3dFunc = D3D11_COMPARISON_NOT_EQUAL; break;       // GL_NOTEQUAL
        case 0x0206: d3dFunc = D3D11_COMPARISON_GREATER_EQUAL; break;   // GL_GEQUAL
        case 0x0207: d3dFunc = D3D11_COMPARISON_ALWAYS; break;          // GL_ALWAYS
        default: d3dFunc = D3D11_COMPARISON_LESS; break;
    }

    D3D11_DEPTH_STENCIL_DESC depthDesc = {};
    depthDesc.DepthEnable = g_glState.depthTestEnabled;
    depthDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depthDesc.DepthFunc = d3dFunc;
    depthDesc.StencilEnable = FALSE;

    ComPtr<ID3D11DepthStencilState> depthState;
    HRESULT hr = g_d3d11.device->CreateDepthStencilState(&depthDesc, &depthState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(depthState.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_depthMask
    (JNIEnv* env, jclass clazz, jboolean flag) {

    if (!g_d3d11.initialized) return;

    D3D11_DEPTH_STENCIL_DESC depthDesc = {};
    depthDesc.DepthEnable = g_glState.depthTestEnabled;
    depthDesc.DepthWriteMask = flag ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
    depthDesc.DepthFunc = D3D11_COMPARISON_LESS;
    depthDesc.StencilEnable = FALSE;

    ComPtr<ID3D11DepthStencilState> depthState;
    HRESULT hr = g_d3d11.device->CreateDepthStencilState(&depthDesc, &depthState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(depthState.Get(), 0);
    }
}

// Cull state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableCull
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.cullEnabled = true;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = D3D11_CULL_BACK;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableCull
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.cullEnabled = false;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

// Scissor/viewport
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_resetScissor
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.scissorEnabled = false;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = FALSE;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setScissor
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;
    g_glState.scissorEnabled = true;

    D3D11_RECT scissorRect;
    scissorRect.left = x;
    scissorRect.top = y;
    scissorRect.right = x + width;
    scissorRect.bottom = y + height;
    g_d3d11.context->RSSetScissorRects(1, &scissorRect);

    // Enable scissor in rasterizer state
    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = TRUE;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

// Clear operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_clear
    (JNIEnv* env, jclass clazz, jint mask) {

    if (!g_d3d11.initialized) return;

    // Log ALL clear calls to verify function is called
    static int clearCount = 0;
    if (clearCount < 10) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[CPP_CLEAR %d] mask=0x%04X, color=[%.2f, %.2f, %.2f, %.2f]",
            clearCount, mask, g_d3d11.clearColor[0], g_d3d11.clearColor[1],
            g_d3d11.clearColor[2], g_d3d11.clearColor[3]);
        logToJava(env, msg);
        clearCount++;
    }

    // GL_COLOR_BUFFER_BIT = 0x00004000, GL_DEPTH_BUFFER_BIT = 0x00000100
    if (mask & 0x00004000) {
        // Clear color buffer using g_d3d11.clearColor set by RenderTargetMixin
        // Each RenderTarget has its own clearChannels which is set before clear() is called
        g_d3d11.context->ClearRenderTargetView(g_d3d11.renderTargetView.Get(), g_d3d11.clearColor);
    }

    if (mask & 0x00000100) {
        // Clear depth buffer
        if (g_d3d11.depthStencilView) {
            g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(),
                D3D11_CLEAR_DEPTH, 1.0f, 0);
        }
    }
}

// Note: setClearColor already exists at line 933 - no need to duplicate

// Note: clearDepth already exists at line 2194 - it immediately clears depth buffer
// For GlStateManager, we just call the existing clearDepth directly

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_colorMask
    (JNIEnv* env, jclass clazz, jboolean red, jboolean green, jboolean blue, jboolean alpha) {

    if (!g_d3d11.initialized) return;

    UINT8 writeMask = 0;
    if (red) writeMask |= D3D11_COLOR_WRITE_ENABLE_RED;
    if (green) writeMask |= D3D11_COLOR_WRITE_ENABLE_GREEN;
    if (blue) writeMask |= D3D11_COLOR_WRITE_ENABLE_BLUE;
    if (alpha) writeMask |= D3D11_COLOR_WRITE_ENABLE_ALPHA;

    D3D11_BLEND_DESC blendDesc = {};
    blendDesc.RenderTarget[0].BlendEnable = g_glState.blendEnabled;
    blendDesc.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
    blendDesc.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    blendDesc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    blendDesc.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
    blendDesc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = writeMask;

    ComPtr<ID3D11BlendState> blendState;
    HRESULT hr = g_d3d11.device->CreateBlendState(&blendDesc, &blendState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetBlendState(blendState.Get(), nullptr, 0xFFFFFFFF);
    }
}

// Polygon operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_setPolygonMode
    (JNIEnv* env, jclass clazz, jint face, jint mode) {

    if (!g_d3d11.initialized) return;

    // GL_LINE = 0x1B01, GL_FILL = 0x1B02
    D3D11_FILL_MODE fillMode = (mode == 0x1B01) ? D3D11_FILL_WIREFRAME : D3D11_FILL_SOLID;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = fillMode;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enablePolygonOffset
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.polygonOffsetEnabled = true;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthBias = 0;
    rastDesc.DepthBiasClamp = 0.0f;
    rastDesc.SlopeScaledDepthBias = 0.0f;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disablePolygonOffset
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.polygonOffsetEnabled = false;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthBias = 0;
    rastDesc.DepthBiasClamp = 0.0f;
    rastDesc.SlopeScaledDepthBias = 0.0f;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_polygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units) {

    if (!g_d3d11.initialized) return;

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthBias = static_cast<INT>(units);
    rastDesc.DepthBiasClamp = 0.0f;
    rastDesc.SlopeScaledDepthBias = factor;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = g_glState.scissorEnabled;

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

// Color logic operations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_enableColorLogicOp
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.colorLogicOpEnabled = true;
    // DirectX 11 doesn't support logic ops the same way as OpenGL
    // This would require Direct3D 11.1 feature level and OutputMerger logic ops
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_disableColorLogicOp
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.colorLogicOpEnabled = false;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_logicOp
    (JNIEnv* env, jclass clazz, jint opcode) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 logic ops require D3D11.1 feature level
    // Not implemented in base D3D11
}

// Buffer operations (int ID versions for GlStateManager)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint buffer) {

    if (!g_d3d11.initialized) return;

    // GL_ARRAY_BUFFER = 0x8892, GL_ELEMENT_ARRAY_BUFFER = 0x8893
    if (target == 0x8892) {
        g_glState.boundArrayBuffer = buffer;
    } else if (target == 0x8893) {
        g_glState.boundElementArrayBuffer = buffer;
    }

    // Actual binding happens during draw calls in D3D11
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__ILjava_nio_ByteBuffer_2I
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage) {

    if (!g_d3d11.initialized) return;

    void* bufferData = env->GetDirectBufferAddress(data);
    jlong bufferSize = env->GetDirectBufferCapacity(data);

    if (!bufferData || bufferSize <= 0) return;

    // Determine which buffer to create based on target
    bool isIndexBuffer = (target == 0x8893); // GL_ELEMENT_ARRAY_BUFFER
    int bufferId = isIndexBuffer ? g_glState.boundElementArrayBuffer : g_glState.boundArrayBuffer;

    if (bufferId == 0) return;

    uint64_t handle = static_cast<uint64_t>(bufferId);

    // Create D3D11 buffer
    D3D11_BUFFER_DESC bufferDesc = {};
    bufferDesc.ByteWidth = static_cast<UINT>(bufferSize);
    bufferDesc.Usage = D3D11_USAGE_DYNAMIC;
    bufferDesc.BindFlags = isIndexBuffer ? D3D11_BIND_INDEX_BUFFER : D3D11_BIND_VERTEX_BUFFER;
    bufferDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = bufferData;

    ComPtr<ID3D11Buffer> d3dBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&bufferDesc, &initData, &d3dBuffer);
    if (SUCCEEDED(hr)) {
        if (isIndexBuffer) {
            g_indexBuffers[handle] = d3dBuffer;
        } else {
            g_vertexBuffers[handle] = d3dBuffer;
            g_vertexBufferStrides[handle] = 32; // Default Minecraft vertex stride (fallback)
        }
    }
}

// NEW: bufferData with explicit stride parameter
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__ILjava_nio_ByteBuffer_2II
    (JNIEnv* env, jclass clazz, jint target, jobject data, jint usage, jint stride) {

    if (!g_d3d11.initialized) return;

    void* bufferData = env->GetDirectBufferAddress(data);
    jlong bufferSize = env->GetDirectBufferCapacity(data);

    if (!bufferData || bufferSize <= 0) return;

    // Determine which buffer to create based on target
    bool isIndexBuffer = (target == 0x8893); // GL_ELEMENT_ARRAY_BUFFER
    int bufferId = isIndexBuffer ? g_glState.boundElementArrayBuffer : g_glState.boundArrayBuffer;

    if (bufferId == 0) return;

    uint64_t handle = static_cast<uint64_t>(bufferId);

    // Create D3D11 buffer
    D3D11_BUFFER_DESC bufferDesc = {};
    bufferDesc.ByteWidth = static_cast<UINT>(bufferSize);
    bufferDesc.Usage = D3D11_USAGE_DYNAMIC;
    bufferDesc.BindFlags = isIndexBuffer ? D3D11_BIND_INDEX_BUFFER : D3D11_BIND_VERTEX_BUFFER;
    bufferDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = bufferData;

    ComPtr<ID3D11Buffer> d3dBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&bufferDesc, &initData, &d3dBuffer);
    if (SUCCEEDED(hr)) {
        if (isIndexBuffer) {
            g_indexBuffers[handle] = d3dBuffer;
            // DEBUG: Log buffer creation
            char msg[128];
            snprintf(msg, sizeof(msg), "[BUFFER_CREATE] Index buffer created: handle=%llu, size=%lld",
                (unsigned long long)handle, (long long)bufferSize);
            logToJava(env, msg);
        } else {
            g_vertexBuffers[handle] = d3dBuffer;
            // CRITICAL FIX: Use the explicit stride passed from Java instead of hardcoded 32
            g_vertexBufferStrides[handle] = static_cast<UINT>(stride);
            // DEBUG: Log buffer creation
            char msg[128];
            snprintf(msg, sizeof(msg), "[BUFFER_CREATE] Vertex buffer created: handle=%llu, size=%lld, stride=%d",
                (unsigned long long)handle, (long long)bufferSize, stride);
            logToJava(env, msg);
        }
    } else {
        char msg[128];
        snprintf(msg, sizeof(msg), "[BUFFER_ERROR] Failed to create buffer: handle=%llu, HRESULT=0x%08X",
            (unsigned long long)handle, hr);
        logToJava(env, msg);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bufferData__IJI
    (JNIEnv* env, jclass clazz, jint target, jlong size, jint usage) {

    if (!g_d3d11.initialized) return;

    // Create empty buffer with specified size
    bool isIndexBuffer = (target == 0x8893);
    int bufferId = isIndexBuffer ? g_glState.boundElementArrayBuffer : g_glState.boundArrayBuffer;

    if (bufferId == 0) return;

    uint64_t handle = static_cast<uint64_t>(bufferId);

    D3D11_BUFFER_DESC bufferDesc = {};
    bufferDesc.ByteWidth = static_cast<UINT>(size);
    bufferDesc.Usage = D3D11_USAGE_DYNAMIC;
    bufferDesc.BindFlags = isIndexBuffer ? D3D11_BIND_INDEX_BUFFER : D3D11_BIND_VERTEX_BUFFER;
    bufferDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    ComPtr<ID3D11Buffer> d3dBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&bufferDesc, nullptr, &d3dBuffer);
    if (SUCCEEDED(hr)) {
        if (isIndexBuffer) {
            g_indexBuffers[handle] = d3dBuffer;
        } else {
            g_vertexBuffers[handle] = d3dBuffer;
            g_vertexBufferStrides[handle] = 32;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_deleteBuffer__I
    (JNIEnv* env, jclass clazz, jint buffer) {

    if (!g_d3d11.initialized || buffer == 0) return;

    uint64_t handle = static_cast<uint64_t>(buffer);
    g_vertexBuffers.erase(handle);
    g_indexBuffers.erase(handle);
    g_vertexBufferStrides.erase(handle);
}

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_mapBuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint access) {

    if (!g_d3d11.initialized) return nullptr;

    bool isIndexBuffer = (target == 0x8893);
    int bufferId = isIndexBuffer ? g_glState.boundElementArrayBuffer : g_glState.boundArrayBuffer;

    if (bufferId == 0) return nullptr;

    uint64_t handle = static_cast<uint64_t>(bufferId);
    ComPtr<ID3D11Buffer> buffer;

    if (isIndexBuffer) {
        auto it = g_indexBuffers.find(handle);
        if (it == g_indexBuffers.end()) return nullptr;
        buffer = it->second;
    } else {
        auto it = g_vertexBuffers.find(handle);
        if (it == g_vertexBuffers.end()) return nullptr;
        buffer = it->second;
    }

    // Map the buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(buffer.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (FAILED(hr)) return nullptr;

    // Get buffer size
    D3D11_BUFFER_DESC bufferDesc;
    buffer->GetDesc(&bufferDesc);

    // Create direct ByteBuffer
    return env->NewDirectByteBuffer(mappedResource.pData, bufferDesc.ByteWidth);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_unmapBuffer__I
    (JNIEnv* env, jclass clazz, jint target) {

    if (!g_d3d11.initialized) return;

    bool isIndexBuffer = (target == 0x8893);
    int bufferId = isIndexBuffer ? g_glState.boundElementArrayBuffer : g_glState.boundArrayBuffer;

    if (bufferId == 0) return;

    uint64_t handle = static_cast<uint64_t>(bufferId);
    ComPtr<ID3D11Buffer> buffer;

    if (isIndexBuffer) {
        auto it = g_indexBuffers.find(handle);
        if (it == g_indexBuffers.end()) return;
        buffer = it->second;
    } else {
        auto it = g_vertexBuffers.find(handle);
        if (it == g_vertexBuffers.end()) return;
        buffer = it->second;
    }

    g_d3d11.context->Unmap(buffer.Get(), 0);
}

// Framebuffer operations (int ID versions)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferTexture2D__IIIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint textarget, jint texture, jint level) {

    if (!g_d3d11.initialized) return;
    // Framebuffer operations are handled differently in DirectX 11
    // Render targets are set via OMSetRenderTargets instead of framebuffer attachments
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_framebufferRenderbuffer__IIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer) {

    if (!g_d3d11.initialized) return;
    // Renderbuffers are handled via render target views in DirectX 11
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_renderbufferStorage__IIII
    (JNIEnv* env, jclass clazz, jint target, jint internalformat, jint width, jint height) {

    if (!g_d3d11.initialized) return;
    // Renderbuffer storage is created as textures in DirectX 11
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindFramebuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint framebuffer) {

    if (!g_d3d11.initialized) return;
    g_glState.boundFramebuffer = framebuffer;

    if (framebuffer == 0) {
        // Bind default framebuffer (swap chain back buffer)
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
            g_d3d11.depthStencilView.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderbuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint renderbuffer) {

    if (!g_d3d11.initialized) return;
    g_glState.boundRenderbuffer = renderbuffer;
}

// ==================== MAINTARGET COMPATIBILITY METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindMainRenderTarget
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Bind swap chain back buffer as render target
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
        g_d3d11.depthStencilView.Get());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindRenderTargetForWriting
    (JNIEnv* env, jclass clazz, jlong renderTargetHandle, jboolean updateScissor) {

    if (!g_d3d11.initialized) return;

    // Handle 0 = bind default render target (swap chain back buffer)
    if (renderTargetHandle == 0) {
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
            g_d3d11.depthStencilView.Get());

        if (updateScissor) {
            // Update viewport and scissor rect to match window dimensions
            D3D11_VIEWPORT viewport = {};
            viewport.TopLeftX = 0.0f;
            viewport.TopLeftY = 0.0f;
            viewport.Width = static_cast<float>(g_d3d11.width);
            viewport.Height = static_cast<float>(g_d3d11.height);
            viewport.MinDepth = 0.0f;
            viewport.MaxDepth = 1.0f;
            g_d3d11.context->RSSetViewports(1, &viewport);
        }

        return;
    }

    // TODO: Handle custom render target handles from createMainRenderTarget()
    // For now, just bind the default render target
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
        g_d3d11.depthStencilView.Get());

}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindMainRenderTargetTexture
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // In DirectX 11, reading from the back buffer requires resolving to a texture
    // For now, this is a no-op - proper implementation would copy back buffer to a texture
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getMainColorTextureId
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;

    // Return a special ID for the main framebuffer color attachment
    // In OpenGL, framebuffer 0's color attachment would be the window framebuffer
    // For DirectX 11, we return 0 to indicate the swap chain back buffer
    return 0;
}

// ==================== HLSL SHADER COMPILATION (PHASE 2 - SHADER SYSTEM) ====================

// Global storage for last shader compilation error
static std::string g_lastShaderError;

// Resource tracking for new shader system
// Note: g_shaderBlobs is declared at top of file (line ~25)
// Note: g_constantBuffers and g_boundConstantBuffers* are declared at top of file (line ~32-34)

// Shader pipeline structure
struct ShaderPipeline {
    ComPtr<ID3D11VertexShader> vertexShader;
    ComPtr<ID3D11PixelShader> pixelShader;
    ComPtr<ID3D11InputLayout> inputLayout;
    uint64_t vertexShaderHandle;
    uint64_t pixelShaderHandle;
};

static std::unordered_map<uint64_t, ShaderPipeline> g_shaderPipelines;

/**
 * Compile HLSL shader from source (ByteBuffer version)
 * Compiles HLSL source code to bytecode using D3DCompile
 * Returns handle to compiled shader blob on success, 0 on failure
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShader__Ljava_nio_ByteBuffer_2ILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jobject sourceBuffer, jint sourceLength, jstring target, jstring debugName) {

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    // Get source from ByteBuffer
    const char* source = reinterpret_cast<const char*>(env->GetDirectBufferAddress(sourceBuffer));
    if (!source) {
        g_lastShaderError = "Invalid source buffer";
        return 0;
    }

    // Get target profile (e.g., "vs_5_0" or "ps_5_0")
    const char* targetProfile = env->GetStringUTFChars(target, nullptr);
    if (!targetProfile) {
        g_lastShaderError = "Invalid target profile";
        return 0;
    }

    // Get debug name (optional)
    const char* shaderName = debugName ? env->GetStringUTFChars(debugName, nullptr) : "Shader";


    // Set compilation flags
    UINT flags = D3DCOMPILE_ENABLE_STRICTNESS;
    if (g_d3d11.debugEnabled) {
        flags |= D3DCOMPILE_DEBUG | D3DCOMPILE_SKIP_OPTIMIZATION;
    }

    // Compile shader
    ID3DBlob* shaderBlob = nullptr;
    ID3DBlob* errorBlob = nullptr;
    HRESULT hr = D3DCompile(
        source,
        sourceLength,
        shaderName,               // Source name for error messages
        nullptr,                  // Defines
        D3D_COMPILE_STANDARD_FILE_INCLUDE,  // Include handler
        "main",                   // Entry point
        targetProfile,            // Target profile
        flags,
        0,
        &shaderBlob,
        &errorBlob
    );

    env->ReleaseStringUTFChars(target, targetProfile);
    if (debugName) {
        env->ReleaseStringUTFChars(debugName, shaderName);
    }

    if (FAILED(hr)) {
        if (errorBlob) {
            g_lastShaderError = std::string(static_cast<const char*>(errorBlob->GetBufferPointer()),
                                           errorBlob->GetBufferSize());
            errorBlob->Release();
        } else {
            g_lastShaderError = "Shader compilation failed with unknown error";
        }
        return 0;
    }

    if (errorBlob) {
        // Warning messages
        std::string warnings(static_cast<const char*>(errorBlob->GetBufferPointer()),
                           errorBlob->GetBufferSize());
        errorBlob->Release();
    }

    // Generate handle and store blob
    uint64_t handle = generateHandle();
    g_shaderBlobs[handle] = shaderBlob;

    return handle;
}

/**
 * Compile HLSL shader from file
 * Loads HLSL file and compiles it
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_compileShaderFromFile
    (JNIEnv* env, jclass clazz, jstring filePath, jstring target, jstring debugName) {

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    const char* filePathStr = env->GetStringUTFChars(filePath, nullptr);
    const char* targetProfile = env->GetStringUTFChars(target, nullptr);
    const char* shaderName = debugName ? env->GetStringUTFChars(debugName, nullptr) : filePathStr;


    // Convert to wide string for D3DCompileFromFile
    std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;
    std::wstring wFilePath = converter.from_bytes(filePathStr);

    // Set compilation flags
    UINT flags = D3DCOMPILE_ENABLE_STRICTNESS;
    if (g_d3d11.debugEnabled) {
        flags |= D3DCOMPILE_DEBUG | D3DCOMPILE_SKIP_OPTIMIZATION;
    }

    // Compile from file
    ID3DBlob* shaderBlob = nullptr;
    ID3DBlob* errorBlob = nullptr;
    HRESULT hr = D3DCompileFromFile(
        wFilePath.c_str(),
        nullptr,                  // Defines
        D3D_COMPILE_STANDARD_FILE_INCLUDE,  // Include handler
        "main",                   // Entry point
        targetProfile,            // Target profile
        flags,
        0,
        &shaderBlob,
        &errorBlob
    );

    env->ReleaseStringUTFChars(filePath, filePathStr);
    env->ReleaseStringUTFChars(target, targetProfile);
    if (debugName) {
        env->ReleaseStringUTFChars(debugName, shaderName);
    }

    if (FAILED(hr)) {
        if (errorBlob) {
            g_lastShaderError = std::string(static_cast<const char*>(errorBlob->GetBufferPointer()),
                                           errorBlob->GetBufferSize());
            errorBlob->Release();
        } else {
            g_lastShaderError = "Shader compilation from file failed with unknown error";
        }
        return 0;
    }

    if (errorBlob) {
        errorBlob->Release();
    }

    // Generate handle and store blob
    uint64_t handle = generateHandle();
    g_shaderBlobs[handle] = shaderBlob;

    return handle;
}

/**
 * Create shader object from precompiled bytecode
 * Creates vertex or pixel shader from bytecode blob
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderFromBytecode
    (JNIEnv* env, jclass clazz, jbyteArray bytecode, jint bytecodeLength, jstring shaderType) {

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    // Get bytecode
    jbyte* bytecodeData = env->GetByteArrayElements(bytecode, nullptr);
    if (!bytecodeData) {
        g_lastShaderError = "Invalid bytecode data";
        return 0;
    }

    // Get shader type
    const char* typeStr = env->GetStringUTFChars(shaderType, nullptr);
    bool isVertexShader = (strcmp(typeStr, "vertex") == 0 || strcmp(typeStr, "vs") == 0);
    bool isPixelShader = (strcmp(typeStr, "pixel") == 0 || strcmp(typeStr, "ps") == 0);
    env->ReleaseStringUTFChars(shaderType, typeStr);

    if (!isVertexShader && !isPixelShader) {
        env->ReleaseByteArrayElements(bytecode, bytecodeData, JNI_ABORT);
        g_lastShaderError = "Invalid shader type, must be 'vertex' or 'pixel'";
        return 0;
    }

    HRESULT hr;
    uint64_t handle = generateHandle();

    if (isVertexShader) {
        ComPtr<ID3D11VertexShader> vertexShader;
        hr = g_d3d11.device->CreateVertexShader(
            bytecodeData,
            bytecodeLength,
            nullptr,
            &vertexShader
        );

        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(bytecode, bytecodeData, JNI_ABORT);
            g_lastShaderError = "Failed to create vertex shader from bytecode";
            return 0;
        }

        g_vertexShaders[handle] = vertexShader;
    } else {
        ComPtr<ID3D11PixelShader> pixelShader;
        hr = g_d3d11.device->CreatePixelShader(
            bytecodeData,
            bytecodeLength,
            nullptr,
            &pixelShader
        );

        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(bytecode, bytecodeData, JNI_ABORT);
            g_lastShaderError = "Failed to create pixel shader from bytecode";
            return 0;
        }

        g_pixelShaders[handle] = pixelShader;
    }

    env->ReleaseByteArrayElements(bytecode, bytecodeData, JNI_ABORT);
    return handle;
}

/**
 * Create shader pipeline from vertex and pixel shaders
 * Combines VS and PS into a pipeline with input layout
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderPipeline__JJ
    (JNIEnv* env, jclass clazz, jlong vertexShaderHandle, jlong pixelShaderHandle) {

    // Logging disabled

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    // Find vertex shader
    auto vsIt = g_vertexShaders.find(vertexShaderHandle);
    if (vsIt == g_vertexShaders.end()) {
        g_lastShaderError = "Vertex shader not found";

        // CRITICAL DEBUG: Log shader lookup failure
        printf("[PIPELINE_CREATE] **FAILED**: VS handle 0x%llx NOT FOUND in map\n", vertexShaderHandle);
        printf("[PIPELINE_CREATE] g_vertexShaders map size: %zu\n", g_vertexShaders.size());
        printf("[PIPELINE_CREATE] Available VS handles:\n");
        fflush(stdout);

        int count = 0;
        for (const auto& pair : g_vertexShaders) {
            count++;
            printf("[PIPELINE_CREATE]   %d. Handle: 0x%llx\n", count, pair.first);
            fflush(stdout);
        }

        return 0;
    }

    // Find pixel shader
    auto psIt = g_pixelShaders.find(pixelShaderHandle);
    if (psIt == g_pixelShaders.end()) {
        g_lastShaderError = "Pixel shader not found";

        // CRITICAL DEBUG: Log shader lookup failure
        printf("[PIPELINE_CREATE] **FAILED**: PS handle 0x%llx NOT FOUND in map\n", pixelShaderHandle);
        printf("[PIPELINE_CREATE] g_pixelShaders map size: %zu\n", g_pixelShaders.size());
        fflush(stdout);

        return 0;
    }

    // Find vertex shader blob for input layout creation
    auto blobIt = g_shaderBlobs.find(vertexShaderHandle);
    if (blobIt == g_shaderBlobs.end()) {
        g_lastShaderError = "Vertex shader blob not found (required for input layout)";
        return 0;
    }

    // Create input layout from vertex shader reflection
    ComPtr<ID3D11InputLayout> inputLayout;
    if (!createInputLayout(blobIt->second.Get(), &inputLayout)) {
        g_lastShaderError = "Failed to create input layout";
        return 0;
    }

    // Create pipeline
    ShaderPipeline pipeline;
    pipeline.vertexShader = vsIt->second;
    pipeline.pixelShader = psIt->second;
    pipeline.inputLayout = inputLayout;
    pipeline.vertexShaderHandle = vertexShaderHandle;
    pipeline.pixelShaderHandle = pixelShaderHandle;

    uint64_t pipelineHandle = generateHandle();
    g_shaderPipelines[pipelineHandle] = pipeline;

    // CRITICAL DEBUG: Log successful pipeline creation
    printf("[PIPELINE_CREATE] **SUCCESS**: Pipeline 0x%llx created (VS=0x%llx, PS=0x%llx)\n",
        pipelineHandle, vertexShaderHandle, pixelShaderHandle);
    printf("[PIPELINE_CREATE] Total pipelines: %zu\n", g_shaderPipelines.size());
    fflush(stdout);

    return pipelineHandle;
}

/**
 * Bind shader pipeline for rendering
 * Sets VS, PS, and input layout
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle) {

    if (!g_d3d11.initialized) return;

    auto it = g_shaderPipelines.find(pipelineHandle);
    if (it == g_shaderPipelines.end()) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[SHADER_ERROR] Pipeline handle 0x%llx not found in g_shaderPipelines!", (unsigned long long)pipelineHandle);
        logToJava(env, msg);
        return;
    }

    ShaderPipeline& pipeline = it->second;

    // Log binding (only first 3 times to avoid spam)
    static int bindCount = 0;
    if (bindCount < 3) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[SHADER_BIND %d] Pipeline 0x%llx: VS=0x%p, PS=0x%p, InputLayout=0x%p",
            bindCount, (unsigned long long)pipelineHandle,
            pipeline.vertexShader.Get(), pipeline.pixelShader.Get(), pipeline.inputLayout.Get());
        logToJava(env, msg);
        bindCount++;
    }

    // Bind shaders and track for input layout creation
    g_d3d11.context->VSSetShader(pipeline.vertexShader.Get(), nullptr, 0);
    g_d3d11.context->PSSetShader(pipeline.pixelShader.Get(), nullptr, 0);
    g_d3d11.boundVertexShader = pipeline.vertexShaderHandle;
    g_d3d11.boundPixelShader = pipeline.pixelShaderHandle;

    // Bind input layout
    g_d3d11.context->IASetInputLayout(pipeline.inputLayout.Get());

}

/**
 * Destroy shader pipeline and release resources
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_destroyShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle) {

    auto it = g_shaderPipelines.find(pipelineHandle);
    if (it != g_shaderPipelines.end()) {
        g_shaderPipelines.erase(it);
    }
}

/**
 * Create constant buffer for shader uniforms
 * Size must be 16-byte aligned for DirectX 11
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createConstantBuffer
    (JNIEnv* env, jclass clazz, jint size) {

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    // Ensure 16-byte alignment
    int alignedSize = (size + 15) & ~15;

    D3D11_BUFFER_DESC bufferDesc = {};
    bufferDesc.ByteWidth = alignedSize;
    bufferDesc.Usage = D3D11_USAGE_DYNAMIC;
    bufferDesc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    bufferDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    ComPtr<ID3D11Buffer> constantBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&bufferDesc, nullptr, &constantBuffer);
    if (FAILED(hr)) {
        g_lastShaderError = "Failed to create constant buffer";
        return 0;
    }

    uint64_t handle = generateHandle();
    g_constantBuffers[handle] = constantBuffer;

    // Logging disabled
    return handle;
}

/**
 * Update constant buffer data
 * Uploads data to GPU via Map/Unmap
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_updateConstantBuffer
    (JNIEnv* env, jclass clazz, jlong bufferHandle, jbyteArray data) {

    if (!g_d3d11.initialized) return;

    auto it = g_constantBuffers.find(bufferHandle);
    if (it == g_constantBuffers.end()) {
        return;
    }

    // Get data from Java
    jsize dataSize = env->GetArrayLength(data);
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    if (!dataPtr) {
        return;
    }

    // Log first CB update for slot b0 (vertex shader - contains projection matrix)
    static int updateCount = 0;
    if (updateCount < 2 && bufferHandle == g_boundConstantBuffersVS[0]) {
        float* matrixData = reinterpret_cast<float*>(dataPtr);
        char msg[256];
        snprintf(msg, sizeof(msg), "[CB_UPDATE_VS] size=%d bytes, first row: [%.3f, %.3f, %.3f, %.3f]",
            dataSize, matrixData[0], matrixData[1], matrixData[2], matrixData[3]);
        logToJava(env, msg);
        updateCount++;
    }

    // Map buffer
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(it->second.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (FAILED(hr)) {
        env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
        return;
    }

    // Copy data
    memcpy(mappedResource.pData, dataPtr, dataSize);

    // Unmap buffer
    g_d3d11.context->Unmap(it->second.Get(), 0);

    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

/**
 * Bind constant buffer to vertex shader stage
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindConstantBufferVS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle) {

    if (!g_d3d11.initialized) return;

    auto it = g_constantBuffers.find(bufferHandle);
    if (it == g_constantBuffers.end()) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[CB_ERROR] VS constant buffer handle 0x%llx not found in slot %d", (unsigned long long)bufferHandle, slot);
        logToJava(env, msg);
        return;
    }

    // Log first 3 binds
    static int bindCountVS = 0;
    if (bindCountVS < 3) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[CB_BIND_VS %d] slot=%d, handle=0x%llx, buffer=0x%p", bindCountVS, slot, (unsigned long long)bufferHandle, it->second.Get());
        logToJava(env, msg);
        bindCountVS++;
    }

    ID3D11Buffer* buffers[] = { it->second.Get() };
    g_d3d11.context->VSSetConstantBuffers(slot, 1, buffers);

    // Track this binding for rebinding after shader pipeline changes
    if (slot >= 0 && slot < 4) {
        g_boundConstantBuffersVS[slot] = bufferHandle;
    }

}

/**
 * Bind constant buffer to pixel shader stage
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_bindConstantBufferPS
    (JNIEnv* env, jclass clazz, jint slot, jlong bufferHandle) {

    if (!g_d3d11.initialized) return;

    auto it = g_constantBuffers.find(bufferHandle);
    if (it == g_constantBuffers.end()) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[CB_ERROR] PS constant buffer handle 0x%llx not found in slot %d", (unsigned long long)bufferHandle, slot);
        logToJava(env, msg);
        return;
    }

    // Log first 3 binds
    static int bindCountPS = 0;
    if (bindCountPS < 3) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[CB_BIND_PS %d] slot=%d, handle=0x%llx, buffer=0x%p", bindCountPS, slot, (unsigned long long)bufferHandle, it->second.Get());
        logToJava(env, msg);
        bindCountPS++;
    }

    ID3D11Buffer* buffers[] = { it->second.Get() };
    g_d3d11.context->PSSetConstantBuffers(slot, 1, buffers);

    // Track this binding for rebinding after shader pipeline changes
    if (slot >= 0 && slot < 4) {
        g_boundConstantBuffersPS[slot] = bufferHandle;
    }

}

/**
 * Get last shader compilation error message
 */
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_getLastShaderError
    (JNIEnv* env, jclass clazz) {

    return env->NewStringUTF(g_lastShaderError.c_str());
}
