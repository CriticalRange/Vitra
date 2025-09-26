$input a_position, a_color0, a_texcoord0, a_texcoord2, a_normal
$output v_sphericalDistance, v_cylindricalDistance, v_color, v_texcoord0
#include <bgfx_shader.sh>

#include "fog.sh"
#include "dynamictransforms.sh"
#include "projection.sh"

SAMPLER2D(s_sampler2, 2);

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture2DLod(lightMap, clamp(vec2(uv) / 256.0, vec2_splat(0.5 / 16.0), vec2_splat(15.5 / 16.0)), 0.0);
}

void main() {
    vec3 pos = a_position + u_modelOffset;
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(pos, 1.0)));

    v_sphericalDistance = fog_spherical_distance(pos);
    v_cylindricalDistance = fog_cylindrical_distance(pos);
    v_color = a_color0 * minecraft_sample_lightmap(s_sampler2, a_texcoord2);
    v_texcoord0 = a_texcoord0;
}