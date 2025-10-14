// rendertype_world_border.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering world border visual effect

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float2 texCoord0 : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Apply model offset and transform position
    float3 pos = input.Position + ModelOffset;
    float4 viewPos = mul(float4(pos, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Apply texture matrix transformation to UV coordinates
    output.texCoord0 = mul(float4(input.UV0, 0.0, 1.0), TextureMat).xy;

    return output;
}
