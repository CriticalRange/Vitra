// rendertype_water_mask.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Outputs solid color for water masking

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Output color modulator directly (typically opaque color for mask)
    output.fragColor = ColorModulator;

    return output;
}
