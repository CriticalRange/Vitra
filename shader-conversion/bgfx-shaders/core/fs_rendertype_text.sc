$input v_color0, v_texcoord0, v_distance

#include <bgfx_shader.sh>

SAMPLER2D(s_texColor, 0);

uniform vec4 u_colorModulator;
uniform vec4 u_fogColor;
uniform vec4 u_fogParams;

void main()
{
    vec4 color = texture2D(s_texColor, v_texcoord0) * v_color0 * u_colorModulator;

    if (color.a < 0.1) {
        discard;
    }

    // Simple fog calculation
    float fogDistance = v_distance.x;
    float fogFactor = clamp((u_fogParams.y - fogDistance) / (u_fogParams.y - u_fogParams.x), 0.0, 1.0);
    color.rgb = mix(u_fogColor.rgb, color.rgb, fogFactor);

    gl_FragColor = color;
}