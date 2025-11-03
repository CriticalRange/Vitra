// position_tex_color.vsh
// Converted from Minecraft 1.21.1 GLSL core/position_tex_color.vsh
// Shader Model 5.0 Vertex Shader
// Purpose: Position + texture + per-vertex color (GUI, HUD, most UI elements)

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
    float4 Color : COLOR0;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
    float4 vertexColor : COLOR0;       // Interpolated vertex color
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform vertex position using MVP matrix
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(MVP, position);

    // Pass through texture coordinates and vertex color
    output.texCoord0 = input.UV0;
    output.vertexColor = input.Color;

    return output;
}
