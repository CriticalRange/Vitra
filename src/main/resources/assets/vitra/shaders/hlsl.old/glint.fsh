// glint.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Applies glint texture with fog-based fade and global alpha control

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample glint texture and apply color modulator
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * ColorModulator;

    // Alpha test - discard transparent fragments
    if (color.a < 0.1) {
        discard;
    }

    // Calculate fog fade (inverse fog - fades out in fog)
    float fogValue = total_fog_value(input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                     FogEnvironmentalStart, FogEnvironmentalEnd,
                                     FogRenderDistanceStart, FogRenderDistanceEnd);
    float fade = (1.0 - fogValue) * GlintAlpha;

    // Apply fade to color RGB, preserve original alpha
    output.fragColor = float4(color.rgb * fade, color.a);

    return output;
}
