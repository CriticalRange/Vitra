$input v_texcoord0

/*
 * BGFX conversion of Minecraft's blit_screen.fsh
 * Simple screen-space blit fragment shader
 */

#include <bgfx_shader.sh>

SAMPLER2D(s_texColor, 0);

void main() {
    gl_FragColor = texture2D(s_texColor, v_texcoord0);
}