$input v_color0, v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

void main()
{
    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);
    gl_FragColor = diffuseColor * v_color0;
}