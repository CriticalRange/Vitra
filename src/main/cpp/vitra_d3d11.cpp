#include "vitra_d3d11.h"
// #include "renderdoc_app.h" // RenderDoc integration disabled - it intercepts debug layer
#include <iostream>
#include <sstream>
#include <random>
#include <locale>
#include <codecvt>
#include <d3d11_3.h>
#include <dxgi1_4.h>
#include <d3dcompiler.h>
#include <vector>
#include <set>
#include <string>
#include <mutex>

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

// ============================================================================
//  +  STYLE STATE MANAGEMENT
// ============================================================================
// CRITICAL: State caching to reduce redundant DirectX calls and prevent UI rendering failures
// Based on combined analysis of both rendering engines
struct CommittedD3D11State {
    // Shader state
    ID3D11VertexShader* pVertexShader = nullptr;
    ID3D11PixelShader*  pPixelShader = nullptr;
    ID3D11InputLayout*  pInputLayout = nullptr;

    // Constant buffer state
    ID3D11Buffer* pVSConstantBuffers[4] = { nullptr, nullptr, nullptr, nullptr };
    ID3D11Buffer* pPSConstantBuffers[4] = { nullptr, nullptr, nullptr, nullptr };

    // Texture state
    ID3D11ShaderResourceView* pShaderResources[16] = { nullptr };
    ID3D11SamplerState* pSamplers[8] = { nullptr };

    // Reset all state tracking
    void reset() {
        pVertexShader = nullptr;
        pPixelShader = nullptr;
        pInputLayout = nullptr;
        memset(pVSConstantBuffers, 0, sizeof(pVSConstantBuffers));
        memset(pPSConstantBuffers, 0, sizeof(pPSConstantBuffers));
        memset(pShaderResources, 0, sizeof(pShaderResources));
        memset(pSamplers, 0, sizeof(pSamplers));
    }
} g_committedState;

// ============================================================================
// THREAD SAFETY FOR D3D11 CONTEXT
// ============================================================================
// CRITICAL: ID3D11DeviceContext is NOT thread-safe (per Microsoft docs)
// Multiple threads must NOT call the same context simultaneously
// Reference: https://learn.microsoft.com/en-us/windows/win32/direct3d11/overviews-direct3d-11-render-multi-thread-intro
//
// This mutex protects ALL ID3D11DeviceContext operations
// ID3D11Device is thread-safe and does NOT need mutex protection
std::mutex g_d3d11ContextMutex;

// ============================================================================
// PIX GPU CAPTURER STATIC INITIALIZATION
// ============================================================================
// CRITICAL: Load WinPixGpuCapturer.dll BEFORE any DirectX calls
// This static initializer runs when the DLL is first loaded by Java
// Per Microsoft documentation: PIX DLL must be loaded before D3D11CreateDevice
static bool LoadPixGpuCapturer() {
    // Check if already loaded
    HMODULE pixModule = GetModuleHandleA("WinPixGpuCapturer.dll");
    if (pixModule) {
        OutputDebugStringA("[Vitra-PIX] ✓ WinPixGpuCapturer.dll already loaded");
        return true;
    }

    // Try to find and load PIX from Program Files
    const char* programFiles = getenv("ProgramFiles");
    if (!programFiles) {
        OutputDebugStringA("[Vitra-PIX] ✗ ProgramFiles environment variable not found");
        return false;
    }

    std::string pixBaseDir = std::string(programFiles) + "\\Microsoft PIX";

    // Find the directory with the highest version number
    WIN32_FIND_DATAA findData;
    std::string searchPath = pixBaseDir + "\\*";
    HANDLE hFind = FindFirstFileA(searchPath.c_str(), &findData);

    if (hFind == INVALID_HANDLE_VALUE) {
        OutputDebugStringA("[Vitra-PIX] ✗ PIX not found in Program Files - Install from https://devblogs.microsoft.com/pix/download/");
        return false;
    }

    std::string latestVersion;
    do {
        if (findData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            std::string dirName = findData.cFileName;
            if (dirName != "." && dirName != ".." && !dirName.empty()) {
                if (latestVersion.empty() || dirName > latestVersion) {
                    latestVersion = dirName;
                }
            }
        }
    } while (FindNextFileA(hFind, &findData));
    FindClose(hFind);

    if (latestVersion.empty()) {
        OutputDebugStringA("[Vitra-PIX] ✗ No PIX version found");
        return false;
    }

    std::string pixDllPath = pixBaseDir + "\\" + latestVersion + "\\WinPixGpuCapturer.dll";
    OutputDebugStringA(("[Vitra-PIX] Found PIX installation: " + pixDllPath).c_str());

    HMODULE loadedPix = LoadLibraryA(pixDllPath.c_str());
    if (loadedPix) {
        OutputDebugStringA("[Vitra-PIX] ✓ Successfully loaded WinPixGpuCapturer.dll");
        OutputDebugStringA("[Vitra-PIX] ✓ PIX GPU capture support ENABLED - You can now attach PIX to this process");
        return true;
    } else {
        DWORD error = GetLastError();
        char errorMsg[512];
        sprintf_s(errorMsg, "[Vitra-PIX] ✗ Failed to load WinPixGpuCapturer.dll! Error: %lu", error);
        OutputDebugStringA(errorMsg);
        return false;
    }
}

// Static initializer - runs when DLL is loaded, BEFORE JNI_OnLoad
static bool g_pixLoaded = LoadPixGpuCapturer();

// ============================================================================
// RENDERDOC IN-APPLICATION API - DISABLED
// ============================================================================
// RenderDoc intercepts the DirectX debug layer and returns dummy interfaces,
// which prevents us from seeing real DirectX errors. Disabled for debugging.
// To re-enable: uncomment the code below and the #include "renderdoc_app.h"
/*
static RENDERDOC_API_1_4_0 *rdoc_api = nullptr;
static bool LoadRenderDoc() { return false; }
static bool g_renderDocLoaded = false;
*/

// Resource tracking
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_vertexBuffers;

// Debug output helper for dbgview integration
void outputToDbgView(const char* severity, const char* category, int messageId, const char* description) {
    char dbgMsg[2048];
    snprintf(dbgMsg, sizeof(dbgMsg), "[Vitra-DX11] [%s] [%s] ID=%d: %s",
             severity, category, messageId, description);
    OutputDebugStringA(dbgMsg);
}
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_indexBuffers;
std::unordered_map<uint64_t, UINT> g_vertexBufferStrides; // Track stride for each vertex buffer
std::unordered_map<uint64_t, UINT> g_indexBufferSizes;    // Track size for each index buffer (VulkanMod-style)
std::unordered_map<uint64_t, UINT> g_vertexBufferSizes;   // Track size for each vertex buffer
std::unordered_map<uint64_t, ComPtr<ID3D11VertexShader>> g_vertexShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11PixelShader>> g_pixelShaders;
std::unordered_map<uint64_t, ComPtr<ID3DBlob>> g_shaderBlobs; // Shader bytecode blobs for input layout creation
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_inputLayouts;
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_vertexFormatInputLayouts; // Input layouts keyed by vertex format hash
std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> g_textures;
std::unordered_map<uint64_t, ComPtr<ID3D11ShaderResourceView>> g_shaderResourceViews;
std::unordered_map<uint64_t, ComPtr<ID3D11SamplerState>> g_textureSamplers; // Per-texture sampler states
std::unordered_map<uint64_t, ComPtr<ID3D11Query>> g_queries;

// Constant buffer tracking (for VulkanMod-style uniform system)
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_constantBuffers;
uint64_t g_boundConstantBuffersVS[4] = {0, 0, 0, 0};  // Track bound constant buffers for vertex shader (b0-b3)
uint64_t g_boundConstantBuffersPS[4] = {0, 0, 0, 0};  // Track bound constant buffers for pixel shader (b0-b3)

// Framebuffer Object (FBO) tracking - VulkanMod-style render target system
struct D3D11Framebuffer {
    ComPtr<ID3D11Texture2D> colorTexture;
    ComPtr<ID3D11RenderTargetView> colorRTV;
    ComPtr<ID3D11ShaderResourceView> colorSRV;
    ComPtr<ID3D11Texture2D> depthTexture;
    ComPtr<ID3D11DepthStencilView> depthDSV;
    int width = 0;
    int height = 0;
    bool hasColor = true;
    bool hasDepth = true;
};
std::unordered_map<int, D3D11Framebuffer> g_framebuffers;  // Map framebuffer ID → D3D11 resources

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
    bool depthMaskEnabled = true;  // Track depth write mask state
    bool cullEnabled = true;
    bool scissorEnabled = false;
    bool polygonOffsetEnabled = false;
    bool colorLogicOpEnabled = false;
};
static GlStateTracking g_glState = {};

// Texture sampler parameters (per-texture)
struct TextureSamplerParams {
    int minFilter = 0x2601; // GL_LINEAR
    int magFilter = 0x2601; // GL_LINEAR
    int wrapS = 0x812F;     // GL_CLAMP_TO_EDGE
    int wrapT = 0x812F;     // GL_CLAMP_TO_EDGE
    bool needsUpdate = true;
};
std::unordered_map<uint64_t, TextureSamplerParams> g_textureSamplerParams;

// Handle generator
std::random_device rd;
std::mt19937_64 gen(rd());
std::uniform_int_distribution<uint64_t> dis;

uint64_t generateHandle() {
    return dis(gen);
}

// Forward declarations
void logToJava(JNIEnv* env, const char* message);
bool validateTextureFormatSupport(DXGI_FORMAT format);
bool validateShaderCompatibility(ID3DBlob* vsBlob, ID3DBlob* psBlob);
jlong createDefaultShaderPipeline();

// Forward declare ShaderPipeline struct (defined later in shader compilation section)
struct ShaderPipeline {
    ComPtr<ID3D11VertexShader> vertexShader;
    ComPtr<ID3D11PixelShader> pixelShader;
    ComPtr<ID3D11InputLayout> inputLayout;
    uint64_t vertexShaderHandle;
    uint64_t pixelShaderHandle;
};
static std::unordered_map<uint64_t, ShaderPipeline> g_shaderPipelines;

