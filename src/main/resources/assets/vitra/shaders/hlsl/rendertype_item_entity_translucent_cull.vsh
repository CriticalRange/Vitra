// rendertype_item_entity_translucent_cull.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering translucent item entities (dropped items) with lighting

#include "cbuffer_common.hlsli"

Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    float2 UV1 : TEXCOORD1;
    int2 UV2 : TEXCOORD2;  // Lightmap coordinates (integer)
    float3 Normal : NORMAL;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord1 : TEXCOORD1;
    float2 texCoord2 : TEXCOORD4;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    // Apply directional lighting and sample lightmap
    float4 litColor = minecraft_mix_light(Light0_Direction, Light1_Direction, input.Normal, input.Color);
    int2 lightmapCoord = input.UV2 >> 4;  // Bit shift instead of divide (faster, avoids warning)
    float4 lightmapColor = Sampler2.Load(int3(lightmapCoord, 0));
    output.vertexColor = litColor * lightmapColor;

    // Pass through all texture coordinates
    output.texCoord0 = input.UV0;
    output.texCoord1 = input.UV1;
    output.texCoord2 = float2(input.UV2);

    return output;
}
