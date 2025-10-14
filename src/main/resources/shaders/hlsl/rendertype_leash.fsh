// rendertype_leash.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Renders leash with flat shading and fog

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    nointerpolation float4 vertexColor : COLOR0;  // Flat shading (no interpolation)
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Apply fog based on distance (uses flat-shaded vertex color)
    output.fragColor = apply_fog(input.vertexColor, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
