// rendertype_glint.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for glint effect (enchantment sparkle)

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample glint texture and apply color modulator
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * ColorModulator;

    // Alpha cutout test
    if (color.a < 0.1) {
        discard;
    }

    // Apply fog fade and glint alpha
    float fade = linear_fog_fade(input.vertexDistance, FogStart, FogEnd) * GlintAlpha;
    output.fragColor = float4(color.rgb * fade, color.a);

    return output;
}
