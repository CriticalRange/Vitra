// rendertype_entity_translucent.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering translucent entities with alpha blending

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : COLOR0;
    float4 lightMapColor : COLOR1;
    float4 overlayColor : COLOR2;
    float2 texCoord0 : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Alpha test (discard nearly transparent fragments)
    if (color.a < 0.1) {
        discard;
    }

    // Apply vertex color and color modulator
    color *= input.vertexColor * ColorModulator;

    // Apply overlay blending
    color.rgb = lerp(input.overlayColor.rgb, color.rgb, input.overlayColor.a);

    // Apply lightmap
    color *= input.lightMapColor;

    // Apply fog
    output.fragColor = linear_fog(color, input.vertexDistance, FogStart, FogEnd, FogColor);

    return output;
}
