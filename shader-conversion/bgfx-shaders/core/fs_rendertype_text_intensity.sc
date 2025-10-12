$input v_color0, v_texcoord0, v_intensity

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

void main()
{
    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);
    vec4 finalColor = diffuseColor * v_color0;
    gl_FragColor = vec4(finalColor.rgb * v_intensity, finalColor.a);
}