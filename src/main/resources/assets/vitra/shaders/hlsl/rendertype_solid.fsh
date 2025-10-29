// rendertype_solid.fsh - Pixel Shader
// Minecraft 1.21.1 solid block rendering

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
Texture2D Sampler2 : register(t2);  // Lightmap
SamplerState Sampler0State : register(s0);
SamplerState Sampler2State : register(s2);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord2 : TEXCOORD2;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor;
    color *= Sampler2.Sample(Sampler2State, input.texCoord2);

    if (color.a < 0.1) {
        discard;
    }

    output.fragColor = color * ColorModulator;
    return output;
}
