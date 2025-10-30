// rendertype_entity_translucent_emissive.fsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Translucent emissive entity fragment shader with overlay and fog fade

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
    float4 vertexColor : COLOR0;       // Vertex color with lighting
    float4 overlayColor : COLOR1;      // Overlay color (damage/hurt tint)
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
    float vertexDistance : TEXCOORD1;  // Fog distance
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

    // Discard nearly transparent pixels
    if (color.a < 0.1) {
        discard;
    }

    // Multiply by vertex color and color modulator
    color *= input.vertexColor * ColorModulator;

    // Mix overlay color (damage/hurt tint) based on overlay alpha
    color.rgb = lerp(input.overlayColor.rgb, color.rgb, input.overlayColor.a);

    // Apply fog fade (fades brightness based on distance)
    output.fragColor = color * linear_fog_fade(input.vertexDistance, FogStart, FogEnd);

    return output;
}
