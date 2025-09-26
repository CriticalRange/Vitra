$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>


SAMPLER2D(s_InSampler, 0);

uniform vec4 u_BlurDir_Radius;
#define u_BlurDir u_BlurDir_Radius.xy
#define u_Radius u_BlurDir_Radius.z

uniform vec4 u_MenuBlurRadius;
#define MenuBlurRadius u_MenuBlurRadius.x

// This shader relies on GL_LINEAR sampling to reduce the amount of texture samples in half.
// Instead of sampling each pixel position with a step of 1 we sample between pixels with a step of 2.
// In the end we sample the last pixel with a half weight, since the amount of pixels to sample is always odd (actualRadius * 2 + 1).
void main()
{
    vec4 blurred = vec4_splat(0.0);
    float actualRadius = u_Radius >= 0.5 ? round(u_Radius) : MenuBlurRadius;
    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {
        blurred += texture2D(s_InSampler, v_texcoord0 + v_texcoord1 * a); // v_texcoord1 is sampleStep
    }
    blurred += texture2D(s_InSampler, v_texcoord0 + v_texcoord1 * actualRadius) / 2.0;
    gl_FragColor = blurred / (actualRadius + 0.5);
}
