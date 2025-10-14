// position.fsh
// Converted from Minecraft 1.21.8 GLSL core/position.fsh
// Shader Model 5.0 Pixel Shader
// Purpose: Simple position-only rendering with fog application

#include "cbuffer_common.hlsli"

// ============================================================================
// Pixel Input Structure (Vertex Shader Output)
// ============================================================================
struct PS_INPUT {
    float4 gl_Position : SV_POSITION;           // Clip-space position (not used in PS)
    float sphericalVertexDistance : TEXCOORD0;  // Spherical fog distance
    float cylindricalVertexDistance : TEXCOORD1; // Cylindrical fog distance
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

    // Apply fog to the color modulator (base color from constant buffer)
    output.fragColor = apply_fog(
        ColorModulator,
        input.sphericalVertexDistance,
        input.cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );

    return output;
}
