// particle.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering particles (smoke, fire, water splashes, etc.) with fog

#include "cbuffer_common.hlsli"

Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
    float4 Color : COLOR0;
    int2 UV2 : TEXCOORD1;  // Lightmap coordinates (integer)
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float2 texCoord0 : TEXCOORD0;
    float4 vertexColor : COLOR0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    // Pass through texture coordinates
    output.texCoord0 = input.UV0;

    // Sample lightmap and multiply with vertex color
    // texelFetch in GLSL uses integer coordinates, UV2 / 16 for lightmap sampling
    int2 lightmapCoord = input.UV2 >> 4;  // Bit shift instead of divide (faster, avoids warning)
    float4 lightmapColor = Sampler2.Load(int3(lightmapCoord, 0));
    output.vertexColor = input.Color * lightmapColor;

    return output;
}
