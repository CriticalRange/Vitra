$input a_position
$output v_sphericalDistance, v_cylindricalDistance
#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"
#include "projection.sh"

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_sphericalDistance = fog_spherical_distance(a_position);
    v_cylindricalDistance = fog_cylindrical_distance(a_position);
}