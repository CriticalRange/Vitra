$input a_position, a_color0, a_texcoord0
$output v_color0, v_texcoord0, v_random

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;
uniform float u_time;

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;
    v_random = fract(sin(dot(a_position.xy, vec2(12.9898, 78.233))) * 43758.5453);
}