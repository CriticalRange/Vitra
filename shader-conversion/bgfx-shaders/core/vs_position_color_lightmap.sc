$input a_position, a_color0, a_texcoord2
$output v_color, v_texcoord2
#include <bgfx_shader.sh>

#include "dynamictransforms.sh"
#include "projection.sh"

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_color = a_color0;
    v_texcoord2 = a_texcoord2;
}