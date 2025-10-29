// rendertype_gui.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering GUI elements (widgets, panels, solid colored UI)
// Vertex format: POSITION + COLOR

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform vertex position using MVP matrix
    output.Position = mul(MVP, float4(input.Position, 1.0));

    // Pass through vertex color
    output.vertexColor = input.Color;

    return output;
}
