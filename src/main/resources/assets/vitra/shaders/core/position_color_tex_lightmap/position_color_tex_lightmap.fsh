// position_color_tex_lightmap.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Samples main texture and lightmap, combines with vertex color

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);
Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord2 : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample main texture and multiply with vertex color
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor;

    // Alpha test - discard transparent fragments
    if (color.a < 0.1) {
        discard;
    }

    // Apply color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
