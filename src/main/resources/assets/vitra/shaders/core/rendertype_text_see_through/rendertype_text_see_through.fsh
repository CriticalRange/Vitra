// rendertype_text_see_through.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders text without depth testing (no fog)

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample text texture and apply vertex color
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor;

    // Alpha test - discard transparent text fragments
    if (color.a < 0.1) {
        discard;
    }

    // Apply color modulator (no fog for see-through text)
    output.fragColor = color * ColorModulator;

    return output;
}
