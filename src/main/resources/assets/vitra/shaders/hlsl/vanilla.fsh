// vanilla.fsh - fallback shader (POSITION + COLOR only, no textures)
// CRITICAL: This must NOT sample textures to work with rendertype_gui
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

    float4 color = input.vertexColor;

    if (color.a == 0.0) {
        discard;
    }

    output.fragColor = color * ColorModulator;
    return output;
}
