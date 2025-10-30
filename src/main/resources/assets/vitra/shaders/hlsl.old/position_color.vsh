// position_color.vsh
// Converted from Minecraft 1.21.1 GLSL core/position_color.vsh
// Shader Model 5.0 Vertex Shader
// Purpose: Position + per-vertex color rendering (UI, debug lines, etc.)

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float4 vertexColor : COLOR0;       // Interpolated vertex color
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // VulkanMod-style transform: Use pre-multiplied MVP matrix
    // column_major: MVP * vec4 (same as GLSL/Vulkan)
    output.gl_Position = mul(MVP, float4(input.Position, 1.0));

    // Pass vertex color to pixel shader
    output.vertexColor = input.Color;

    return output;
}
