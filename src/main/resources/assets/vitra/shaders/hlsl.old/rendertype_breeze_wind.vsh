// rendertype_breeze_wind.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Breeze wind effect rendering (wind particles from Breeze mob)

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
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float vertexDistance : TEXCOORD0;  // Fog distance
    float4 vertexColor : COLOR0;       // Vertex color modulated by lightmap
    float4 lightMapColor : COLOR1;     // Lightmap color
    float2 texCoord0 : TEXCOORD1;      // Transformed texture coordinates
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position through Model-View-Projection pipeline
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Calculate fog distance using FogShape (0 = spherical, 1 = cylindrical)
    output.vertexDistance = fog_distance(input.Position, FogShape);

    // Sample lightmap texture (UV2 / 16 using bit shift to avoid division)
    output.lightMapColor = Sampler2.Load(int3(input.UV2 >> 4, 0));

    // Modulate vertex color by lightmap
    output.vertexColor = input.Color * output.lightMapColor;

    // Apply texture transformation matrix for animated effect
    output.texCoord0 = mul(float4(input.UV0, 0.0, 1.0), TextureMat).xy;

    return output;
}
