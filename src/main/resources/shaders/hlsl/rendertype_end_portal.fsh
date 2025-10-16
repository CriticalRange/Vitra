// rendertype_end_portal.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders animated End Portal with multiple rotating texture layers

#include "cbuffer_common.hlsli"

Texture2D Sampler0 : register(t0);
SamplerState Sampler0State : register(s0);
Texture2D Sampler1 : register(t1);
SamplerState Sampler1State : register(s1);

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float4 texProj0 : TEXCOORD0;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

// End Portal color palette (16 layers)
static const float3 COLORS[16] = {
    float3(0.022087, 0.098399, 0.110818),
    float3(0.011892, 0.095924, 0.089485),
    float3(0.027636, 0.101689, 0.100326),
    float3(0.046564, 0.109883, 0.114838),
    float3(0.064901, 0.117696, 0.097189),
    float3(0.063761, 0.086895, 0.123646),
    float3(0.084817, 0.111994, 0.166380),
    float3(0.097489, 0.154120, 0.091064),
    float3(0.106152, 0.131144, 0.195191),
    float3(0.097721, 0.110188, 0.187229),
    float3(0.133516, 0.138278, 0.148582),
    float3(0.070006, 0.243332, 0.235792),
    float3(0.196766, 0.142899, 0.214696),
    float3(0.047281, 0.315338, 0.321970),
    float3(0.204675, 0.390010, 0.302066),
    float3(0.080955, 0.314821, 0.661491)
};

// Scale and translate matrix for texture projection
static const float4x4 SCALE_TRANSLATE = float4x4(
    0.5, 0.0, 0.0, 0.0,
    0.0, 0.5, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.25, 0.25, 0.0, 1.0
);

// Number of portal layers (compile-time constant, can be overridden)
#ifndef PORTAL_LAYERS
#define PORTAL_LAYERS 15
#endif

// Calculate transformation matrix for each portal layer
float4x4 end_portal_layer(float layer) {
    // Translation matrix (moves texture over time)
    float4x4 translate = float4x4(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        17.0 / layer, (2.0 + layer / 1.5) * (GameTime * 1.5), 0.0, 1.0
    );

    // Rotation matrix (rotates each layer differently)
    float2x2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));

    // Scale matrix
    float scale = (4.5 - layer / 4.0) * 2.0;
    float2x2 scaleMatrix = float2x2(scale, 0.0, 0.0, scale);

    // Combine scale and rotation into 4x4 matrix
    float4x4 scaleRotate = float4x4(
        (scaleMatrix * rotate)[0].x, (scaleMatrix * rotate)[0].y, 0.0, 0.0,
        (scaleMatrix * rotate)[1].x, (scaleMatrix * rotate)[1].y, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    return mul(mul(scaleRotate, translate), SCALE_TRANSLATE);
}

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Sample base layer texture (layer 0)
    float3 color = Sampler0.Sample(Sampler0State, input.texProj0.xy / input.texProj0.w).rgb * COLORS[0];

    // Accumulate additional portal layers
    [unroll]
    for (int i = 0; i < PORTAL_LAYERS; i++) {
        float4x4 layerTransform = end_portal_layer(float(i + 1));
        float4 transformedCoord = mul(input.texProj0, layerTransform);
        float3 layerSample = Sampler1.Sample(Sampler1State, transformedCoord.xy / transformedCoord.w).rgb;
        color += layerSample * COLORS[i];
    }

    // Apply fog to final color
    output.fragColor = apply_fog(float4(color, 1.0), input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
