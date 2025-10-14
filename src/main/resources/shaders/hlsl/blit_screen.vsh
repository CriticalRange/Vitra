// blit_screen.vsh - Vertex Shader
// Minecraft 1.21.8 GLSL to HLSL Shader Model 5.0 conversion
// Used for full-screen blitting operations (post-processing, screen copy)

#include "cbuffer_common.hlsli"

struct VS_INPUT {
    float3 Position : POSITION;
    float2 UV0 : TEXCOORD0;
};

struct VS_OUTPUT {
    float4 Position : SV_POSITION;
    float2 texCoord : TEXCOORD0;
};

VS_OUTPUT main(VS_INPUT input) {
    VS_OUTPUT output;

    // Transform position from [0,1] to NDC [-1,1]
    float2 screenPos = input.Position.xy * 2.0 - 1.0;
    output.Position = float4(screenPos.x, screenPos.y, 1.0, 1.0);

    // Pass through texture coordinates (Position.xy is already in [0,1])
    output.texCoord = input.Position.xy;

    return output;
}
