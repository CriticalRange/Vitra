// rendertype_text.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering text with depth testing and fog

#include "cbuffer_common.hlsli"

Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    int2 UV2 : TEXCOORD1;  // Lightmap coordinates (integer)
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // VulkanMod-style transform: Use pre-multiplied MVP matrix
    // column_major: MVP * vec4 (same as GLSL/Vulkan)
    output.Position = mul(MVP, float4(input.Position, 1.0));

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    // Sample lightmap and multiply with vertex color
    int2 lightmapCoord = input.UV2 >> 4;  // Bit shift instead of divide (faster, avoids warning)
    float4 lightmapColor = Sampler2.Load(int3(lightmapCoord, 0));
    output.vertexColor = input.Color * lightmapColor;

    // Pass through texture coordinates
    output.texCoord0 = input.UV0;

    return output;
}
