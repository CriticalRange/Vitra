// position_color_lightmap.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Samples lightmap texture and applies to vertex color

#include "cbuffer_common.hlsli"

Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord2 : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample lightmap texture and multiply with vertex color
    float4 color = Sampler2.Sample(Sampler2State, input.texCoord2) * input.vertexColor;

    // Apply color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
