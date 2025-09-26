$input v_color, v_texcoord0, v_lightMap

#include <bgfx_shader.sh>

#include "dynamictransforms.sh"

SAMPLER2D(s_sampler0, 0);

void main() {
    vec4 color = texture2D(s_sampler0, v_texcoord0) * v_color;
    if (color.a < 0.1) {
        discard;
    }
    gl_FragColor = color * u_colorModulator;
}
