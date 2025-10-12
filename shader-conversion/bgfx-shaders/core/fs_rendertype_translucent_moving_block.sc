$input v_color0, v_texcoord0, v_normal, v_translucency

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

void main()
{
    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);
    vec4 finalColor = diffuseColor * v_color0;

    // Apply translucency
    gl_FragColor = vec4(finalColor.rgb, finalColor.a * v_translucency);
}