// VulkanMod-style: Check if currently bound index buffer has enough capacity
// Returns the buffer COM pointer if valid, nullptr if check/resize failed
ComPtr<ID3D11Buffer> ensureIndexBufferCapacity(JNIEnv* env, UINT indexCount, UINT firstIndex) {
    // Get currently bound index buffer from device context
    ID3D11Buffer* pBoundBuffer = nullptr;
    DXGI_FORMAT format;
    UINT offset;
    g_d3d11.context->IAGetIndexBuffer(&pBoundBuffer, &format, &offset);

    if (!pBoundBuffer) {
        logToJava(env, "[BUFFER_CHECK] No index buffer bound!");
        return nullptr;  // No index buffer bound
    }

    // Calculate required size (2 bytes for R16_UINT, 4 bytes for R32_UINT)
    UINT bytesPerIndex = (format == DXGI_FORMAT_R16_UINT) ? 2 : 4;
    UINT requiredSize = (firstIndex + indexCount) * bytesPerIndex;

    // Get current buffer size
    D3D11_BUFFER_DESC desc;
    pBoundBuffer->GetDesc(&desc);
    UINT currentSize = desc.ByteWidth;

    // Always log buffer checks to debug why resize isn't happening
    char checkMsg[256];
    snprintf(checkMsg, sizeof(checkMsg), "[BUFFER_CHECK] Required=%u, Current=%u, IndexCount=%u, FirstIndex=%u",
        requiredSize, currentSize, indexCount, firstIndex);
    logToJava(env, checkMsg);

    if (requiredSize <= currentSize) {
        // Buffer is large enough
        ComPtr<ID3D11Buffer> result;
        result.Attach(pBoundBuffer);  // Transfer ownership
        return result;
    }

    // Buffer too small - need to resize (VulkanMod 2x growth pattern)
    UINT newSize = (currentSize + requiredSize) * 2;

    char msg[256];
    snprintf(msg, sizeof(msg), "[BUFFER_RESIZE] Index buffer too small! Reallocating from %u to %u bytes (indexCount=%u, firstIndex=%u)",
        currentSize, newSize, indexCount, firstIndex);
    logToJava(env, msg);

    // Create new larger buffer
    D3D11_BUFFER_DESC newDesc = desc;
    newDesc.ByteWidth = newSize;

    ComPtr<ID3D11Buffer> newBuffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&newDesc, nullptr, &newBuffer);

    if (FAILED(hr)) {
        logToJava(env, "[BUFFER_RESIZE] ✗ FAILED to create new buffer");
        pBoundBuffer->Release();
        return nullptr;
    }

    // Copy old buffer data to new buffer using CopySubresourceRegion
    // (Can't use Map with D3D11_MAP_READ on DYNAMIC buffers - they only support CPU_ACCESS_WRITE)
    D3D11_BOX srcBox;
    srcBox.left = 0;
    srcBox.right = currentSize;
    srcBox.top = 0;
    srcBox.bottom = 1;
    srcBox.front = 0;
    srcBox.back = 1;

    g_d3d11.context->CopySubresourceRegion(newBuffer.Get(), 0, 0, 0, 0, pBoundBuffer, 0, &srcBox);

    // Re-bind the new buffer
    g_d3d11.context->IASetIndexBuffer(newBuffer.Get(), format, offset);

    logToJava(env, "[BUFFER_RESIZE] ✓ Index buffer successfully resized and rebound");

    pBoundBuffer->Release();  // Release old buffer
    return newBuffer;
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
    // STRICT MODE: warnings are treated as errors
    UINT flags = D3DCOMPILE_WARNINGS_ARE_ERRORS;
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

    if (FAILED(hr) || errorBlob) {
        if (errorBlob) {
            const char* errorMsg = (const char*)errorBlob->GetBufferPointer();
            char logBuffer[2048];
            snprintf(logBuffer, sizeof(logBuffer), "[SHADER_COMPILE_FATAL] Target=%s, Error: %.1500s", target, errorMsg);
            OutputDebugStringA(logBuffer);

            // CRASH: Shader compilation must be perfect
            MessageBoxA(nullptr, logBuffer, "FATAL: Shader Compilation Failed", MB_OK | MB_ICONERROR);
            errorBlob->Release();
            std::terminate();
        }
        return false;
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
bool createInputLayoutFromVertexFormat(JNIEnv* env, const jint* vertexFormatDesc, jint descLength, ID3DBlob* vertexShaderBlob, ID3D11InputLayout** inputLayout) {
    char msg[512];

    sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Entry: descLength=%d, vsBlob=%p, inputLayout=%p", descLength, vertexShaderBlob, inputLayout);
    logToJava(env, msg);

    if (descLength < 1) {
        logToJava(env, "[INPUT_LAYOUT_CREATE] ERROR: descLength < 1");
        return false;
    }

    int elementCount = vertexFormatDesc[0];
    sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Element count: %d, required descLength: %d", elementCount, 1 + elementCount * 4);
    logToJava(env, msg);

    if (elementCount == 0 || descLength < 1 + elementCount * 4) {
        sprintf_s(msg, "[INPUT_LAYOUT_CREATE] ERROR: Invalid element count or descLength (elementCount=%d, descLength=%d)", elementCount, descLength);
        logToJava(env, msg);
        return false;
    }

    std::vector<D3D11_INPUT_ELEMENT_DESC> inputElements;

    // Track semantic indices for each semantic name (inspired by VulkanMod's location tracking)
    // This ensures: TEXCOORD0, TEXCOORD1, COLOR0, COLOR1, etc.
    std::unordered_map<std::string, UINT> semanticIndices;

    // Vertex format encoding: [elementCount, usage1, type1, count1, offset1, usage2, type2, count2, offset2, ...]
    // Usage: POSITION=0, COLOR=1, UV=2, NORMAL=3, etc. (VertexFormatElement.Usage ordinal)
    // Type: FLOAT=0, UBYTE=1, BYTE=2, USHORT=3, SHORT=4, UINT=5, INT=6 (VertexFormatElement.Type ordinal)

    for (int i = 0; i < elementCount; i++) {
        int baseIdx = 1 + i * 4;
        int usage = vertexFormatDesc[baseIdx];
        int type = vertexFormatDesc[baseIdx + 1];
        int count = vertexFormatDesc[baseIdx + 2];
        int offset = vertexFormatDesc[baseIdx + 3];

        sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Element %d: usage=%d, type=%d, count=%d, offset=%d", i, usage, type, count, offset);
        logToJava(env, msg);

        D3D11_INPUT_ELEMENT_DESC elementDesc = {};

        // Map usage to semantic name
        // Minecraft's VertexFormatElement.Usage actual runtime ordinals:
        // POSITION=0, COLOR=1, UV0=2, UV1=3, UV2=4, NORMAL=5, PADDING=6
        // NOTE: Usage=2 can be either UV or COLOR depending on the data type!
        const char* semanticName = nullptr;
        switch (usage) {
            case 0: semanticName = "POSITION"; break;
            case 1: semanticName = "COLOR"; break;
            case 2: // UV0 (FLOAT) or COLOR (UBYTE)
                if (type == 1 || type == 2) { // UBYTE or BYTE = COLOR
                    semanticName = "COLOR";
                } else { // FLOAT = UV
                    semanticName = "TEXCOORD";
                }
                break;
            case 3: // UV1 (lightmap/secondary UV)
                semanticName = "TEXCOORD";
                break;
            case 4: // UV2
                semanticName = "TEXCOORD";
                break;
            case 5: semanticName = "NORMAL"; break;
            case 6: semanticName = "PADDING"; break; // Padding
            case 7: semanticName = "TANGENT"; break;
            case 8: semanticName = "BITANGENT"; break;
            default: semanticName = "TEXCOORD"; break;
        }

        elementDesc.SemanticName = semanticName;

        // Automatically assign semantic index based on usage count
        // First TEXCOORD → index 0, second TEXCOORD → index 1, etc.
        // This matches what shaders expect and fixes the multiple TEXCOORD issue
        std::string semanticKey(semanticName);
        if (semanticIndices.find(semanticKey) == semanticIndices.end()) {
            semanticIndices[semanticKey] = 0;
        }
        elementDesc.SemanticIndex = semanticIndices[semanticKey];
        semanticIndices[semanticKey]++;

        sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Assigned semantic: %s%d", semanticName, elementDesc.SemanticIndex);
        logToJava(env, msg);

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
        // BGFX PATTERN: First element MUST have offset 0, subsequent elements use D3D11_APPEND_ALIGNED_ELEMENT
        // This lets D3D11 automatically calculate proper alignment instead of manual offset tracking
        if (i == 0) {
            elementDesc.AlignedByteOffset = 0;  // Force first element to 0 (bgfx pattern)
        } else {
            elementDesc.AlignedByteOffset = D3D11_APPEND_ALIGNED_ELEMENT;  // Let D3D11 auto-calculate alignment
        }
        elementDesc.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
        elementDesc.InstanceDataStepRate = 0;

        sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Element %d mapped: semantic=%s, semanticIndex=%d, format=%d, offset=%s",
            i, elementDesc.SemanticName, elementDesc.SemanticIndex, elementDesc.Format,
            (i == 0) ? "0" : "D3D11_APPEND_ALIGNED_ELEMENT");
        logToJava(env, msg);

        inputElements.push_back(elementDesc);
    }

    sprintf_s(msg, "[INPUT_LAYOUT_CREATE] Calling CreateInputLayout with %zu elements, shader blob size=%zu",
        inputElements.size(), vertexShaderBlob->GetBufferSize());
    logToJava(env, msg);

    // CRITICAL FIX: Try to create input layout, and if it fails due to shader-format mismatch, skip the draw gracefully
    // This handles cases where POSITION-only vertex data tries to use POSITION+TEXCOORD shader
    // D3DGetBlobPart already extracted the input signature from our compiled HLSL bytecode,
    // so CreateInputLayout can validate whether the vertex format matches the shader's expectations
    HRESULT hr = g_d3d11.device->CreateInputLayout(
        inputElements.data(),
        static_cast<UINT>(inputElements.size()),
        vertexShaderBlob->GetBufferPointer(),
        vertexShaderBlob->GetBufferSize(),
        inputLayout
    );

    if (FAILED(hr)) {
        // E_INVALIDARG (0x80070057) typically means shader-format mismatch
        if (hr == E_INVALIDARG) {
            sprintf_s(msg, "[INPUT_LAYOUT_COMPAT] Shader-format mismatch detected (HRESULT=0x%08X), skipping draw gracefully", hr);
            logToJava(env, msg);
            return false;  // Skip draw instead of logging error
        }

        sprintf_s(msg, "[INPUT_LAYOUT_CREATE] ERROR: CreateInputLayout FAILED with HRESULT=0x%08X", hr);
        logToJava(env, msg);
        return false;
    }

    sprintf_s(msg, "[INPUT_LAYOUT_CREATE] SUCCESS: Input layout created (handle=%p)", *inputLayout);
    logToJava(env, msg);
    return true;
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
    // Create two default shaders: one with TEXCOORD0 and one without
    // This fixes the shader linkage error when vertex data doesn't include UV coordinates

    // Shader 1: Full vertex format (POSITION + TEXCOORD0 + COLOR0)
    const char* vertexShaderWithUV = R"(
        // Constant buffer for transformation matrices (register b0)
        cbuffer TransformBuffer : register(b0) {
            float4x4 ModelViewProjection;
            float4x4 ModelView;
            float4x4 Projection;
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
            float4 worldPos = float4(input.pos, 1.0);
            output.pos = mul(ModelViewProjection, worldPos);
            output.tex = input.tex;
            output.color = input.color;
            return output;
        }
    )";

    // Shader 2: Minimal vertex format (POSITION + COLOR0 only)
    const char* vertexShaderWithoutUV = R"(
        // Constant buffer for transformation matrices (register b0)
        cbuffer TransformBuffer : register(b0) {
            float4x4 ModelViewProjection;
            float4x4 ModelView;
            float4x4 Projection;
        };

        struct VS_INPUT {
            float3 pos : POSITION;
            float4 color : COLOR0;
        };

        struct VS_OUTPUT {
            float4 pos : SV_POSITION;
            float4 color : COLOR0;
        };

        VS_OUTPUT main(VS_INPUT input) {
            VS_OUTPUT output;
            float4 worldPos = float4(input.pos, 1.0);
            output.pos = mul(ModelViewProjection, worldPos);
            output.color = input.color;
            return output;
        }
    )";

    // Pixel shader with texture sampling (for use with UV coordinates)
    const char* pixelShaderWithUV = R"(
        Texture2D texture0 : register(t0);
        SamplerState sampler0 : register(s0);

        struct PS_INPUT {
            float4 pos : SV_POSITION;
            float2 tex : TEXCOORD0;
            float4 color : COLOR0;
        };

        float4 main(PS_INPUT input) : SV_TARGET {
            float4 texColor = texture0.Sample(sampler0, input.tex);
            float4 finalColor = texColor * input.color;
            finalColor.a = saturate(finalColor.a);
            return finalColor;
        }
    )";

    // Pixel shader without texture sampling (for use without UV coordinates)
    const char* pixelShaderWithoutUV = R"(
        struct PS_INPUT {
            float4 pos : SV_POSITION;
            float4 color : COLOR0;
        };

        float4 main(PS_INPUT input) : SV_TARGET {
            return input.color;
        }
    )";

    // Compile and create shaders with UV coordinates
    ID3DBlob* vertexBlobWithUV = nullptr;
    if (compileShader(vertexShaderWithUV, "vs_4_0", &vertexBlobWithUV)) {
        HRESULT hr = g_d3d11.device->CreateVertexShader(
            vertexBlobWithUV->GetBufferPointer(),
            vertexBlobWithUV->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultVertexShaderWithUV
        );

        if (SUCCEEDED(hr)) {
            createInputLayout(vertexBlobWithUV, &g_d3d11.defaultInputLayoutWithUV);
        }
        vertexBlobWithUV->Release();
    }

    ID3DBlob* vertexBlobWithoutUV = nullptr;
    if (compileShader(vertexShaderWithoutUV, "vs_4_0", &vertexBlobWithoutUV)) {
        HRESULT hr = g_d3d11.device->CreateVertexShader(
            vertexBlobWithoutUV->GetBufferPointer(),
            vertexBlobWithoutUV->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultVertexShaderWithoutUV
        );

        if (SUCCEEDED(hr)) {
            createInputLayout(vertexBlobWithoutUV, &g_d3d11.defaultInputLayoutWithoutUV);
        }
        vertexBlobWithoutUV->Release();
    }

    ID3DBlob* pixelBlobWithUV = nullptr;
    if (compileShader(pixelShaderWithUV, "ps_4_0", &pixelBlobWithUV)) {
        g_d3d11.device->CreatePixelShader(
            pixelBlobWithUV->GetBufferPointer(),
            pixelBlobWithUV->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultPixelShaderWithUV
        );
        pixelBlobWithUV->Release();
    }

    ID3DBlob* pixelBlobWithoutUV = nullptr;
    if (compileShader(pixelShaderWithoutUV, "ps_4_0", &pixelBlobWithoutUV)) {
        g_d3d11.device->CreatePixelShader(
            pixelBlobWithoutUV->GetBufferPointer(),
            pixelBlobWithoutUV->GetBufferSize(),
            nullptr,
            &g_d3d11.defaultPixelShaderWithoutUV
        );
        pixelBlobWithoutUV->Release();
    }

    // Create default sampler state for texture sampling
    // CRITICAL FIX: Use CLAMP instead of WRAP to prevent UV coordinate wrapping artifacts
    // Minecraft textures (UI, blocks, panorama) need clamping to avoid seams and holes
    D3D11_SAMPLER_DESC samplerDesc = {};
    samplerDesc.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    samplerDesc.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDesc.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDesc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
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

    // Also store the UV shader variants for automatic shader selection
    uint64_t uvShaderHandle = generateHandle();
    g_vertexShaders[uvShaderHandle] = g_d3d11.defaultVertexShaderWithUV;
    g_pixelShaders[uvShaderHandle] = g_d3d11.defaultPixelShaderWithUV;

    uint64_t noUvShaderHandle = generateHandle();
    g_vertexShaders[noUvShaderHandle] = g_d3d11.defaultVertexShaderWithoutUV;
    g_pixelShaders[noUvShaderHandle] = g_d3d11.defaultPixelShaderWithoutUV;


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

    // CRITICAL FIX:  +  style texture format validation
    if (!validateTextureFormatSupport(texDesc.Format)) {
        logToJava(nullptr, "[TEXTURE_ERROR] RGBA8_UNORM format not supported, trying fallback format");
        // Try a more widely supported format
        texDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        if (!validateTextureFormatSupport(texDesc.Format)) {
            logToJava(nullptr, "[TEXTURE_CRITICAL] No suitable texture format supported!");
            return; // Can't create texture
        }
    }

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

    // TEMPORARY TEST: Create default depth-stencil state with depth DISABLED
    // This is to test if UI is being clipped by depth test
    // TODO: Proper depth state management per render type
    D3D11_DEPTH_STENCIL_DESC depthStencilDesc = {};
    depthStencilDesc.DepthEnable = FALSE;  // TEMP: Disable depth test to see if UI appears
    depthStencilDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ZERO;
    depthStencilDesc.DepthFunc = D3D11_COMPARISON_ALWAYS; // Always pass
    depthStencilDesc.StencilEnable = FALSE;
    depthStencilDesc.StencilReadMask = D3D11_DEFAULT_STENCIL_READ_MASK;
    depthStencilDesc.StencilWriteMask = D3D11_DEFAULT_STENCIL_WRITE_MASK;

    hr = g_d3d11.device->CreateDepthStencilState(&depthStencilDesc, &g_d3d11.depthStencilState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilState.Get(), 0);
        // Logging disabled
    } else {
    }

    // CRITICAL FIX: Create cached depth stencil states for reuse
    // DirectX best practice: create once, reuse instead of recreating every frame

    // Cached state 1: Depth testing ENABLED
    D3D11_DEPTH_STENCIL_DESC depthEnabledDesc = {};
    depthEnabledDesc.DepthEnable = TRUE;
    depthEnabledDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depthEnabledDesc.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;
    depthEnabledDesc.StencilEnable = FALSE;

    hr = g_d3d11.device->CreateDepthStencilState(&depthEnabledDesc, &g_d3d11.depthStencilStateEnabled);
    if (FAILED(hr)) {
        // Log error but continue
    }

    // Cached state 2: Depth testing DISABLED (for UI rendering)
    D3D11_DEPTH_STENCIL_DESC depthDisabledDesc = {};
    depthDisabledDesc.DepthEnable = FALSE;
    depthDisabledDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ZERO;
    depthDisabledDesc.DepthFunc = D3D11_COMPARISON_ALWAYS;
    depthDisabledDesc.StencilEnable = FALSE;

    hr = g_d3d11.device->CreateDepthStencilState(&depthDisabledDesc, &g_d3d11.depthStencilStateDisabled);
    if (FAILED(hr)) {
        // Log error but continue
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

    // CRITICAL FIX: Enable scissor test
    // Per DirectX 11 spec: RSSetScissorRects has NO effect if ScissorEnable = FALSE
    // Reference: https://learn.microsoft.com/en-us/windows/win32/api/d3d11/nf-d3d11-id3d11devicecontext-rssetscissorrects
    // Without this, all RSSetScissorRects calls throughout the codebase are IGNORED
    rasterizerDesc.ScissorEnable = TRUE;

    rasterizerDesc.MultisampleEnable = FALSE;
    rasterizerDesc.AntialiasedLineEnable = FALSE;

    hr = g_d3d11.device->CreateRasterizerState(&rasterizerDesc, &g_d3d11.rasterizerState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(g_d3d11.rasterizerState.Get());

        // Track scissor state - now enabled by default
        g_d3d11.scissorEnabled = true;
        g_glState.scissorEnabled = true;

        // Logging disabled
    } else {
    }

    // CRITICAL FIX: Set ALL 16 scissor rectangles to full viewport
    // DirectX 11 has 16 scissor slots (D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE = 16)
    // If ScissorEnable=TRUE but some slots have 0x0 rects, those slots clip everything!
    // We must initialize ALL slots to prevent red (0x0) scissor rects in RenderDoc
    D3D11_RECT scissorRects[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    for (int i = 0; i < D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE; i++) {
        scissorRects[i].left = 0;
        scissorRects[i].top = 0;
        scissorRects[i].right = g_d3d11.width;
        scissorRects[i].bottom = g_d3d11.height;
    }
    g_d3d11.context->RSSetScissorRects(D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE, scissorRects);

    // Logging disabled
}


// JNI Implementation
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug, jboolean useWarp) {

  
    g_d3d11.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d11.width = width;
    g_d3d11.height = height;
    g_d3d11.debugEnabled = enableDebug;

    // ==================== PIX GPU CAPTURER STATUS ====================
    // NOTE: PIX DLL is loaded via static initializer when library loads
    // Check if it loaded successfully
    if (enableDebug) {
        HMODULE pixModule = GetModuleHandleA("WinPixGpuCapturer.dll");
        if (pixModule) {
            char pixMsg[256];
            sprintf_s(pixMsg, "[PIX] ✓ WinPixGpuCapturer.dll loaded at startup (handle=%p)", pixModule);
            logToJava(env, pixMsg);
            logToJava(env, "[PIX] ✓ PIX GPU capture support ENABLED - You can attach PIX to this process");
        } else {
            logToJava(env, "[PIX] ✗ PIX DLL not loaded - Install PIX from https://devblogs.microsoft.com/pix/download/");
            logToJava(env, "[PIX] Frame capture will not be available (standard debugging still works)");
        }
    }

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
    // Only enable debug layer when explicitly requested
    UINT creationFlags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;

    if (enableDebug) {
        creationFlags |= D3D11_CREATE_DEVICE_DEBUG;
        logToJava(env, "[DEVICE_CREATE] ✓ DirectX Debug Layer ENABLED");
    } else {
        logToJava(env, "[DEVICE_CREATE] DirectX Debug Layer DISABLED - Production mode");
    }

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

        char hrMsg[512];
        sprintf_s(hrMsg, "[DEBUG_LAYER] QueryInterface(ID3D11InfoQueue) result: HRESULT=0x%08X, infoQueue=%p", debugHr, g_d3d11.infoQueue.Get());
        logToJava(env, hrMsg);

        if (SUCCEEDED(debugHr) && g_d3d11.infoQueue) {
            // Successfully acquired debug interface
            logToJava(env, "[DEBUG_LAYER] ✓ ID3D11InfoQueue acquired successfully!");

            // Configure debug layer based on Context7 DirectX 11 documentation best practices
            // Reference: https://learn.microsoft.com/en-us/windows/win32/direct3d11/using-the-debug-layer-to-test-apps

            // 1. Enable debug output to debugger (OutputDebugString) for dbgview
            g_d3d11.infoQueue->SetMuteDebugOutput(FALSE);
            logToJava(env, "[DEBUG_LAYER] ✓ Debug output enabled for dbgview");

            // 2. Configure break on severity for critical issues only
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_CORRUPTION, TRUE);
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_ERROR, FALSE);
            g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_WARNING, FALSE);
            logToJava(env, "[DEBUG_LAYER] ✓ Break on severity configured");

            // 3. Increase message storage limit for comprehensive logging
            g_d3d11.infoQueue->SetMessageCountLimit(4096);
            logToJava(env, "[DEBUG_LAYER] ✓ Message count limit set to 4096");

            // 4. Clear any existing messages and filters
            g_d3d11.infoQueue->ClearStoredMessages();
            g_d3d11.infoQueue->ClearStorageFilter();
            g_d3d11.infoQueue->ClearRetrievalFilter();
            logToJava(env, "[DEBUG_LAYER] ✓ Cleared messages and filters");

            // 5. Push empty filters to allow all messages
            HRESULT storageResult = g_d3d11.infoQueue->PushEmptyStorageFilter();
            HRESULT retrievalResult = g_d3d11.infoQueue->PushEmptyRetrievalFilter();

            if (SUCCEEDED(storageResult) && SUCCEEDED(retrievalResult)) {
                logToJava(env, "[DEBUG_LAYER] ✓ Empty filters applied successfully");
            } else {
                logToJava(env, "[DEBUG_LAYER] ⚠️ Filter application failed");
            }

            // 6. Enable all message categories by creating an allow-all filter
            D3D11_INFO_QUEUE_FILTER filter = {};
            D3D11_MESSAGE_SEVERITY allowSeverities[] = {
                D3D11_MESSAGE_SEVERITY_CORRUPTION,
                D3D11_MESSAGE_SEVERITY_ERROR,
                D3D11_MESSAGE_SEVERITY_WARNING,
                D3D11_MESSAGE_SEVERITY_INFO,
                D3D11_MESSAGE_SEVERITY_MESSAGE
            };

            filter.AllowList.NumSeverities = ARRAYSIZE(allowSeverities);
            filter.AllowList.pSeverityList = allowSeverities;
            g_d3d11.infoQueue->PushStorageFilter(&filter);
            logToJava(env, "[DEBUG_LAYER] ✓ All message severities enabled");

            // 7. Add a test message to verify the debug layer is working
            g_d3d11.infoQueue->AddApplicationMessage(D3D11_MESSAGE_SEVERITY_INFO,
                "Vitra: DirectX 11 Debug Layer initialized successfully");

            UINT64 messageCount = g_d3d11.infoQueue->GetNumStoredMessages();
            sprintf_s(hrMsg, "[DEBUG_LAYER] Test: %llu messages in queue", messageCount);
            logToJava(env, hrMsg);
            OutputDebugStringA(hrMsg);  // Also send to dbgview

            logToJava(env, "[DEBUG_LAYER] ✓ DirectX 11 Debug Layer configured for dbgview");
            OutputDebugStringA("[Vitra-DX11] [INFO] [INIT] Debug layer configured for dbgview output");

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
    // CRITICAL FIX: b0 must match HLSL cbuffer_common.hlsli DynamicTransforms layout (240 bytes)
    // cbuffer DynamicTransforms : register(b0) {
    //     float4x4 MVP;             // 64 bytes
    //     float4x4 ModelViewMat;    // 64 bytes
    //     float4 ColorModulator;    // 16 bytes
    //     float3 ModelOffset;       // 12 bytes
    //     float _pad0;              // 4 bytes
    //     float4x4 TextureMat;      // 64 bytes
    //     float LineWidth;          // 4 bytes
    //     float3 _pad1;             // 12 bytes
    // };
    // Total: 240 bytes

    // Create constant buffer b0 (DynamicTransforms) with correct size
    D3D11_BUFFER_DESC cbDesc = {};
    cbDesc.Usage = D3D11_USAGE_DYNAMIC;
    cbDesc.ByteWidth = 240;  // Match HLSL DynamicTransforms cbuffer size (was 176)
    cbDesc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    cbDesc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    hr = g_d3d11.device->CreateBuffer(&cbDesc, nullptr, &g_d3d11.constantBuffers[0]);
    if (SUCCEEDED(hr)) {
        // Initialize b0 with identity MVP and default ColorModulator
        D3D11_MAPPED_SUBRESOURCE mappedResource;
        hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
        if (SUCCEEDED(hr)) {
            char* bufferData = (char*)mappedResource.pData;

            // Initialize MVP to identity (offset 0, 64 bytes)
            float identity[16] = {
                1,0,0,0,
                0,1,0,0,
                0,0,1,0,
                0,0,0,1
            };
            memcpy(bufferData, identity, 64);

            // Initialize ModelViewMat to identity (offset 64, 64 bytes)
            memcpy(bufferData + 64, identity, 64);

            // Initialize ColorModulator to white (offset 128, 16 bytes)
            float whiteColor[4] = {1.0f, 1.0f, 1.0f, 1.0f};
            memcpy(bufferData + 128, whiteColor, 16);

            // Rest of buffer (ModelOffset, TextureMat, LineWidth) will be zero-initialized

            g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);

            // Bind constant buffer to both shader stages
            g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
            g_d3d11.context->PSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
        }
    }

    // Initialize clear color state (FIX: Missing - causes pink background)
    // Ensure values are exactly 0.0f to avoid denormalized number warnings
    g_d3d11.clearColor[0] = 0.0f; // R
    g_d3d11.clearColor[1] = 0.0f; // G
    g_d3d11.clearColor[2] = 0.0f; // B
    g_d3d11.clearColor[3] = 1.0f; // A

    // Initialize shader color (ColorModulator uniform) to white (1,1,1,1)
    // This is critical - if not initialized, will stay at Mojang logo red!
    g_d3d11.shaderColor[0] = 1.0f; // R
    g_d3d11.shaderColor[1] = 1.0f; // G
    g_d3d11.shaderColor[2] = 1.0f; // B
    g_d3d11.shaderColor[3] = 1.0f; // A

    // FIX #5: Initialize async fence system (VulkanMod pattern)
    g_d3d11.currentFrameIndex = 0;
    for (int i = 0; i < g_d3d11.MAX_FRAMES_IN_FLIGHT; i++) {
        // Create disjoint query for each frame
        D3D11_QUERY_DESC queryDesc;
        queryDesc.Query = D3D11_QUERY_TIMESTAMP_DISJOINT;
        queryDesc.MiscFlags = 0;
        g_d3d11.device->CreateQuery(&queryDesc, &g_d3d11.frameFences[i].disjointQuery);

        // Create timestamp queries
        queryDesc.Query = D3D11_QUERY_TIMESTAMP;
        g_d3d11.device->CreateQuery(&queryDesc, &g_d3d11.frameFences[i].timestampStart);
        g_d3d11.device->CreateQuery(&queryDesc, &g_d3d11.frameFences[i].timestampEnd);

        g_d3d11.frameFences[i].signaled = false;
    }

    g_d3d11.initialized = true;

    // CRITICAL FIX: Initialize state tracking after successful initialization
    // This prevents UI rendering issues caused by invalid state
    g_committedState.reset();

    // Logging disabled
    return JNI_TRUE;
}

// ============================================================================
//  +  STYLE STATE-AWARE HELPER FUNCTIONS
// ============================================================================
// These functions prevent redundant DirectX calls and ensure proper state management

void setVertexShader(ID3D11VertexShader* pShader) {
    if (g_committedState.pVertexShader != pShader) {
        g_committedState.pVertexShader = pShader;
        g_d3d11.context->VSSetShader(pShader, nullptr, 0);

        // CRITICAL FIX (bgfx pattern): IMMEDIATELY bind constant buffers after shader change
        ID3D11Buffer* vsBuffers[4] = {
            g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
            g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
            g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
            g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
        };
        g_d3d11.context->VSSetConstantBuffers(0, 4, vsBuffers);
    }
}

void setPixelShader(ID3D11PixelShader* pShader) {
    if (g_committedState.pPixelShader != pShader) {
        g_committedState.pPixelShader = pShader;
        g_d3d11.context->PSSetShader(pShader, nullptr, 0);

        // CRITICAL FIX (bgfx pattern): IMMEDIATELY bind constant buffers after shader change
        ID3D11Buffer* psBuffers[4] = {
            g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
            g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
            g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
            g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
        };
        g_d3d11.context->PSSetConstantBuffers(0, 4, psBuffers);
    }
}

void setInputLayout(ID3D11InputLayout* pLayout) {
    if (g_committedState.pInputLayout != pLayout) {
        g_committedState.pInputLayout = pLayout;
        g_d3d11.context->IASetInputLayout(pLayout);
    }
}

void setVertexShaderConstantBuffer(UINT slot, ID3D11Buffer* pBuffer) {
    if (g_committedState.pVSConstantBuffers[slot] != pBuffer) {
        g_committedState.pVSConstantBuffers[slot] = pBuffer;
        g_d3d11.context->VSSetConstantBuffers(slot, 1, &pBuffer);
    }
}

void setPixelShaderConstantBuffer(UINT slot, ID3D11Buffer* pBuffer) {
    if (g_committedState.pPSConstantBuffers[slot] != pBuffer) {
        g_committedState.pPSConstantBuffers[slot] = pBuffer;
        g_d3d11.context->PSSetConstantBuffers(slot, 1, &pBuffer);
    }
}

// ============================================================================
//  +  STYLE SHADER VALIDATION FUNCTIONS
// ============================================================================

bool validateShaderCompatibility(ID3DBlob* vsBlob, ID3DBlob* psBlob) {
    ComPtr<ID3D11ShaderReflection> vsReflector, psReflector;

    HRESULT vsHr = D3DReflect(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(),
                             IID_ID3D11ShaderReflection, &vsReflector);
    HRESULT psHr = D3DReflect(psBlob->GetBufferPointer(), psBlob->GetBufferSize(),
                             IID_ID3D11ShaderReflection, &psReflector);

    if (FAILED(vsHr) || FAILED(psHr)) {
        char msg[512];
        sprintf_s(msg, "[SHADER_VALIDATION] Failed to create shader reflectors: VS=0x%08X, PS=0x%08X", vsHr, psHr);
        logToJava(nullptr, msg);
        return false;
    }

    D3D11_SHADER_DESC vsDesc, psDesc;
    vsReflector->GetDesc(&vsDesc);
    psReflector->GetDesc(&psDesc);

    // Validate that vertex shader outputs match pixel shader inputs
    if (vsDesc.OutputParameters != psDesc.InputParameters) {
        char msg[512];
        sprintf_s(msg, "[SHADER_VALIDATION] VS/PS parameter count mismatch: VS outputs=%d, PS inputs=%d",
                 vsDesc.OutputParameters, psDesc.InputParameters);
        logToJava(nullptr, msg);

        // Don't fail - let it try to render, but log the issue
        // This allows for partial compatibility cases
    }

    return true;
}

bool validateTextureFormatSupport(DXGI_FORMAT format) {
    if (!g_d3d11.device) return false;

    UINT formatSupport = 0;
    HRESULT hr = g_d3d11.device->CheckFormatSupport(format, &formatSupport);

    if (FAILED(hr)) {
        char msg[256];
        sprintf_s(msg, "[TEXTURE_VALIDATION] Format check failed for format %d: HRESULT=0x%08X", (int)format, hr);
        logToJava(nullptr, msg);
        return false;
    }

    // Check for required texture support flags
    UINT requiredFlags = D3D11_FORMAT_SUPPORT_TEXTURE2D | D3D11_FORMAT_SUPPORT_SHADER_SAMPLE;
    if ((formatSupport & requiredFlags) != requiredFlags) {
        char msg[256];
        sprintf_s(msg, "[TEXTURE_VALIDATION] Format %d lacks required support flags: support=0x%08X",
                 (int)format, formatSupport);
        logToJava(nullptr, msg);
        return false;
    }

    return true;
}

