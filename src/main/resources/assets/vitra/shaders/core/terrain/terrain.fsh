// terrain.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Applies block texture with lighting and optional alpha cutout

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample block texture and apply vertex color and color modulator
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor * ColorModulator;

    // Alpha cutout - discard fragments below threshold if ALPHA_CUTOUT is defined
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // Apply fog based on distance
    output.fragColor = apply_fog(color, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
