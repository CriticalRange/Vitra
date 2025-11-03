// entity_outline_box_blur.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Box blur for entity outlines (simpler version with fixed radius)

#include "cbuffer_common.hlsli"

Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 sampleStep : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    float4 blurred = float4(0.0, 0.0, 0.0, 0.0);
    float radius = 2.0;

    for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * a);
    }

    blurred += DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + input.sampleStep * radius) / 2.0;
    output.fragColor = float4((blurred / (radius + 0.5)).rgb, blurred.a);

    return output;
}
