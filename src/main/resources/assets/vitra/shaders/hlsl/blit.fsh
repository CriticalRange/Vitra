// blit.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Simple blit with color modulation for post-processing effects

#include "cbuffer_common.hlsli"

Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Use ColorModulator from cbuffer_common.hlsli (b0, offset 128)
    output.fragColor = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord) * ColorModulator;

    return output;
}
