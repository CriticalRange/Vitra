// rendertype_gui.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders solid color GUI elements

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Use vertex color modulated by ColorModulator uniform
    float4 color = input.vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    output.fragColor = color * ColorModulator;

    return output;
}
