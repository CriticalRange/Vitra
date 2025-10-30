// rendertype_armor_entity_glint.fsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Armor glint effect fragment shader with fog fade

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

    // Sample glint texture and apply color modulation
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * ColorModulator;

    // Discard nearly transparent pixels
    if (color.a < 0.1) {
        discard;
    }

    // Calculate fade factor based on fog distance and glint alpha
    float fade = linear_fog_fade(input.vertexDistance, FogStart, FogEnd) * GlintAlpha;

    // Apply fade to RGB, preserve original alpha
    output.fragColor = float4(color.rgb * fade, color.a);

    return output;
}
