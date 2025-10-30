// rendertype_glint_translucent.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for translucent glint effect (enchantment sparkle with alpha blending)

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distance using FogShape
    output.vertexDistance = fog_distance(input.Position, FogShape);

    // Apply texture matrix (for animated glint effect)
    output.texCoord0 = mul(float4(input.UV0, 0.0, 1.0), TextureMat).xy;

    return output;
}
