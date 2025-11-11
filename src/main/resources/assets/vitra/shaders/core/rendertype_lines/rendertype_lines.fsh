// rendertype_lines.fsh - Pixel Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Renders lines with standard fog blending

// CRITICAL FIX: Inline cbuffer definitions instead of #include
// D3DCompile with D3D_COMPILE_STANDARD_FILE_INCLUDE can't resolve includes from JAR resources
// Only including the cbuffers actually used by shaders


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

struct PS_INPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
};

struct PS_OUTPUT {
    float4 fragColor : SV_TARGET0;
};

PS_OUTPUT main(PS_INPUT input) {
    PS_OUTPUT output;

    // Apply color modulator to vertex color
    float4 color = input.vertexColor * ColorModulator;

    // Apply fog based on distance
    output.fragColor = apply_fog(color, input.sphericalVertexDistance, input.cylindricalVertexDistance,
                                  FogEnvironmentalStart, FogEnvironmentalEnd,
                                  FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    return output;
}
