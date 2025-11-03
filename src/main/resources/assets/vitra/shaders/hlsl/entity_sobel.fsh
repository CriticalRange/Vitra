// entity_sobel.fsh - Fragment Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Sobel edge detection for entity outlines

#include "cbuffer_common.hlsli"

Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    float4 center = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord);
    float4 left = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord - float2(input.oneTexel.x, 0.0));
    float4 right = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + float2(input.oneTexel.x, 0.0));
    float4 up = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord - float2(0.0, input.oneTexel.y));
    float4 down = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + float2(0.0, input.oneTexel.y));

    float leftDiff  = abs(center.a - left.a);
    float rightDiff = abs(center.a - right.a);
    float upDiff    = abs(center.a - up.a);
    float downDiff  = abs(center.a - down.a);

    float total = clamp(leftDiff + rightDiff + upDiff + downDiff, 0.0, 1.0);
    float3 outColor = center.rgb * center.a + left.rgb * left.a + right.rgb * right.a + up.rgb * up.a + down.rgb * down.a;

    output.fragColor = float4(outColor * 0.2, total);

    return output;
}
