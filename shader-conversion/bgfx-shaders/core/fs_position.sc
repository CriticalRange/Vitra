$input v_sphericalDistance, v_cylindricalDistance

#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"

void main() {
    gl_FragColor = apply_fog(u_colorModulator, v_sphericalDistance, v_cylindricalDistance, u_fogEnvironmentalStart, u_fogEnvironmentalEnd, u_fogRenderDistanceStart, u_fogRenderDistanceEnd, u_fogColor);
}
