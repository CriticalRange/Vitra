// HLSL version of Minecraft's blit.fsh
// Converted from GLSL #version 150

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 gl_Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET;
};

// Texture sampler
Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample texture and modulate with color
    float4 texColor = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord);
    output.fragColor = texColor * ColorModulator;

    return output;
}
