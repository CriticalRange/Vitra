// sky.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Applies sky color with special fog blending (uses FogSkyEnd)

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Apply fog to color modulator (sky color)
    // Uses FogSkyEnd for all fog parameters (spherical and cylindrical use same distance)
    output.fragColor = apply_fog(ColorModulator, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);

    return output;
}
