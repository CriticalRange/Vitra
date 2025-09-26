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

// Use uniforms instead of global arrays to avoid HLSL limitations
uniform vec4 u_layerColors[6];
uniform vec4 u_layerDepths[2]; // Pack 6 depths into 2 vec4s (x,y,z,w each)

vec3 blend( vec3 dst, vec4 src )
{
    return ( dst * ( 1.0 - src.a ) ) + src.rgb;
}

void main()
{
    // Sample all layers first
    vec4 mainColor = texture2D( s_MainSampler, v_texcoord0 );
    float mainDepth = texture2D( s_MainDepthSampler, v_texcoord0 ).r;

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

    // Use weighted blended order-independent transparency approach
    // This avoids the need for dynamic arrays entirely
    vec3 result = mainColor.rgb;
    float totalWeight = 1.0;

    // Apply each layer with depth-based weighting
    if ( translucent.a > 0.0 ) {
        float weight = translucent.a * (1.0 - abs(translucentDepth - mainDepth));
        result = lerp( result, translucent.rgb, weight );
    }

    if ( itemEntity.a > 0.0 ) {
        float weight = itemEntity.a * (1.0 - abs(itemEntityDepth - mainDepth));
        result = lerp( result, itemEntity.rgb, weight );
    }

    if ( particles.a > 0.0 ) {
        float weight = particles.a * (1.0 - abs(particlesDepth - mainDepth));
        result = lerp( result, particles.rgb, weight );
    }

    if ( weather.a > 0.0 ) {
        float weight = weather.a * (1.0 - abs(weatherDepth - mainDepth));
        result = lerp( result, weather.rgb, weight );
    }

    if ( clouds.a > 0.0 ) {
        float weight = clouds.a * (1.0 - abs(cloudsDepth - mainDepth));
        result = lerp( result, clouds.rgb, weight );
    }

    gl_FragColor = vec4( result, 1.0 );
}