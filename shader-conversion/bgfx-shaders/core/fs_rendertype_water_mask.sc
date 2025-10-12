$input v_color0

#include <bgfx_shader.sh>

void main()
{
    // Water mask - typically renders only to stencil/depth buffer
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
}