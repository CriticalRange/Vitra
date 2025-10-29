// position_tex.vsh
// Converted from Minecraft 1.21.1 GLSL core/position_tex.vsh
// Shader Model 5.0 Vertex Shader
// Purpose: Position + texture coordinate rendering (textured quads, UI, blocks)

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Use MVP matrix transformation (Minecraft provides this)
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(MVP, position);

    // Pass texture coordinates to pixel shader
    output.texCoord0 = input.UV0;

    return output;
}
