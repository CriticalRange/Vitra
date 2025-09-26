$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>


SAMPLER2D(s_InSampler, 0);

void main()
{
    vec4 blurred = vec4_splat(0.0);
    float radius = 2.0;
    for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += texture2D(s_InSampler, v_texcoord0 + v_texcoord1 * a); // v_texcoord1 is sampleStep
    }
    blurred += texture2D(s_InSampler, v_texcoord0 + v_texcoord1 * radius) / 2.0;
    gl_FragColor = vec4((blurred / (radius + 0.5)).rgb, blurred.a);
}
