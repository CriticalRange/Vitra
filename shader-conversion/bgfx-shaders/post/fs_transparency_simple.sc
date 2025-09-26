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
    // Start with opaque base layer
    vec3 result = texture2D( s_MainSampler, v_texcoord0 ).rgb;

    // Simple back-to-front blending without depth sorting
    // This assumes layers are already properly ordered by render passes

    vec4 clouds = texture2D( s_CloudsSampler, v_texcoord0 );
    if ( clouds.a > 0.0 ) result = blend( result, clouds );

    vec4 weather = texture2D( s_WeatherSampler, v_texcoord0 );
    if ( weather.a > 0.0 ) result = blend( result, weather );

    vec4 particles = texture2D( s_ParticlesSampler, v_texcoord0 );
    if ( particles.a > 0.0 ) result = blend( result, particles );

    vec4 itemEntity = texture2D( s_ItemEntitySampler, v_texcoord0 );
    if ( itemEntity.a > 0.0 ) result = blend( result, itemEntity );

    vec4 translucent = texture2D( s_TranslucentSampler, v_texcoord0 );
    if ( translucent.a > 0.0 ) result = blend( result, translucent );

    gl_FragColor = vec4( result, 1.0 );
}