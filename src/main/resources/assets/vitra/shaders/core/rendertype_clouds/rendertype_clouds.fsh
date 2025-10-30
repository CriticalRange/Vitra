// rendertype_clouds.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Applies distance-based alpha fade to cloud geometry

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : COLOR0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Start with vertex color
    float4 color = input.vertexColor;

    // Apply distance-based alpha fade (clouds fade out at distance)
    // Uses linear_fog_value with start = 0 and end = FogCloudsEnd
    float fogValue = linear_fog_value(input.vertexDistance, 0, FogCloudsEnd);
    color.a *= 1.0 - fogValue;

    output.fragColor = color;

    return output;
}
