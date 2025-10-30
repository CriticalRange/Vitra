// rendertype_armor_cutout_no_cull.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering armor with cutout transparency and no backface culling
// Vertex format: POSITION + COLOR + UV0 + UV2 (lightmap) + NORMAL

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    float2 UV1 : TEXCOORD1;  // Overlay texture coordinates (for damage/enchantment effects)
    int2 UV2 : TEXCOORD2;    // Lightmap coordinates (integer)
    float3 Normal : NORMAL;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord1 : TEXCOORD1;
    float2 texCoord2 : TEXCOORD2;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Pass through color, UVs, overlay, and lightmap
    output.vertexColor = input.Color;
    output.texCoord0 = input.UV0;
    output.texCoord1 = input.UV1;
    output.texCoord2 = input.UV2;

    return output;
}
