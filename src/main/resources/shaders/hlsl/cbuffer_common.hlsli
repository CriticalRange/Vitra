// cbuffer_common.hlsli
// Common constant buffer definitions for Minecraft 1.21.1 GLSL to HLSL conversion
// This file should be included by all HLSL shaders

// Use column-major matrix layout to match GLSL behavior
#pragma pack_matrix(column_major)

// ============================================================================
// Buffer 0: DynamicTransforms (register b0)
// ============================================================================
// Updated per draw call with model-view matrix, color tint, texture transform
cbuffer DynamicTransforms : register(b0) {
    float4x4 ModelViewMat;    // Offset 0, 64 bytes
    float4 ColorModulator;    // Offset 64, 16 bytes
    float3 ModelOffset;       // Offset 80, 12 bytes
    float _pad0;              // Offset 92, 4 bytes (padding)
    float4x4 TextureMat;      // Offset 96, 64 bytes
    float LineWidth;          // Offset 160, 4 bytes
    float3 _pad1;             // Offset 164, 12 bytes (padding)
};
// Total: 176 bytes (must be multiple of 16)

// ============================================================================
// Buffer 1: Projection (register b1)
// ============================================================================
// Projection matrix (orthographic or perspective), updated when camera changes
cbuffer Projection : register(b1) {
    float4x4 ProjMat;         // 64 bytes
};
// Total: 64 bytes

// ============================================================================
// Buffer 2: Fog (register b2)
// ============================================================================
// Fog rendering parameters, updated when fog settings change
cbuffer Fog : register(b2) {
    float4 FogColor;                  // Offset 0, 16 bytes
    float FogEnvironmentalStart;      // Offset 16, 4 bytes
    float FogEnvironmentalEnd;        // Offset 20, 4 bytes
    float FogRenderDistanceStart;     // Offset 24, 4 bytes
    float FogRenderDistanceEnd;       // Offset 28, 4 bytes
    float FogSkyEnd;                  // Offset 32, 4 bytes
    float FogCloudsEnd;               // Offset 36, 4 bytes
    float2 _pad2;                     // Offset 40, 8 bytes (padding)
};
// Total: 48 bytes

// ============================================================================
// Buffer 3: Globals (register b3)
// ============================================================================
// Global game state (screen resolution, animation time, effects)
cbuffer Globals : register(b3) {
    float2 ScreenSize;        // Offset 0, 8 bytes
    float GlintAlpha;         // Offset 8, 4 bytes
    float GameTime;           // Offset 12, 4 bytes
    int MenuBlurRadius;       // Offset 16, 4 bytes
    float3 _pad3;             // Offset 20, 12 bytes (padding)
};
// Total: 32 bytes

// ============================================================================
// Buffer 4: Lighting (register b4)
// ============================================================================
// Directional lighting for entities and blocks
#define MINECRAFT_LIGHT_POWER   (0.6)
#define MINECRAFT_AMBIENT_LIGHT (0.4)

cbuffer Lighting : register(b4) {
    float3 Light0_Direction;  // Offset 0, 12 bytes
    float _pad4;              // Offset 12, 4 bytes (padding)
    float3 Light1_Direction;  // Offset 16, 12 bytes
    float _pad5;              // Offset 28, 4 bytes (padding)
};
// Total: 32 bytes

// ============================================================================
// Helper Functions
// ============================================================================

// --- Projection Utilities ---
float4 projection_from_position(float4 position) {
    float4 projection = position * 0.5;
    projection.xy = float2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = position.zw;
    return projection;
}

// --- Fog Utilities ---
float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    // Add small epsilon to prevent division by zero (even though guards above should prevent it)
    float denominator = max(fogEnd - fogStart, 0.0001);
    return (vertexDistance - fogStart) / denominator;
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

float fog_spherical_distance(float3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(float3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}

// --- Lighting Utilities ---
float4 minecraft_mix_light(float3 lightDir0, float3 lightDir1, float3 normal, float4 color) {
    float light0 = max(0.0, dot(lightDir0, normal));
    float light1 = max(0.0, dot(lightDir1, normal));
    float lightAccum = min(1.0, (light0 + light1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    return float4(color.rgb * lightAccum, color.a);
}

// --- Matrix Utilities ---
float2x2 mat2_rotate_z(float radians) {
    return float2x2(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}
