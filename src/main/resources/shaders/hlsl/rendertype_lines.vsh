// rendertype_lines.vsh - Vertex Shader
// Minecraft 1.21.1 GLSL to HLSL Shader Model 5.0 conversion
// Used for rendering thick lines (hitboxes, debug rendering) with screen-space width

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float4 Color : COLOR0;
    float3 Normal : NORMAL;
    uint VertexID : SV_VertexID;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float sphericalVertexDistance : TEXCOORD2;
    float cylindricalVertexDistance : TEXCOORD3;
    float4 vertexColor : COLOR0;
};

// View shrink constant to prevent z-fighting
static const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
static const float4x4 VIEW_SCALE = float4x4(
    VIEW_SHRINK, 0.0, 0.0, 0.0,
    0.0, VIEW_SHRINK, 0.0, 0.0,
    0.0, 0.0, VIEW_SHRINK, 0.0,
    0.0, 0.0, 0.0, 1.0
);

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform line start and end positions to clip space
    float4 linePosStart = mul(mul(mul(float4(input.Position, 1.0), ModelViewMat), VIEW_SCALE), ProjMat);
    float4 linePosEnd = mul(mul(mul(float4(input.Position + input.Normal, 1.0), ModelViewMat), VIEW_SCALE), ProjMat);

    // Convert to normalized device coordinates
    float3 ndc1 = linePosStart.xyz / linePosStart.w;
    float3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    // Calculate line direction in screen space
    float2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);

    // Calculate perpendicular offset for line width
    float2 lineOffset = float2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;

    // Ensure consistent offset direction
    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    // Alternate vertices are offset in opposite directions to create line thickness
    if (input.VertexID % 2 == 0) {
        output.Position = float4((ndc1 + float3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        output.Position = float4((ndc1 - float3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    // Calculate fog distances
    output.sphericalVertexDistance = fog_spherical_distance(input.Position);
    output.cylindricalVertexDistance = fog_cylindrical_distance(input.Position);

    // Pass through vertex color
    output.vertexColor = input.Color;

    return output;
}
