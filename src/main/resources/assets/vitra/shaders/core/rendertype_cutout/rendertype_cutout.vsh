// rendertype_cutout.vsh - same as rendertype_solid
#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    float2 UV2 : TEXCOORD2;
    float3 Normal : NORMAL;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord2 : TEXCOORD2;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Use ModelOffset (same as VulkanMod's ChunkOffset concept)
    float4 pos = float4(input.Position + ModelOffset, 1.0);
    output.Position = mul(mul(pos, ModelViewMat), ProjMat);

    output.vertexColor = input.Color;
    output.texCoord0 = input.UV0;
    output.texCoord2 = input.UV2;

    return output;
}
