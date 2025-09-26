$input v_color

#include <bgfx_shader.sh>

#include "dynamictransforms.sh"

void main() {
    vec4 color = v_color;
    if (color.a == 0.0) {
        discard;
    }
    gl_FragColor = color * u_colorModulator;
}
