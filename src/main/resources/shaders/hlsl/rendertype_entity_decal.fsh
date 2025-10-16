// rendertype_entity_decal.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Applies decal texture with overlay blending and fog

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
    float4 overlayColor : COLOR1;
    float2 texCoord0 : TEXCOORD0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample decal texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Alpha test - discard transparent fragments
    if (color.a < 0.1) {
        discard;
    }

    // Apply overlay blending (mix based on overlay alpha)
    color.rgb = lerp(input.overlayColor.rgb, color.rgb, input.overlayColor.a);

    // Apply vertex color and color modulator
    color *= input.vertexColor * ColorModulator;

    // Apply fog based on distance
    output.fragColor = apply_fog(color, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
