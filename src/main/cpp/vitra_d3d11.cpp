#include "vitra_d3d11.h"
#include <iostream>
#include <random>

// Global D3D11 resources
D3D11Resources g_d3d11 = {};

// Resource tracking
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_vertexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3D11Buffer>> g_indexBuffers;
std::unordered_map<uint64_t, ComPtr<ID3D11VertexShader>> g_vertexShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11PixelShader>> g_pixelShaders;
std::unordered_map<uint64_t, ComPtr<ID3D11InputLayout>> g_inputLayouts;
std::unordered_map<uint64_t, ComPtr<ID3D11Texture2D>> g_textures;
std::unordered_map<uint64_t, ComPtr<ID3D11ShaderResourceView>> g_shaderResourceViews;

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

bool createInputLayout(ID3DBlob* vertexShaderBlob, ID3D11InputLayout** inputLayout) {
    // Define vertex input layout (position, texcoord, color)
    D3D11_INPUT_ELEMENT_DESC layout[] = {
        { "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, D3D11_INPUT_PER_VERTEX_DATA, 0 }
    };

    HRESULT hr = g_d3d11.device->CreateInputLayout(
        layout,
        ARRAYSIZE(layout),
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
        std::cerr << "Failed to get back buffer" << std::endl;
        return;
    }

    hr = g_d3d11.device->CreateRenderTargetView(backBuffer.Get(), nullptr, &g_d3d11.renderTargetView);
    if (FAILED(hr)) {
        std::cerr << "Failed to create render target view" << std::endl;
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
        std::cerr << "Failed to create depth stencil buffer" << std::endl;
        return;
    }

    hr = g_d3d11.device->CreateDepthStencilView(g_d3d11.depthStencilBuffer.Get(), nullptr, &g_d3d11.depthStencilView);
    if (FAILED(hr)) {
        std::cerr << "Failed to create depth stencil view" << std::endl;
        return;
    }

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
    // Simple vertex shader
    const char* vertexShaderSource = R"(
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
            output.pos = float4(input.pos, 1.0);
            output.tex = input.tex;
            output.color = input.color;
            return output;
        }
    )";

    // Simple pixel shader
    const char* pixelShaderSource = R"(
        struct PS_INPUT {
            float4 pos : SV_POSITION;
            float2 tex : TEXCOORD0;
            float4 color : COLOR0;
        };

        float4 main(PS_INPUT input) : SV_TARGET {
            return input.color;
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
}

