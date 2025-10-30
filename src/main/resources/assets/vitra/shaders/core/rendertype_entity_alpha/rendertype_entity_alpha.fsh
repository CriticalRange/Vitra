// rendertype_entity_alpha.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders entities with alpha testing based on vertex color alpha

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 vertexColor : COLOR0;
    float2 texCoord0 : TEXCOORD0;
    float2 texCoord1 : TEXCOORD1;
    float2 texCoord2 : TEXCOORD2;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample entity texture
    float4 color = Sampler0.Sample(Sampler0State, input.texCoord0);

    // Alpha test - discard if texture alpha is less than vertex alpha
    if (color.a < input.vertexColor.a) {
        discard;
    }

    // Output texture color directly (no color modulator)
    output.fragColor = color;

    return output;
}
