// terrain.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering terrain blocks (world geometry) with lightmap

#include "cbuffer_common.hlsli"

Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    int2 UV2 : TEXCOORD1;  // Lightmap coordinates (integer)
    float3 Normal : NORMAL;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
};

// Sample lightmap with clamping to prevent edge bleeding
float4 minecraft_sample_lightmap(int2 uv) {
    float2 uvFloat = float2(uv) / 256.0;
    float2 clampedUV = clamp(uvFloat, float2(0.5 / 16.0, 0.5 / 16.0), float2(15.5 / 16.0, 15.5 / 16.0));
    // Use SampleLevel in vertex shader (Sample() requires gradients, only works in pixel shaders)
    return Sampler2.SampleLevel(Sampler2State, clampedUV, 0);
}

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Apply model offset and transform position
    float3 pos = input.Position + ModelOffset;
    float4 viewPos = mul(float4(pos, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate fog distances (using offset position)
    output.sphericalVertexDistance = fog_spherical_distance(pos);
    output.cylindricalVertexDistance = fog_cylindrical_distance(pos);

    // Sample lightmap and multiply with vertex color
    output.vertexColor = input.Color * minecraft_sample_lightmap(input.UV2);

    // Pass through texture coordinates
    output.texCoord0 = input.UV0;

    return output;
}
