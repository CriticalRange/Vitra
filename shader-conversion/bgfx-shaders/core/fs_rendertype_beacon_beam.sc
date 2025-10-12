$input v_color0, v_texcoord0, v_height

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

uniform float u_time;
uniform vec4 u_beamColor;

void main()
{
    vec2 uv = v_texcoord0;

    // Animated beam effect
    float animation = fract(u_time * 0.5 + v_height * 0.1);
    uv.y += animation;

    vec4 diffuseColor = texture2D(s_diffuse, uv);

    // Apply beam color with intensity based on height
    float intensity = 1.0 - abs(v_height - 0.5) * 2.0;
    vec4 beamColorMod = vec4(u_beamColor.rgb, u_beamColor.a * intensity);

    gl_FragColor = diffuseColor * v_color0 * beamColorMod;
}