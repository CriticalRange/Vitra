$input v_color, v_texcoord2

#include <bgfx_shader.sh>

#include "dynamictransforms.sh"

SAMPLER2D(s_sampler2, 2);

void main() {
    vec4 color = texture2D(s_sampler2, v_texcoord2) * v_color;
    gl_FragColor = color * u_colorModulator;
}
