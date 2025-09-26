$input a_position
$output v_texcoord0, v_texcoord1
#include <bgfx_shader.sh>


uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

uniform vec4 u_BlurDir_Radius;
#define u_BlurDir u_BlurDir_Radius.xy
#define u_Radius u_BlurDir_Radius.z

void main()
{
    vec4 outPos = mul(u_modelViewProj, vec4(a_position.xy * u_OutSize, 0.0, 1.0));
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    vec2 oneTexel = 1.0 / u_InSize;
    v_texcoord1 = oneTexel * u_BlurDir; // sampleStep

    v_texcoord0 = a_position.xy;
}