// JNI Implementation
JNIEXPORT jboolean JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_initializeDirectX
    (JNIEnv* env, jclass clazz, jlong windowHandle, jint width, jint height, jboolean enableDebug) {

    g_d3d11.hwnd = reinterpret_cast<HWND>(windowHandle);
    g_d3d11.width = width;
    g_d3d11.height = height;
    g_d3d11.debugEnabled = enableDebug;

    // Create device and swap chain
    UINT createDeviceFlags = 0;
    if (g_d3d11.debugEnabled) {
        createDeviceFlags |= D3D11_CREATE_DEVICE_DEBUG;
    }

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
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    D3D_FEATURE_LEVEL selectedFeatureLevel;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(
        nullptr,
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr,
        createDeviceFlags,
        featureLevels,
        ARRAYSIZE(featureLevels),
        D3D11_SDK_VERSION,
        &swapChainDesc,
        &g_d3d11.swapChain,
        &g_d3d11.device,
        &selectedFeatureLevel,
        &g_d3d11.context
    );

    if (FAILED(hr)) {
        std::cerr << "Failed to create D3D11 device and swap chain: " << std::hex << hr << std::endl;
        return JNI_FALSE;
    }

    updateRenderTargetView();
    setDefaultShaders();

    g_d3d11.initialized = true;
    std::cout << "DirectX 11 initialized successfully" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_shutdown
    (JNIEnv* env, jclass clazz) {

    // Clear all resources
    g_vertexBuffers.clear();
    g_indexBuffers.clear();
    g_vertexShaders.clear();
    g_pixelShaders.clear();
    g_inputLayouts.clear();
    g_textures.clear();
    g_shaderResourceViews.clear();

    // Release DirectX resources
    g_d3d11.defaultInputLayout.Reset();
    g_d3d11.defaultPixelShader.Reset();
    g_d3d11.defaultVertexShader.Reset();
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
    }
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_beginFrame
    (JNIEnv* env, jclass clazz) {

    if (!g_d3d11.initialized) return;

    // Set render targets
    g_d3d11.context->OMSetRenderTargets(1, g_d3d11.renderTargetView.GetAddressOf(), g_d3d11.depthStencilView.Get());

    // Set viewport
    g_d3d11.context->RSSetViewports(1, &g_d3d11.viewport);
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

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createVertexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint stride) {

    if (!g_d3d11.initialized) return 0;

    // Null check for data array
    if (data == nullptr) {
        std::cerr << "createVertexBuffer: data array is null" << std::endl;
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "createVertexBuffer: failed to get array elements" << std::endl;
        return 0;
    }

    D3D11_BUFFER_DESC desc = {};
    desc.Usage = D3D11_USAGE_DYNAMIC; // Changed from DEFAULT to DYNAMIC for CPU access
    desc.ByteWidth = size;
    desc.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE; // Added CPU write access for mapping

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = bytes;

    ComPtr<ID3D11Buffer> buffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&desc, &initData, &buffer);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (FAILED(hr)) {
        std::cerr << "Failed to create vertex buffer: " << std::hex << hr << std::endl;
        return 0;
    }

    uint64_t handle = generateHandle();
    g_vertexBuffers[handle] = buffer;

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createIndexBuffer
    (JNIEnv* env, jclass clazz, jbyteArray data, jint size, jint format) {

    if (!g_d3d11.initialized) return 0;

    // Null check for data array
    if (data == nullptr) {
        std::cerr << "createIndexBuffer: data array is null" << std::endl;
        return 0;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        std::cerr << "createIndexBuffer: failed to get array elements" << std::endl;
        return 0;
    }

    D3D11_BUFFER_DESC desc = {};
    desc.Usage = D3D11_USAGE_DYNAMIC; // Changed from DEFAULT to DYNAMIC for CPU access
    desc.ByteWidth = size;
    desc.BindFlags = D3D11_BIND_INDEX_BUFFER;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE; // Added CPU write access for mapping

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = bytes;

    ComPtr<ID3D11Buffer> buffer;
    HRESULT hr = g_d3d11.device->CreateBuffer(&desc, &initData, &buffer);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (FAILED(hr)) {
        std::cerr << "Failed to create index buffer: " << std::hex << hr << std::endl;
        return 0;
    }

    uint64_t handle = generateHandle();
    g_indexBuffers[handle] = buffer;

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShader
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
        } else {
            handle = 0;
        }
    } else if (type == 1) { // Pixel shader
        ComPtr<ID3D11PixelShader> shader;
        HRESULT hr = g_d3d11.device->CreatePixelShader(
            bytes, size, nullptr, &shader);

        if (SUCCEEDED(hr)) {
            g_pixelShaders[handle] = shader;
        } else {
            handle = 0;
        }
    }

    env->ReleaseByteArrayElements(bytecode, bytes, JNI_ABORT);

    return static_cast<jlong>(handle);
}

JNIEXPORT jlong JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_createShaderPipeline
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
    g_vertexShaders.erase(h);
    g_pixelShaders.erase(h);
    g_inputLayouts.erase(h);
    g_textures.erase(h);
    g_shaderResourceViews.erase(h);
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
}

JNIEXPORT void JNICALL Java_com_vitra_render_jni_VitraNativeRenderer_draw
    (JNIEnv* env, jclass clazz, jlong vertexBuffer, jlong indexBuffer, jint vertexCount, jint indexCount) {

    if (!g_d3d11.initialized) return;

    // Set vertex buffer
    uint64_t vbHandle = static_cast<uint64_t>(vertexBuffer);
    auto vbIt = g_vertexBuffers.find(vbHandle);
    if (vbIt != g_vertexBuffers.end()) {
        UINT stride = 32; // position(12) + texcoord(8) + color(4) + padding(8)
        UINT offset = 0;
        g_d3d11.context->IASetVertexBuffers(0, 1, vbIt->second.GetAddressOf(), &stride, &offset);
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
            g_d3d11.context->DrawIndexed(indexCount, 0, 0);
        }
    } else {
        if (vertexCount > 0) {
            g_d3d11.context->Draw(vertexCount, 0);
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
                g_d3d11.context->DrawIndexed(indexCount, 0, 0);
            }
        }
    } else {
        // Draw non-indexed
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

    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = width;
    desc.Height = height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; // Default to RGBA8
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem = bytes;
    initData.SysMemPitch = width * 4; // RGBA = 4 bytes per pixel

    ComPtr<ID3D11Texture2D> texture;
    HRESULT hr = g_d3d11.device->CreateTexture2D(&desc, &initData, &texture);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (FAILED(hr)) {
        std::cerr << "Failed to create texture: " << std::hex << hr << std::endl;
        return 0;
    }

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

    return static_cast<jlong>(handle);
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