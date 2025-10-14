// rendertype_crumbling.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Applies block breaking texture overlay with fog

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord2 : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample breaking animation texture and apply vertex color
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor;

    // Alpha test - discard transparent fragments
    if (color.a < 0.1) {
        discard;
    }

    // Apply color modulator
    color = color * ColorModulator;

    // Apply fog based on distance
    output.fragColor = apply_fog(color, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
