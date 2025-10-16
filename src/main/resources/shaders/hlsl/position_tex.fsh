// position_tex.fsh
// Converted from Minecraft 1.21.1 GLSL core/position_tex.fsh
// Shader Model 5.0 Pixel Shader
// Purpose: Textured rendering with color modulation

#include "cbuffer_common.hlsli"

// ============================================================================
// Texture and Sampler Bindings
// ============================================================================
Texture2D Sampler0 : register(t0);           // Texture slot 0
SamplerState Sampler0State : register(s0);   // Sampler slot 0

// ============================================================================
// Pixel Input Structure (Vertex Shader Output)
// ============================================================================
struct PS_INPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position (not used in PS)
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
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

    // Sample texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Discard fully transparent pixels (GLSL discard → HLSL discard)
    if (color.a == 0.0) {
        discard;
    }

    // Modulate texture color with global color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
