$input v_sphericalDistance, v_cylindricalDistance

#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"

void main() {
    gl_FragColor = apply_fog(u_colorModulator, v_sphericalDistance, v_cylindricalDistance, 0.0, u_fogSkyEnd, u_fogSkyEnd, u_fogSkyEnd, u_fogColor);
}
