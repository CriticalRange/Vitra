$input v_sphericalDistance, v_cylindricalDistance, v_color, v_texcoord0

#include <bgfx_shader.sh>
#include "fog.sh"
#include "dynamictransforms.sh"

SAMPLER2D(s_sampler0, 0);

void main() {
    vec4 color = texture2D(s_sampler0, v_texcoord0) * v_color * u_colorModulator;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    gl_FragColor = apply_fog(color, v_sphericalDistance, v_cylindricalDistance, u_fogEnvironmentalStart, u_fogEnvironmentalEnd, u_fogRenderDistanceStart, u_fogRenderDistanceEnd, u_fogColor);
}