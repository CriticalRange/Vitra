// panorama.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Samples cubemap texture for panoramic background

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

    // CRITICAL TEST: Output cyan to verify panorama shader is what we see
    output.fragColor = float4(0.0, 1.0, 1.0, 1.0);  // Bright cyan

    // Original code (disabled for testing):
    // output.fragColor = Sampler0.Sample(Sampler0State, input.texCoord0);

    return output;
}
