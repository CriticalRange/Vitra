$input a_position, a_color0, a_normal
$output v_sphericalDistance, v_cylindricalDistance, v_color
#include <bgfx_shader.sh>

#include "fog.sh"
#include "globals.sh"
#include "dynamictransforms.sh"
#include "projection.sh"

void main() {
    const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
    const mat4 VIEW_SCALE = mat4(
        VIEW_SHRINK, 0.0, 0.0, 0.0,
        0.0, VIEW_SHRINK, 0.0, 0.0,
        0.0, 0.0, VIEW_SHRINK, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    vec4 linePosStart = mul(u_projMat, mul(VIEW_SCALE, mul(u_modelViewMat, vec4(a_position, 1.0))));
    vec4 linePosEnd = mul(u_projMat, mul(VIEW_SCALE, mul(u_modelViewMat, vec4(a_position + a_normal, 1.0))));

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * u_screenSize);
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * u_lineWidth / u_screenSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    if (gl_VertexID % 2 == 0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    v_sphericalDistance = fog_spherical_distance(a_position);
    v_cylindricalDistance = fog_cylindrical_distance(a_position);
    v_color = a_color0;
}