// rendertype_armor_entity_glint.vsh
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Purpose: Armor glint effect rendering (enchantment sparkles)

#include "cbuffer_common.hlsli"

// ============================================================================
// Vertex Input Structure
// ============================================================================
struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
};

// ============================================================================
// Vertex Output Structure (Pixel Shader Input)
// ============================================================================
struct VS_OUTPUT {
    float4 gl_Position : SV_POSITION;  // Clip-space position
    float vertexDistance : TEXCOORD0;  // Fog distance
    float2 texCoord0 : TEXCOORD1;      // Transformed texture coordinates
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

    // Apply texture transformation matrix for animated glint effect
    output.texCoord0 = mul(float4(input.UV0, 0.0, 1.0), TextureMat).xy;

    return output;
}