jlong createDefaultShaderPipeline() {
    //  +  style fallback shader creation
    // Create a simple working shader pipeline for error recovery
    if (!g_d3d11.defaultVertexShader || !g_d3d11.defaultPixelShader) {
        logToJava(nullptr, "[SHADER_FALLBACK] No default shaders available for fallback");
        return 0;
    }

    // Use existing default shader pipeline handle if already created
    if (g_d3d11.defaultShaderPipelineHandle != 0) {
        return g_d3d11.defaultShaderPipelineHandle;
    }

    // Create a new simple pipeline using default shaders
    ShaderPipeline pipeline;
    pipeline.vertexShader = g_d3d11.defaultVertexShader;
    pipeline.pixelShader = g_d3d11.defaultPixelShader;
    pipeline.inputLayout = g_d3d11.defaultInputLayout;

    // Generate handle and store pipeline
    uint64_t handle = generateHandle();
    g_shaderPipelines[handle] = pipeline;

    char msg[256];
    sprintf_s(msg, "[SHADER_FALLBACK] Created fallback pipeline: handle=0x%llx", handle);
    logToJava(nullptr, msg);

    return static_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shutdown
    (JNIEnv* env, jclass clazz) {

    // CRITICAL FIX: Reset state tracking before shutdown
    // This prevents crashes during cleanup
    g_committedState.reset();

    // Clear all resources
    g_vertexBuffers.clear();
    g_indexBuffers.clear();
    g_vertexBufferStrides.clear();
    g_vertexShaders.clear();
    g_pixelShaders.clear();
    g_inputLayouts.clear();
    g_textures.clear();
    g_shaderResourceViews.clear();
    g_textureSamplers.clear();        // Clear per-texture samplers
    g_textureSamplerParams.clear();   // Clear sampler parameters

    // Release DirectX resources
    g_d3d11.defaultInputLayout.Reset();
    g_d3d11.defaultPixelShader.Reset();
    g_d3d11.defaultVertexShader.Reset();
    g_d3d11.defaultInputLayoutWithUV.Reset();
    g_d3d11.defaultPixelShaderWithUV.Reset();
    g_d3d11.defaultVertexShaderWithUV.Reset();
    g_d3d11.defaultInputLayoutWithoutUV.Reset();
    g_d3d11.defaultPixelShaderWithoutUV.Reset();
    g_d3d11.defaultVertexShaderWithoutUV.Reset();
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resize
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
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_recreateSwapChain
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_beginFrame
    (JNIEnv* env, jclass clazz) {

    // THREAD SAFETY: Lock context mutex for entire frame setup
    // Prevents race conditions if multiple threads call D3D11 operations
    std::lock_guard<std::mutex> lock(g_d3d11ContextMutex);

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

    // FIX #5: Wait for fence from previous frame (VulkanMod pattern)
    // This prevents GPU/CPU race conditions by ensuring frame N-2 is complete
    // before we start frame N (double buffering)
    D3D11Resources::FrameFence& fence = g_d3d11.frameFences[g_d3d11.currentFrameIndex];
    if (fence.signaled) {
        // Poll timestamp end query (non-blocking check)
        UINT64 endTime;
        while (g_d3d11.context->GetData(fence.timestampEnd.Get(), &endTime,
               sizeof(UINT64), 0) == S_FALSE) {
            // Query not ready yet, spin-wait (or could use Sleep(0) to yield)
            // In practice, this should rarely block since we're 2 frames behind
        }
        fence.signaled = false;
    }

    // Set render targets
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    // DON'T clear in beginFrame - let Minecraft's RenderSystem.clear() handle it
    // Clearing here causes issues because:
    // 1. If we clear to black, we override Minecraft's intended clear color
    // 2. If we clear to stored color, we clear twice (here + Minecraft's clear)
    // The correct approach is to NOT clear here at all

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

    // CRITICAL: Reset scissor rectangles to full viewport at frame start
    // If a previous frame set a small scissor (e.g., for GUI), it persists!
    // Without this, only part of the screen renders (diagonal split artifact)
    D3D11_RECT scissorRects[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    for (int i = 0; i < D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE; i++) {
        scissorRects[i].left = 0;
        scissorRects[i].top = 0;
        scissorRects[i].right = static_cast<LONG>(g_d3d11.viewport.Width);
        scissorRects[i].bottom = static_cast<LONG>(g_d3d11.viewport.Height);
    }
    g_d3d11.context->RSSetScissorRects(D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE, scissorRects);

    // CRITICAL: Clear depth buffer at start of each frame
    // Without this, depth testing will fail and geometry will render incorrectly
    // NOTE: We do NOT clear color buffer here - that's handled by explicit clear() calls
    if (g_d3d11.depthStencilView) {
        g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
    }

    // ====================================================================
    // FIX #4: DYNAMIC STATE RESET (VulkanMod Pattern)
    // Reset ALL pipeline state to prevent stale bindings from previous frame
    // ====================================================================

    // 1. Reset all texture bindings (unbind all SRVs)
    // This prevents GUI textures bleeding into 3D world and vice versa
    ID3D11ShaderResourceView* nullSRVs[D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT] = { nullptr };
    g_d3d11.context->PSSetShaderResources(0, D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT, nullSRVs);
    g_d3d11.context->VSSetShaderResources(0, D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT, nullSRVs);

    // 2. Reset all constant buffers (unbind all CBs)
    // This prevents wrong uniform data from being accessed
    ID3D11Buffer* nullCBs[D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT] = { nullptr };
    g_d3d11.context->VSSetConstantBuffers(0, D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT, nullCBs);
    g_d3d11.context->PSSetConstantBuffers(0, D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT, nullCBs);

    // 3. Reset shader bindings
    // Pipelines will rebind their shaders, so start clean
    g_d3d11.context->VSSetShader(nullptr, nullptr, 0);
    g_d3d11.context->PSSetShader(nullptr, nullptr, 0);
    g_d3d11.context->IASetInputLayout(nullptr);

    // 4. Reset sampler bindings
    ID3D11SamplerState* nullSamplers[D3D11_COMMONSHADER_SAMPLER_SLOT_COUNT] = { nullptr };
    g_d3d11.context->PSSetSamplers(0, D3D11_COMMONSHADER_SAMPLER_SLOT_COUNT, nullSamplers);

    // CRITICAL FIX: Force depth test DISABLED at frame start for UI rendering
    // Many UI elements don't explicitly call disableDepthTest(), expecting it to be off by default
    // This matches OpenGL behavior where depth test is disabled unless explicitly enabled
    if (g_d3d11.depthStencilStateDisabled) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
        g_glState.depthTestEnabled = false;
    }

    // Log state info (first 2 frames)
    if (frameCount < 2) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[STATE] DepthStencilState=0x%p (DISABLED at frame start), BlendState=0x%p",
            g_d3d11.depthStencilStateDisabled.Get(), g_d3d11.blendState.Get());
        logToJava(env, msg);
    }

    // REMOVED: Do NOT bind default shaders in beginFrame()!
    // Shaders are set by bindShaderPipeline() and must persist across frames.
    // Binding default shaders here causes shader/input layout mismatch:
    // - bindPipeline() sets correct shader + input layout (e.g., position_tex_color)
    // - beginFrame() was overwriting with default shader every frame
    // - Result: All rendering uses wrong shader/input layout
    //
    // if (g_d3d11.defaultVertexShader && g_d3d11.defaultPixelShader) {
    //     g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    //     g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    //
    //     // CRITICAL FIX: Restore default input layout binding for UI rendering
    //     // Based on  and  analysis - UI elements fail without input layout
    //     if (g_d3d11.defaultInputLayout) {
    //         g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    //     }
    // }

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

    // CRITICAL FIX: Bind default blend state for UI rendering
    // UI elements require alpha blending to be visible
    // Without this, UI elements render with wrong transparency
    if (!g_d3d11.blendState) {
        // Create default blend state with alpha blending enabled
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

        g_d3d11.device->CreateBlendState(&blendDesc, &g_d3d11.blendState);
    }

    if (g_d3d11.blendState) {
        float blendFactor[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
    }

    // CRITICAL FIX: Bind default depth/stencil state for proper depth testing
    if (!g_d3d11.depthStencilState) {
        // Create default depth/stencil state with depth testing enabled
        D3D11_DEPTH_STENCIL_DESC depthDesc = {};
        depthDesc.DepthEnable = TRUE;
        depthDesc.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
        depthDesc.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;
        depthDesc.StencilEnable = FALSE;

        g_d3d11.device->CreateDepthStencilState(&depthDesc, &g_d3d11.depthStencilState);
    }

    if (g_d3d11.depthStencilState) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilState.Get(), 0);
    }

    // NOTE: Projection matrix should be set by Minecraft's RenderSystem
    // The projection matrix will be synchronized from RenderSystem via setProjectionMatrix JNI method
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_endFrame
    (JNIEnv* env, jclass clazz) {

    // THREAD SAFETY: Lock context mutex for Present operation
    // Prevents race conditions during frame presentation
    std::lock_guard<std::mutex> lock(g_d3d11ContextMutex);

    static int callCount = 0;
    static int presentCount = 0;

    if (callCount < 5) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[ENDFRAME %d] Called: initialized=%d, swapChain=0x%p",
            callCount, g_d3d11.initialized, g_d3d11.swapChain.Get());
        logToJava(env, msg);
    }
    callCount++;

    if (!g_d3d11.initialized || !g_d3d11.swapChain) {
        if (callCount < 5) {
            logToJava(env, "[ENDFRAME ERROR] Early return - not initialized or no swap chain!");
        }
        return;
    }

    // NOTE: Unlike the initial assumption, Minecraft DOES render directly to the swap chain backbuffer
    // via MainTargetMixin.bindWrite() → bindMainRenderTarget() → OMSetRenderTargets(backbuffer)
    // This matches VulkanMod's approach of rendering directly to the presentation surface
    // Therefore, we do NOT need to copy/blit anything - just present the backbuffer!

    // FIX #5: Signal fence for this frame (VulkanMod pattern)
    D3D11Resources::FrameFence& fence = g_d3d11.frameFences[g_d3d11.currentFrameIndex];
    g_d3d11.context->End(fence.timestampEnd.Get());
    fence.signaled = true;

    // Advance to next frame index (double buffering)
    g_d3d11.currentFrameIndex = (g_d3d11.currentFrameIndex + 1) % g_d3d11.MAX_FRAMES_IN_FLIGHT;

    // Flush context before Present (VulkanMod equivalent: vkQueueWaitIdle)
    // This ensures all GPU commands are submitted before presenting
    g_d3d11.context->Flush();

    // Present the frame
    HRESULT hr = g_d3d11.swapChain->Present(1, 0);

    if (presentCount < 5) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[PRESENT %d] SwapChain->Present() called! HRESULT=0x%08X (%s)",
            presentCount, hr, SUCCEEDED(hr) ? "SUCCESS" : "FAILED");
        logToJava(env, msg);
    }
    presentCount++;

    if (FAILED(hr)) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[PRESENT ERROR] Present() FAILED with HRESULT=0x%08X", hr);
        logToJava(env, msg);
    }
}

