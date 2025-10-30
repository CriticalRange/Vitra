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

    // VulkanMod-style transform: Use pre-multiplied MVP matrix
    // column_major: MVP * vec4 (same as GLSL/Vulkan)
    output.gl_Position = mul(MVP, float4(input.Position, 1.0));

    // Calculate fog distances for vertex position
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    return output;
}
