// rendertype_outline.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Renders solid color outline based on alpha mask

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

    // Sample texture to get alpha mask
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Discard completely transparent pixels (no outline)
    if (color.a == 0.0) {
        discard;
    }

    // Output solid color outline (uses ColorModulator RGB and alpha, ignores texture color)
    output.fragColor = float4(ColorModulator.rgb * input.vertexColor.rgb, ColorModulator.a);

    return output;
}
