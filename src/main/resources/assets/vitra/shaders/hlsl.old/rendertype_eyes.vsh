// rendertype_eyes.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Glowing eyes rendering (spider eyes, enderman eyes, etc.)

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float4 vertexColor : COLOR0;       // Interpolated vertex color
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
    float vertexDistance : TEXCOORD1;  // Fog distance
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position through Model-View-Projection pipeline
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Calculate fog distance (spherical fog, shape = 0)
    output.vertexDistance = fog_distance(input.Position, 0);

    // Pass vertex color to pixel shader
    output.vertexColor = input.Color;

    // Pass texture coordinates to pixel shader
    output.texCoord0 = input.UV0;

    return output;
}
