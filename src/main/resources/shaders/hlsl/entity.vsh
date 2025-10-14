// entity.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering most entities (mobs, players, armor stands) with lighting and overlay

#include "cbuffer_common.hlsli"

Texture2D Sampler1 : register(t1);
SamplerState Sampler1State : register(s1);
Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

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
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float4 lightMapColor : COLOR1;
    float4 overlayColor : COLOR2;
    float2 texCoord0 : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    // Apply directional lighting (or just use vertex color if NO_CARDINAL_LIGHTING is defined)
#ifdef NO_CARDINAL_LIGHTING
    output.vertexColor = input.Color;
#else
    output.vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, input.Normal, input.Color);
#endif

    // Sample lightmap (unless EMISSIVE is defined, which makes entities glow)
#ifndef EMISSIVE
    int2 lightmapCoord = input.UV2 >> 4;  // Bit shift instead of divide (faster and avoids warning)
    output.lightMapColor = Sampler2.Load(int3(lightmapCoord, 0));
#else
    output.lightMapColor = float4(1.0, 1.0, 1.0, 1.0);
#endif

    // Sample overlay texture (damage tint, spectral effect, etc.)
    output.overlayColor = Sampler1.Load(int3(input.UV1, 0));

    // Apply texture matrix if APPLY_TEXTURE_MATRIX is defined
#ifdef APPLY_TEXTURE_MATRIX
    output.texCoord0 = mul(float4(input.UV0, 0.0, 1.0), TextureMat).xy;
#else
    output.texCoord0 = input.UV0;
#endif

    return output;
}
