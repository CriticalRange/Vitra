$input v_sphericalDistance, v_cylindricalDistance, v_color

#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"

void main() {
    vec4 color = v_color * u_colorModulator;
    gl_FragColor = apply_fog(color, v_sphericalDistance, v_cylindricalDistance, u_fogEnvironmentalStart, u_fogEnvironmentalEnd, u_fogRenderDistanceStart, u_fogRenderDistanceEnd, u_fogColor);
}
