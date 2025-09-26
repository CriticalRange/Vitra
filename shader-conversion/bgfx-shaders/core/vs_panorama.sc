$input a_position
$output v_texcoord0
#include <bgfx_shader.sh>

#include "dynamictransforms.sh"
#include "projection.sh"

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_texcoord0 = a_position;
}