$input v_color0, v_texcoord0, v_texcoord1, v_crumbling_factor

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);
SAMPLER2D(s_crumbling, 1);

void main()
{
    if (v_crumbling_factor < 0.5) {
        discard; // Remove crumbling parts
    }

    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);
    vec4 crumblingTexture = texture2D(s_crumbling, v_texcoord1);

    gl_FragColor = diffuseColor * v_color0 * crumblingTexture;
}