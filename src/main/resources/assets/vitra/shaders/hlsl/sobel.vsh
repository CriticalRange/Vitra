// HLSL version of Minecraft's sobel.vsh
// Converted from GLSL #version 150

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float4 Position : POSITION;
};

struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position
    float4 outPos = mul(ProjMat, float4(input.Position.xy, 0.0, 1.0));
    output.gl_Position = float4(outPos.xy, 0.2, 1.0);

    // TODO: InSize and OutSize should come from uniforms/cbuffer
    float2 InSize = float2(854.0, 480.0);
    float2 OutSize = float2(854.0, 480.0);

    // Calculate one texel size
    output.oneTexel = 1.0 / InSize;

    // Calculate texture coordinates
    output.texCoord = input.Position.xy / OutSize;

    return output;
}
