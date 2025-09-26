$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>

SAMPLER2D(s_InSampler, 0);
SAMPLER2D(s_BlurSampler, 1);

uniform vec4 u_OutSize_InSize;
#define u_OutSize u_OutSize_InSize.xy
#define u_InSize u_OutSize_InSize.zw

uniform vec4 u_Scissor;
uniform vec4 u_Vignette;

void main()
{
    vec4 ScaledTexel = texture2D(s_InSampler, v_texcoord1); // v_texcoord1 is scaledCoord
    vec4 BlurTexel = texture2D(s_BlurSampler, v_texcoord0);
    vec4 OutTexel = ScaledTexel;

    // -- Alpha Clipping --
    if (v_texcoord1.x < u_Scissor.x) OutTexel = BlurTexel;
    if (v_texcoord1.y < u_Scissor.y) OutTexel = BlurTexel;
    if (v_texcoord1.x > u_Scissor.z) OutTexel = BlurTexel;
    if (v_texcoord1.y > u_Scissor.w) OutTexel = BlurTexel;

    vec2 scaledCoordClamped = clamp(v_texcoord1, 0.0, 1.0);

    if (scaledCoordClamped.x < u_Vignette.x) OutTexel = lerp(BlurTexel, OutTexel, (u_Scissor.x - scaledCoordClamped.x) / (u_Scissor.x - u_Vignette.x));
    if (scaledCoordClamped.y < u_Vignette.y) OutTexel = lerp(BlurTexel, OutTexel, (u_Scissor.y - scaledCoordClamped.y) / (u_Scissor.y - u_Vignette.y));
    if (scaledCoordClamped.x > u_Vignette.z) OutTexel = lerp(BlurTexel, OutTexel, (u_Scissor.z - scaledCoordClamped.x) / (u_Scissor.z - u_Vignette.z));
    if (scaledCoordClamped.y > u_Vignette.w) OutTexel = lerp(BlurTexel, OutTexel, (u_Scissor.w - scaledCoordClamped.y) / (u_Scissor.w - u_Vignette.w));
    gl_FragColor = vec4(OutTexel.rgb, 1.0);
}