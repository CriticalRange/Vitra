// rendertype_gui_text_highlight.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for text selection highlight in GUI

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

    // Use vertex color directly
    float4 color = input.vertexColor;

    // Discard fully transparent pixels
    if (color.a == 0.0) {
        discard;
    }

    // Apply color modulator
    output.fragColor = color * ColorModulator;

    return output;
}
