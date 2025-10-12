$input a_position
$output v_color0, v_fog_distance

#include <bgfx_shader.sh>

uniform mat4 u_modelViewMat;
uniform mat4 u_projMat;
uniform vec4 u_cloudColor;
uniform vec3 u_cloudOffset;
uniform vec3 u_cellSize;

// Cloud face generation constants
const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

void main()
{
    int quadVertex = gl_VertexID % 4;
    int index = (gl_VertexID / 4) * 3;

    // Generate cloud face vertices procedurally
    vec3 vertices[24] = vec3[](
        // Bottom face
        vec3(1, 0, 0), vec3(1, 0, 1), vec3(0, 0, 1), vec3(0, 0, 0),
        // Top face
        vec3(0, 1, 0), vec3(0, 1, 1), vec3(1, 1, 1), vec3(1, 1, 0),
        // North face
        vec3(0, 0, 0), vec3(0, 1, 0), vec3(1, 1, 0), vec3(1, 0, 0),
        // South face
        vec3(1, 0, 1), vec3(1, 1, 1), vec3(0, 1, 1), vec3(0, 0, 1),
        // West face
        vec3(0, 0, 1), vec3(0, 1, 1), vec3(0, 1, 0), vec3(0, 0, 0),
        // East face
        vec3(1, 0, 0), vec3(1, 1, 0), vec3(1, 1, 1), vec3(1, 0, 1)
    );

    vec4 faceColors[6] = vec4[](
        vec4(0.7, 0.7, 0.7, 0.8),  // Bottom
        vec4(1.0, 1.0, 1.0, 0.8),  // Top
        vec4(0.8, 0.8, 0.8, 0.8),  // North
        vec4(0.8, 0.8, 0.8, 0.8),  // South
        vec4(0.9, 0.9, 0.9, 0.8),  // West
        vec4(0.9, 0.9, 0.9, 0.8)   // East
    );

    // Simplified cloud vertex generation (would need cloud face data in real implementation)
    int direction = (gl_VertexID / 24) % 6;
    vec3 faceVertex = vertices[direction * 4 + quadVertex];
    vec3 pos = faceVertex + a_position;

    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(pos, 1.0)));

    v_fog_distance = length(pos);
    v_color0 = faceColors[direction] * u_cloudColor;
}