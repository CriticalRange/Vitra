// rendertype_end_gateway.fsh - same as rendertype_end_portal
#include "cbuffer_common.hlsli"

TextureCube Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float3 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample cubemap
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    output.fragColor = color * ColorModulator;
    return output;
}
