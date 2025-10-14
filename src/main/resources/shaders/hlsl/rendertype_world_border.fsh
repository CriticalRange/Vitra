// rendertype_world_border.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Applies animated world border texture with alpha masking

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample world border texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Discard completely transparent pixels
    if (color.a == 0.0) {
        discard;
    }

    // Apply color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
