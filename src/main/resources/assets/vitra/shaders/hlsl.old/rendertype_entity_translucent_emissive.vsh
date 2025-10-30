// rendertype_entity_translucent_emissive.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Translucent emissive entity rendering (glowing entities with overlay)

#include "cbuffer_common.hlsli"

// ============================================================================
// Texture and Sampler Declarations
// ============================================================================
Texture2D Sampler1 : register(t1);
SamplerState Sampler1State : register(s1);
Texture2D Sampler2 : register(t2);
SamplerState Sampler2State : register(s2);

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;
    int2 UV1 : TEXCOORD1;  // Overlay coordinates
    float3 Normal : NORMAL;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float4 vertexColor : COLOR0;       // Vertex color with lighting
    float4 overlayColor : COLOR1;      // Overlay color (damage/hurt tint)
    float2 texCoord0 : TEXCOORD0;      // Texture coordinates
    float vertexDistance : TEXCOORD1;  // Fog distance
};

// ============================================================================
// Vertex Shader Main Entry Point
// ============================================================================
VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position through Model-View-Projection pipeline
    float4 position = float4(input.Position, 1.0);
    output.gl_Position = mul(mul(position, ModelViewMat), ProjMat);

    // Calculate fog distance (spherical fog, shape = 0)
    output.vertexDistance = fog_distance(input.Position, 0);

    // Apply Minecraft lighting (mix two light sources based on normal)
    output.vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, input.Normal, input.Color);

    // Sample overlay texture (damage/hurt effect)
    output.overlayColor = Sampler1.Load(int3(input.UV1, 0));

    // Pass texture coordinates to pixel shader
    output.texCoord0 = input.UV0;

    return output;
}
