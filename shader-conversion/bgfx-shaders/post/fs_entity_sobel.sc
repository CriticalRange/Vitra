$input v_texcoord0, v_texcoord1

#include <bgfx_shader.sh>


SAMPLER2D(s_InSampler, 0);

void main()
{
    vec4 center = texture2D(s_InSampler, v_texcoord0);
    vec4 left = texture2D(s_InSampler, v_texcoord0 - vec2(v_texcoord1.x, 0.0));
    vec4 right = texture2D(s_InSampler, v_texcoord0 + vec2(v_texcoord1.x, 0.0));
    vec4 up = texture2D(s_InSampler, v_texcoord0 - vec2(0.0, v_texcoord1.y));
    vec4 down = texture2D(s_InSampler, v_texcoord0 + vec2(0.0, v_texcoord1.y));
    float leftDiff  = abs(center.a - left.a);
    float rightDiff = abs(center.a - right.a);
    float upDiff    = abs(center.a - up.a);
    float downDiff  = abs(center.a - down.a);
    float total = clamp(leftDiff + rightDiff + upDiff + downDiff, 0.0, 1.0);
    vec3 outColor = center.rgb * center.a + left.rgb * left.a + right.rgb * right.a + up.rgb * up.a + down.rgb * down.a;
    gl_FragColor = vec4(outColor * 0.2, total);
}
