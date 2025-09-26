$input a_position, a_color0, a_texcoord0, a_texcoord2
$output v_sphericalDistance, v_cylindricalDistance, v_texcoord0, v_color
#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"
#include "projection.sh"

SAMPLER2D(s_sampler2, 2);

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_sphericalDistance = fog_spherical_distance(a_position);
    v_cylindricalDistance = fog_cylindrical_distance(a_position);
    v_texcoord0 = a_texcoord0;
    v_color = a_color0 * texture2DLod(s_sampler2, vec2(a_texcoord2) / 16.0, 0.0);
}