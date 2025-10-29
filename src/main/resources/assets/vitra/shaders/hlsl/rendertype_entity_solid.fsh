// rendertype_entity_solid.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering solid entities (no transparency)

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);  // Base texture
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float4 lightMapColor : COLOR1;
    float4 overlayColor : COLOR2;
    float2 texCoord0 : TEXCOORD0;
    float vertexDistance : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample base texture and apply vertex color and color modulator (VulkanMod pattern)
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0) * input.vertexColor * ColorModulator;

    // Apply overlay blending (VulkanMod pattern: mix overlayColor into color based on overlay alpha)
    color.rgb = lerp(input.overlayColor.rgb, color.rgb, input.overlayColor.a);

    // Apply lightmap
    color *= input.lightMapColor;

    // Apply linear fog (VulkanMod pattern)
    output.fragColor = linear_fog(color, input.vertexDistance, FogStart, FogEnd, FogColor);

    return output;
}
