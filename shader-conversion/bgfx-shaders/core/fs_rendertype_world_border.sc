$input v_color0, v_texcoord0, v_border_progress

#include <bgfx_shader.sh>

uniform vec4 u_borderColor;

void main()
{
    // Animated world border effect
    float animation = sin(v_border_progress * 6.28318) * 0.5 + 0.5;
    vec4 borderColorMod = vec4(u_borderColor.rgb, u_borderColor.a * animation);

    gl_FragColor = v_color0 * borderColorMod;
}