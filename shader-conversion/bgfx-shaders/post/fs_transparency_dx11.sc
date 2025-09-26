$input v_texcoord0

#include <bgfx_shader.sh>

SAMPLER2D(s_MainSampler, 0);
SAMPLER2D(s_MainDepthSampler, 1);
SAMPLER2D(s_TranslucentSampler, 2);
SAMPLER2D(s_TranslucentDepthSampler, 3);
SAMPLER2D(s_ItemEntitySampler, 4);
SAMPLER2D(s_ItemEntityDepthSampler, 5);
SAMPLER2D(s_ParticlesSampler, 6);
SAMPLER2D(s_ParticlesDepthSampler, 7);
SAMPLER2D(s_WeatherSampler, 8);
SAMPLER2D(s_WeatherDepthSampler, 9);
SAMPLER2D(s_CloudsSampler, 10);
SAMPLER2D(s_CloudsDepthSampler, 11);

vec3 blend( vec3 dst, vec4 src )
{
    return ( dst * ( 1.0 - src.a ) ) + src.rgb;
}

void main()
{
    // Get base layer
    vec3 result = texture2D( s_MainSampler, v_texcoord0 ).rgb;
    float baseDepth = texture2D( s_MainDepthSampler, v_texcoord0 ).r;

    // Sample all transparency layers
    vec4 translucent = texture2D( s_TranslucentSampler, v_texcoord0 );
    float translucentDepth = texture2D( s_TranslucentDepthSampler, v_texcoord0 ).r;

    vec4 itemEntity = texture2D( s_ItemEntitySampler, v_texcoord0 );
    float itemEntityDepth = texture2D( s_ItemEntityDepthSampler, v_texcoord0 ).r;

    vec4 particles = texture2D( s_ParticlesSampler, v_texcoord0 );
    float particlesDepth = texture2D( s_ParticlesDepthSampler, v_texcoord0 ).r;

    vec4 weather = texture2D( s_WeatherSampler, v_texcoord0 );
    float weatherDepth = texture2D( s_WeatherDepthSampler, v_texcoord0 ).r;

    vec4 clouds = texture2D( s_CloudsSampler, v_texcoord0 );
    float cloudsDepth = texture2D( s_CloudsDepthSampler, v_texcoord0 ).r;

    // Manual depth sorting with unrolled comparisons
    // Sort 5 layers: translucent, itemEntity, particles, weather, clouds

    // Compare and swap based on depth (closer = larger depth value)
    vec4 layer1 = translucent, layer2 = itemEntity, layer3 = particles, layer4 = weather, layer5 = clouds;
    float depth1 = translucentDepth, depth2 = itemEntityDepth, depth3 = particlesDepth, depth4 = weatherDepth, depth5 = cloudsDepth;

    // Bubble sort network for 5 elements (unrolled)
    if (depth1 < depth2) { vec4 tmpC = layer1; layer1 = layer2; layer2 = tmpC; float tmpD = depth1; depth1 = depth2; depth2 = tmpD; }
    if (depth3 < depth4) { vec4 tmpC = layer3; layer3 = layer4; layer4 = tmpC; float tmpD = depth3; depth3 = depth4; depth4 = tmpD; }
    if (depth1 < depth3) { vec4 tmpC = layer1; layer1 = layer3; layer3 = tmpC; float tmpD = depth1; depth1 = depth3; depth3 = tmpD; }
    if (depth2 < depth4) { vec4 tmpC = layer2; layer2 = layer4; layer4 = tmpC; float tmpD = depth2; depth2 = depth4; depth4 = tmpD; }
    if (depth4 < depth5) { vec4 tmpC = layer4; layer4 = layer5; layer5 = tmpC; float tmpD = depth4; depth4 = depth5; depth5 = tmpD; }
    if (depth2 < depth3) { vec4 tmpC = layer2; layer2 = layer3; layer3 = tmpC; float tmpD = depth2; depth2 = depth3; depth3 = tmpD; }
    if (depth1 < depth2) { vec4 tmpC = layer1; layer1 = layer2; layer2 = tmpC; float tmpD = depth1; depth1 = depth2; depth2 = tmpD; }
    if (depth3 < depth4) { vec4 tmpC = layer3; layer3 = layer4; layer4 = tmpC; float tmpD = depth3; depth3 = depth4; depth4 = tmpD; }
    if (depth2 < depth3) { vec4 tmpC = layer2; layer2 = layer3; layer3 = tmpC; float tmpD = depth2; depth2 = depth3; depth3 = tmpD; }

    // Apply layers in depth order (farthest to nearest)
    if (layer5.a > 0.0) result = blend( result, layer5 );
    if (layer4.a > 0.0) result = blend( result, layer4 );
    if (layer3.a > 0.0) result = blend( result, layer3 );
    if (layer2.a > 0.0) result = blend( result, layer2 );
    if (layer1.a > 0.0) result = blend( result, layer1 );

    gl_FragColor = vec4( result, 1.0 );
}