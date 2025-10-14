// entity.fsh - Pixel Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Applies entity texture with overlay, lighting, and fog

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float4 lightMapColor : COLOR1;
    float4 overlayColor : COLOR2;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample entity texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Alpha test - discard transparent fragments
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // Apply vertex color and color modulator
    color *= input.vertexColor * ColorModulator;

    // Apply overlay blending (unless NO_OVERLAY is defined)
#ifndef NO_OVERLAY
    color.rgb = lerp(input.overlayColor.rgb, color.rgb, input.overlayColor.a);
#endif

    // Apply lightmap (unless EMISSIVE is defined)
#ifndef EMISSIVE
    color *= input.lightMapColor;
#endif

    // Apply fog based on distance
    output.fragColor = apply_fog(color, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
