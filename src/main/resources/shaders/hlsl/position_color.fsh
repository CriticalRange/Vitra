// position_color.fsh
// Converted from Minecraft 1.21.8 GLSL core/position_color.fsh
// Shader Model 5.0 Pixel Shader
// Purpose: Position + per-vertex color rendering with color modulation

#include "cbuffer_common.hlsli"

// ============================================================================
// Pixel Input Structure (Vertex Shader Output)
// ============================================================================
struct PS_INPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position (not used in PS)
    float4 vertexColor : COLOR0;       // Interpolated vertex color
};

// ============================================================================
// Pixel Shader Output Structure
// ============================================================================
struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;  // Output color
};

// ============================================================================
// Pixel Shader Main Entry Point
// ============================================================================
PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    float4 color = input.vertexColor;

    // Discard fully transparent pixels (GLSL discard → HLSL clip)
    if (color.a == 0.0) {
        discard;
    }

    // Modulate vertex color with global color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
