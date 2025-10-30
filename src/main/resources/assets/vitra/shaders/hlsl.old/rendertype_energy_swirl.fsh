// rendertype_energy_swirl.fsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Energy swirl effect fragment shader with fog fade

#include "cbuffer_common.hlsli"

// ============================================================================
// Texture and Sampler Declarations
// ============================================================================
Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

// ============================================================================
// Pixel Input Structure (Vertex Shader Output)
// ============================================================================
struct PS_INPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position (not used in PS)
    float vertexDistance : TEXCOORD0;  // Fog distance
    float4 vertexColor : COLOR0;       // Interpolated vertex color
    float2 texCoord0 : TEXCOORD1;      // Transformed texture coordinates
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

    // Sample texture and multiply by vertex color and color modulator
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor * ColorModulator;

    // Discard nearly transparent pixels
    if (color.a < 0.1) {
        discard;
    }

    // Apply fog fade (fades color brightness based on distance)
    output.fragColor = color * linear_fog_fade(input.vertexDistance, FogStart, FogEnd);

    return output;
}
