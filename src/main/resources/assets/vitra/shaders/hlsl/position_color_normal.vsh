// position_color_normal.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Position + color + normal rendering with lighting calculations

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float3 Normal : NORMAL;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float vertexDistance : TEXCOORD0;  // Fog distance
    float4 vertexColor : COLOR0;       // Interpolated vertex color
    float4 normal : NORMAL0;           // Transformed normal
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

    // Transform normal to view space (w=0 means direction vector, not point)
    output.normal = mul(float4(input.Normal, 0.0), ModelViewMat);

    return output;
}
