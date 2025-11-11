// rendertype_clouds.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering procedural cloud geometry with face culling and colors

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

// Cloud-specific constant buffer (register b5)
cbuffer CloudInfo : register(b5) {
    float4 CloudColor;       // Cloud base color
    float3 CloudOffset;      // World offset for cloud position
    float _cloudPad0;        // Padding
    float3 CellSize;         // Size of each cloud cell
    float _cloudPad1;        // Padding
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

// Cloud face data buffer (structured buffer for integer data)
Buffer<int> CloudFaces : register(t3);

struct VS_INPUT {
    uint VertexID : SV_VertexID;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : COLOR0;
};

// Bit flags for cloud face encoding
static const int FLAG_MASK_DIR = 7;
static const int FLAG_INSIDE_FACE = 1 << 4;
static const int FLAG_USE_TOP_COLOR = 1 << 5;
static const int FLAG_EXTRA_Z = 1 << 6;
static const int FLAG_EXTRA_X = 1 << 7;

// Cube face vertices (6 faces × 4 vertices each)
static const float3 vertices[24] = {
    // Bottom face (0-3)
    float3(1, 0, 0), float3(1, 0, 1), float3(0, 0, 1), float3(0, 0, 0),
    // Top face (4-7)
    float3(0, 1, 0), float3(0, 1, 1), float3(1, 1, 1), float3(1, 1, 0),
    // North face (8-11)
    float3(0, 0, 0), float3(0, 1, 0), float3(1, 1, 0), float3(1, 0, 0),
    // South face (12-15)
    float3(1, 0, 1), float3(1, 1, 1), float3(0, 1, 1), float3(0, 0, 1),
    // West face (16-19)
    float3(0, 0, 1), float3(0, 1, 1), float3(0, 1, 0), float3(0, 0, 0),
    // East face (20-23)
    float3(1, 0, 0), float3(1, 1, 0), float3(1, 1, 1), float3(1, 0, 1)
};

// Face colors (darker on bottom/sides, brighter on top)
static const float4 faceColors[6] = {
    float4(0.7, 0.7, 0.7, 0.8),  // Bottom
    float4(1.0, 1.0, 1.0, 0.8),  // Top
    float4(0.8, 0.8, 0.8, 0.8),  // North
    float4(0.8, 0.8, 0.8, 0.8),  // South
    float4(0.9, 0.9, 0.9, 0.8),  // West
    float4(0.9, 0.9, 0.9, 0.8)   // East
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Calculate which vertex of which quad this is
    int quadVertex = input.VertexID % 4;
    int index = (input.VertexID / 4) * 3;

    // Fetch cloud face data from buffer
    int cellX = CloudFaces.Load(index);
    int cellZ = CloudFaces.Load(index + 1);
    int dirAndFlags = CloudFaces.Load(index + 2);

    // Extract direction and flags
    int direction = dirAndFlags & FLAG_MASK_DIR;
    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;

    // Extract extra bits for extended cell coordinates
    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);

    // Get face vertex (flip winding if inside face)
    float3 faceVertex = vertices[(direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex)];

    // Calculate world position
    float3 pos = (faceVertex * CellSize) + (float3(cellX, 0, cellZ) * CellSize) + CloudOffset;

    // Transform to clip space
 output.Position = mul(MVP, float4(pos, 1.0));

    // Calculate distance for fog
    output.vertexDistance = fog_spherical_distance(pos);

    // Select color based on face direction or top color override
    output.vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * CloudColor;

    return output;
}
