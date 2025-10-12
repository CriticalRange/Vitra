$input v_color0, v_normal

#include <bgfx_shader.sh>

uniform vec4 u_outlineColor;
uniform float u_outlineWidth;

void main()
{
    // Simple outline rendering - solid color
    gl_FragColor = vec4(u_outlineColor.rgb, 1.0);
}