/**
 * Reset dynamic rendering state (VulkanMod Renderer.resetDynamicState() pattern)
 *
 * VulkanMod calls this at the start of each render pass to ensure clean state.
 * Reference: VulkanMod Renderer.java:600-604
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resetDynamicState
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.context) {
        return;
    }

    std::lock_guard<std::mutex> lock(g_d3d11ContextMutex);

    // Reset depth bias (VulkanMod: vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F))
    // D3D11 equivalent: RSSetState with DepthBias = 0
    // Note: D3D11 depth bias is part of rasterizer state, not a separate command
    // We'll reset it via the rasterizer state in the pipeline

    // Reset stencil reference value (default = 0)
    g_d3d11.context->OMSetDepthStencilState(nullptr, 0);

    // Reset blend factor to default (1, 1, 1, 1)
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(nullptr, blendFactor, 0xFFFFFFFF);

    // NOTE: We don't reset line width because D3D11 doesn't support it
    // (Vulkan vkCmdSetLineWidth equivalent doesn't exist in D3D11)
}

// REMOVED: Old clear(float, float, float, float) - replaced by clear(int mask)
// The old implementation caused confusion with duplicate clear methods
// Now using clear(int mask) which respects setClearColor() state

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setClearColor
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

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createVertexBuffer
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

    // Name the resource for DirectX debugging
    char bufferName[64];
    snprintf(bufferName, sizeof(bufferName), "VitraVertexBuffer_%llu", handle);
    buffer->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(bufferName), bufferName);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createIndexBuffer
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
    g_indexBufferSizes[handle] = size;  // Track buffer size (VulkanMod-style)

    // Name the resource for DirectX debugging
    char bufferName[64];
    snprintf(bufferName, sizeof(bufferName), "VitraIndexBuffer_%llu", handle);
    buffer->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(bufferName), bufferName);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createGLShader
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

            // Name the resource for DirectX debugging
            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraVertexShader_%llu", handle);
            shader->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(shaderName), shaderName);
        } else {
            handle = 0;
        }
    } else if (type == 1) { // Pixel shader
        ComPtr<ID3D11PixelShader> shader;
        HRESULT hr = g_d3d11.device->CreatePixelShader(
            bytes, size, nullptr, &shader);

        if (SUCCEEDED(hr)) {
            g_pixelShaders[handle] = shader;

            // Name the resource for DirectX debugging
            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraPixelShader_%llu", handle);
            shader->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(shaderName), shaderName);
        } else {
            handle = 0;
        }
    }

    env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createGLShaderPipeline
    (JNIEnv* env, jclass clazz, jlong vertexShader, jlong pixelShader) {

    if (!g_d3d11.initialized) return 0;

    // For simplicity, we'll just return the vertex shader handle as the pipeline handle
    // In a more complete implementation, you'd create a pipeline state object
    return vertexShader;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyResource
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderPipeline
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

    // REMOVED: Do not set default input layout - it must match actual vertex data
    // g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Set default sampler state for texture sampling
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

    // CRITICAL FIX (bgfx pattern): IMMEDIATELY bind ALL constant buffers after shader change
    // VSSetShader/PSSetShader can unbind constant buffers - bind them all at once
    ID3D11Buffer* vsBuffers[4] = {
        g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
        g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
        g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
        g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
    };
    ID3D11Buffer* psBuffers[4] = {
        g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
        g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
        g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
        g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
    };
    g_d3d11.context->VSSetConstantBuffers(0, 4, vsBuffers);
    g_d3d11.context->PSSetConstantBuffers(0, 4, psBuffers);

    // CRITICAL FIX: Rebind all active textures after shader pipeline change
    // DirectX 11 clears texture bindings when shaders change, just like constant buffers.
    // This fixes the "textures not loading" issue (red screen, no Mojang logo).
    static int rebindCount = 0;
    for (int slot = 0; slot < 32; ++slot) {
        jint textureId = g_glState.boundTextures[slot];
        if (textureId > 0) {
            // Look up the shader resource view for this texture ID
            uint64_t handle = static_cast<uint64_t>(textureId);
            auto it = g_shaderResourceViews.find(handle);
            if (it != g_shaderResourceViews.end() && it->second.Get() != nullptr) {
                ID3D11ShaderResourceView* srv = it->second.Get();
                g_d3d11.context->PSSetShaderResources(slot, 1, &srv);

                // DEBUG: Log first 10 texture rebindings
                if (rebindCount < 10) {
                    char msg[256];
                    snprintf(msg, sizeof(msg), "[PIPELINE_TEX_REBIND %d] Rebound texture ID=%d to slot %d, SRV=%p",
                        rebindCount, textureId, slot, (void*)srv);
                    logToJava(env, msg);
                    rebindCount++;
                }
            }
        }
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getDefaultShaderPipeline
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.defaultVertexShader) {
        return 0;
    }

    // Return the stored default shader pipeline handle
    // This handle was created during initialization in setDefaultShaders()
    return static_cast<jlong>(g_d3d11.defaultShaderPipelineHandle);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_draw
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

    // REMOVED: Do NOT bind default shaders in draw()!
    // Shaders are set by bindShaderPipeline() and must NOT be overwritten.
    // Binding default shaders here causes shader/input layout mismatch:
    // - bindPipeline() sets correct shader + input layout
    // - draw() was overwriting with default shaders
    // - Result: Wrong shader/input layout used for rendering
    //
    // The correct pipeline state (shaders + input layout) is set by:
    // 1. D3D11Pipeline.bind() → setInputLayoutFromVertexFormat() + bindShaderPipeline()
    // 2. These bindings must persist until the next pipeline bind
    //
    // This draw() function should only perform the actual draw call, not modify pipeline state.

    // CRITICAL FIX: -style constant buffer binding to BOTH shaders
    // UI shaders need constant buffers in both vertex and pixel shaders
    if (g_d3d11.constantBuffers[0] && g_d3d11.constantBuffers[0].Get()) {
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
        g_d3d11.context->PSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf()); // CRITICAL: Was missing!
    }

    // CRITICAL FIX: Rebind textures before drawing
    // DirectX 11 may unbind textures during state changes
    char msg[512];
    sprintf_s(msg, "[DRAW_TEXTURE_REBIND] g_glState.boundTextures: [0]=%d [1]=%d [2]=%d [3]=%d",
        g_glState.boundTextures[0], g_glState.boundTextures[1],
        g_glState.boundTextures[2], g_glState.boundTextures[3]);
    logToJava(env, msg);

    for (int slot = 0; slot < 8; ++slot) {
        jint textureId = g_glState.boundTextures[slot];
        if (textureId > 0) {
            uint64_t handle = static_cast<uint64_t>(textureId);
            auto it = g_shaderResourceViews.find(handle);
            if (it != g_shaderResourceViews.end() && it->second.Get() != nullptr) {
                ID3D11ShaderResourceView* srv = it->second.Get();
                g_d3d11.context->PSSetShaderResources(slot, 1, &srv);
                sprintf_s(msg, "[DRAW_TEXTURE_REBIND] Rebound texture ID=%d to slot %d, SRV=%p", textureId, slot, (void*)srv);
                logToJava(env, msg);
            }
        }
    }

    // CRITICAL: Ensure sampler state is bound for texture sampling
    // DirectX 11 state can be overwritten, so we re-bind before each draw
    if (g_d3d11.defaultSamplerState && g_d3d11.defaultSamplerState.Get()) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

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
            // VULKAN MOD-STYLE: Check capacity and auto-resize if needed
            UINT requiredSize = (firstIndex + indexCount) * 2;  // 2 bytes per 16-bit index

            // Get current buffer size from tracking map
            auto sizeIt = g_indexBufferSizes.find(ibHandle);
            UINT currentSize = (sizeIt != g_indexBufferSizes.end()) ? sizeIt->second : 0;

            if (requiredSize > currentSize) {
                // VulkanMod pattern: Reallocate with 2x growth
                UINT newSize = (currentSize + requiredSize) * 2;

                char msg[256];
                snprintf(msg, sizeof(msg), "[BUFFER_RESIZE] Index buffer too small! Reallocating from %u to %u bytes (indexCount=%d, firstIndex=%d)",
                    currentSize, newSize, indexCount, firstIndex);
                logToJava(env, msg);

                // Create new larger buffer
                D3D11_BUFFER_DESC desc = {};
                desc.Usage = D3D11_USAGE_DYNAMIC;
                desc.ByteWidth = newSize;
                desc.BindFlags = D3D11_BIND_INDEX_BUFFER;
                desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

                ComPtr<ID3D11Buffer> newBuffer;
                HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &newBuffer);

                if (SUCCEEDED(hr)) {
                    // Copy old data to new buffer (if there was any)
                    if (ibIt->second && currentSize > 0) {
                        // FIXED: Cannot use D3D11_MAP_READ on DYNAMIC buffers (per DirectX 11 specification)
                        // DYNAMIC buffers only support D3D11_CPU_ACCESS_WRITE, not READ
                        // Solution: Use CopySubresourceRegion for GPU-to-GPU copy
                        // Reference: https://learn.microsoft.com/en-us/windows/win32/api/d3d11/ne-d3d11-d3d11_usage

                        // Use CopySubresourceRegion to copy old buffer contents to new buffer
                        // This is a GPU-side operation, no CPU mapping needed
                        D3D11_BOX srcBox = {};
                        srcBox.left = 0;
                        srcBox.right = currentSize;
                        srcBox.top = 0;
                        srcBox.bottom = 1;
                        srcBox.front = 0;
                        srcBox.back = 1;

                        g_d3d11.context->CopySubresourceRegion(
                            newBuffer.Get(),    // destination
                            0,                  // dest subresource
                            0, 0, 0,           // dest x, y, z
                            ibIt->second.Get(), // source
                            0,                  // source subresource
                            &srcBox             // source box (region to copy)
                        );

                        // No need to Map/Unmap - GPU copy is complete
                        char copyMsg[256];
                        snprintf(copyMsg, sizeof(copyMsg), "[BUFFER_RESIZE] Copied %u bytes using CopySubresourceRegion (GPU-side)", currentSize);
                        logToJava(env, copyMsg);
                    }

                    // Replace buffer
                    ibIt->second = newBuffer;
                    g_indexBufferSizes[ibHandle] = newSize;

                    // Re-bind index buffer
                    g_d3d11.context->IASetIndexBuffer(newBuffer.Get(), DXGI_FORMAT_R16_UINT, 0);

                    logToJava(env, "[BUFFER_RESIZE] ✓ Index buffer successfully resized");
                } else {
                    logToJava(env, "[BUFFER_RESIZE] ✗ FAILED to resize buffer - skipping draw");
                    return;
                }
            }

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

                // VulkanMod-style: Ensure index buffer capacity before drawing
                // This will auto-resize if needed, only fails if resize is impossible
                ensureIndexBufferCapacity(env, indexCount, firstIndex);

                // CRITICAL FIX (bgfx pattern): Re-bind ALL constant buffers immediately before EVERY draw
                // DirectX 11 may unbind constant buffers during pipeline state changes
                // bgfx always binds constant buffers right before draw calls to guarantee availability
                ID3D11Buffer* vsBuffers[4] = {
                    g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
                    g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
                    g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
                    g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
                };
                ID3D11Buffer* psBuffers[4] = {
                    g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
                    g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
                    g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
                    g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
                };
                g_d3d11.context->VSSetConstantBuffers(0, 4, vsBuffers);
                g_d3d11.context->PSSetConstantBuffers(0, 4, psBuffers);

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

                // CRITICAL FIX (bgfx pattern): Re-bind ALL constant buffers immediately before EVERY draw
                // DirectX 11 may unbind constant buffers during pipeline state changes
                ID3D11Buffer* vsBuffers[4] = {
                    g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
                    g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
                    g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
                    g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
                };
                ID3D11Buffer* psBuffers[4] = {
                    g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
                    g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
                    g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
                    g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
                };
                g_d3d11.context->VSSetConstantBuffers(0, 4, vsBuffers);
                g_d3d11.context->PSSetConstantBuffers(0, 4, psBuffers);

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

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isInitialized
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPrimitiveTopology
    (JNIEnv* env, jclass clazz, jint topology) {

    if (!g_d3d11.initialized) return;

    g_d3d11.currentTopology = glTopologyToD3D11(topology);
    g_d3d11.context->IASetPrimitiveTopology(g_d3d11.currentTopology);
}

// Draw with explicit vertex format - creates correct input layout based on actual vertex data
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawWithVertexFormat
    (JNIEnv* env, jclass clazz, jlong vbHandle, jlong ibHandle, jint baseVertex, jint firstIndex, jint vertexOrIndexCount, jint instanceCount, jintArray vertexFormatDesc) {

    char msg[256];
    sprintf_s(msg, "[D3D11_DRAW] drawWithVertexFormat called: vb=0x%llx, ib=0x%llx, count=%d, initialized=%d, boundVS=0x%llx",
        vbHandle, ibHandle, vertexOrIndexCount, g_d3d11.initialized, g_d3d11.boundVertexShader);
    logToJava(env, msg);

    if (!g_d3d11.initialized) {
        logToJava(env, "[D3D11_DRAW] ERROR: Not initialized!");
        return;
    }

    // Get vertex format descriptor from Java
    jint* formatDesc = env->GetIntArrayElements(vertexFormatDesc, nullptr);
    jsize formatDescLength = env->GetArrayLength(vertexFormatDesc);

    sprintf_s(msg, "[D3D11_DRAW] Got vertex format: formatDesc=%p, length=%d", formatDesc, formatDescLength);
    logToJava(env, msg);

    if (formatDesc == nullptr || formatDescLength < 1) {
        logToJava(env, "[D3D11_DRAW] ERROR: Invalid format descriptor!");
        if (formatDesc) env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    // Compute hash of vertex format for input layout caching
    uint64_t formatHash = 0;
    for (jsize i = 0; i < formatDescLength; i++) {
        formatHash = formatHash * 31 + formatDesc[i];
    }

    // CRITICAL FIX: Include shader handle in cache key!
    // Different shaders with same vertex format need different input layouts
    // XOR shader handle into format hash to create composite key
    uint64_t cacheKey = formatHash ^ g_d3d11.boundVertexShader;

    sprintf_s(msg, "[D3D11_DRAW] Computed format hash: 0x%llx, shader: 0x%llx, cacheKey: 0x%llx",
        formatHash, g_d3d11.boundVertexShader, cacheKey);
    logToJava(env, msg);

    // CRITICAL FIX: Create input layout from ACTUAL vertex buffer format
    // DirectX 11 requires input layout to match the ACTUAL vertex buffer data, not shader expectations
    // The stride mismatch error occurs when input layout says 20 bytes but vertex buffer has 12 bytes
    //
    // VulkanMod validation (BufferUploaderMixin:58-62) ensures shader format == meshData format,
    // so this vertex format descriptor should match what the shader expects.
    // However, we still need to create and bind the input layout HERE based on actual vertex data.

    // CRITICAL: Detect vertex format to select compatible shader
    // Analyze the vertex format to determine what attributes it has
    bool hasUV = false;
    bool hasColor = false;

    // Java format: [elementCount, usage1, type1, count1, offset1, usage2, type2, count2, offset2, ...]
    // First int is element count, then 4 ints per element
    int elementCount = formatDesc[0];  // First element is the count

    for (int i = 0; i < elementCount; i++) {
        int baseIdx = 1 + i * 4;  // Skip first element (count), then 4 ints per element
        int usage = formatDesc[baseIdx];  // usage is first int in each element descriptor
        if (usage == 3) hasUV = true;    // UV usage = 3
        if (usage == 2) hasColor = true; // COLOR usage = 2
    }

    sprintf_s(msg, "[D3D11_DRAW] Format analysis: elements=%d, hasUV=%d, hasColor=%d", elementCount, hasUV, hasColor);
    logToJava(env, msg);

    // Get vertex shader blob for input layout creation
    auto blobIt = g_shaderBlobs.find(g_d3d11.boundVertexShader);
    if (blobIt == g_shaderBlobs.end()) {
        sprintf_s(msg, "[D3D11_DRAW] ERROR: Vertex shader blob not found for handle 0x%llx", g_d3d11.boundVertexShader);
        logToJava(env, msg);
        env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    ID3DBlob* vertexShaderBlob = blobIt->second.Get();

    // NOTE: Removed manual shader/format mismatch check
    // D3D11 will validate input layout compatibility automatically
    // The previous check was too strict and blocked valid draw calls where
    // missing vertex attributes get default values (e.g., UV=0,0 for position-only geometry)

    // Check if we have an input layout cached for this format+shader combination
    auto layoutIt = g_vertexFormatInputLayouts.find(cacheKey);
    ID3D11InputLayout* inputLayout = nullptr;

    if (layoutIt != g_vertexFormatInputLayouts.end()) {
        // Use cached input layout
        inputLayout = layoutIt->second.Get();
        sprintf_s(msg, "[D3D11_DRAW] Using CACHED input layout for cacheKey=0x%llx: %p", cacheKey, (void*)inputLayout);
        logToJava(env, msg);
    } else {
        // Create NEW input layout from vertex format descriptor
        sprintf_s(msg, "[D3D11_DRAW] Creating NEW input layout for cacheKey=0x%llx", cacheKey);
        logToJava(env, msg);

        // Call existing helper function to create input layout
        ComPtr<ID3D11InputLayout> newInputLayout;
        bool success = createInputLayoutFromVertexFormat(env, formatDesc, formatDescLength, vertexShaderBlob, newInputLayout.GetAddressOf());

        if (success && newInputLayout) {
            // Cache the input layout
            g_vertexFormatInputLayouts[cacheKey] = newInputLayout;
            inputLayout = newInputLayout.Get();
            sprintf_s(msg, "[D3D11_DRAW] Cached new input layout: %p", (void*)inputLayout);
            logToJava(env, msg);
        } else {
            logToJava(env, "[D3D11_DRAW] ERROR: Failed to create input layout!");
            env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
            return;
        }
    }

    // Release Java array after using it
    env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);

    // CRITICAL: Set input layout BEFORE binding vertex buffers (DirectX 11 requirement)
    if (inputLayout) {
        g_d3d11.context->IASetInputLayout(inputLayout);
        sprintf_s(msg, "[D3D11_DRAW] Set input layout: %p (BEFORE vertex buffer binding)", (void*)inputLayout);
        logToJava(env, msg);
    } else {
        logToJava(env, "[D3D11_DRAW] ERROR: No input layout to bind!");
        return;
    }

    // CRITICAL FIX: Rebind textures before drawing
    // OpenGL allows glBindTexture(0) to unbind, but DirectX needs textures bound for draws
    // This mirrors what setShaderPipeline does (lines 1806-1820)
    for (int slot = 0; slot < 8; ++slot) {
        jint textureId = g_glState.boundTextures[slot];
        if (textureId > 0) {
            uint64_t handle = static_cast<uint64_t>(textureId);
            auto it = g_shaderResourceViews.find(handle);
            if (it != g_shaderResourceViews.end() && it->second.Get() != nullptr) {
                ID3D11ShaderResourceView* srv = it->second.Get();
                g_d3d11.context->PSSetShaderResources(slot, 1, &srv);
            }
        }
    }

    // CRITICAL FIX: Rebind sampler states before drawing
    // DirectX 11 may unbind sampler states during pipeline changes
    // Without samplers, textures cannot be sampled (textures appear black/missing)
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
        sprintf_s(msg, "[DRAW_CHECK] Rebound sampler state: %p", (void*)g_d3d11.defaultSamplerState.Get());
        logToJava(env, msg);
    } else {
        logToJava(env, "[DRAW_CHECK] WARNING: No default sampler state!");
    }

    // CRITICAL: Check complete DirectX state before drawing
    ID3D11VertexShader* boundVS = nullptr;
    ID3D11PixelShader* boundPS = nullptr;
    g_d3d11.context->VSGetShader(&boundVS, nullptr, nullptr);
    g_d3d11.context->PSGetShader(&boundPS, nullptr, nullptr);

    sprintf_s(msg, "[DRAW_CHECK] Shaders - VS: %p, PS: %p", (void*)boundVS, (void*)boundPS);
    logToJava(env, msg);

    if (boundVS) boundVS->Release();
    if (boundPS) boundPS->Release();

    if (!boundVS || !boundPS) {
        sprintf_s(msg, "[DRAW_ERROR] Missing shaders! VS=%p, PS=%p", (void*)boundVS, (void*)boundPS);
        logToJava(env, msg);
    }

    // Check constant buffers
    ID3D11Buffer* vsCB0 = nullptr;
    ID3D11Buffer* vsCB1 = nullptr;
    g_d3d11.context->VSGetConstantBuffers(0, 1, &vsCB0);
    g_d3d11.context->VSGetConstantBuffers(1, 1, &vsCB1);
    sprintf_s(msg, "[DRAW_CHECK] VS Constant Buffer slot 0: %p, slot 1: %p", (void*)vsCB0, (void*)vsCB1);
    logToJava(env, msg);
    if (vsCB0) vsCB0->Release();
    if (vsCB1) vsCB1->Release();

    // Check render targets
    ID3D11RenderTargetView* rtv = nullptr;
    ID3D11DepthStencilView* dsv = nullptr;
    g_d3d11.context->OMGetRenderTargets(1, &rtv, &dsv);
    sprintf_s(msg, "[DRAW_CHECK] Render Target: %p, Depth: %p", (void*)rtv, (void*)dsv);
    logToJava(env, msg);
    if (rtv) rtv->Release();
    if (dsv) dsv->Release();

    // Check viewport
    D3D11_VIEWPORT viewport;
    UINT numViewports = 1;
    g_d3d11.context->RSGetViewports(&numViewports, &viewport);
    sprintf_s(msg, "[DRAW_CHECK] Viewport: %.0fx%.0f at (%.0f, %.0f)",
        viewport.Width, viewport.Height, viewport.TopLeftX, viewport.TopLeftY);
    logToJava(env, msg);

    // CRITICAL: Check scissor rect - this could be why UI isn't visible!
    D3D11_RECT scissorRect;
    UINT numScissorRects = 1;
    g_d3d11.context->RSGetScissorRects(&numScissorRects, &scissorRect);
    sprintf_s(msg, "[DRAW_CHECK] Scissor: (%ld,%ld) to (%ld,%ld) [%ldx%ld]",
        scissorRect.left, scissorRect.top, scissorRect.right, scissorRect.bottom,
        scissorRect.right - scissorRect.left, scissorRect.bottom - scissorRect.top);
    logToJava(env, msg);

    // Check rasterizer state
    ID3D11RasterizerState* rastState = nullptr;
    g_d3d11.context->RSGetState(&rastState);
    if (rastState) {
        D3D11_RASTERIZER_DESC rastDesc;
        rastState->GetDesc(&rastDesc);
        sprintf_s(msg, "[DRAW_CHECK] Rasterizer - CullMode: %d, FillMode: %d",
            rastDesc.CullMode, rastDesc.FillMode);
        logToJava(env, msg);
        rastState->Release();
    } else {
        logToJava(env, "[DRAW_CHECK] Rasterizer state: NULL");
    }

    // Check textures
    ID3D11ShaderResourceView* srv = nullptr;
    g_d3d11.context->PSGetShaderResources(0, 1, &srv);
    sprintf_s(msg, "[DRAW_CHECK] Texture slot 0: %p", (void*)srv);
    logToJava(env, msg);
    if (srv) srv->Release();

    // Check samplers
    ID3D11SamplerState* sampler = nullptr;
    g_d3d11.context->PSGetSamplers(0, 1, &sampler);
    sprintf_s(msg, "[DRAW_CHECK] Sampler slot 0: %p", (void*)sampler);
    logToJava(env, msg);
    if (sampler) sampler->Release();

    // Find vertex buffer
    auto vbIt = g_vertexBuffers.find(vbHandle);
    if (vbIt == g_vertexBuffers.end()) {
        sprintf_s(msg, "[DRAW_ERROR] Vertex buffer handle 0x%llx not found!", vbHandle);
        logToJava(env, msg);
        return;
    }

    ID3D11Buffer* vertexBuffer = vbIt->second.Get();
    UINT stride = g_vertexBufferStrides[vbHandle];
    UINT offset = 0;

    g_d3d11.context->IASetVertexBuffers(0, 1, &vertexBuffer, &stride, &offset);

    // CRITICAL: Check input layout before drawing!
    // If input layout is NULL, DirectX will silently drop the draw call
    ID3D11InputLayout* boundInputLayout = nullptr;
    g_d3d11.context->IAGetInputLayout(&boundInputLayout);
    if (!boundInputLayout) {
        logToJava(env, "[DRAW_ERROR] ❌ Input layout is NULL! Draw will be dropped!");
        sprintf_s(msg, "[DRAW_ERROR] Pipeline bind should have set input layout for VS=0x%llx", g_d3d11.boundVertexShader);
        logToJava(env, msg);
        // Continue anyway to see if this is the problem
    } else {
        sprintf_s(msg, "[DRAW_CHECK] ✓ Input layout: %p", (void*)boundInputLayout);
        logToJava(env, msg);
        boundInputLayout->Release();
    }

    // Check depth/stencil and blend states
    ID3D11DepthStencilState* boundDepthState = nullptr;
    UINT stencilRef = 0;
    g_d3d11.context->OMGetDepthStencilState(&boundDepthState, &stencilRef);
    if (boundDepthState) {
        D3D11_DEPTH_STENCIL_DESC dsDesc;
        boundDepthState->GetDesc(&dsDesc);
        sprintf_s(msg, "[DRAW_CHECK] DepthState: DepthEnable=%d, DepthWriteMask=%d, DepthFunc=%d",
            dsDesc.DepthEnable, dsDesc.DepthWriteMask, dsDesc.DepthFunc);
        logToJava(env, msg);
        boundDepthState->Release();
    }

    ID3D11BlendState* boundBlendState = nullptr;
    FLOAT blendFactor[4];
    UINT sampleMask = 0;
    g_d3d11.context->OMGetBlendState(&boundBlendState, blendFactor, &sampleMask);
    if (boundBlendState) {
        D3D11_BLEND_DESC blendDesc;
        boundBlendState->GetDesc(&blendDesc);
        sprintf_s(msg, "[DRAW_CHECK] BlendState: BlendEnable=%d, SrcBlend=%d, DestBlend=%d",
            blendDesc.RenderTarget[0].BlendEnable,
            blendDesc.RenderTarget[0].SrcBlend,
            blendDesc.RenderTarget[0].DestBlend);
        logToJava(env, msg);
        boundBlendState->Release();
    }

    sprintf_s(msg, "[DRAW_EXECUTE] Drawing: vbHandle=0x%llx, ibHandle=0x%llx, count=%d, stride=%u",
        vbHandle, ibHandle, vertexOrIndexCount, stride);
    logToJava(env, msg);

    // Draw indexed or non-indexed
    if (ibHandle != 0) {
        auto ibIt = g_indexBuffers.find(ibHandle);
        if (ibIt == g_indexBuffers.end()) {
            sprintf_s(msg, "[DRAW_ERROR] Index buffer handle 0x%llx not found!", ibHandle);
            logToJava(env, msg);
            return;
        }

        ID3D11Buffer* indexBuffer = ibIt->second.Get();
        g_d3d11.context->IASetIndexBuffer(indexBuffer, DXGI_FORMAT_R32_UINT, 0);

        // VulkanMod-style: Ensure index buffer capacity before drawing
        // This will auto-resize if needed, only fails if resize is impossible
        ensureIndexBufferCapacity(env, vertexOrIndexCount, firstIndex);

        if (instanceCount > 1) {
            g_d3d11.context->DrawIndexedInstanced(vertexOrIndexCount, instanceCount, firstIndex, baseVertex, 0);
        } else {
            g_d3d11.context->DrawIndexed(vertexOrIndexCount, firstIndex, baseVertex);
        }
        sprintf_s(msg, "[DRAW_COMPLETE] DrawIndexed(%d) executed", vertexOrIndexCount);
        logToJava(env, msg);
    } else {
        if (instanceCount > 1) {
            g_d3d11.context->DrawInstanced(vertexOrIndexCount, instanceCount, baseVertex, 0);
        } else {
            g_d3d11.context->Draw(vertexOrIndexCount, baseVertex);
        }
        sprintf_s(msg, "[DRAW_COMPLETE] Draw(%d) executed", vertexOrIndexCount);
        logToJava(env, msg);
    }
}

// CRITICAL FIX: Set input layout from vertex format
// This is called when binding a pipeline to ensure input layout matches vertex format
// Prevents "TEXCOORD linkage error" when draw() is called without vertex format parameter
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setInputLayoutFromVertexFormat
    (JNIEnv* env, jclass clazz, jlong vertexShaderHandle, jintArray vertexFormatDesc) {

    if (!g_d3d11.initialized) {
        logToJava(env, "[INPUT_LAYOUT_SET] ERROR: Not initialized");
        return;
    }

    if (vertexShaderHandle == 0) {
        logToJava(env, "[INPUT_LAYOUT_SET] ERROR: Invalid vertex shader handle");
        return;
    }

    // Get vertex format descriptor from Java
    jint* formatDesc = env->GetIntArrayElements(vertexFormatDesc, nullptr);
    jsize formatDescLength = env->GetArrayLength(vertexFormatDesc);

    if (formatDesc == nullptr || formatDescLength < 1) {
        logToJava(env, "[INPUT_LAYOUT_SET] ERROR: Invalid format descriptor");
        if (formatDesc) env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    // Get vertex shader blob
    auto blobIt = g_shaderBlobs.find(static_cast<uint64_t>(vertexShaderHandle));
    if (blobIt == g_shaderBlobs.end()) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[INPUT_LAYOUT_SET] ERROR: Shader blob not found for handle 0x%llx", vertexShaderHandle);
        logToJava(env, msg);
        env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    ID3DBlob* vsBlob = blobIt->second.Get();
    if (!vsBlob) {
        logToJava(env, "[INPUT_LAYOUT_SET] ERROR: Shader blob is null");
        env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    // Compute hash of vertex format for caching
    uint64_t formatHash = 0;
    for (jsize i = 0; i < formatDescLength; i++) {
        formatHash = formatHash * 31 + formatDesc[i];
    }

    // CRITICAL FIX: Include shader handle in cache key (same as draw function)
    uint64_t cacheKey = formatHash ^ static_cast<uint64_t>(vertexShaderHandle);

    // TEMPORARY DEBUG: Disable caching to test if cache is causing mismatches
    // Check if we already have this input layout cached
    // auto layoutIt = g_vertexFormatInputLayouts.find(cacheKey);
    // if (layoutIt != g_vertexFormatInputLayouts.end()) {
    //     // Use cached input layout
    //     ID3D11InputLayout* layout = layoutIt->second.Get();
    //     g_d3d11.context->IASetInputLayout(layout);

    //     char msg[512];
    //     snprintf(msg, sizeof(msg), "[INPUT_LAYOUT_SET] Using cached input layout %p for cacheKey 0x%llx (formatHash: 0x%llx, vertexShaderHandle: 0x%llx)",
    //         (void*)layout, cacheKey, formatHash, vertexShaderHandle);
    //     logToJava(env, msg);

    //     env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
    //     return;
    // }

    // Create new input layout from vertex format
    ID3D11InputLayout* rawInputLayout = nullptr;
    if (!createInputLayoutFromVertexFormat(env, formatDesc, formatDescLength, vsBlob, &rawInputLayout)) {
        logToJava(env, "[INPUT_LAYOUT_SET] ERROR: Failed to create input layout");
        env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
        return;
    }

    // Cache and set the input layout
    ComPtr<ID3D11InputLayout> inputLayout;
    inputLayout.Attach(rawInputLayout);
    g_vertexFormatInputLayouts[cacheKey] = inputLayout;  // CRITICAL FIX: Use cacheKey (format + shader)
    g_d3d11.context->IASetInputLayout(inputLayout.Get());

    char msg[512];
    snprintf(msg, sizeof(msg), "[INPUT_LAYOUT_SET] Created and set NEW input layout %p for cacheKey 0x%llx (formatHash: 0x%llx, vertexShaderHandle: 0x%llx)",
        (void*)inputLayout.Get(), cacheKey, formatHash, vertexShaderHandle);
    logToJava(env, msg);

    env->ReleaseIntArrayElements(vertexFormatDesc, formatDesc, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawMeshData
    (JNIEnv* env, jclass clazz, jobject vertexBuffer, jobject indexBuffer,
     jint vertexCount, jint indexCount, jint primitiveMode, jint vertexSize) {

    if (!g_d3d11.initialized || vertexBuffer == nullptr) return;

    // CRITICAL FIX: Ensure render target is bound before every draw call
    // This prevents the "no render target bound" DirectX debug warning
    if (g_d3d11.renderTargetView && g_d3d11.depthStencilView) {
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());
    }

    // REMOVED: Do NOT bind default shaders here!
    // Shaders are set by bindShaderPipeline() and must NOT be overwritten.
    // Binding default shaders here causes shader/input layout mismatch:
    // - bindPipeline() sets correct shader + input layout (e.g., position_tex_color with POSITION+TEXCOORD+COLOR)
    // - drawMeshData() was overwriting with default shader (only POSITION)
    // - Result: RenderDoc shows only POSITION in input layout, TEXCOORD/COLOR undefined
    //
    // if (g_d3d11.defaultVertexShader) {
    //     g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    // }
    // if (g_d3d11.defaultPixelShader) {
    //     g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    // }

    // REMOVED: Do not set default input layout - it must match actual vertex data
    // if (g_d3d11.defaultInputLayout) {
    //     g_d3d11.context->IASetInputLayout(g_d3d11.defaultInputLayout.Get());
    // }

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

                // VulkanMod-style: Ensure index buffer capacity (temp buffer should already be correct size)
                ensureIndexBufferCapacity(env, indexCount, 0);

                g_d3d11.context->DrawIndexed(indexCount, 0, 0);
            }
        }
    } else {
        // Draw non-indexed
        // Logging disabled
        g_d3d11.context->Draw(vertexCount, 0);
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTexture
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

    // Name the resources for DirectX debugging
    char textureName[64];
    char srvName[64];
    snprintf(textureName, sizeof(textureName), "VitraTexture_%llu", handle);
    snprintf(srvName, sizeof(srvName), "VitraTextureSRV_%llu", handle);
    texture->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(textureName), textureName);
    srv->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(srvName), srvName);

    return static_cast<jlong>(handle);
}

/**
 * Create DirectX 11 texture from raw pixel data
 * Returns a handle (jlong) instead of boolean like createTextureFromId
 * Used by D3D11GlTexture for immediate texture creation with data
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTextureFromData
    (JNIEnv* env, jclass clazz, jbyteArray data, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Handle empty data array (allocate texture without initial data)
    jsize dataLength = 0;
    jbyte* bytes = nullptr;

    if (data != nullptr) {
        dataLength = env->GetArrayLength(data);
        if (dataLength > 0) {
            bytes = env->GetByteArrayElements(data, nullptr);
            if (!bytes) {
                return 0;
            }
        }
    }

    // Create DirectX 11 texture descriptor
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = width;
    desc.Height = height;
    desc.MipLevels = 1;  // Single mip level (mipmaps uploaded separately)
    desc.ArraySize = 1;
    desc.Format = static_cast<DXGI_FORMAT>(format);  // Use DirectX format directly
    desc.SampleDesc.Count = 1;
    desc.SampleDesc.Quality = 0;
    desc.Usage = D3D11_USAGE_DEFAULT;  // GPU read/write, UpdateSubresource for updates
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;  // Shader resource
    desc.CPUAccessFlags = 0;  // No CPU access (use UpdateSubresource)
    desc.MiscFlags = 0;

    // Create texture WITH or WITHOUT initial data based on input
    ComPtr<ID3D11Texture2D> texture;
    HRESULT hr;

    if (bytes != nullptr && dataLength > 0) {
        // Create with initial data
        D3D11_SUBRESOURCE_DATA initData = {};
        initData.pSysMem = bytes;
        initData.SysMemPitch = width * 4;  // Assume 4 bytes per pixel (RGBA/BGRA)
        initData.SysMemSlicePitch = 0;  // Not used for 2D textures

        hr = g_d3d11.device->CreateTexture2D(&desc, &initData, &texture);
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    } else {
        // Create without initial data (will be uploaded via UpdateSubresource later)
        hr = g_d3d11.device->CreateTexture2D(&desc, nullptr, &texture);
    }

    if (FAILED(hr)) {
        return 0;
    }

    // Create shader resource view
    D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
    srvDesc.Format = desc.Format;
    srvDesc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    srvDesc.Texture2D.MipLevels = 1;
    srvDesc.Texture2D.MostDetailedMip = 0;

    ComPtr<ID3D11ShaderResourceView> srv;
    hr = g_d3d11.device->CreateShaderResourceView(texture.Get(), &srvDesc, &srv);

    if (FAILED(hr)) {
        return 0;
    }

    // Generate handle and store texture + SRV
    uint64_t handle = generateHandle();
    g_textures[handle] = texture;
    g_shaderResourceViews[handle] = srv;

    return static_cast<jlong>(handle);
}

/**
 * Create DirectX 11 texture from OpenGL texture ID
 * Based on VulkanMod's texture allocation pattern
 * This creates an empty texture that will be filled via texSubImage2D later
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createTextureFromId
    (JNIEnv* env, jclass clazz, jint textureId, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return JNI_FALSE;

    uint64_t handle = static_cast<uint64_t>(textureId);

    // CRITICAL FIX (VulkanMod pattern): Keep old texture alive until new one is fully created
    // This prevents binding gaps where texture ID exists but has no data
    // Old textures will be released via ComPtr when we overwrite the map entry

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

    // Log texture creation (first 20 textures)
    static int createCount = 0;
    if (createCount < 20) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[TEXTURE_CREATE %d] Created texture ID=%d, size=%dx%d, format=%d, Total textures: %zu",
            createCount, textureId, width, height, format, g_shaderResourceViews.size());
        logToJava(env, msg);
        createCount++;
    }

    return JNI_TRUE;
}

/**
 * Update existing texture data using ID3D11DeviceContext::UpdateSubresource
 * This is MUCH more efficient than destroying and recreating the texture!
 *
 * Uses UpdateSubresource instead of Map/Unmap because textures are created with D3D11_USAGE_DEFAULT
 * (not DYNAMIC), which is the correct usage for shader resource views.
 */
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_updateTexture
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTexture__IJ
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

    // CRITICAL: Bind per-texture sampler if available (VulkanMod pattern)
    // This enables per-texture blur/clamp/mipmap parameters
    if (textureHandle != 0) {
        auto samplerIt = g_textureSamplers.find(textureHandle);
        if (samplerIt != g_textureSamplers.end()) {
            ID3D11SamplerState* sampler = samplerIt->second.Get();
            g_d3d11.context->PSSetSamplers(slot, 1, &sampler);
        } else {
            // No per-texture sampler, use default
            if (g_d3d11.defaultSamplerState) {
                ID3D11SamplerState* defaultSampler = g_d3d11.defaultSamplerState.Get();
                g_d3d11.context->PSSetSamplers(slot, 1, &defaultSampler);
            }
        }
    }

    // CRITICAL DEBUG: Log texture bindings to see if framebuffer textures are bound
    static int bindCount = 0;
    if (bindCount < 100) {  // Log first 100 bindings
        char msg[256];
        snprintf(msg, sizeof(msg), "[TEX_BIND] slot=%d, handle=0x%llx, srv=%p",
                 slot, (unsigned long long)textureHandle, srv);
        logToJava(env, msg);
        bindCount++;
    }

    // Temporary debug logging to diagnose UI texture issues
    if (bindCount < 50) {  // Log first 50 bindings to avoid spam
        if (srv) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[TEXTURE_BIND] Bound texture handle 0x%llX to slot %d, SRV=%p",
                (unsigned long long)textureHandle, slot, (void*)srv);
            logToJava(env, msg);
        } else {
            char msg[256];
            snprintf(msg, sizeof(msg), "[TEXTURE_BIND] Unbound texture from slot %d (NULL SRV)", slot);
            logToJava(env, msg);
        }
        bindCount++;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setConstantBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint slot) {

    if (!g_d3d11.initialized || slot < 0 || slot >= 4) return;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;

    // CRITICAL FIX: Use 's approach for constant buffers
    // According to  and Microsoft best practices:
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
        desc.Usage = D3D11_USAGE_DEFAULT;  //  uses DEFAULT, not DYNAMIC
        desc.ByteWidth = ((size + 15) / 16) * 16; // Align to 16 bytes
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = 0;  // No CPU access flags for DEFAULT usage

        HRESULT hr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[slot]);
        if (FAILED(hr)) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
    }

    // Update constant buffer data using UpdateSubresource ( approach)
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setViewport
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setScissorRect
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    g_d3d11.scissorRect.left = x;
    g_d3d11.scissorRect.top = y;
    g_d3d11.scissorRect.right = x + width;
    g_d3d11.scissorRect.bottom = y + height;
    g_d3d11.scissorEnabled = true;

    // CRITICAL FIX: Set ALL 16 scissor rects to prevent 0x0 rects in other slots
    D3D11_RECT scissorRects[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    scissorRects[0] = g_d3d11.scissorRect;
    for (int i = 1; i < D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE; i++) {
        scissorRects[i].left = 0;
        scissorRects[i].top = 0;
        scissorRects[i].right = g_d3d11.width;
        scissorRects[i].bottom = g_d3d11.height;
    }
    g_d3d11.context->RSSetScissorRects(D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE, scissorRects);
}

// ==================== DEBUG LAYER IMPLEMENTATION ====================

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDebugMessages
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeClearDebugMessages
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.infoQueue) return;

    g_d3d11.infoQueue->ClearStoredMessages();
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeSetDebugSeverity
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeBreakOnError
    (JNIEnv* env, jclass clazz, jboolean enabled) {

    if (!g_d3d11.infoQueue) return;

    // Break on error severity messages
    g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_ERROR, enabled ? TRUE : FALSE);
    g_d3d11.infoQueue->SetBreakOnSeverity(D3D11_MESSAGE_SEVERITY_CORRUPTION, enabled ? TRUE : FALSE);
}

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDeviceInfo
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

JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeGetDebugStats
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

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeValidateShader
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendState
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setDepthState
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

    // CRITICAL FIX: Use CACHED depth stencil states instead of creating new ones dynamically!
    // Creating new states causes them to override our carefully set cached states in depthMask()/depthFunc()
    // This was the root cause of the UI rendering bug - setDepthState() was being called from JniUtils
    // and creating new states with DepthEnable=TRUE, DepthWriteMask=ALL, overriding our UI depth-disabled state

    // Update global state tracker
    g_glState.depthTestEnabled = depthTestEnabled;
    g_glState.depthMaskEnabled = depthWriteEnabled;

    char msg[256];
    sprintf_s(msg, "[DEPTH_STATE] setDepthState called: test=%d, write=%d, func=0x%X - using cached state",
        depthTestEnabled, depthWriteEnabled, depthFunc);
    logToJava(env, msg);

    // Use the appropriate cached state based on depthTestEnabled
    if (!depthTestEnabled) {
        // Depth test is disabled - use depth-disabled cached state (no depth test, no depth writes)
        if (g_d3d11.depthStencilStateDisabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
            logToJava(env, "[DEPTH_STATE] Applied depthStencilStateDisabled (DepthEnable=0, DepthWriteMask=0)");
        }
    } else {
        // Depth test is enabled - use depth-enabled cached state
        if (g_d3d11.depthStencilStateEnabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateEnabled.Get(), 0);
            logToJava(env, "[DEPTH_STATE] Applied depthStencilStateEnabled (DepthEnable=1, DepthWriteMask=1)");
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setRasterizerState
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clearDepth
    (JNIEnv* env, jclass clazz, jfloat depth) {

    if (!g_d3d11.initialized || !g_d3d11.depthStencilView) return;

    // Clear only the depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, depth, 0);

    // Logging disabled
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setColorMask
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setOrthographicProjection
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setProjectionMatrix
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTransformMatrices
    (JNIEnv* env, jclass clazz, jfloatArray mvpData, jfloatArray modelViewData, jfloatArray projectionData) {

    logToJava(env, "[CB_UPLOAD_NATIVE] setTransformMatrices() called");

    if (!g_d3d11.initialized) {
        logToJava(env, "[CB_UPLOAD_NATIVE] EARLY RETURN - g_d3d11.initialized is FALSE");
        return;
    }

    if (!g_d3d11.constantBuffers[0]) {
        logToJava(env, "[CB_UPLOAD_NATIVE] EARLY RETURN - g_d3d11.constantBuffers[0] is NULL");
        return;
    }

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

    // VulkanMod-style: Use pre-multiplied MVP matrix from Minecraft
    // Minecraft provides MVP already calculated, so we don't need to multiply ourselves
    // We'll store it in b0 for direct use in shaders (like VulkanMod does)

    float mvpMat[16], modelViewMat[16], projMat[16];

    // Copy MVP matrix as-is (column-major, no transpose needed for HLSL column_major pragma)
    memcpy(mvpMat, mvpElements, 64);

    // Also copy ModelView and Projection separately for shaders that still use them
    memcpy(modelViewMat, mvElements, 64);
    memcpy(projMat, projElements, 64);

    // Release Java arrays
    env->ReleaseFloatArrayElements(mvpData, mvpElements, JNI_ABORT);
    env->ReleaseFloatArrayElements(modelViewData, mvElements, JNI_ABORT);
    env->ReleaseFloatArrayElements(projectionData, projElements, JNI_ABORT);

    // CRITICAL FIX: Upload to b0 (DynamicTransforms) with NEW layout per cbuffer_common.hlsli
    // cbuffer DynamicTransforms : register(b0) {
    //     float4x4 MVP;             // Offset 0, 64 bytes - PRE-MULTIPLIED MVP
    //     float4x4 ModelViewMat;    // Offset 64, 64 bytes
    //     float4 ColorModulator;    // Offset 128, 16 bytes
    //     float3 ModelOffset;       // Offset 144, 12 bytes
    //     float _pad0;              // Offset 156, 4 bytes
    //     float4x4 TextureMat;      // Offset 160, 64 bytes
    //     float LineWidth;          // Offset 224, 4 bytes
    // };
    // Total: 240 bytes
    D3D11_MAPPED_SUBRESOURCE mappedResource;
    HRESULT hr = g_d3d11.context->Map(g_d3d11.constantBuffers[0].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        char* bufferData = (char*)mappedResource.pData;

        // Copy MVP to offset 0 (64 bytes) - VulkanMod style
        memcpy(bufferData, mvpMat, 64);

        // Copy ModelView to offset 64 (64 bytes) - for shaders that still use separate matrices
        memcpy(bufferData + 64, modelViewMat, 64);

        // Copy ColorModulator to offset 128 (16 bytes)
        memcpy(bufferData + 128, g_d3d11.shaderColor, 16);

        // CRITICAL FIX: Zero-initialize remaining fields to prevent garbage values
        // D3D11_MAP_WRITE_DISCARD gives UNINITIALIZED memory (0xCCCCCCCC pattern)!
        // We must explicitly zero unused fields or they'll contain garbage

        // ModelOffset at offset 144 (12 bytes) - initialize to (0,0,0)
        memset(bufferData + 144, 0, 12);

        // _pad0 at offset 156 (4 bytes) - zero padding
        memset(bufferData + 156, 0, 4);

        // TextureMat at offset 160 (64 bytes) - initialize to identity matrix
        float identityMatrix[16] = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        memcpy(bufferData + 160, identityMatrix, 64);

        // LineWidth at offset 224 (4 bytes) - initialize to 1.0
        float defaultLineWidth = 1.0f;
        memcpy(bufferData + 224, &defaultLineWidth, 4);

        // _pad1 at offset 228 (12 bytes) - zero remaining padding to complete 240-byte buffer
        memset(bufferData + 228, 0, 12);

        g_d3d11.context->Unmap(g_d3d11.constantBuffers[0].Get(), 0);
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
        g_d3d11.context->PSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());

        // CRITICAL DEBUG: Log MVP matrix values
        char msg[256];
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] ✓ Uploaded MVP to b0 offset 0 (64 bytes)");
        logToJava(env, msg);
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] MVP[0]: %.3f, %.3f, %.3f, %.3f", mvpMat[0], mvpMat[1], mvpMat[2], mvpMat[3]);
        logToJava(env, msg);
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] MVP[12]: %.3f, MVP[13]: %.3f, MVP[14]: %.3f, MVP[15]: %.3f", mvpMat[12], mvpMat[13], mvpMat[14], mvpMat[15]);
        logToJava(env, msg);

        // Log ColorModulator
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] ColorModulator=(%.3f,%.3f,%.3f,%.3f) at b0+128",
            g_d3d11.shaderColor[0], g_d3d11.shaderColor[1], g_d3d11.shaderColor[2], g_d3d11.shaderColor[3]);
        logToJava(env, msg);
    } else {
        char msg[128];
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] FAILED to upload ModelView to b0 - HRESULT 0x%08X", hr);
        logToJava(env, msg);
        return;
    }

    // Upload Projection matrix to b1 (separate Projection cbuffer)
    // Create b1 if it doesn't exist
    if (!g_d3d11.constantBuffers[1]) {
        D3D11_BUFFER_DESC desc = {};
        desc.ByteWidth = 64;  // ProjMat is 64 bytes
        desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

        HRESULT createHr = g_d3d11.device->CreateBuffer(&desc, nullptr, &g_d3d11.constantBuffers[1]);
        if (FAILED(createHr)) {
            char msg[128];
            sprintf_s(msg, "[CB_UPLOAD_NATIVE] FAILED to create b1 constant buffer - HRESULT 0x%08X", createHr);
            logToJava(env, msg);
            return;
        }
    }

    hr = g_d3d11.context->Map(g_d3d11.constantBuffers[1].Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mappedResource);
    if (SUCCEEDED(hr)) {
        memcpy(mappedResource.pData, projMat, 64);
        g_d3d11.context->Unmap(g_d3d11.constantBuffers[1].Get(), 0);
        g_d3d11.context->VSSetConstantBuffers(1, 1, g_d3d11.constantBuffers[1].GetAddressOf());
        g_d3d11.context->PSSetConstantBuffers(1, 1, g_d3d11.constantBuffers[1].GetAddressOf());

        // Always log for debugging
        char msg[256];
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] ✓ Uploaded Projection to b1 (64 bytes)");
        logToJava(env, msg);
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] Projection[0]: %.3f, %.3f, %.3f, %.3f", projMat[0], projMat[1], projMat[2], projMat[3]);
        logToJava(env, msg);
    } else {
        char msg[128];
        sprintf_s(msg, "[CB_UPLOAD_NATIVE] FAILED to upload Projection to b1 - HRESULT 0x%08X", hr);
        logToJava(env, msg);
    }
}

// ==================== BUFFER MAPPING IMPLEMENTATION ====================

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_mapBuffer
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_unmapBuffer
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyBuffer
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTexture
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTextureRegion
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTextureToBuffer
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
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFence
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_signalFence
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
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isFenceSignaled
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForFence
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


// ============================================================================
// UNIFORM MANAGEMENT (FIX: Missing - causes ray artifacts)
// ============================================================================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform4f
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniformMatrix4f
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform1i
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform1f
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_useProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized) return;

    // REMOVED: Do NOT bind default shaders in useProgram()!
    // Shaders are set by bindShaderPipeline() and must NOT be overwritten.
    // useProgram() is called by OpenGL compatibility layer, but we use DirectX pipelines instead.
    // Binding default shaders here causes shader/input layout mismatch.
    //
    // For DirectX 11, programs are managed via D3D11Pipeline.bind() which calls bindShaderPipeline().
    // This function is kept as a no-op for OpenGL compatibility.

    // if (program != 0) {
    //     // Use default shaders (simplified approach)
    //     g_d3d11.context->VSSetShader(g_d3d11.defaultVertexShader.Get(), nullptr, 0);
    //     g_d3d11.context->PSSetShader(g_d3d11.defaultPixelShader.Get(), nullptr, 0);
    // } else {
    //     // No program bound - clear shaders
    //     g_d3d11.context->VSSetShader(nullptr, nullptr, 0);
    //     g_d3d11.context->PSSetShader(nullptr, nullptr, 0);
    // }
}

// ==================== DIRECT OPENGL → DIRECTX 11 TRANSLATION (VULKANMOD APPROACH) ====================

