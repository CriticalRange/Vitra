$input a_position, a_color0, a_texcoord0, a_normal
$output v_color0, v_texcoord0, v_shadow_strength

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;
uniform vec3 u_lightDir;

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;

    // Calculate shadow strength based on light direction
    float lightFactor = max(dot(normalize(a_normal), normalize(u_lightDir)), 0.0);
    v_shadow_strength = 1.0 - lightFactor;
}