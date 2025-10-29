// HLSL version of Minecraft's entity_sobel.fsh
// Converted from GLSL #version 150

#include "cbuffer_common.hlsli"

struct PS_INPUT {
    float4 gl_Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET;
};

// Texture sampler
Texture2D DiffuseSampler : register(t0);
SamplerState DiffuseSamplerState : register(s0);

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sobel edge detection - samples center and 4 neighbors
    float4 center = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord);
    float4 left   = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord - float2(input.oneTexel.x, 0.0));
    float4 right  = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + float2(input.oneTexel.x, 0.0));
    float4 up     = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord - float2(0.0, input.oneTexel.y));
    float4 down   = DiffuseSampler.Sample(DiffuseSamplerState, input.texCoord + float2(0.0, input.oneTexel.y));

    // Calculate alpha differences for edge detection
    float leftDiff  = abs(center.a - left.a);
    float rightDiff = abs(center.a - right.a);
    float upDiff    = abs(center.a - up.a);
    float downDiff  = abs(center.a - down.a);
    float total = clamp(leftDiff + rightDiff + upDiff + downDiff, 0.0, 1.0);

    // Blend colors from all samples
    float3 outColor = center.rgb * center.a + left.rgb * left.a + right.rgb * right.a + up.rgb * up.a + down.rgb * down.a;

    output.fragColor = float4(outColor * 0.2, total);

    return output;
}
