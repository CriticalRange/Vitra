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

#define NUM_LAYERS 6

vec4 color_layers[NUM_LAYERS];
float depth_layers[NUM_LAYERS];
int active_layers = 0;

void try_insert( vec4 color, float depth )
{
    if ( color.a == 0.0 ) {
        return;
    }

    color_layers[active_layers] = color;
    depth_layers[active_layers] = depth;

    int jj = active_layers++;
    int ii = jj - 1;
    while ( jj > 0 && depth_layers[jj] > depth_layers[ii] ) {
        float depthTemp = depth_layers[ii];
        depth_layers[ii] = depth_layers[jj];
        depth_layers[jj] = depthTemp;

        vec4 colorTemp = color_layers[ii];
        color_layers[ii] = color_layers[jj];
        color_layers[jj] = colorTemp;

        jj = ii--;
    }
}

vec3 blend( vec3 dst, vec4 src )
{
    return ( dst * ( 1.0 - src.a ) ) + src.rgb;
}

void main()
{
    color_layers[0] = vec4( texture2D( s_MainSampler, v_texcoord0 ).rgb, 1.0 );
    depth_layers[0] = texture2D( s_MainDepthSampler, v_texcoord0 ).r;
    active_layers = 1;

    try_insert( texture2D( s_TranslucentSampler, v_texcoord0 ), texture2D( s_TranslucentDepthSampler, v_texcoord0 ).r );
    try_insert( texture2D( s_ItemEntitySampler, v_texcoord0 ), texture2D( s_ItemEntityDepthSampler, v_texcoord0 ).r );
    try_insert( texture2D( s_ParticlesSampler, v_texcoord0 ), texture2D( s_ParticlesDepthSampler, v_texcoord0 ).r );
    try_insert( texture2D( s_WeatherSampler, v_texcoord0 ), texture2D( s_WeatherDepthSampler, v_texcoord0 ).r );
    try_insert( texture2D( s_CloudsSampler, v_texcoord0 ), texture2D( s_CloudsDepthSampler, v_texcoord0 ).r );

    vec3 texelAccum = color_layers[0].rgb;
    for ( int ii = 1; ii < active_layers; ++ii ) {
        texelAccum = blend( texelAccum, color_layers[ii] );
    }

    gl_FragColor = vec4( texelAccum.rgb, 1.0 );
}
