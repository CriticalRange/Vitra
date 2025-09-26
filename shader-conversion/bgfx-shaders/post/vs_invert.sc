$input a_position
$output v_texcoord0
#include <bgfx_shader.sh>


uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

void main()
{
    vec4 outPos = mul(u_modelViewProj, vec4(a_position.xy * u_OutSize, 0.0, 1.0));
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    vec2 sizeRatio = u_OutSize / u_InSize;
    v_texcoord0 = a_position.xy;
    v_texcoord0.x = v_texcoord0.x * sizeRatio.x;
    v_texcoord0.y = v_texcoord0.y * sizeRatio.y;
    v_texcoord0.y = sizeRatio.y - v_texcoord0.y;
}