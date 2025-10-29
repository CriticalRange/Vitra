// rendertype_text_background_see_through.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders solid colored backgrounds without depth testing (no fog)

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
    float4 color = input.vertexColor * ColorModulator;

    // Alpha test - discard transparent background fragments
    if (color.a < 0.1) {
        discard;
    }

    // Output color (no fog for see-through backgrounds)
    output.fragColor = color;

    return output;
}
