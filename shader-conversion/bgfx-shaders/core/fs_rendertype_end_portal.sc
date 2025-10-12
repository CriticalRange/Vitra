$input v_color0, v_texcoord0, v_portal_depth

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

uniform float u_time;
uniform vec4 u_portalColor;

void main()
{
    // End portal sky effect
    vec2 uv = v_texcoord0;

    // Animated swirling effect
    float angle = u_time * 0.5 + v_portal_depth * 0.1;
    mat2 rotation = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    uv = mul(rotation, uv - 0.5) + 0.5;

    vec4 diffuseColor = texture2D(s_diffuse, uv);

    // Apply portal color with depth-based modulation
    float depthMod = 1.0 - clamp(v_portal_depth * 0.1, 0.0, 0.8);
    vec4 portalColorMod = vec4(u_portalColor.rgb, u_portalColor.a * depthMod);

    gl_FragColor = diffuseColor * v_color0 * portalColorMod;
}