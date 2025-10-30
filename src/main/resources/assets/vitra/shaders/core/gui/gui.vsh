// gui.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering GUI elements (buttons, text, icons) - inlined constant buffers

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

    // VulkanMod-style transform: Use pre-multiplied MVP matrix
    // column_major: MVP * vec4 (same as GLSL/Vulkan)
    output.Position = mul(MVP, float4(input.Position, 1.0));

    // Pass through vertex color
    output.vertexColor = input.Color;

    return output;
}
