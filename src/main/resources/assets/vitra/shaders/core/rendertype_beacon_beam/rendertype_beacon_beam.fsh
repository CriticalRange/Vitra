// rendertype_beacon_beam.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Applies beacon beam texture with depth-based fog

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample beacon texture and apply vertex color and color modulator
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);
    color *= input.vertexColor * ColorModulator;

    // Calculate fragment distance from depth buffer
    // Reconstruct view-space Z from fragment depth
    float fragmentDistance = -ProjMat[3].z / ((input.Position.z) * -2.0 + 1.0 - ProjMat[2].z);

    // Apply fog using fragment distance (both spherical and cylindrical use same value)
    output.fragColor = apply_fog(color, fragmentDistance, fragmentDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
