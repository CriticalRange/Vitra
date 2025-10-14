// rendertype_end_portal.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering the End Portal multi-layered animation effect

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 texProj0 : TEXCOORD0;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate projected texture coordinates from position
    output.texProj0 = projection_from_position(output.Position);

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    return output;
}
