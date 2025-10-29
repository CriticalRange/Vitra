// rendertype_tripwire.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Tripwire rendering (string/wire blocks)

#include "cbuffer_common.hlsli"

// ============================================================================
// Texture and Sampler Declarations
// ============================================================================
Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    int2 UV2 : TEXCOORD2;  // Lightmap coordinates
    float3 Normal : NORMAL;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float4 vertexColor : COLOR0;       // Vertex color modulated by lightmap
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
    float vertexDistance : TEXCOORD1;  // Fog distance
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Apply chunk/model offset to position (used for terrain rendering)
    float3 pos = input.Position + ModelOffset;

    // Transform position through Model-View-Projection pipeline
    float4 position = float4(pos, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Calculate fog distance (spherical fog, shape = 0)
    output.vertexDistance = fog_distance(pos, 0);

    // Sample lightmap and multiply with vertex color
    float4 lightMapColor = minecraft_sample_lightmap(Sampler2, Sampler2State, input.UV2);
    output.vertexColor = input.Color * lightMapColor;

    // Pass texture coordinates to pixel shader
    output.texCoord0 = input.UV0;

    return output;
}
