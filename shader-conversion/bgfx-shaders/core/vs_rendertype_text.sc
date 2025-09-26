$input a_position, a_color0, a_texcoord0, a_texcoord1
$output v_color0, v_texcoord0, v_distance

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;
SAMPLER2D(s_lightmap, 2);

void main()
{
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));

    // Calculate fog distance (simple distance from camera)
    vec3 worldPos = mul(u_modelViewMat, vec4(a_position, 1.0)).xyz;
    v_distance.x = length(worldPos);
    v_distance.y = length(worldPos.xz);

    // Sample lightmap for vertex color modulation
    vec2 lightmapCoord = a_texcoord1 / 16.0;
    vec4 lightmapColor = texture2DLod(s_lightmap, lightmapCoord, 0.0);

    v_color0 = a_color0 * lightmapColor;
    v_texcoord0 = a_texcoord0;
}