// rendertype_clouds.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering procedural cloud geometry with face culling and colors

#include "cbuffer_common.hlsli"

// Cloud-specific constant buffer (register b5)
cbuffer CloudInfo : register(b5) {
    float4 CloudColor;       // Cloud base color
    float3 CloudOffset;      // World offset for cloud position
    float _cloudPad0;        // Padding
    float3 CellSize;         // Size of each cloud cell
    float _cloudPad1;        // Padding
};

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
    float4 viewPos = mul(float4(pos, 1.0), ModelViewMat);
    output.Position = mul(viewPos, ProjMat);

    // Calculate distance for fog
    output.vertexDistance = fog_spherical_distance(pos);

    // Select color based on face direction or top color override
    output.vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * CloudColor;

    return output;
}
