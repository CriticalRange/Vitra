$input a_position
$output v_texcoord0
#include <bgfx_shader.sh>

/*
 * BGFX conversion of Minecraft's blit_screen.vsh
 * Simple screen-space blit vertex shader
 */


void main() {
    vec2 screenPos = a_position.xy * 2.0 - 1.0;
    gl_Position = vec4(screenPos.x, screenPos.y, 1.0, 1.0);
    v_texcoord0 = a_position.xy;
}