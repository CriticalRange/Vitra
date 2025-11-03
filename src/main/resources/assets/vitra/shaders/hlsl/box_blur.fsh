// box_blur.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Box blur effect for post-processing

#include "cbuffer_common.hlsli"

Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

cbuffer UniformBuffer : register(b5) {
    float Radius;
    float RadiusMultiplier;
    float2 padding;
};

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 sampleStep : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

// This shader relies on linear sampling to reduce texture samples in half.
// Instead of sampling each pixel position with a step of 1 we sample between pixels with a step of 2.
// In the end we sample the last pixel with a half weight, since the amount of pixels to sample is always odd (actualRadius * 2 + 1).
PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    float4 blurred = float4(0.0, 0.0, 0.0, 0.0);
    float actualRadius = round(Radius * RadiusMultiplier);

    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {
        blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * a);
    }

    blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * actualRadius) / 2.0;
    output.fragColor = blurred / (actualRadius + 0.5);

    return output;
}
