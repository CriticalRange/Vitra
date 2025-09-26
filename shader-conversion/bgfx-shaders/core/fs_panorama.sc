$input v_texcoord0

#include <bgfx_shader.sh>


SAMPLERCUBE(s_sampler0, 0);

void main() {
    gl_FragColor = textureCube(s_sampler0, v_texcoord0);
}
