$input a_position, a_color0, a_normal
$output v_color0, v_normal

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_color0 = a_color0;
    v_normal = a_normal;
}