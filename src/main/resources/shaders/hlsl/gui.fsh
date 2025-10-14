// gui.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Renders solid color GUI elements with alpha masking - inlined constant buffers

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

    // Apply color modulator to vertex color
    float4 color = input.vertexColor;

    // Discard completely transparent pixels
    if (color.a == 0.0) {
        discard;
    }

    // Apply color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
