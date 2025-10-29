// HLSL version of Minecraft's box_blur.fsh
// Converted from GLSL #version 150

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 gl_Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 sampleStep : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET;
};

// Texture sampler
Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

// Additional uniforms (should be in cbuffer)
// For now using defaults
static const float Radius = 5.0;
static const float RadiusMultiplier = 1.0;

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Box blur implementation
    // This shader relies on linear sampling to reduce texture samples
    float4 blurred = float4(0.0, 0.0, 0.0, 0.0);
    float actualRadius = round(Radius * RadiusMultiplier);

    // Sample pixels with step of 2 (using linear interpolation)
    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {
        blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * a);
    }

    // Sample last pixel with half weight
    blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * actualRadius) / 2.0;

    output.fragColor = blurred / (actualRadius + 0.5);

    return output;
}
