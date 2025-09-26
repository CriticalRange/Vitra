$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_InSampler, 0);

uniform vec4 u_InverseAmount;
#define InverseAmount u_InverseAmount.x

void main()
{
    vec4 diffuseColor = texture2D(s_InSampler, v_texcoord0);
    vec4 invertColor = 1.0 - diffuseColor;
    vec4 outColor = lerp(diffuseColor, invertColor, InverseAmount);
    gl_FragColor = vec4(outColor.rgb, 1.0);
}