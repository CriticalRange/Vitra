// position_color.vsh
// Converted from Minecraft 1.21.8 GLSL core/position_color.vsh
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

    // Transform position through Model-View-Projection pipeline
    // Note: Matrix multiplication order matches GLSL due to column_major pragma
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Pass vertex color to pixel shader
    output.vertexColor = input.Color;

    return output;
}
