$input v_color0, v_texcoord0, v_random

#include <bgfx_shader.sh>

uniform float u_time;
uniform vec4 u_lightningColor;

void main()
{
    // Animated lightning effect
    float flash = sin(u_time * 20.0 + v_random * 10.0) * 0.5 + 0.5;
    flash = pow(flash, 8.0); // Sharper flashes

    vec4 lightningColor = vec4(u_lightningColor.rgb, u_lightningColor.a * flash);
    gl_FragColor = lightningColor * v_color0;
}