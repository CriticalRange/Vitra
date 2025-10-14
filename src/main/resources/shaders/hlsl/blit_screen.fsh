// blit_screen.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Samples and outputs screen texture (simple blit operation)

#include "cbuffer_common.hlsli"

Texture2D InSampler : register(t0);
SamplerState InSamplerState : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample screen texture (no color modulator in original)
    output.fragColor = InSampler.Sample(InSamplerState, input.texCoord);

    return output;
}