// Create shader from precompiled bytecode (for .cso files)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createGLProgramShader___3BII
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

        // CRITICAL FIX: Extract input signature from compiled shader bytecode
        // The input signature is required for CreateInputLayout() to work correctly
        // D3DGetBlobPart extracts the INPUT_SIGNATURE_BLOB from the full shader bytecode
        ComPtr<ID3DBlob> signatureBlob;
        HRESULT hr2 = D3DGetBlobPart(bytes, size, D3D_BLOB_INPUT_SIGNATURE_BLOB, 0, &signatureBlob);

        // LOG: Parse shader input signature to debug vertex format mismatches
        ID3D11ShaderReflection* pReflection = nullptr;
        if (SUCCEEDED(D3DReflect(bytes, size, IID_ID3D11ShaderReflection, (void**)&pReflection)) && pReflection) {
            D3D11_SHADER_DESC shaderDesc;
            pReflection->GetDesc(&shaderDesc);

            char logMsg[512];
            snprintf(logMsg, sizeof(logMsg),
                    "[VS_CREATED] Bytecode=%d bytes, InputParams=%d, BlobSize=%zu",
                    size, shaderDesc.InputParameters,
                    signatureBlob ? signatureBlob->GetBufferSize() : 0);
            logToJava(env, logMsg);

            // Log each input parameter
            for (UINT i = 0; i < shaderDesc.InputParameters; i++) {
                D3D11_SIGNATURE_PARAMETER_DESC paramDesc;
                pReflection->GetInputParameterDesc(i, &paramDesc);

                char paramMsg[256];
                snprintf(paramMsg, sizeof(paramMsg),
                        "[VS_INPUT_%d] %s%d (Mask=0x%X)",
                        i, paramDesc.SemanticName, paramDesc.SemanticIndex, paramDesc.Mask);
                logToJava(env, paramMsg);
            }

            pReflection->Release();
        }

        if (FAILED(hr2)) {
            // Fallback: If we can't extract signature, store the full bytecode
            // This might happen with older shader models or malformed shaders
            printf("[SHADER_CREATE_VS] WARNING: D3DGetBlobPart failed (HRESULT=0x%08X), storing full bytecode as fallback\n", hr2);
            ComPtr<ID3DBlob> fullBlob;
            if (SUCCEEDED(D3DCreateBlob(size, &fullBlob))) {
                memcpy(fullBlob->GetBufferPointer(), bytes, size);
                signatureBlob = fullBlob;
            } else {
                env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);
                return 0;
            }
        }

        shaderHandle = generateHandle();

        // CRITICAL FIX: Store FULL bytecode, not just signature!
        // CreateInputLayout() requires the full shader bytecode to validate input layout.
        // Storing only the signature blob causes input layout validation failures.
        ComPtr<ID3DBlob> fullBytecodeBlob;
        if (SUCCEEDED(D3DCreateBlob(size, &fullBytecodeBlob))) {
            memcpy(fullBytecodeBlob->GetBufferPointer(), bytes, size);
        }

        // CRITICAL: Store in maps BEFORE logging
        g_vertexShaders[shaderHandle] = vertexShader;
        g_shaderBlobs[shaderHandle] = fullBytecodeBlob ? fullBytecodeBlob : signatureBlob;  // Store full bytecode for input layout

        // Verify storage immediately
        auto verifyIt = g_vertexShaders.find(shaderHandle);
        if (verifyIt != g_vertexShaders.end()) {
            printf("[SHADER_CREATE_VS] SUCCESS: Handle 0x%llx stored and verified, map size=%zu, blob size=%zu\n",
                shaderHandle, g_vertexShaders.size(), signatureBlob->GetBufferSize());
            fflush(stdout);
        } else {
            printf("[SHADER_CREATE_VS] **ERROR**: Handle 0x%llx NOT FOUND after storage!\n", shaderHandle);
            fflush(stdout);
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shaderSource
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShader
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

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createProgram
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique DirectX 11 program handle (shader pipeline)
    uint64_t programHandle = generateHandle();

    // VULKANMOD APPROACH: Return handle as OpenGL program ID
    // Real implementation would create DirectX 11 shader pipeline
    return (jint)programHandle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_attachShader
    (JNIEnv* env, jclass clazz, jint program, jint shader) {

    if (!g_d3d11.initialized || program == 0 || shader == 0) return;

    // VULKANMOD APPROACH: Associate DirectX shader with program pipeline
    // Real implementation would update shader pipeline with attached shaders
    // For now, this is a no-op - handles are managed by the caller
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_linkProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized || program == 0) return;

    // VULKANMOD APPROACH: Finalize DirectX 11 shader pipeline
    // Real implementation would create complete shader pipeline from attached shaders
    // For now, this is a no-op - pipeline creation happens when needed
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_validateProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (!g_d3d11.initialized || program == 0) return;

    // VULKANMOD APPROACH: Validate DirectX 11 shader pipeline
    // Real implementation would validate pipeline completeness
    // For now, always return success
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteShader
    (JNIEnv* env, jclass clazz, jint shader) {

    if (shader == 0) return;

    // VULKANMOD APPROACH: Destroy DirectX 11 shader
    // Real implementation would convert shader ID to DirectX handle and destroy
    // For now, this is a no-op - handles are managed by Java garbage collection
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteProgram
    (JNIEnv* env, jclass clazz, jint program) {

    if (program == 0) return;

    // VULKANMOD APPROACH: Destroy DirectX 11 shader pipeline
    // Real implementation would convert program ID to DirectX pipeline handle and destroy
    // For now, this is a no-op
}

// Vertex attribute management (VulkanMod approach - direct DirectX calls)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Enable vertex attribute in DirectX input layout
    // Real implementation would update input layout state
    // For now, this is a no-op - input layout is created with shaders
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableVertexAttribArray
    (JNIEnv* env, jclass clazz, jint index) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Disable vertex attribute
    // Real implementation would update input layout state
    // For now, this is a no-op
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jlong pointer) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Define vertex attribute format for DirectX input layout
    // Real implementation would store for input layout creation
    // For now, this is a no-op - input layout comes from shader reflection
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribPointer_1
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jboolean normalized, jint stride, jobject pointer) {

    // ByteBuffer version - delegate to long pointer version
    Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribPointer(env, clazz, index, size, type, normalized, stride, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glVertexAttribIPointer
    (JNIEnv* env, jclass clazz, jint index, jint size, jint type, jint stride, jlong pointer) {

    if (!g_d3d11.initialized) return;

    // VULKANMOD APPROACH: Define integer vertex attribute format
    // Real implementation would store for input layout creation
    // For now, this is a no-op
}

// Uniform location management (VulkanMod approach - direct DirectX constant buffers)
JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetUniformLocation
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

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetUniformLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name) {

    // ByteBuffer version - simplified
    return -1;
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetAttribLocation
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

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_glGetAttribLocation_1
    (JNIEnv* env, jclass clazz, jint program, jobject name) {

    // ByteBuffer version - simplified
    return -1;
}

// Additional uniform methods (VulkanMod approach)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform2f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1) {

    if (!g_d3d11.initialized || location < 0) return;

    // Pack into 4-float uniform for DirectX constant buffer
    float data[4] = { v0, v1, 0.0f, 0.0f };
    Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform4f(env, clazz, location, data[0], data[1], data[2], data[3]);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform3f
    (JNIEnv* env, jclass clazz, jint location, jfloat v0, jfloat v1, jfloat v2) {

    if (!g_d3d11.initialized || location < 0) return;

    // Pack into 4-float uniform for DirectX constant buffer
    float data[4] = { v0, v1, v2, 0.0f };
    Java_com_vitra_render_jni_VitraD3D11Renderer_setUniform4f(env, clazz, location, data[0], data[1], data[2], data[3]);
}

// ==================== MISSING FRAMEBUFFER AND TEXTURE METHODS ====================

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFramebuffer
    (JNIEnv* env, jclass clazz, jint width, jint height) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();

    // For simplicity, return the handle directly (framebuffer creation is implicit in DirectX 11)
    return (jlong)handle;
}

// REMOVED: This overload (long, int) was causing Java overload resolution issues
// Java was calling this instead of the correct (int, int) overload at line 8283
// Keeping the code here for reference but commented out
/*
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target) {
    // WRONG OVERLOAD - DO NOT USE
}
*/

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferTexture2D
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint textarget, jlong textureHandle, jint level) {

    if (!g_d3d11.initialized) return;

    uint64_t fboId = (uint64_t)framebufferHandle;
    uint64_t texId = (uint64_t)textureHandle;

    // Get or create framebuffer entry
    D3D11Framebuffer& fbo = g_framebuffers[fboId];

    // GL_COLOR_ATTACHMENT0 = 0x8CE0
    if (attachment == 0x8CE0 || attachment == 0x8CE0 + 0) {
        // Find the texture in our texture map
        auto texIt = g_textures.find(texId);
        if (texIt == g_textures.end()) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO] ERROR: Texture handle 0x%llx not found for framebuffer 0x%llx",
                     (unsigned long long)texId, (unsigned long long)fboId);
            logToJava(env, msg);
            return;
        }

        ID3D11Texture2D* colorTexture = texIt->second.Get();
        fbo.colorTexture = colorTexture;

        // Get texture dimensions
        D3D11_TEXTURE2D_DESC texDesc;
        colorTexture->GetDesc(&texDesc);
        fbo.width = texDesc.Width;
        fbo.height = texDesc.Height;

        // Create Render Target View (RTV) for rendering TO this texture
        HRESULT hr = g_d3d11.device->CreateRenderTargetView(colorTexture, nullptr, &fbo.colorRTV);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO] ERROR: CreateRenderTargetView failed (hr=0x%08X) for fbo=0x%llx",
                     hr, (unsigned long long)fboId);
            logToJava(env, msg);
            return;
        }

        // Create Shader Resource View (SRV) for sampling FROM this texture in shaders
        // This is CRITICAL for blit_screen to sample the UI framebuffer!
        hr = g_d3d11.device->CreateShaderResourceView(colorTexture, nullptr, &fbo.colorSRV);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO] ERROR: CreateShaderResourceView failed (hr=0x%08X) for fbo=0x%llx",
                     hr, (unsigned long long)fboId);
            logToJava(env, msg);
            return;
        }

        // CRITICAL: Register the SRV in the texture binding map so bindTexture can find it!
        // When blit_screen binds the framebuffer's texture, it needs to find this SRV
        g_shaderResourceViews[texId] = fbo.colorSRV;

        char msg[256];
        snprintf(msg, sizeof(msg), "[FBO] Attached texture 0x%llx to framebuffer 0x%llx (RTV=%p, SRV=%p, %dx%d)",
                 (unsigned long long)texId, (unsigned long long)fboId,
                 (void*)fbo.colorRTV.Get(), (void*)fbo.colorSRV.Get(),
                 fbo.width, fbo.height);
        logToJava(env, msg);

        fbo.hasColor = true;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferRenderbuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target, jint attachment, jint renderbuffertarget, jlong renderbufferHandle) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, this maps to setting depth/stencil views
    // This is a placeholder implementation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_checkFramebufferStatus
    (JNIEnv* env, jclass clazz, jlong framebufferHandle, jint target) {

    if (!g_d3d11.initialized) return 0;

    // Always return framebuffer complete for simplicity
    return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyFramebuffer
    (JNIEnv* env, jclass clazz, jlong framebufferHandle) {

    if (!g_d3d11.initialized) return;

    uint64_t fboId = (uint64_t)framebufferHandle;

    auto fboIt = g_framebuffers.find(fboId);
    if (fboIt != g_framebuffers.end()) {
        // ComPtr automatically releases resources
        g_framebuffers.erase(fboIt);

        char msg[256];
        snprintf(msg, sizeof(msg), "[FBO] Destroyed framebuffer 0x%llx",
                 (unsigned long long)fboId);
        logToJava(env, msg);
    }
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createRenderbuffer
    (JNIEnv* env, jclass clazz, jint width, jint height, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();
    return (jlong)handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target) {

    if (!g_d3d11.initialized) return;

    // Renderbuffer binding in DirectX 11 is handled through depth/stencil views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderbufferStorage
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle, jint target, jint internalformat, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    // In DirectX 11, this is handled through texture/surface creation
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyRenderbuffer
    (JNIEnv* env, jclass clazz, jlong renderbufferHandle) {

    if (!g_d3d11.initialized) return;

    // Remove from tracking map
    // This is handled by the destroyResource method
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createVertexArray
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 0;

    // Generate unique handle and create tracking entry
    uint64_t handle = generateHandle();
    return (jlong)handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle) {

    if (!g_d3d11.initialized) return;

    // Vertex array binding in DirectX 11 is handled through input layouts
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyVertexArray
    (JNIEnv* env, jclass clazz, jlong vertexArrayHandle) {

    if (!g_d3d11.initialized) return;

    // Remove from tracking map
    // This is handled by the destroyResource method
}

// ==================== MISSING UNIFORM AND STATE METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameter
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param) {

    if (!g_d3d11.initialized) return;

    // Texture parameter setting in DirectX 11 is handled through sampler states
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameterf
    (JNIEnv* env, jclass clazz, jint target, jint pname, jfloat param) {

    if (!g_d3d11.initialized) return;

    // Texture parameter setting in DirectX 11 is handled through sampler states
    // This is a placeholder implementation
}

/**
 * Set texture parameter for a specific texture handle (per-texture sampler state)
 * This is the CRITICAL implementation for VulkanMod-style per-texture blur/clamp/mipmap parameters
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureParameter__JII
    (JNIEnv* env, jclass clazz, jlong textureHandle, jint pname, jint param) {

    if (!g_d3d11.initialized) return;

    uint64_t handle = static_cast<uint64_t>(textureHandle);

    // Get or create sampler params for this texture
    TextureSamplerParams& params = g_textureSamplerParams[handle];

    // Update the specific parameter
    switch (pname) {
        case 0x2801: // GL_TEXTURE_MIN_FILTER
            if (params.minFilter != param) {
                params.minFilter = param;
                params.needsUpdate = true;
            }
            break;
        case 0x2800: // GL_TEXTURE_MAG_FILTER
            if (params.magFilter != param) {
                params.magFilter = param;
                params.needsUpdate = true;
            }
            break;
        case 0x2802: // GL_TEXTURE_WRAP_S
            if (params.wrapS != param) {
                params.wrapS = param;
                params.needsUpdate = true;
            }
            break;
        case 0x2803: // GL_TEXTURE_WRAP_T
            if (params.wrapT != param) {
                params.wrapT = param;
                params.needsUpdate = true;
            }
            break;
        default:
            // Ignore unsupported parameters
            return;
    }

    // If parameters changed, recreate sampler state
    if (params.needsUpdate) {
        D3D11_SAMPLER_DESC samplerDesc = {};

        // Convert OpenGL filter to D3D11 filter
        // GL_NEAREST = 0x2600, GL_LINEAR = 0x2601
        bool minLinear = (params.minFilter == 0x2601);
        bool magLinear = (params.magFilter == 0x2601);

        if (minLinear && magLinear) {
            samplerDesc.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
        } else if (!minLinear && !magLinear) {
            samplerDesc.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
        } else if (minLinear) {
            samplerDesc.Filter = D3D11_FILTER_MIN_LINEAR_MAG_POINT_MIP_LINEAR;
        } else {
            samplerDesc.Filter = D3D11_FILTER_MIN_POINT_MAG_LINEAR_MIP_POINT;
        }

        // Convert OpenGL wrap mode to D3D11 address mode
        // GL_CLAMP_TO_EDGE = 0x812F, GL_REPEAT = 0x2901
        D3D11_TEXTURE_ADDRESS_MODE addressModeU = (params.wrapS == 0x812F)
            ? D3D11_TEXTURE_ADDRESS_CLAMP : D3D11_TEXTURE_ADDRESS_WRAP;
        D3D11_TEXTURE_ADDRESS_MODE addressModeV = (params.wrapT == 0x812F)
            ? D3D11_TEXTURE_ADDRESS_CLAMP : D3D11_TEXTURE_ADDRESS_WRAP;

        samplerDesc.AddressU = addressModeU;
        samplerDesc.AddressV = addressModeV;
        samplerDesc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        samplerDesc.MipLODBias = 0.0f;
        samplerDesc.MaxAnisotropy = 1;
        samplerDesc.ComparisonFunc = D3D11_COMPARISON_ALWAYS;
        samplerDesc.BorderColor[0] = 0.0f;
        samplerDesc.BorderColor[1] = 0.0f;
        samplerDesc.BorderColor[2] = 0.0f;
        samplerDesc.BorderColor[3] = 0.0f;
        samplerDesc.MinLOD = 0.0f;
        samplerDesc.MaxLOD = D3D11_FLOAT32_MAX;

        // Create new sampler state
        ComPtr<ID3D11SamplerState> newSampler;
        HRESULT hr = g_d3d11.device->CreateSamplerState(&samplerDesc, &newSampler);

        if (SUCCEEDED(hr)) {
            // Store sampler (ComPtr will auto-release old one)
            g_textureSamplers[handle] = newSampler;
            params.needsUpdate = false;

            // Log sampler creation (first 10 only)
            static int samplerCreateCount = 0;
            if (samplerCreateCount < 10) {
                char msg[256];
                snprintf(msg, sizeof(msg), "[SAMPLER_CREATE %d] Texture ID=%lld, filter=%s/%s, wrap=%s/%s",
                    samplerCreateCount,
                    handle,
                    minLinear ? "LINEAR" : "NEAREST",
                    magLinear ? "LINEAR" : "NEAREST",
                    addressModeU == D3D11_TEXTURE_ADDRESS_CLAMP ? "CLAMP" : "WRAP",
                    addressModeV == D3D11_TEXTURE_ADDRESS_CLAMP ? "CLAMP" : "WRAP");
                logToJava(env, msg);
                samplerCreateCount++;
            }
        } else {
            char msg[256];
            snprintf(msg, sizeof(msg), "[SAMPLER_CREATE_ERROR] Failed to create sampler for texture ID=%lld, HRESULT=0x%08X", handle, hr);
            logToJava(env, msg);
        }
    }
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTextureParameter
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

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTextureLevelParameter
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param) {

    if (!g_d3d11.initialized) return;

    // Pixel store mode in DirectX 11 is handled through texture upload parameters
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setLineWidth
    (JNIEnv* env, jclass clazz, jfloat width) {

    if (!g_d3d11.initialized) return;

    // Line width in DirectX 11 is handled through rasterizer state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPolygonOffset
    (JNIEnv* env, jclass clazz, jfloat factor, jfloat units) {

    if (!g_d3d11.initialized) return;

    // Polygon offset in DirectX 11 is handled through rasterizer state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendFunc
    (JNIEnv* env, jclass clazz, jint sfactor, jint dfactor) {

    if (!g_d3d11.initialized) return;

    // Blend function in DirectX 11 is handled through blend state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setBlendEquation
    (JNIEnv* env, jclass clazz, jint mode, jint modeAlpha) {

    if (!g_d3d11.initialized) return;

    // Blend equation in DirectX 11 is handled through blend state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setDrawBuffers
    (JNIEnv* env, jclass clazz, jintArray buffers) {

    if (!g_d3d11.initialized) return;

    // Draw buffers in DirectX 11 are handled through render target views
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilOpSeparate
    (JNIEnv* env, jclass clazz, jint face, jint sfail, jint dpfail, jint dppass) {

    if (!g_d3d11.initialized) return;

    // Stencil operations in DirectX 11 are handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilFuncSeparate
    (JNIEnv* env, jclass clazz, jint face, jint func, jint ref, jint mask) {

    if (!g_d3d11.initialized) return;

    // Stencil function in DirectX 11 is handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setStencilMaskSeparate
    (JNIEnv* env, jclass clazz, jint face, jint mask) {

    if (!g_d3d11.initialized) return;

    // Stencil mask in DirectX 11 is handled through depth-stencil state
    // This is a placeholder implementation
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getMaxTextureSize
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return 1024;

    // Return a reasonable default texture size for DirectX 11
    return 4096; // 4K textures are common
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_finish
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;

    // Finish in DirectX 11 - flush all commands
    g_d3d11.context->Flush();
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setHint
    (JNIEnv* env, jclass clazz, jint target, jint hint) {

    if (!g_d3d11.initialized) return;

    // Hints in DirectX 11 are mostly ignored or handled through driver settings
    // This is a placeholder implementation
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_copyTexSubImage2D
    (JNIEnv* env, jclass clazz, jint target, jint level, jint xoffset, jint yoffset, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;

    // Texture sub-image copying in DirectX 11 is handled through CopySubresourceRegion
    // This is a placeholder implementation
}

// ==================== PERFORMANCE OPTIMIZATION METHODS ====================
// Based on Direct3D 11 Performance Optimization Documentation

// Multithreading and Command List Support
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createDeferredContext
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

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createCommandList
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_executeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle) {

    if (!g_d3d11.initialized || commandListHandle == 0) return;

    auto it = g_inputLayouts.find(commandListHandle);
    if (it == g_inputLayouts.end()) return;

    ID3D11CommandList* commandList = reinterpret_cast<ID3D11CommandList*>(it->second.Get());


    g_d3d11.context->ExecuteCommandList(commandList, TRUE);

    // Clean up command list after execution
    g_inputLayouts.erase(it);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_closeCommandList
    (JNIEnv* env, jclass clazz, jlong commandListHandle) {

    if (!g_d3d11.initialized || commandListHandle == 0) return;

    auto it = g_inputLayouts.find(commandListHandle);
    if (it != g_inputLayouts.end()) {
        g_inputLayouts.erase(it);
    }
}

// Batching and Optimization
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_beginTextBatch
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for text rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Enable blending for text transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_endTextBatch
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Flush any batched text rendering commands
    g_d3d11.context->Flush();
}

// REMOVED: beginFrameSafe and endFrameSafe - use beginFrame/endFrame instead

// Performance Profiling and Debugging
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getDebugStats
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) {
        return env->NewStringUTF("DirectX 11 not initialized");
    }


    // Logging disabled - return empty stats
    return env->NewStringUTF("Debug stats disabled");
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_nativeProcessDebugMessages
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
    OutputDebugStringA(countMsg);  // Also output to DebugView
    if (messageCount > 0) {

        // Open debug log file for appending
        static FILE* debugLogFile = nullptr;
        static bool logFileInitialized = false;

        if (!logFileInitialized) {
            // Get current working directory to ensure correct path
            char workingDir[MAX_PATH];
            GetCurrentDirectoryA(MAX_PATH, workingDir);

            // Log the working directory for debugging
            char wdMsg[512];
            sprintf_s(wdMsg, "[NATIVE] Current working directory: %s", workingDir);
            logToJava(env, wdMsg);

            // Create debug/logs directory structure
            CreateDirectoryA("debug", NULL);
            CreateDirectoryA("debug\\logs", NULL);

            // Open log file with timestamp in debug/logs/
            time_t now = time(nullptr);
            struct tm timeinfo;
            localtime_s(&timeinfo, &now);
            char filename[256];
            strftime(filename, sizeof(filename), "debug\\logs\\dx11_native_%Y%m%d_%H%M%S.log", &timeinfo);

            fopen_s(&debugLogFile, filename, "w");
            if (debugLogFile) {
                fprintf(debugLogFile, "=== DirectX 11 Native Debug Log ===\n");
                fprintf(debugLogFile, "Initialized at: %s\n", asctime(&timeinfo));
                fprintf(debugLogFile, "Working directory: %s\n", workingDir);
                fprintf(debugLogFile, "Configuration:\n");
                fprintf(debugLogFile, "  - Message limit: 4096\n");
                fprintf(debugLogFile, "  - Break on corruption: YES\n");
                fprintf(debugLogFile, "  - Break on error: NO\n");
                fprintf(debugLogFile, "  - Logging: File + Java Console\n");
                fprintf(debugLogFile, "==========================================\n\n");
                fflush(debugLogFile);

                char successMsg[512];
                sprintf_s(successMsg, "[NATIVE] Debug log file created successfully: %s", filename);
                logToJava(env, successMsg);
            } else {
                char errorMsg[512];
                sprintf_s(errorMsg, "[NATIVE] ERROR: Failed to create debug log file: %s", filename);
                logToJava(env, errorMsg);
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

                // Enhanced output to dbgview with consistent formatting
                outputToDbgView(severityStr, categoryStr, message->ID, message->pDescription);

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

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isDebugEnabled
    (JNIEnv* env, jclass clazz) {

    return g_d3d11.debugEnabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDebug
    (JNIEnv* env, jclass clazz, jboolean enable) {

    g_d3d11.debugEnabled = (enable == JNI_TRUE);

    if (g_d3d11.debugEnabled) {
    } else {
    }
}

// Resource Optimization
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_precompileShaderForDirectX11
    (JNIEnv* env, jclass clazz, jbyteArray hlslBytecode, jint size, jstring entryPoint, jstring target) {

    if (!g_d3d11.initialized) return 0;

    jbyte* bytecode = env->GetByteArrayElements(hlslBytecode, nullptr);
    if (!bytecode) return 0;

    const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
    const char* targetStr = env->GetStringUTFChars(target, nullptr);


    ID3DBlob* shaderBlob = nullptr;
    ID3DBlob* errorBlob = nullptr;

    // STRICT MODE: warnings are treated as errors
    UINT flags = D3DCOMPILE_OPTIMIZATION_LEVEL3 | D3DCOMPILE_WARNINGS_ARE_ERRORS;

    HRESULT hr = D3DCompile(
        bytecode,
        size,
        nullptr,
        nullptr,
        nullptr,
        entry,
        targetStr,
        flags,
        0,
        &shaderBlob,
        &errorBlob
    );

    if (FAILED(hr) || errorBlob) {
        if (errorBlob) {
            const char* errorMsg = (const char*)errorBlob->GetBufferPointer();
            char logBuffer[2048];
            snprintf(logBuffer, sizeof(logBuffer), "[SHADER_COMPILE_FATAL] Bytecode compilation failed for target=%s, Error: %.1500s", targetStr, errorMsg);
            logToJava(env, logBuffer);
            OutputDebugStringA(logBuffer);

            // CRASH: Shader compilation must be perfect
            MessageBoxA(nullptr, logBuffer, "FATAL: Shader Compilation Failed", MB_OK | MB_ICONERROR);
            errorBlob->Release();
            std::terminate();
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

// NEW: Correct signature matching Java declaration: precompileShaderForDirectX11(String shaderSource, int shaderType)
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_precompileShaderForDirectX11__Ljava_lang_String_2I
    (JNIEnv* env, jclass clazz, jstring shaderSource, jint shaderType) {

    if (!g_d3d11.initialized) return 0;

    // CRITICAL: Check for null Java string before calling GetStringUTFChars
    // This prevents JVM access violation when shaderSource is null
    if (shaderSource == nullptr) {
        OutputDebugStringA("[precompileShaderForDirectX11] ERROR: shaderSource is null!");
        return 0;
    }

    // Get HLSL source code from Java string
    const char* hlslSource = env->GetStringUTFChars(shaderSource, nullptr);
    if (!hlslSource) return 0;

    // Determine target profile based on shader type
    const char* target = nullptr;
    if (shaderType == 0) { // SHADER_TYPE_VERTEX
        target = "vs_5_0";
    } else if (shaderType == 1) { // SHADER_TYPE_PIXEL
        target = "ps_5_0";
    } else {
        env->ReleaseStringUTFChars(shaderSource, hlslSource);
        return 0;
    }

    // Set compilation flags - STRICT MODE: warnings are treated as errors
    UINT flags = D3DCOMPILE_WARNINGS_ARE_ERRORS;  // CRITICAL: Fail on any warning
    if (g_d3d11.debugEnabled) {
        flags |= D3DCOMPILE_DEBUG | D3DCOMPILE_SKIP_OPTIMIZATION;
    }

    // Compile HLSL source
    ID3DBlob* shaderBlob = nullptr;
    ID3DBlob* errorBlob = nullptr;

    HRESULT hr = D3DCompile(
        hlslSource,
        strlen(hlslSource),
        nullptr,                      // Source name
        nullptr,                      // Defines
        D3D_COMPILE_STANDARD_FILE_INCLUDE,  // Include handler for #include directives
        "main",                       // Entry point
        target,                       // Target profile
        flags,
        0,
        &shaderBlob,
        &errorBlob
    );

    if (FAILED(hr) || errorBlob) {
        // CRITICAL: Crash on any error or warning
        if (errorBlob) {
            const char* errorMsg = (const char*)errorBlob->GetBufferPointer();
            char logBuffer[2048];
            snprintf(logBuffer, sizeof(logBuffer), "[SHADER_COMPILE_FATAL] Target=%s, Error: %.1500s", target, errorMsg);
            logToJava(env, logBuffer);
            OutputDebugStringA(logBuffer);
            errorBlob->Release();

            // CRASH: Shader compilation must be perfect
            MessageBoxA(nullptr, logBuffer, "FATAL: Shader Compilation Failed", MB_OK | MB_ICONERROR);
            std::terminate();
        }
        env->ReleaseStringUTFChars(shaderSource, hlslSource);
        return 0;
    }

    // Create shader based on type
    uint64_t handle = generateHandle();
    if (shaderType == 0) { // Vertex shader
        ID3D11VertexShader* vertexShader = nullptr;
        hr = g_d3d11.device->CreateVertexShader(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(), nullptr, &vertexShader);
        if (SUCCEEDED(hr)) {
            g_vertexShaders[handle] = vertexShader;

            // CRITICAL: Store vertex shader bytecode for input layout creation later
            // Input layouts require vertex shader bytecode signature
            ComPtr<ID3DBlob> storedBlob;
            D3DGetBlobPart(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(),
                          D3D_BLOB_INPUT_SIGNATURE_BLOB, 0, &storedBlob);
            if (storedBlob) {
                g_shaderBlobs[handle] = storedBlob;  // Store for input layout creation

                // LOG: Parse and display shader input signature to debug mismatch issues
                ID3D11ShaderReflection* pReflection = nullptr;
                hr = D3DReflect(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(),
                               IID_ID3D11ShaderReflection, (void**)&pReflection);
                if (SUCCEEDED(hr) && pReflection) {
                    D3D11_SHADER_DESC shaderDesc;
                    pReflection->GetDesc(&shaderDesc);

                    char inputSigLog[512];
                    snprintf(inputSigLog, sizeof(inputSigLog),
                            "[SHADER_COMPILE_VS] Compiled vertex shader - InputParams: %d, BlobSize: %zu bytes",
                            shaderDesc.InputParameters, storedBlob->GetBufferSize());
                    logToJava(env, inputSigLog);

                    // Log each input parameter
                    for (UINT i = 0; i < shaderDesc.InputParameters; i++) {
                        D3D11_SIGNATURE_PARAMETER_DESC paramDesc;
                        pReflection->GetInputParameterDesc(i, &paramDesc);

                        char paramLog[256];
                        snprintf(paramLog, sizeof(paramLog),
                                "[SHADER_INPUT_%d] %s%d (Mask: 0x%X)",
                                i, paramDesc.SemanticName, paramDesc.SemanticIndex, paramDesc.Mask);
                        logToJava(env, paramLog);
                    }

                    pReflection->Release();
                }
            }

            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraVertexShader_%llu", handle);
            vertexShader->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(shaderName), shaderName);
        } else {
            handle = 0;
        }
    } else if (shaderType == 1) { // Pixel shader
        ID3D11PixelShader* pixelShader = nullptr;
        hr = g_d3d11.device->CreatePixelShader(shaderBlob->GetBufferPointer(), shaderBlob->GetBufferSize(), nullptr, &pixelShader);
        if (SUCCEEDED(hr)) {
            g_pixelShaders[handle] = pixelShader;

            char shaderName[64];
            snprintf(shaderName, sizeof(shaderName), "VitraPixelShader_%llu", handle);
            pixelShader->SetPrivateData(WKPDID_D3DDebugObjectName, strlen(shaderName), shaderName);
        } else {
            handle = 0;
        }
    }

    shaderBlob->Release();
    env->ReleaseStringUTFChars(shaderSource, hlslSource);

    return handle;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_discardResource
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_evictResource
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

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isResident
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeCrosshairRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for crosshair rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);

    // CRITICAL FIX: Use cached depth-disabled state instead of nullptr
    // nullptr causes D3D11 to use DEFAULT state (depth writes ENABLED!)
    if (g_d3d11.depthStencilStateDisabled) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
    }

    // Enable blending for proper overlay
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeButtonRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Set optimal state for UI button rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Enable blending for button transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);

    // CRITICAL FIX: Use cached depth-disabled state instead of nullptr
    // nullptr causes D3D11 to use DEFAULT state (depth writes ENABLED!)
    if (g_d3d11.depthStencilStateDisabled) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeContainerBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Use efficient quad rendering for backgrounds
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Optimized blending for backgrounds
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeContainerLabels
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimize for text rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // High-quality text rendering with proper blending
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeDirtBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Efficient background rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // No blending for solid backgrounds
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeFadingBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for fade effects
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Enable smooth fading with proper blending
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeLogoRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // High-quality logo rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Proper alpha blending for logo transparency
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizePanoramaRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimize for panoramic backgrounds
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Disable blending for solid panorama
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeScreenBackground
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Most efficient full-screen quad rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // No blending for solid backgrounds
    g_d3d11.context->OMSetBlendState(nullptr, nullptr, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeSlotHighlight
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for UI highlights
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);

    // Proper blending for highlights
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeSlotRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Efficient slot rendering
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // Standard blending for slot items
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeTooltipRendering
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Optimized for text tooltips
    g_d3d11.context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

    // High-quality text rendering
    float blendFactor[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
    g_d3d11.context->OMSetBlendState(g_d3d11.blendState.Get(), blendFactor, 0xFFFFFFFF);
}

// Matrix Optimizations
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isMatrixDirectX11Optimized
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixMultiplication
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixInversion
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeMatrixTranspose
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeTranslationMatrix
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeRotationMatrix
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_optimizeScaleMatrix
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
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_isShaderDirectX11Compatible
    (JNIEnv* env, jclass clazz, jlong shaderHandle) {

    if (!g_d3d11.initialized || shaderHandle == 0) return JNI_FALSE;

    // Check if shader exists in our tracking maps
    if (g_vertexShaders.find(shaderHandle) != g_vertexShaders.end() ||
        g_pixelShaders.find(shaderHandle) != g_pixelShaders.end()) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getOptimizedDirectX11Shader
    (JNIEnv* env, jclass clazz, jlong originalShaderHandle) {

    if (!g_d3d11.initialized || originalShaderHandle == 0) return 0;

    // For now, return the same handle - optimization would be done during compilation
    // In a full implementation, this could recompile with higher optimization
    return originalShaderHandle;
}

// Frame Synchronization and VSync
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setVsync
    (JNIEnv* env, jclass clazz, jboolean enabled) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;


    // VSync is controlled via Present() sync interval (0 = no VSync, 1 = VSync)
    // SetMaximumFrameLatency would require DXGI 1.2+ (IDXGISwapChain2),
    // but basic VSync works via Present's first parameter
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getOptimalFramerateLimit
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForGpuCommands
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_waitForIdle
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

// FIX #1: Submit pending texture/buffer uploads (VulkanMod pattern)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_submitPendingUploads
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;

    // For D3D11, this is simpler than Vulkan's UploadManager
    // We just need to flush the context to ensure all pending uploads complete
    // In the future, this would handle staging buffer → GPU texture copies
    g_d3d11.context->Flush();
}

// Display and Window Management
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_handleDisplayResize
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setWindowActiveState
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_initializeDirectXSafe
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_shutdownSafe
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
    g_textureSamplers.clear();        // Clear per-texture samplers
    g_textureSamplerParams.clear();   // Clear sampler parameters
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_prepareRenderContext
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_cleanupRenderContext
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Flush any pending commands
    g_d3d11.context->Flush();

    // Clear state to default
    g_d3d11.context->ClearState();
}

// Buffer Management Optimizations
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_adjustOrthographicProjection
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_adjustPerspectiveProjection
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_drawMesh
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clearDepthBuffer
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Clear depth buffer
    g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_presentFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.swapChain) return;

    // Flush pending GPU commands before presenting (prevents stalls)
    // VulkanMod equivalent: vkQueueWaitIdle before vkQueuePresentKHR
    // This ensures all GPU work is submitted before we present the frame
    g_d3d11.context->Flush();

    // Present the frame
    HRESULT hr = g_d3d11.swapChain->Present(1, 0); // VSync enabled
    if (FAILED(hr)) {
    }
}

// ==================== MISSING METHODS FOR RENDERSYSTEMMIXIN ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setModelViewMatrix
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setTextureMatrix
    (JNIEnv* env, jclass clazz, jfloatArray matrixData) {
    // Stub implementation - texture matrix
    if (!g_d3d11.initialized) return;
    // TODO: Implement texture matrix handling
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {
    if (!g_d3d11.initialized) return;

    // CRITICAL FIX: Store shader color in g_d3d11 structure
    // This is the ColorModulator uniform that multiplies all fragment colors
    // When not properly reset, it stays at Mojang logo red (0.937, 0.196, 0.239, 1.0)
    g_d3d11.shaderColor[0] = r;
    g_d3d11.shaderColor[1] = g;
    g_d3d11.shaderColor[2] = b;
    g_d3d11.shaderColor[3] = a;

    // Upload ColorModulator to fragment constant buffer (b1)
    // Layout: ColorModulator (vec4, 16 bytes at offset 0)
    // This will be uploaded to b1 when the shader is bound via uploadStandardUniforms()
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderFogColor
    (JNIEnv* env, jclass clazz, jfloat r, jfloat g, jfloat b, jfloat a) {
    // Stub implementation - fog color
    if (!g_d3d11.initialized) return;
    // TODO: Implement fog color handling
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setShaderLightDirection
    (JNIEnv* env, jclass clazz, jint index, jfloat x, jfloat y, jfloat z) {
    // Stub implementation - light direction
    if (!g_d3d11.initialized) return;
    // TODO: Implement light direction handling
}

// ==================== GLSTATEMANAGER COMPATIBILITY METHODS ====================

// NOTE: GlStateTracking structure and g_glState global variable are declared at the top of the file (line 36-51)
// This is necessary for early access in setShaderPipeline() function

// Texture operations (int ID versions for GlStateManager)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTexture__I
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

    // ENHANCED LOGGING: Log function entry with parameters
    static int entryLogCount = 0;
    if (entryLogCount < 50) {  // Log first 50 calls
        char msg[128];
        snprintf(msg, sizeof(msg), "[BIND_ENTRY %d] bindTexture called: ID=%d, currentUnit=%d",
            entryLogCount, textureId, g_glState.currentTextureUnit);
        logToJava(env, msg);
        entryLogCount++;
    }

    // CRITICAL: Validate texture unit is in range BEFORE array access!
    if (g_glState.currentTextureUnit < 0 || g_glState.currentTextureUnit >= 32) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[TEXTURE_ERROR] Invalid texture unit: %d (valid range: 0-31)",
            g_glState.currentTextureUnit);
        logToJava(env, msg);
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

            // CRITICAL: Bind per-texture sampler if available (VulkanMod pattern)
            // This enables per-texture blur/clamp/mipmap parameters
            auto samplerIt = g_textureSamplers.find(handle);
            if (samplerIt != g_textureSamplers.end()) {
                ID3D11SamplerState* sampler = samplerIt->second.Get();
                ctx->PSSetSamplers(g_glState.currentTextureUnit, 1, &sampler);
            } else {
                // No per-texture sampler, use default
                if (g_d3d11.defaultSamplerState) {
                    ID3D11SamplerState* defaultSampler = g_d3d11.defaultSamplerState.Get();
                    ctx->PSSetSamplers(g_glState.currentTextureUnit, 1, &defaultSampler);
                }
            }

            // ENHANCED LOGGING: Log ALL texture bindings (no limit) to diagnose UI issue
            static int bindCount = 0;
            char msg[128];
            snprintf(msg, sizeof(msg), "[TEXTURE_BIND %d] Successfully bound texture ID=%d to slot %d, SRV=0x%p",
                bindCount, textureId, g_glState.currentTextureUnit, srv);
            logToJava(env, msg);
            bindCount++;
        } else {
            // CRITICAL: Texture found in map but SRV pointer is NULL!
            static int nullSrvCount = 0;
            if (nullSrvCount < 20) {
                char msg[128];
                snprintf(msg, sizeof(msg), "[TEXTURE_ERROR] Texture ID=%d found in map but SRV is NULL! Slot=%d",
                    textureId, g_glState.currentTextureUnit);
                logToJava(env, msg);
                nullSrvCount++;
            }
        }
    } else {
        // CRITICAL: Log when texture not found - this is likely the issue!
        static int notFoundCount = 0;
        if (notFoundCount < 20) {  // Increase to 20 to see more errors
            char msg[512];

            // List first 10 texture IDs currently in the map
            std::string texIdList = "";
            int count = 0;
            for (const auto& pair : g_shaderResourceViews) {
                if (count < 10) {
                    texIdList += std::to_string(pair.first) + " ";
                    count++;
                } else {
                    texIdList += "...";
                    break;
                }
            }

            snprintf(msg, sizeof(msg), "[TEXTURE_ERROR %d] Texture ID=%d NOT FOUND! Total textures: %zu, Existing IDs: %s",
                notFoundCount, textureId, g_shaderResourceViews.size(), texIdList.c_str());
            logToJava(env, msg);
            notFoundCount++;
        }
    }
}

/**
 * Set the active texture unit for subsequent texture operations
 * Maps to GL_TEXTURE0 + textureUnit
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setActiveTextureUnit
    (JNIEnv* env, jclass clazz, jint textureUnit) {

    // Validate texture unit range
    if (textureUnit < 0 || textureUnit >= 32) {
        return; // Invalid texture unit - safe no-op
    }

    // Update current texture unit
    g_glState.currentTextureUnit = textureUnit;
}

/**
 * Bind texture to specific slot (VulkanMod VTextureSelector pattern)
 * This is the CRITICAL method for D3D11TextureSelector.bindShaderTextures()
 *
 * Unlike bindTexture__I which binds to currentTextureUnit, this binds directly to a specific slot.
 * This ensures textures are bound to the correct shader sampler slots regardless of active texture state.
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindTextureToSlot__IJ
    (JNIEnv* env, jclass clazz, jint slot, jlong d3d11Handle) {

    // CRITICAL: VulkanMod pattern - safe no-op if DirectX not initialized
    if (!g_d3d11.initialized) {
        return;
    }

    // Check context validity
    if (!g_d3d11.context || !g_d3d11.context.Get()) {
        return;
    }

    ID3D11DeviceContext* ctx = g_d3d11.context.Get();
    if (!ctx) {
        return;
    }

    // Validate slot is in range
    if (slot < 0 || slot >= 16) {  // D3D11 shader supports up to 128, but Minecraft uses max 16
        char msg[128];
        snprintf(msg, sizeof(msg), "[TEXTURE_SELECTOR_ERROR] Invalid texture slot: %d (valid range: 0-15)", slot);
        logToJava(env, msg);
        return;
    }

    // Handle unbind (d3d11Handle == 0)
    if (d3d11Handle == 0) {
        ID3D11ShaderResourceView* nullSRV = nullptr;
        ctx->PSSetShaderResources(slot, 1, &nullSRV);
        return;
    }

    // Lookup D3D11 SRV by handle (handle is texture ID)
    uint64_t handle = static_cast<uint64_t>(d3d11Handle);
    auto it = g_shaderResourceViews.find(handle);
    if (it != g_shaderResourceViews.end()) {
        ID3D11ShaderResourceView* srv = it->second.Get();
        if (srv != nullptr) {
            // Bind SRV to pixel shader slot
            ID3D11ShaderResourceView* srvArray[1] = { srv };
            ctx->PSSetShaderResources(slot, 1, srvArray);

            // Bind sampler for this slot
            auto samplerIt = g_textureSamplers.find(handle);
            if (samplerIt != g_textureSamplers.end()) {
                ID3D11SamplerState* sampler = samplerIt->second.Get();
                ctx->PSSetSamplers(slot, 1, &sampler);
            } else if (g_d3d11.defaultSamplerState) {
                ID3D11SamplerState* defaultSampler = g_d3d11.defaultSamplerState.Get();
                ctx->PSSetSamplers(slot, 1, &defaultSampler);
            }

            // Debug logging
            static int bindCount = 0;
            if (bindCount < 100) {  // Log first 100 bindings
                char msg[256];
                snprintf(msg, sizeof(msg), "[TEXTURE_SELECTOR_BIND %d] Bound texture handle=0x%llx to slot %d, SRV=0x%p",
                    bindCount, (unsigned long long)d3d11Handle, slot, srv);
                logToJava(env, msg);
                bindCount++;
            }
        } else {
            char msg[128];
            snprintf(msg, sizeof(msg), "[TEXTURE_SELECTOR_ERROR] Texture handle=0x%llx found but SRV is NULL! Slot=%d",
                (unsigned long long)d3d11Handle, slot);
            logToJava(env, msg);
        }
    } else {
        static int notFoundCount = 0;
        if (notFoundCount < 20) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[TEXTURE_SELECTOR_ERROR %d] Texture handle=0x%llx NOT FOUND! Total textures: %zu, Slot=%d",
                notFoundCount, (unsigned long long)d3d11Handle, g_shaderResourceViews.size(), slot);
            logToJava(env, msg);
            notFoundCount++;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteTexture__I
    (JNIEnv* env, jclass clazz, jint textureId) {

    if (!g_d3d11.initialized || textureId == 0) return;

    uint64_t handle = static_cast<uint64_t>(textureId);

    // Remove from tracking maps
    g_textures.erase(handle);
    g_shaderResourceViews.erase(handle);
    g_textureSamplers.erase(handle);        // Remove per-texture sampler
    g_textureSamplerParams.erase(handle);   // Remove sampler parameters

    // Unbind if currently bound
    for (int i = 0; i < 32; i++) {
        if (g_glState.boundTextures[i] == textureId) {
            g_glState.boundTextures[i] = 0;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_releaseTexture
    (JNIEnv* env, jclass clazz, jlong textureHandle) {

    if (!g_d3d11.initialized || textureHandle == 0) return;

    // Remove from tracking maps (ComPtr will automatically release)
    g_textures.erase(textureHandle);
    g_shaderResourceViews.erase(textureHandle);
    g_textureSamplers.erase(textureHandle);        // Remove per-texture sampler
    g_textureSamplerParams.erase(textureHandle);   // Remove sampler parameters

    // Unbind if currently bound (need to convert handle to OpenGL ID for state tracking)
    // Note: This is a simplified unbind - we're using handle-based tracking
    for (int i = 0; i < 32; i++) {
        if (g_glState.boundTextures[i] == static_cast<int>(textureHandle)) {
            g_glState.boundTextures[i] = 0;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texImage2D
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texSubImage2D
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texSubImage2DWithPitch
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

        // Log first 20 texture uploads
        static int uploadCount = 0;
        if (uploadCount < 20) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[TEXTURE_UPLOAD %d] Uploaded to texture ID=%d, region=(%d,%d,%d,%d), rowPitch=%d",
                uploadCount, textureId, offsetX, offsetY, width, height, rowPitch);
            logToJava(env, msg);
            uploadCount++;
        }
    } catch (...) {
        // Invalid memory access - skip texture upload
        char msg[128];
        snprintf(msg, 128, "[TEXTURE_UPLOAD_ERROR] Failed to upload to texture ID=%d - invalid memory access", textureId);
        logToJava(env, msg);
        return;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_activeTexture
    (JNIEnv* env, jclass clazz, jint texture) {

    if (!g_d3d11.initialized) return;

    // GL_TEXTURE0 = 0x84C0
    int unit = texture - 0x84C0;
    if (unit >= 0 && unit < 32) {
        // DEBUG: Log first 20 activeTexture calls
        static int activeTexCount = 0;
        if (activeTexCount < 20) {
            char msg[128];
            snprintf(msg, sizeof(msg), "[ACTIVE_TEXTURE %d] Changed from unit %d to unit %d (GL enum: 0x%X)",
                activeTexCount, g_glState.currentTextureUnit, unit, texture);
            logToJava(env, msg);
            activeTexCount++;
        }

        g_glState.currentTextureUnit = unit;
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_texParameteri
    (JNIEnv* env, jclass clazz, jint target, jint pname, jint param) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 uses sampler states instead of texture parameters
    // This would need a sampler state cache, but for now we'll use default samplers
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getTexLevelParameter
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_pixelStore
    (JNIEnv* env, jclass clazz, jint pname, jint param) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 doesn't have pixel store parameters like OpenGL
    // These are handled differently in D3D11 (pitch/stride in UpdateSubresource)
}

// Blend state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableBlend
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableBlend
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendFunc
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendFuncSeparate
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_blendEquation
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableDepthTest
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.depthTestEnabled = true;

    // CRITICAL FIX: Reuse cached depth stencil state instead of creating new one every frame
    // This fixes the bug where local ComPtr was destroyed immediately after OMSetDepthStencilState
    if (g_d3d11.depthStencilStateEnabled) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateEnabled.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableDepthTest
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.depthTestEnabled = false;

    // CRITICAL FIX: Reuse cached depth stencil state instead of creating new one every frame
    // This fixes the bug where local ComPtr was destroyed immediately after OMSetDepthStencilState
    // causing undefined behavior and invisible UI rendering
    if (g_d3d11.depthStencilStateDisabled) {
        g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_depthFunc
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

    // CRITICAL FIX: For now, just use cached states to avoid local ComPtr destruction bug
    // When depth test is disabled, ALWAYS use depth-disabled state (no depth writes)
    // TODO: Support different depth functions by creating more cached states
    if (!g_glState.depthTestEnabled) {
        if (g_d3d11.depthStencilStateDisabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
        }
    } else {
        if (g_d3d11.depthStencilStateEnabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateEnabled.Get(), 0);
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_depthMask
    (JNIEnv* env, jclass clazz, jboolean flag) {

    if (!g_d3d11.initialized) return;

    // CRITICAL: Track depth mask state
    g_glState.depthMaskEnabled = flag;

    // CRITICAL FIX: Use CACHED depth stencil states (don't create local ones that get destroyed!)
    // When depth test is disabled, ALWAYS use the cached depthStencilStateDisabled (has DepthWriteMask=ZERO)
    // This fixes the bug where local ComPtr was destroyed immediately, causing undefined behavior
    char msg[256];
    sprintf_s(msg, "[DEPTH_MASK] depthMask(%d) called: depthTestEnabled=%d, using cached state",
        flag, g_glState.depthTestEnabled);
    logToJava(env, msg);

    if (!g_glState.depthTestEnabled) {
        // Depth test is disabled - ALWAYS use depth-disabled cached state (no depth writes)
        if (g_d3d11.depthStencilStateDisabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateDisabled.Get(), 0);
        }
    } else {
        // Depth test is enabled - use depth-enabled cached state
        if (g_d3d11.depthStencilStateEnabled) {
            g_d3d11.context->OMSetDepthStencilState(g_d3d11.depthStencilStateEnabled.Get(), 0);
        }
    }
}

// Cull state
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableCull
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableCull
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_resetScissor
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.scissorEnabled = false;

    // CRITICAL FIX: Set ALL 16 scissor rects to full viewport when disabling scissor test
    // D3D11 requires valid scissor rects even when ScissorEnable=FALSE
    // Must set ALL 16 slots to prevent 0x0 rects from clipping everything
    D3D11_RECT fullScreenScissors[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    for (int i = 0; i < D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE; i++) {
        fullScreenScissors[i].left = 0;
        fullScreenScissors[i].top = 0;
        fullScreenScissors[i].right = g_d3d11.width;
        fullScreenScissors[i].bottom = g_d3d11.height;
    }
    g_d3d11.context->RSSetScissorRects(D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE, fullScreenScissors);

    D3D11_RASTERIZER_DESC rastDesc = {};
    rastDesc.FillMode = D3D11_FILL_SOLID;
    rastDesc.CullMode = g_glState.cullEnabled ? D3D11_CULL_BACK : D3D11_CULL_NONE;
    rastDesc.FrontCounterClockwise = FALSE;
    rastDesc.DepthClipEnable = TRUE;
    rastDesc.ScissorEnable = FALSE;  // Disable scissor test, but still need valid rect above

    ComPtr<ID3D11RasterizerState> rastState;
    HRESULT hr = g_d3d11.device->CreateRasterizerState(&rastDesc, &rastState);
    if (SUCCEEDED(hr)) {
        g_d3d11.context->RSSetState(rastState.Get());
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setScissor
    (JNIEnv* env, jclass clazz, jint x, jint y, jint width, jint height) {

    if (!g_d3d11.initialized) return;
    g_glState.scissorEnabled = true;

    // DEBUG: Log first 30 scissor calls to trace where 0x0 comes from
    static int scissorCount = 0;
    if (scissorCount < 30) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[SET_SCISSOR %d] x=%d, y=%d, w=%d, h=%d -> rect=(%d,%d) to (%d,%d)",
            scissorCount++, x, y, width, height, x, y, x + width, y + height);
        logToJava(env, msg);
    }

    // CRITICAL FIX: Set ALL 16 scissor rects to prevent 0x0 rects in other slots
    // Slot 0 gets the requested scissor rect, slots 1-15 get full screen
    D3D11_RECT scissorRects[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    scissorRects[0].left = x;
    scissorRects[0].top = y;
    scissorRects[0].right = x + width;
    scissorRects[0].bottom = y + height;

    // Fill remaining slots with full screen scissor to prevent clipping
    for (int i = 1; i < D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE; i++) {
        scissorRects[i].left = 0;
        scissorRects[i].top = 0;
        scissorRects[i].right = g_d3d11.width;
        scissorRects[i].bottom = g_d3d11.height;
    }
    g_d3d11.context->RSSetScissorRects(D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE, scissorRects);

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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_clear
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
        // CRITICAL FIX: Clear the CURRENTLY BOUND render target, not always the backbuffer!
        // Minecraft renders to FBOs, so we need to get the currently bound RTV and clear that
        ComPtr<ID3D11RenderTargetView> currentRTV;
        ComPtr<ID3D11DepthStencilView> currentDSV;
        g_d3d11.context->OMGetRenderTargets(1, &currentRTV, &currentDSV);

        if (currentRTV) {
            g_d3d11.context->ClearRenderTargetView(currentRTV.Get(), g_d3d11.clearColor);
        } else {
            // Fallback to backbuffer if no RTV is bound
            g_d3d11.context->ClearRenderTargetView(g_d3d11.renderTargetView.Get(), g_d3d11.clearColor);
        }
    }

    if (mask & 0x00000100) {
        // CRITICAL FIX: Clear the CURRENTLY BOUND depth stencil, not always the backbuffer DSV!
        ComPtr<ID3D11RenderTargetView> currentRTV;
        ComPtr<ID3D11DepthStencilView> currentDSV;
        g_d3d11.context->OMGetRenderTargets(1, &currentRTV, &currentDSV);

        if (currentDSV) {
            g_d3d11.context->ClearDepthStencilView(currentDSV.Get(), D3D11_CLEAR_DEPTH, 1.0f, 0);
        } else if (g_d3d11.depthStencilView) {
            // Fallback to backbuffer DSV if no DSV is bound
            g_d3d11.context->ClearDepthStencilView(g_d3d11.depthStencilView.Get(),
                D3D11_CLEAR_DEPTH, 1.0f, 0);
        }
    }
}

// Note: setClearColor already exists at line 933 - no need to duplicate

// Note: clearDepth already exists at line 2194 - it immediately clears depth buffer
// For GlStateManager, we just call the existing clearDepth directly

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_colorMask
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_setPolygonMode
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enablePolygonOffset
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disablePolygonOffset
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_polygonOffset
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_enableColorLogicOp
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.colorLogicOpEnabled = true;
    // DirectX 11 doesn't support logic ops the same way as OpenGL
    // This would require Direct3D 11.1 feature level and OutputMerger logic ops
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_disableColorLogicOp
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;
    g_glState.colorLogicOpEnabled = false;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_logicOp
    (JNIEnv* env, jclass clazz, jint opcode) {

    if (!g_d3d11.initialized) return;
    // DirectX 11 logic ops require D3D11.1 feature level
    // Not implemented in base D3D11
}

// Buffer operations (int ID versions for GlStateManager)
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindBuffer__II
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__ILjava_nio_ByteBuffer_2I
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
            g_indexBufferSizes[handle] = static_cast<UINT>(bufferSize);  // Track size
        } else {
            g_vertexBuffers[handle] = d3dBuffer;
            g_vertexBufferStrides[handle] = 32; // Default Minecraft vertex stride (fallback)
        }
    }
}

// NEW: bufferData with explicit stride parameter
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__ILjava_nio_ByteBuffer_2II
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
            g_indexBufferSizes[handle] = static_cast<UINT>(bufferSize);  // Track size
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bufferData__IJI
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
            g_indexBufferSizes[handle] = static_cast<UINT>(size);  // Track size
        } else {
            g_vertexBuffers[handle] = d3dBuffer;
            g_vertexBufferStrides[handle] = 32;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_deleteBuffer__I
    (JNIEnv* env, jclass clazz, jint buffer) {

    if (!g_d3d11.initialized || buffer == 0) return;

    uint64_t handle = static_cast<uint64_t>(buffer);
    g_vertexBuffers.erase(handle);
    g_indexBuffers.erase(handle);
    g_vertexBufferStrides.erase(handle);
}

JNIEXPORT jobject JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_mapBuffer__II
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_unmapBuffer__I
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferTexture2D__IIIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint textarget, jint texture, jint level) {

    if (!g_d3d11.initialized) return;
    // Framebuffer operations are handled differently in DirectX 11
    // Render targets are set via OMSetRenderTargets instead of framebuffer attachments
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_framebufferRenderbuffer__IIII
    (JNIEnv* env, jclass clazz, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer) {

    if (!g_d3d11.initialized) return;
    // Renderbuffers are handled via render target views in DirectX 11
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderbufferStorage__IIII
    (JNIEnv* env, jclass clazz, jint target, jint internalformat, jint width, jint height) {

    if (!g_d3d11.initialized) return;
    // Renderbuffer storage is created as textures in DirectX 11
}

// ==================== FRAMEBUFFER OBJECT (FBO) SYSTEM ====================
// VulkanMod-style FBO system for DirectX 11

// Helper function to create or resize an FBO
bool createOrResizeFramebuffer(JNIEnv* env, int framebufferId, int width, int height, bool hasColor, bool hasDepth) {
    if (!g_d3d11.initialized) return false;

    // Check if FBO already exists
    auto it = g_framebuffers.find(framebufferId);
    if (it != g_framebuffers.end()) {
        D3D11Framebuffer& fbo = it->second;
        // If size matches, no need to recreate
        if (fbo.width == width && fbo.height == height &&
            fbo.hasColor == hasColor && fbo.hasDepth == hasDepth) {
            return true;  // FBO already exists with correct dimensions
        }

        // Size changed - need to recreate
        char msg[256];
        snprintf(msg, sizeof(msg), "[FBO_RESIZE] Resizing framebuffer %d from %dx%d to %dx%d",
            framebufferId, fbo.width, fbo.height, width, height);
        logToJava(env, msg);

        // Release old resources (ComPtr will handle cleanup automatically)
        fbo.colorTexture.Reset();
        fbo.colorRTV.Reset();
        fbo.colorSRV.Reset();
        fbo.depthTexture.Reset();
        fbo.depthDSV.Reset();
    }

    // Create new FBO
    D3D11Framebuffer fbo = {};
    fbo.width = width;
    fbo.height = height;
    fbo.hasColor = hasColor;
    fbo.hasDepth = hasDepth;

    HRESULT hr;

    // Create color texture and views if needed
    if (hasColor) {
        D3D11_TEXTURE2D_DESC colorDesc = {};
        colorDesc.Width = width;
        colorDesc.Height = height;
        colorDesc.MipLevels = 1;
        colorDesc.ArraySize = 1;
        colorDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        colorDesc.SampleDesc.Count = 1;
        colorDesc.SampleDesc.Quality = 0;
        colorDesc.Usage = D3D11_USAGE_DEFAULT;
        colorDesc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        colorDesc.CPUAccessFlags = 0;
        colorDesc.MiscFlags = 0;

        hr = g_d3d11.device->CreateTexture2D(&colorDesc, nullptr, &fbo.colorTexture);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_CREATE] Failed to create color texture for framebuffer %d: HRESULT=0x%08X", framebufferId, hr);
            logToJava(env, msg);
            return false;
        }

        // Create render target view
        hr = g_d3d11.device->CreateRenderTargetView(fbo.colorTexture.Get(), nullptr, &fbo.colorRTV);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_CREATE] Failed to create RTV for framebuffer %d: HRESULT=0x%08X", framebufferId, hr);
            logToJava(env, msg);
            return false;
        }

        // Create shader resource view for reading the texture
        hr = g_d3d11.device->CreateShaderResourceView(fbo.colorTexture.Get(), nullptr, &fbo.colorSRV);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_CREATE] Failed to create SRV for framebuffer %d: HRESULT=0x%08X", framebufferId, hr);
            logToJava(env, msg);
            return false;
        }
    }

    // Create depth texture and view if needed
    if (hasDepth) {
        D3D11_TEXTURE2D_DESC depthDesc = {};
        depthDesc.Width = width;
        depthDesc.Height = height;
        depthDesc.MipLevels = 1;
        depthDesc.ArraySize = 1;
        depthDesc.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
        depthDesc.SampleDesc.Count = 1;
        depthDesc.SampleDesc.Quality = 0;
        depthDesc.Usage = D3D11_USAGE_DEFAULT;
        depthDesc.BindFlags = D3D11_BIND_DEPTH_STENCIL;
        depthDesc.CPUAccessFlags = 0;
        depthDesc.MiscFlags = 0;

        hr = g_d3d11.device->CreateTexture2D(&depthDesc, nullptr, &fbo.depthTexture);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_CREATE] Failed to create depth texture for framebuffer %d: HRESULT=0x%08X", framebufferId, hr);
            logToJava(env, msg);
            return false;
        }

        // Create depth stencil view
        hr = g_d3d11.device->CreateDepthStencilView(fbo.depthTexture.Get(), nullptr, &fbo.depthDSV);
        if (FAILED(hr)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_CREATE] Failed to create DSV for framebuffer %d: HRESULT=0x%08X", framebufferId, hr);
            logToJava(env, msg);
            return false;
        }
    }

    // Store in map
    g_framebuffers[framebufferId] = std::move(fbo);

    // CRITICAL FIX: Clear framebuffer after creation to avoid showing uninitialized GPU memory
    // This fixes the black/gray triangle artifacts on GUI backgrounds
    if (hasColor) {
        float clearColor[4] = { 0.0f, 0.0f, 0.0f, 0.0f }; // Transparent black
        g_d3d11.context->ClearRenderTargetView(g_framebuffers[framebufferId].colorRTV.Get(), clearColor);
    }
    if (hasDepth) {
        g_d3d11.context->ClearDepthStencilView(g_framebuffers[framebufferId].depthDSV.Get(),
            D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL, 1.0f, 0);
    }

    static int createLogCount = 0;
    if (createLogCount < 10) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[FBO_CREATE %d] Created framebuffer %d (%dx%d, color=%d, depth=%d) and cleared",
            createLogCount, framebufferId, width, height, hasColor, hasDepth);
        logToJava(env, msg);
        createLogCount++;
    }

    return true;
}

// JNI function to create framebuffer textures
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createFramebufferTextures
    (JNIEnv* env, jclass clazz, jint framebufferId, jint width, jint height, jboolean hasColor, jboolean hasDepth) {

    if (!g_d3d11.initialized) return JNI_FALSE;

    bool success = createOrResizeFramebuffer(env, framebufferId, width, height, hasColor, hasDepth);
    return success ? JNI_TRUE : JNI_FALSE;
}

// JNI function to destroy framebuffer
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyFramebuffer
    (JNIEnv* env, jclass clazz, jint framebufferId) {

    if (!g_d3d11.initialized) return;

    auto it = g_framebuffers.find(framebufferId);
    if (it != g_framebuffers.end()) {
        g_framebuffers.erase(it);  // ComPtr will automatically release resources

        static int destroyCount = 0;
        if (destroyCount < 10) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_DESTROY %d] Destroyed framebuffer %d", destroyCount, framebufferId);
            logToJava(env, msg);
            destroyCount++;
        }
    }
}

// JNI function to bind framebuffer texture for reading
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindFramebufferTexture
    (JNIEnv* env, jclass clazz, jint framebufferId, jint textureUnit) {

    if (!g_d3d11.initialized) return;

    auto it = g_framebuffers.find(framebufferId);
    if (it != g_framebuffers.end() && it->second.colorSRV) {
        // Bind the framebuffer's color texture to the specified texture unit
        ID3D11ShaderResourceView* srvs[] = { it->second.colorSRV.Get() };
        g_d3d11.context->PSSetShaderResources(textureUnit, 1, srvs);

        static int bindTexCount = 0;
        if (bindTexCount < 5) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_BIND_TEX %d] Bound framebuffer %d texture to unit %d",
                bindTexCount, framebufferId, textureUnit);
            logToJava(env, msg);
            bindTexCount++;
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindFramebuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint framebuffer) {

    if (!g_d3d11.initialized) return;
    g_glState.boundFramebuffer = framebuffer;

    if (framebuffer == 0) {
        // Bind default framebuffer (swap chain back buffer)
        g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
            g_d3d11.depthStencilView.Get());

        static int bindCount = 0;
        if (bindCount < 5) {
            char msg[256];
            snprintf(msg, sizeof(msg), "[FBO_BIND %d] Bound default framebuffer (backbuffer)", bindCount);
            logToJava(env, msg);
            bindCount++;
        }
    } else {
        // Bind custom framebuffer
        auto it = g_framebuffers.find(framebuffer);
        if (it != g_framebuffers.end()) {
            D3D11Framebuffer& fbo = it->second;

            // Bind the FBO's render target and depth stencil
            ID3D11RenderTargetView* rtv = fbo.hasColor ? fbo.colorRTV.Get() : nullptr;
            ID3D11DepthStencilView* dsv = fbo.hasDepth ? fbo.depthDSV.Get() : nullptr;

            g_d3d11.context->OMSetRenderTargets(1, &rtv, dsv);

            static int customBindCount = 0;
            if (customBindCount < 5) {
                char msg[256];
                snprintf(msg, sizeof(msg), "[FBO_BIND %d] Bound custom framebuffer %d (%dx%d, RTV=0x%p, DSV=0x%p)",
                    customBindCount, framebuffer, fbo.width, fbo.height, rtv, dsv);
                logToJava(env, msg);
                customBindCount++;
            }
        } else {
            // Framebuffer doesn't exist yet - bind backbuffer as fallback
            // FBO will be created when glRenderbufferStorage is called
            g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
                g_d3d11.depthStencilView.Get());

            static int fallbackCount = 0;
            if (fallbackCount < 10) {
                char msg[256];
                snprintf(msg, sizeof(msg), "[FBO_BIND_FALLBACK %d] Framebuffer %d doesn't exist yet, using backbuffer",
                    fallbackCount, framebuffer);
                logToJava(env, msg);
                fallbackCount++;
            }
        }
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderbuffer__II
    (JNIEnv* env, jclass clazz, jint target, jint renderbuffer) {

    if (!g_d3d11.initialized) return;
    g_glState.boundRenderbuffer = renderbuffer;
}

// ==================== MAINTARGET COMPATIBILITY METHODS ====================

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindMainRenderTarget
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // Bind swap chain back buffer as render target
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(),
        g_d3d11.depthStencilView.Get());
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindRenderTargetForWriting
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

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindMainRenderTargetTexture
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;


    // In DirectX 11, reading from the back buffer requires resolving to a texture
    // For now, this is a no-op - proper implementation would copy back buffer to a texture
}

JNIEXPORT jint JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getMainColorTextureId
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
// Note: ShaderPipeline struct and g_shaderPipelines declared at top of file (line ~241-248)

/**
 * Compile HLSL shader from source (ByteBuffer version)
 * Compiles HLSL source code to bytecode using D3DCompile
 * Returns handle to compiled shader blob on success, 0 on failure
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShader__Ljava_nio_ByteBuffer_2ILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jobject sourceBuffer, jint sourceLength, jstring target, jstring debugName) {

    // IMMEDIATELY log that we're in this function - BEFORE any other code
    std::cout << "[C++_BYTEBUFFER_VERSION] ENTERED ByteBuffer compileShader" << std::endl;
    std::cout.flush();
    logToJava(env, "[BYTEBUFFER_VERSION] compileShader ByteBuffer version CALLED");

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


    // Set compilation flags - STRICT MODE
    UINT flags = D3DCOMPILE_WARNINGS_ARE_ERRORS;
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

    if (FAILED(hr) || errorBlob) {
        if (errorBlob) {
            g_lastShaderError = std::string(static_cast<const char*>(errorBlob->GetBufferPointer()),
                                           errorBlob->GetBufferSize());

            // CRITICAL FIX:  +  style graceful error handling
            // Don't crash the entire application! Use fallback shaders instead.
            char logBuffer[2048];
            snprintf(logBuffer, sizeof(logBuffer), "[SHADER_COMPILE_ERROR] %s: %.1500s", shaderName, g_lastShaderError.c_str());
            logToJava(env, logBuffer);
            OutputDebugStringA(logBuffer);

            errorBlob->Release();

            // ATTEMPT FALLBACK: Try to use default shaders instead of crashing
            if (g_d3d11.defaultVertexShader && g_d3d11.defaultPixelShader) {
                char fallbackMsg[512];
                snprintf(fallbackMsg, sizeof(fallbackMsg), "[SHADER_FALLBACK] Using default shaders for %s", shaderName);
                logToJava(env, fallbackMsg);
                return createDefaultShaderPipeline();
            } else {
                // LAST RESORT: Create a minimal working pipeline
                char errorMsg[512];
                snprintf(errorMsg, sizeof(errorMsg), "[SHADER_CRITICAL] No fallback shaders available for %s", shaderName);
                logToJava(env, errorMsg);
                return 0; // Return invalid handle but don't crash
            }
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
 * Compile HLSL shader from byte array (overload for Java byte[])
 * Returns handle to compiled shader blob on success, 0 on failure
 * RENAMED TO compileShaderBYTEARRAY FOR DIAGNOSTIC
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShaderBYTEARRAY___3BILjava_lang_String_2Ljava_lang_String_2
    (JNIEnv* env, jclass clazz, jbyteArray sourceArray, jint sourceLength, jstring target, jstring debugName) {

    // IMMEDIATELY log that we're in this function - BEFORE any other code
    std::cout << "[C++_BYTE_ARRAY_VERSION] ENTERED byte array compileShaderBYTEARRAY" << std::endl;
    std::cout.flush();
    logToJava(env, "[BYTE_ARRAY_VERSION] compileShaderBYTEARRAY byte array version CALLED");

    if (!g_d3d11.initialized) {
        g_lastShaderError = "DirectX 11 not initialized";
        return 0;
    }

    // Get byte array elements
    jbyte* sourceBytes = env->GetByteArrayElements(sourceArray, nullptr);
    if (!sourceBytes) {
        g_lastShaderError = "Failed to get byte array elements";
        return 0;
    }

    const char* targetProfile = env->GetStringUTFChars(target, nullptr);
    const char* shaderName = debugName ? env->GetStringUTFChars(debugName, nullptr) : "shader";

    // Compile shader using D3DCompile
    ComPtr<ID3DBlob> shaderBlob;
    ComPtr<ID3DBlob> errorBlob;

    // STRICT MODE: warnings are treated as errors
    UINT flags = D3DCOMPILE_WARNINGS_ARE_ERRORS;
    if (g_d3d11.debugEnabled) {
        flags |= D3DCOMPILE_DEBUG | D3DCOMPILE_SKIP_OPTIMIZATION;
    }

    HRESULT hr = D3DCompile(
        sourceBytes,
        sourceLength,
        shaderName,
        nullptr,
        D3D_COMPILE_STANDARD_FILE_INCLUDE,
        "main",
        targetProfile,
        flags,
        0,
        &shaderBlob,
        &errorBlob
    );

    // Release byte array
    env->ReleaseByteArrayElements(sourceArray, sourceBytes, JNI_ABORT);
    env->ReleaseStringUTFChars(target, targetProfile);
    if (debugName) {
        env->ReleaseStringUTFChars(debugName, shaderName);
    }

    if (FAILED(hr) || errorBlob) {
        if (errorBlob) {
            g_lastShaderError = std::string(static_cast<const char*>(errorBlob->GetBufferPointer()),
                                          errorBlob->GetBufferSize());

            // CRASH: Shader compilation must be perfect
            char logBuffer[2048];
            snprintf(logBuffer, sizeof(logBuffer), "[SHADER_COMPILE_FATAL] %s: %.1500s", shaderName, g_lastShaderError.c_str());
            logToJava(env, logBuffer);
            OutputDebugStringA(logBuffer);
            MessageBoxA(nullptr, logBuffer, "FATAL: Shader Compilation Failed", MB_OK | MB_ICONERROR);
            std::terminate();
        } else {
            g_lastShaderError = "D3DCompile failed with HRESULT: " + std::to_string(hr);
        }
        return 0;
    }

    if (errorBlob) {
        // Warning messages (don't manually release, ComPtr will handle it)
        std::string warnings(static_cast<const char*>(errorBlob->GetBufferPointer()),
                           errorBlob->GetBufferSize());
    }

    // Generate handle and store blob
    uint64_t handle = generateHandle();
    g_shaderBlobs[handle] = shaderBlob;

    // Debug: Log successful storage
    char logMsg[256];
    sprintf_s(logMsg, "[SHADER_BLOB_BYTE_ARRAY] Stored blob handle 0x%llX, blob size: %zu bytes, map size: %zu",
              handle, shaderBlob->GetBufferSize(), g_shaderBlobs.size());
    logToJava(env, logMsg);

    return handle;
}

/**
 * Compile HLSL shader from file
 * Loads HLSL file and compiles it
 */
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_compileShaderFromFile
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
    UINT flags = 0;  // Removed D3DCOMPILE_ENABLE_STRICTNESS - was treating warnings as errors
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
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createShaderFromBytecode
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
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createShaderPipeline__JJ
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

    // CRITICAL FIX: Do NOT create input layout during pipeline creation
    // VulkanMod pattern: Input layouts are created dynamically during draw calls
    // based on the ACTUAL vertex buffer format, not the shader's expectations
    // The shader blob now contains only the input signature (not full bytecode),
    // so we can't use D3DReflect here anyway

    // Verify the shader blob exists (for input layout creation during draws)
    auto blobIt = g_shaderBlobs.find(vertexShaderHandle);
    if (blobIt == g_shaderBlobs.end()) {
        g_lastShaderError = "Vertex shader blob not found (input signature required for draw calls)";
        printf("[PIPELINE_CREATE] WARNING: Shader blob not found for VS handle 0x%llx\n", vertexShaderHandle);
        // Don't fail - input layout will be created during draw calls
    }

    // Create pipeline WITHOUT input layout (VulkanMod pattern)
    ShaderPipeline pipeline;
    pipeline.vertexShader = vsIt->second;
    pipeline.pixelShader = psIt->second;
    pipeline.inputLayout = nullptr;  // Will be set during draw calls based on vertex format
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindShaderPipeline
    (JNIEnv* env, jclass clazz, jlong pipelineHandle) {

    if (!g_d3d11.initialized) return;

    auto it = g_shaderPipelines.find(pipelineHandle);
    if (it == g_shaderPipelines.end()) {
        char msg[128];
        snprintf(msg, sizeof(msg), "[SHADER_ERROR] Pipeline handle 0x%llx not found in g_shaderPipelines!", (unsigned long long)pipelineHandle);
        logToJava(env, msg);

        // CRITICAL FIX:  +  style fallback pipeline recovery
        // Try to create a fallback pipeline instead of failing completely
        jlong fallbackHandle = createDefaultShaderPipeline();
        if (fallbackHandle != 0) {
            char fallbackMsg[256];
            snprintf(fallbackMsg, sizeof(fallbackMsg), "[SHADER_FALLBACK] Using fallback pipeline 0x%llx for failed pipeline 0x%llx",
                     (unsigned long long)fallbackHandle, (unsigned long long)pipelineHandle);
            logToJava(env, fallbackMsg);
            it = g_shaderPipelines.find(fallbackHandle);
        } else {
            logToJava(env, "[SHADER_CRITICAL] No fallback pipeline available!");
            return;
        }
    }

    // CRITICAL FIX:  +  style pipeline validation before binding
    ShaderPipeline& pipeline = it->second;
    if (!pipeline.vertexShader || !pipeline.pixelShader) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[PIPELINE_VALIDATION] Pipeline 0x%llx has missing shaders: VS=%p, PS=%p",
                 (unsigned long long)pipelineHandle, pipeline.vertexShader.Get(), pipeline.pixelShader.Get());
        logToJava(env, msg);
        return;
    }

    // CRITICAL FIX: VulkanMod pattern - only bind if pipeline changed!
    // This prevents other code from overriding our pipeline binding
    static uint64_t s_boundPipelineHandle = 0;
    if (s_boundPipelineHandle == pipelineHandle) {
        // Pipeline already bound, skip redundant bind
        return;
    }

    // Log binding (only first 10 times to see different pipelines)
    static int bindCount = 0;
    if (bindCount < 10) {
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
    s_boundPipelineHandle = pipelineHandle;  // Track bound pipeline

    // CRITICAL FIX: Do NOT bind input layout here!
    // Input layout must be set based on the ACTUAL vertex buffer format during draw calls,
    // not based on what the shader expects. The shader's input layout is incompatible
    // with vertex data that has different attributes (e.g., shader expects TEXCOORD but
    // vertex buffer only has POSITION+COLOR).
    //
    // The input layout will be created and bound by drawWithVertexFormat() based on the
    // actual vertex data format, ensuring shader inputs match buffer data.

    // CRITICAL FIX (bgfx pattern): IMMEDIATELY bind ALL constant buffers after shader change
    // VSSetShader/PSSetShader can unbind constant buffers on some GPU drivers
    // bgfx ALWAYS binds constant buffers immediately after setting shaders
    ID3D11Buffer* vsBuffers[4] = {
        g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
        g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
        g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
        g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
    };
    ID3D11Buffer* psBuffers[4] = {
        g_d3d11.constantBuffers[0] ? g_d3d11.constantBuffers[0].Get() : nullptr,
        g_d3d11.constantBuffers[1] ? g_d3d11.constantBuffers[1].Get() : nullptr,
        g_d3d11.constantBuffers[2] ? g_d3d11.constantBuffers[2].Get() : nullptr,
        g_d3d11.constantBuffers[3] ? g_d3d11.constantBuffers[3].Get() : nullptr
    };
    g_d3d11.context->VSSetConstantBuffers(0, 4, vsBuffers);
    g_d3d11.context->PSSetConstantBuffers(0, 4, psBuffers);

    // CRITICAL FIX (VulkanMod pattern): Re-bind textures after pipeline change
    // DirectX 11 does NOT preserve texture bindings when PSSetShader() is called!
    // We must re-bind all textures that Minecraft has stored in RenderSystem.shaderTextures
    // This matches VulkanMod's bindShaderTextures() approach
    for (int slot = 0; slot < 12; ++slot) {  // Minecraft uses up to 12 texture slots
        jint textureId = g_glState.boundTextures[slot];
        if (textureId > 0) {
            // Look up the D3D11 shader resource view for this texture
            uint64_t handle = static_cast<uint64_t>(textureId);
            auto it = g_shaderResourceViews.find(handle);
            if (it != g_shaderResourceViews.end() && it->second.Get() != nullptr) {
                ID3D11ShaderResourceView* srv = it->second.Get();
                ID3D11ShaderResourceView* srvArray[1] = { srv };
                g_d3d11.context->PSSetShaderResources(slot, 1, srvArray);

                // Log first few re-bindings for debugging
                static int rebindLog = 0;
                if (rebindLog < 5) {
                    char msg[256];
                    snprintf(msg, sizeof(msg), "[PIPELINE_TEXTURE_REBIND %d] Re-bound texture ID=%d to slot %d after pipeline change", rebindLog, textureId, slot);
                    logToJava(env, msg);
                    rebindLog++;
                }
            }
        }
    }
// CRITICAL FIX: Bind default sampler state for texture sampling
    // Without this, pixel shaders can't sample textures, causing the D3D11 warning:
    // "The Pixel Shader unit expects a Sampler to be set at Slot 0, but none is bound"
    if (g_d3d11.defaultSamplerState) {
        g_d3d11.context->PSSetSamplers(0, 1, g_d3d11.defaultSamplerState.GetAddressOf());
    }

}

/**
 * Destroy shader pipeline and release resources
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_destroyShaderPipeline
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
JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_createConstantBuffer
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_updateConstantBuffer
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

    // CRITICAL DEBUG: Log ENTIRE constant buffer to find garbage source
    static int updateCount = 0;
    if (updateCount < 3) {
        float* floatData = reinterpret_cast<float*>(dataPtr);
        char msg[2048];

        // Log first buffer update in detail (all 64 floats = 256 bytes)
        snprintf(msg, sizeof(msg), "[CB_NATIVE_FULL %d] handle=0x%llx, size=%d bytes\n"
            "  [0-15]   MVP:         %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f\n"
            "  [16-31]  ModelView:   %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f\n"
            "  [32-35]  ColorMod:    %.3f %.3f %.3f %.3f\n"
            "  [36-38]  ModelOffset: %.3f %.3f %.3f (pad: %.3f)\n"
            "  [40-55]  TextureMat:  %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f | %.3f %.3f %.3f %.3f\n"
            "  [56]     LineWidth:   %.3f\n"
            "  [57-63]  _pad1:       %.3f %.3f %.3f | (extra: %.3f %.3f %.3f %.3f)",
            updateCount, (unsigned long long)bufferHandle, dataSize,
            floatData[0], floatData[1], floatData[2], floatData[3], floatData[4], floatData[5], floatData[6], floatData[7],
            floatData[8], floatData[9], floatData[10], floatData[11], floatData[12], floatData[13], floatData[14], floatData[15],
            floatData[16], floatData[17], floatData[18], floatData[19], floatData[20], floatData[21], floatData[22], floatData[23],
            floatData[24], floatData[25], floatData[26], floatData[27], floatData[28], floatData[29], floatData[30], floatData[31],
            floatData[32], floatData[33], floatData[34], floatData[35],
            floatData[36], floatData[37], floatData[38], floatData[39],
            floatData[40], floatData[41], floatData[42], floatData[43], floatData[44], floatData[45], floatData[46], floatData[47],
            floatData[48], floatData[49], floatData[50], floatData[51], floatData[52], floatData[53], floatData[54], floatData[55],
            floatData[56],
            floatData[57], floatData[58], floatData[59]);
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindConstantBufferVS
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
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_bindConstantBufferPS
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
 * Upload and bind all UBOs (Uniform Buffer Objects / Constant Buffers)
 * Called after shader uniforms are uploaded to ensure constant buffers are bound
 * Based on VulkanMod's UBO update pattern
 */
JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_uploadAndBindUBOs
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized || !g_d3d11.context) {
        return;
    }

    // Bind all constant buffers that have been created
    // Note: Constant buffers are already uploaded via setProjectionMatrix, setModelViewMatrix, etc.
    // This function ensures they're bound to the correct shader stages

    // Bind constant buffers to vertex shader
    for (int i = 0; i < 4; i++) {
        if (g_boundConstantBuffersVS[i] != 0) {
            auto it = g_constantBuffers.find(g_boundConstantBuffersVS[i]);
            if (it != g_constantBuffers.end() && it->second) {
                ID3D11Buffer* buffers[] = { it->second.Get() };
                g_d3d11.context->VSSetConstantBuffers(i, 1, buffers);
            }
        }
    }

    // Bind constant buffers to pixel shader
    for (int i = 0; i < 4; i++) {
        if (g_boundConstantBuffersPS[i] != 0) {
            auto it = g_constantBuffers.find(g_boundConstantBuffersPS[i]);
            if (it != g_constantBuffers.end() && it->second) {
                ID3D11Buffer* buffers[] = { it->second.Get() };
                g_d3d11.context->PSSetConstantBuffers(i, 1, buffers);
            }
        }
    }

    // Also bind the primary constant buffer (b0) if it exists
    if (g_d3d11.constantBuffers[0]) {
        g_d3d11.context->VSSetConstantBuffers(0, 1, g_d3d11.constantBuffers[0].GetAddressOf());
    }
}

/**
 * Get bytecode from compiled shader blob handle
 * Returns the bytecode bytes for a shader blob that was compiled with compileShader()
 */
JNIEXPORT jbyteArray JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getBlobBytecode
    (JNIEnv* env, jclass clazz, jlong blobHandle) {

    char logMsg[256];
    sprintf_s(logMsg, "[GET_BLOB_BYTECODE] Called with handle 0x%llX, map size: %zu",
              static_cast<uint64_t>(blobHandle), g_shaderBlobs.size());
    logToJava(env, logMsg);

    if (blobHandle == 0) {
        logToJava(env, "[GET_BLOB_BYTECODE] ERROR: Blob handle is 0");
        return nullptr;
    }

    // Find the blob in our map
    uint64_t handle = static_cast<uint64_t>(blobHandle);
    auto it = g_shaderBlobs.find(handle);
    if (it == g_shaderBlobs.end()) {
        sprintf_s(logMsg, "[GET_BLOB_BYTECODE] ERROR: Blob handle 0x%llX not found in map", handle);
        logToJava(env, logMsg);
        return nullptr;
    }

    ID3DBlob* blob = it->second.Get();
    if (!blob) {
        return nullptr;
    }

    // Get buffer pointer and size
    const void* bufferPtr = blob->GetBufferPointer();
    SIZE_T bufferSize = blob->GetBufferSize();

    // Create Java byte array
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bufferSize));
    if (!result) {
        return nullptr;
    }

    // Copy bytecode to Java array
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bufferSize),
                            static_cast<const jbyte*>(bufferPtr));

    return result;
}

/**
 * Get last shader compilation error message
 */
JNIEXPORT jstring JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_getLastShaderError
    (JNIEnv* env, jclass clazz) {

    return env->NewStringUTF(g_lastShaderError.c_str());
}

// ============================================================================
// RENDERDOC IN-APPLICATION API - DISABLED (JNI STUBS)
// ============================================================================
// RenderDoc intercepts debug layer - disabled to see real DirectX errors

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocIsAvailable
    (JNIEnv* env, jclass clazz) {
    return JNI_FALSE;  // RenderDoc disabled
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocStartFrameCapture
    (JNIEnv* env, jclass clazz) {
    return JNI_FALSE;  // RenderDoc disabled
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocEndFrameCapture
    (JNIEnv* env, jclass clazz) {
    return JNI_FALSE;  // RenderDoc disabled
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocTriggerCapture
    (JNIEnv* env, jclass clazz) {
    // RenderDoc disabled
}

JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraD3D11Renderer_renderDocIsCapturing
    (JNIEnv* env, jclass clazz) {
    return JNI_FALSE;  // RenderDoc disabled
}
