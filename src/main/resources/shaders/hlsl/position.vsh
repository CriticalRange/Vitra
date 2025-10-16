// position.vsh
// Converted from Minecraft 1.21.1 GLSL core/position.vsh
// Shader Model 5.0 Vertex Shader
// Purpose: Simple position-only rendering with fog distance calculation

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;           // Clip-space position
    float sphericalVertexDistance : TEXCOORD0;  // Spherical fog distance
    float cylindricalVertexDistance : TEXCOORD1; // Cylindrical fog distance
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position through Model-View-Projection pipeline
    // Note: Matrix multiplication order matches GLSL due to column_major pragma
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Calculate fog distances for vertex position
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    return output;
}
