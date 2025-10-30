// rendertype_solid.vsh - Vertex Shader
// Minecraft 1.21.1 solid block rendering (opaque terrain)
// Vertex format: POSITION + COLOR + UV + UV2 (lightmap) + NORMAL

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    float2 UV2 : TEXCOORD2;  // Lightmap
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
    // ModelOffset is set per-draw to position chunks relative to camera
    float4 pos = float4(input.Position + ModelOffset, 1.0);
    output.Position = mul(mul(pos, ModelViewMat), ProjMat);

    output.vertexColor = input.Color;
    output.texCoord0 = input.UV0;
    output.texCoord2 = input.UV2;

    return output;
}
