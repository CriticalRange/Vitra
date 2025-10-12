$input v_color0, v_fog_distance

#include <bgfx_shader.sh>

uniform vec4 u_fogColor;
uniform float u_fogStart;
uniform float u_fogEnd;

void main()
{
    // Apply fog to cloud color
    float fogFactor = clamp((u_fogEnd - v_fog_distance) / (u_fogEnd - u_fogStart), 0.0, 1.0);
    gl_FragColor = mix(u_fogColor, v_color0, fogFactor);
}