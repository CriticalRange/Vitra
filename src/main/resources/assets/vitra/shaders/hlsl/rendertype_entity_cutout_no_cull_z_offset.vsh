// rendertype_entity_cutout_no_cull_z_offset.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering entities with cutout transparency, no backface culling, and Z-offset
// Note: Z-offset (depth bias) is typically applied via render state, not shader code

#include "cbuffer_common.hlsli"

Texture2D Sampler1 : register(t1);
Texture2D Sampler2 : register(t2);

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    int2 UV1 : TEXCOORD1;  // Overlay coordinates (integer)
    int2 UV2 : TEXCOORD2;  // Lightmap coordinates (integer)
    float3 Normal : NORMAL;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : COLOR0;
    float4 lightMapColor : COLOR1;
    float4 overlayColor : COLOR2;
    float2 texCoord0 : TEXCOORD1;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distance using FogShape
    output.vertexDistance = fog_distance(input.Position, FogShape);

    // Apply directional lighting
    output.vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, input.Normal, input.Color);

    // Fetch lightmap color (UV2 >> 4 is faster than UV2 / 16)
    int2 lightmapCoord = input.UV2 >> 4;
    output.lightMapColor = Sampler2.Load(int3(lightmapCoord, 0));

    // Fetch overlay color
    output.overlayColor = Sampler1.Load(int3(input.UV1, 0));

    // Pass through base texture coordinates
    output.texCoord0 = input.UV0;

    return output;
}
