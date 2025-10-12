$input v_color0, v_texcoord0, v_leash_distance

#include <bgfx_shader.sh>

uniform vec4 u_leashColor;

void main()
{
    // Leash gradient effect based on distance
    float gradient = 1.0 - clamp(v_leash_distance * 0.1, 0.0, 0.8);
    vec4 leashColorMod = vec4(u_leashColor.rgb, u_leashColor.a * gradient);

    gl_FragColor = v_color0 * leashColorMod;
}