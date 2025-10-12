$input a_position, a_color0, a_texcoord0
$output v_color0, v_texcoord0, v_see_through

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;
    v_see_through = 0.7; // Semi-transparent
}