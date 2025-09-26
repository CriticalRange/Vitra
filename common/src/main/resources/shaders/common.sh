#ifndef COMMON_SH_HEADER_GUARD
#define COMMON_SH_HEADER_GUARD

#include "bgfx_shader.sh"

// Common uniforms used by most shaders
uniform mat4 u_modelViewProj;
uniform vec4 u_time;
uniform vec4 u_lightDir;

#endif // COMMON_SH_HEADER_GUARD