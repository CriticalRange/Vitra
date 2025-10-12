$input a_position, a_color0, a_texcoord0, a_normal
$output v_color0, v_texcoord0, v_normal, v_view_normal

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;

void main()
{
    vec4 worldPos = mul(u_modelViewMat, vec4(a_position, 1.0));
    gl_Position = mul(u_projMat, worldPos);

    v_color0 = a_color0;
    v_texcoord0 = a_texcoord0;
    v_normal = a_normal;

    // Calculate view-space normal for back-face culling
    mat3 normalMatrix = mat3(transpose(inverse(u_modelViewMat)));
    v_view_normal = normalize(mul(normalMatrix, a_normal));
}