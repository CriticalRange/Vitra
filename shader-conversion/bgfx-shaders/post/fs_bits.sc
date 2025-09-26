$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>


SAMPLER2D(s_InSampler, 0);

uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

uniform vec4 u_Resolution_MosaicSize;
#define u_Resolution u_Resolution_MosaicSize.x
#define u_MosaicSize u_Resolution_MosaicSize.y

void main()
{
    vec2 mosaicInSize = u_InSize / u_MosaicSize;
    vec2 fractPix = fract(v_texcoord0 * mosaicInSize) / mosaicInSize;

    vec4 baseTexel = texture2D(s_InSampler, v_texcoord0 - fractPix);

    vec3 fractTexel = baseTexel.rgb - fract(baseTexel.rgb * u_Resolution) / u_Resolution;
    float luma = dot(fractTexel, vec3(0.3, 0.59, 0.11));
    vec3 chroma = (fractTexel - luma) * 1.5; // Saturation constant
    baseTexel.rgb = luma + chroma;
    baseTexel.a = 1.0;

    gl_FragColor = baseTexel;
}
