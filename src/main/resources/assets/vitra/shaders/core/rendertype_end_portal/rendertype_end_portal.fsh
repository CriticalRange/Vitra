// rendertype_end_portal.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders animated End Portal with multiple rotating texture layers

// CRITICAL FIX: Inline cbuffer definitions instead of #include
// D3DCompile with D3D_COMPILE_STANDARD_FILE_INCLUDE can't resolve includes from JAR resources
// Only including the cbuffers actually used by shaders

#pragma pack_matrix(column_major)

cbuffer DynamicTransforms : register(b0) {
    float4x4 MVP;             // Pre-multiplied MVP matrix
    float4x4 ModelViewMat;    // Model-view matrix
    float4 ColorModulator;    // Color tint/modulation
    float3 ModelOffset;       // Model position offset
    float _pad0;
    float4x4 TextureMat;      // Texture transformation matrix
    float LineWidth;
    float3 _pad1;
};

cbuffer Projection : register(b1) {
    float4x4 ProjMat;         // Projection matrix
};

cbuffer Fog : register(b2) {
    float4 FogColor;
    float FogStart;
    float FogEnd;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    int FogShape;
    float3 _pad2;
};

cbuffer Globals : register(b3) {
    float2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    float3 _pad3;
};

cbuffer Lighting : register(b4) {
    float3 Light0_Direction;
    float _pad4;
    float3 Light1_Direction;
    float _pad5;
};

// Helper functions from cbuffer_common.hlsli
float4 linear_fog(float4 inColor, float vertexDistance, float fogStart, float fogEnd, float4 fogColor) {
    return (vertexDistance <= fogStart) ? inColor : lerp(inColor, fogColor, smoothstep(fogStart, fogEnd, vertexDistance) * fogColor.a);
}

float fog_distance(float3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    } else {
        float distXZ = length(pos.xz);
        float distY = abs(pos.y);
        return max(distXZ, distY);
    }
}

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    return (vertexDistance <= fogStart) ? 0.0 :
           (vertexDistance < fogEnd) ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
}

float linear_fog_fade(float vertexDistance, float fogStart, float fogEnd) {
    return (vertexDistance <= fogStart) ? 1.0 :
           (vertexDistance >= fogEnd) ? 0.0 :
           smoothstep(fogEnd, fogStart, vertexDistance);
}

float2x2 mat2_rotate_z(float radians) {
    return float2x2(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}

float fog_spherical_distance(float3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(float3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance,
                      float environmentalStart, float environmentalEnd,
                      float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmentalEnd),
               linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

float4 apply_fog(float4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance,
                 float environmentalStart, float environmentalEnd,
                 float renderDistanceStart, float renderDistanceEnd, float4 fogColor) {
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
                                     environmentalStart, environmentalEnd,
                                     renderDistanceStart, renderDistanceEnd);
    return float4(lerp(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float4 minecraft_mix_light(float3 lightDir0, float3 lightDir1, float3 normal, float4 color) {
    float light0 = max(0.0, dot(lightDir0, normal));
    float light1 = max(0.0, dot(lightDir1, normal));
    float lightAccum = min(1.0, (light0 + light1) * 0.6 + 0.4);
    return float4(color.rgb * lightAccum, color.a);
}

float4 minecraft_sample_lightmap(Texture2D lightMap, SamplerState lightMapState, int2 uv) {
    return lightMap.Load(int3((uv >> 4) & 0xFF, 0));
}

float4 projection_from_position(float4 position) {
    float4 projection = position * 0.5;
    projection.xy = float2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = position.zw;
    return projection;
}

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
