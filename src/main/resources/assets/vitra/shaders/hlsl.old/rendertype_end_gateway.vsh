// rendertype_end_gateway.vsh - same as rendertype_end_portal
#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float3 texCoord0 : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    output.Position = mul(mul(float4(input.Position, 1.0), ModelViewMat), ProjMat);
    output.texCoord0 = input.Position;

    return output;
}
