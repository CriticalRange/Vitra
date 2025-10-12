$input v_color0, v_texcoord0, v_normal, v_view_normal

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);

void main()
{
    // Back-face culling for translucent items
    if (dot(v_view_normal, vec3(0.0, 0.0, 1.0)) > 0.0) {
        discard; // Cull front faces
    }

    vec4 diffuseColor = texture2D(s_diffuse, v_texcoord0);
    gl_FragColor = diffuseColor * v_color0;
}