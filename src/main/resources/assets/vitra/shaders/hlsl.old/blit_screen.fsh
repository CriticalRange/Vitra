// blit_screen.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
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

    // CRITICAL TEST: Output magenta to verify blit_screen is final output
    output.fragColor = float4(1.0, 0.0, 1.0, 1.0);  // Bright magenta

    // Original code (disabled for testing):
    // output.fragColor = InSampler.Sample(InSamplerState, input.texCoord);

    return output;
}
