$input v_color0, v_texcoord0, v_shadow_strength

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

void main()
{
    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);

    // Apply shadow darkness
    vec4 shadowColor = vec4(0.0, 0.0, 0.0, 1.0);
    vec4 finalColor = mix(diffuseColor * v_color0, shadowColor, v_shadow_strength * 0.7);

    gl_FragColor = vec4(finalColor.rgb, diffuseColor.a * v_color0.a);
}