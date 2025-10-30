// HLSL version of Minecraft's blit.vsh
// Converted from GLSL #version 150

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float4 Position : POSITION;
};

struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
};

// Uniforms from cbuffer (ProjMat is in DynamicTransforms b0)
// OutSize needs to be added to cbuffer

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position
    float4 outPos = mul(ProjMat, float4(input.Position.xy, 0.0, 1.0));
    output.gl_Position = float4(outPos.xy, 0.2, 1.0);

    // Calculate texture coordinates
    // Note: OutSize should be set as uniform, hardcoding common values for now
    float2 OutSize = float2(854.0, 480.0); // TODO: Pass as uniform
    output.texCoord = input.Position.xy / OutSize;

    return output;
}
