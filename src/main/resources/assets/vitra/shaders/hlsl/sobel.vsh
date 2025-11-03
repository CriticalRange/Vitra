// sobel.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Sobel edge detection vertex shader

#include "cbuffer_common.hlsli"

// Additional effect uniforms (cbuffer b5 - avoiding conflicts with common buffers)
cbuffer EffectUniforms : register(b5) {
    float2 InSize;
    float2 OutSize;
    float4 padding_effect;
};

struct VS_INPUT {
    float4 Position : POSITION;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position
    float4 outPos = mul(ProjMat, float4(input.Position.xy, 0.0, 1.0));
    output.Position = float4(outPos.xy, 0.2, 1.0);

    // Calculate one texel size
    output.oneTexel = 1.0 / InSize;

    // Calculate texture coordinates
    output.texCoord = input.Position.xy / OutSize;

    return output;
}
