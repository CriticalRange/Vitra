$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>


SAMPLER2D(s_InSampler, 0);

uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

uniform vec4 u_RedMatrix;
uniform vec4 u_GreenMatrix;
uniform vec4 u_BlueMatrix;

void main()
{
    vec4 InTexel = texture2D(s_InSampler, v_texcoord0);

    // Color Matrix
    float RedValue = dot(InTexel.rgb, u_RedMatrix.rgb);
    float GreenValue = dot(InTexel.rgb, u_GreenMatrix.rgb);
    float BlueValue = dot(InTexel.rgb, u_BlueMatrix.rgb);
    vec3 OutColor = vec3(RedValue, GreenValue, BlueValue);

    // Saturation
    vec3 Gray = vec3(0.3, 0.59, 0.11);
    float Saturation = 1.8;
    float Luma = dot(OutColor, Gray);
    vec3 Chroma = OutColor - Luma;
    OutColor = (Chroma * Saturation) + Luma;

    gl_FragColor = vec4(OutColor, 1.0);
}
