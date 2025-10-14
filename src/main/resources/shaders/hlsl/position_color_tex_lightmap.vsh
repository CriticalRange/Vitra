// position_color_tex_lightmap.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for textured, colored geometry with lightmap (GUI elements with lighting)

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float2 UV0 : TEXCOORD0;  // Main texture coordinates
    float2 UV2 : TEXCOORD1;  // Lightmap coordinates
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord2 : TEXCOORD1;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position: Position -> ModelView -> Projection
    float4 viewPos = mul(float4(input.Position, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Pass through vertex color
    output.vertexColor = input.Color;

    // Pass through both texture coordinate sets
    output.texCoord0 = input.UV0;
    output.texCoord2 = input.UV2;

    return output;
}
