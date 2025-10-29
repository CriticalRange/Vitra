// HLSL version of Minecraft's entity_outline_box_blur.fsh
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

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Box blur for entity outlines with fixed radius
    float4 blurred = float4(0.0, 0.0, 0.0, 0.0);
    float radius = 2.0;

    // Sample pixels with step of 2
    for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * a);
    }

    // Sample last pixel with half weight
    blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * radius) / 2.0;

    // Normalize and preserve alpha channel separately
    float4 result = blurred / (radius + 0.5);
    output.fragColor = float4(result.rgb, blurred.a);

    return output;
}
