$input a_position
$output v_texcoord0, v_texcoord1
#include <bgfx_shader.sh>


uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

uniform vec4 u_InScale_InOffset;
#define u_InScale u_InScale_InOffset.xy
#define u_InOffset u_InScale_InOffset.zw

uniform vec4 u_InRotation;
#define InRotation u_InRotation.x

void main()
{
    vec4 outPos = mul(u_modelViewProj, vec4(a_position.xy * u_OutSize, 0.0, 1.0));
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    v_texcoord0 = a_position.xy;

    float Deg2Rad = 0.0174532925;
    float InRadians = InRotation * Deg2Rad;
    float Cosine = cos(InRadians);
    float Sine = sin(InRadians);
    float RotU = v_texcoord0.x * Cosine - v_texcoord0.y * Sine;
    float RotV = v_texcoord0.y * Cosine + v_texcoord0.x * Sine;
    v_texcoord1 = vec2(RotU, RotV) * u_InScale + u_InOffset; // scaledCoord
}