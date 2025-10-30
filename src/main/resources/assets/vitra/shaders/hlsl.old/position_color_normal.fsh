// position_color_normal.fsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Position + color + normal rendering with fog and color modulation

#include "cbuffer_common.hlsli"

// ============================================================================
// Pixel Input Structure (Vertex Shader Output)
// ============================================================================
struct PS_INPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position (not used in PS)
    float vertexDistance : TEXCOORD0;  // Fog distance
    float4 vertexColor : COLOR0;       // Interpolated vertex color
    float4 normal : NORMAL0;           // Transformed normal
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

    // Apply color modulation
    float4 color = input.vertexColor * ColorModulator;

    // Discard nearly transparent pixels
    if (color.a < 0.1) {
        discard;
    }

    // Apply linear fog
    output.fragColor = linear_fog(color, input.vertexDistance, FogStart, FogEnd, FogColor);

    return output;
}
