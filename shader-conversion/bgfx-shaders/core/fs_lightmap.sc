#include <bgfx_shader.sh>
#include "dynamictransforms.sh"

SAMPLER2D(s_sampler2, 2);

void main() {
    gl_FragColor = texture2D(s_sampler2, vec2_splat(0.5));
}
