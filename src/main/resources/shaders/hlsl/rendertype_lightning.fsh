// rendertype_lightning.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Renders lightning with inverse fog (fades out in fog instead of blending)

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Calculate fog value and apply inverse (1.0 - fog) to fade out lightning in fog
    float fogValue = total_fog_value(input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                     FogEnvironmentalStart, FogEnvironmentalEnd,
                                     FogRenderDistanceStart, FogRenderDistanceEnd);

    // Lightning fades out (becomes transparent) in fog areas
    output.fragColor = input.vertexColor * ColorModulator * (1.0 - fogValue);

    return output;
}
