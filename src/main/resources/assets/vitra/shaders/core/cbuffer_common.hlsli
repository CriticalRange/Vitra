// cbuffer_common.hlsli
// Common constant buffer definitions for Minecraft 1.21.1 GLSL to HLSL conversion
// This file should be included by all HLSL shaders

// CRITICAL: Use column_major (HLSL default without pragma)
// JOML stores matrices in column-major format in memory
// HLSL's default column_major matches JOML's output from matrix.get(byteBuffer)
// #pragma pack_matrix(column_major)  // This is the default, no pragma needed

// ============================================================================
// Buffer 0: DynamicTransforms (register b0)
// ============================================================================
// Updated per draw call with model-view matrix, color tint, texture transform
cbuffer DynamicTransforms : register(b0) {
    float4x4 MVP;             // Offset 0, 64 bytes - ADDED: Pre-multiplied MVP from Minecraft
    float4x4 ModelViewMat;    // Offset 64, 64 bytes
    float4 ColorModulator;    // Offset 128, 16 bytes
    float3 ModelOffset;       // Offset 144, 12 bytes (VulkanMod: ChunkOffset/ModelOffset - positions relative to camera)
    float _pad0;              // Offset 156, 4 bytes (padding)
    float4x4 TextureMat;      // Offset 160, 64 bytes
    float LineWidth;          // Offset 224, 4 bytes
    float3 _pad1;             // Offset 228, 12 bytes (padding)
};
// Total: 240 bytes (must be multiple of 16)

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
    float FogStart;                   // Offset 16, 4 bytes (VulkanMod-style simple fog)
    float FogEnd;                     // Offset 20, 4 bytes (VulkanMod-style simple fog)
    float FogEnvironmentalStart;      // Offset 24, 4 bytes
    float FogEnvironmentalEnd;        // Offset 28, 4 bytes
    float FogRenderDistanceStart;     // Offset 32, 4 bytes
    float FogRenderDistanceEnd;       // Offset 36, 4 bytes
    float FogSkyEnd;                  // Offset 40, 4 bytes
    float FogCloudsEnd;               // Offset 44, 4 bytes
    int FogShape;                     // Offset 48, 4 bytes (0 = spherical, 1 = cylindrical)
    float3 _pad2;                     // Offset 52, 12 bytes (padding)
};
// Total: 64 bytes (must be multiple of 16)

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
// VulkanMod-style simple fog (used by entity shaders)
float4 linear_fog(float4 inColor, float vertexDistance, float fogStart, float fogEnd, float4 fogColor) {
    return (vertexDistance <= fogStart) ? inColor : lerp(inColor, fogColor, smoothstep(fogStart, fogEnd, vertexDistance) * fogColor.a);
}

float fog_distance(float3 pos, int shape) {
    if (shape == 0) {
        // Spherical fog
        return length(pos);
    } else {
        // Cylindrical fog
        float distXZ = length(pos.xz);
        float distY = abs(pos.y);
        return max(distXZ, distY);
    }
}

// Advanced fog implementation: single ternary guarantees return value for HLSL flow analysis
float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    return (vertexDistance <= fogStart) ? 0.0 :
           (vertexDistance < fogEnd) ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
}

// Fog fade for glint effects (returns 1.0 at fogStart, 0.0 at fogEnd)
// VulkanMod-style implementation: single ternary guarantees return value for HLSL flow analysis
float linear_fog_fade(float vertexDistance, float fogStart, float fogEnd) {
    return (vertexDistance <= fogStart) ? 1.0 :
           (vertexDistance >= fogEnd) ? 0.0 :
           smoothstep(fogEnd, fogStart, vertexDistance);
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

// Sample lightmap texture (converts UV2 coordinates to lightmap texel)
// VulkanMod uses bitfieldExtract(uv, 4, 8) which extracts bits [4:11]
// Simplified: UV2 / 16 or UV2 >> 4
float4 minecraft_sample_lightmap(Texture2D lightMap, SamplerState lightMapState, float2 uv) {
    // Cast float2 to int2 for bitshift operations - prevents D3DCompile crash
    int2 uvInt = (int2)uv;
    return lightMap.Load(int3((uvInt >> 4) & 0xFF, 0));
}

// --- Matrix Utilities ---
float2x2 mat2_rotate_z(float radians) {
    return float2x2(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}
