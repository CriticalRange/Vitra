// rendertype_water_mask.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering water depth mask (stencil buffer effect)

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    return output;
}
