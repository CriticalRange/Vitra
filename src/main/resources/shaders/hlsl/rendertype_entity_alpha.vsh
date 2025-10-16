// rendertype_entity_alpha.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering semi-transparent entities with alpha testing

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    float2 UV1 : TEXCOORD1;
    float2 UV2 : TEXCOORD2;
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

    // Pass through all attributes
    output.vertexColor = input.Color;
    output.texCoord0 = input.UV0;
    output.texCoord1 = input.UV1;
    output.texCoord2 = input.UV2;

    return output;
}
