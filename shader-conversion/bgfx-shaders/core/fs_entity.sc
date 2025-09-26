$input v_sphericalDistance, v_cylindricalDistance, v_color, v_lightMap, v_overlay, v_texcoord0

/*
 * BGFX conversion of Minecraft's entity.fsh
 * Entity fragment shader with fog, lighting, and overlay support
 */

#include <bgfx_shader.sh>
#include "fog.sh"
#include "dynamictransforms.sh"

SAMPLER2D(s_texColor, 0);

void main() {
    vec4 color = texture2D(s_texColor, v_texcoord0);

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    color = color * v_color * u_colorModulator;

#ifndef NO_OVERLAY
    color.rgb = lerp(v_overlay.rgb, color.rgb, v_overlay.a);
#endif

#ifndef EMISSIVE
    color = color * v_lightMap;
#endif

    gl_FragColor = apply_fog(color, v_sphericalDistance, v_cylindricalDistance, u_fogEnvironmentalStart, u_fogEnvironmentalEnd, u_fogRenderDistanceStart, u_fogRenderDistanceEnd, u_fogColor);
}