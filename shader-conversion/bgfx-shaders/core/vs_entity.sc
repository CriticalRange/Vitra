$input a_position, a_color0, a_texcoord0, a_texcoord1, a_texcoord2, a_normal
$output v_sphericalDistance, v_cylindricalDistance, v_color, v_lightMap, v_overlay, v_texcoord0
#include <bgfx_shader.sh>

/*
 * BGFX conversion of Minecraft's entity.vsh
 * Entity rendering with lighting, fog, and texture mapping
 */

#include "light.sh"
#include "fog.sh"
#include "dynamictransforms.sh"
#include "projection.sh"

SAMPLER2D(s_lightMap, 1);
SAMPLER2D(s_overlay, 2);

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    v_sphericalDistance = fog_spherical_distance(a_position);
    v_cylindricalDistance = fog_cylindrical_distance(a_position);

#ifdef NO_CARDINAL_LIGHTING
    v_color = a_color0;
#else
    v_color = minecraft_mix_light(u_light0Direction, u_light1Direction, a_normal, a_color0);
#endif

#ifndef EMISSIVE
    v_lightMap = texture2DLod(s_lightMap, vec2(a_texcoord2) / 16.0, 0.0);
#endif
    v_overlay = texture2DLod(s_overlay, vec2(a_texcoord1), 0.0);

    v_texcoord0 = a_texcoord0;
#ifdef APPLY_TEXTURE_MATRIX
    v_texcoord0 = mul(u_textureMat, vec4(a_texcoord0, 0.0, 1.0)).xy;
#endif
}