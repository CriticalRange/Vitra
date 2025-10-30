// panorama.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering panoramic main menu background (cubemap)

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float3 texCoord0 : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // CRITICAL FIX: With column_major matrices in HLSL, multiply as: position * matrix
    // NOT matrix * position (that's for row_major)
    output.Position = mul(MVP, float4(input.Position, 1.0));

    // Use position as cubemap texture coordinates
    output.texCoord0 = input.Position;

    return output;
}
