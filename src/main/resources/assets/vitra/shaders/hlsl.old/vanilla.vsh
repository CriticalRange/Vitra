// vanilla.vsh - fallback shader (POSITION + COLOR only, no UVs)
// CRITICAL: This must NOT have UVs to work with rendertype_gui and other simple shaders
#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    output.Position = mul(mul(float4(input.Position, 1.0), ModelViewMat), ProjMat);
    output.vertexColor = input.Color;

    return output;
}
