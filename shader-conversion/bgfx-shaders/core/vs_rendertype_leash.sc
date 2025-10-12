$input a_position, a_color0, a_texcoord0
$output v_color0, v_texcoord0, v_leash_distance

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;
uniform float u_time;

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    // Calculate leash distance for gradient effect
    vec4 worldPos = mul(u_modelViewMat, vec4(a_position, 1.0));
    v_leash_distance = length(worldPos.xyz);

    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;
}