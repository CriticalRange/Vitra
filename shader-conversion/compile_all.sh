#!/bin/bash

SHADERC="./shaderc.exe"
BGFX_INCLUDE="C:/Users/Ahmet/Documents/bgfx/src"
LOCAL_INCLUDE="bgfx-shaders/include"
OUTPUT_DIR="compiled-binaries/dx11"

echo "Compiling all BGFX shaders to DirectX 11..."

success=0
total=0

compile_shader() {
    local shader="$1"
    local type="$2"
    local varying="$3"

    name=$(basename "$shader" .sc)
    echo "Compiling $name..."
    if $SHADERC -f "$shader" -o "$OUTPUT_DIR/${name}.bin" --type $type --platform windows -p s_5_0 -i "$BGFX_INCLUDE" -i "$LOCAL_INCLUDE" --varyingdef "$varying" >/dev/null 2>&1; then
        success=$((success + 1))
    else
        echo "FAILED: $name"
    fi
    total=$((total + 1))
}

# Core shaders with specific varying definitions
compile_shader "bgfx-shaders/core/vs_entity.sc" "v" "bgfx-shaders/core/entity_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_entity.sc" "f" "bgfx-shaders/core/entity_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_glint.sc" "v" "bgfx-shaders/core/position_tex_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_glint.sc" "f" "bgfx-shaders/core/position_tex_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_gui.sc" "v" "bgfx-shaders/core/position_color_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_gui.sc" "f" "bgfx-shaders/core/position_color_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_panorama.sc" "v" "bgfx-shaders/core/panorama_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_panorama.sc" "f" "bgfx-shaders/core/panorama_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_particle.sc" "v" "bgfx-shaders/core/particle_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_particle.sc" "f" "bgfx-shaders/core/particle_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position.sc" "v" "bgfx-shaders/core/position_fog_distance_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position.sc" "f" "bgfx-shaders/core/position_fog_distance_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position_color.sc" "v" "bgfx-shaders/core/position_color_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position_color.sc" "f" "bgfx-shaders/core/position_color_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position_color_lightmap.sc" "v" "bgfx-shaders/core/position_color_lightmap_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position_color_lightmap.sc" "f" "bgfx-shaders/core/position_color_lightmap_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position_color_tex_lightmap.sc" "v" "bgfx-shaders/core/position_color_tex_lightmap_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position_color_tex_lightmap.sc" "f" "bgfx-shaders/core/position_color_tex_lightmap_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position_tex.sc" "v" "bgfx-shaders/core/position_tex_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position_tex.sc" "f" "bgfx-shaders/core/position_tex_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_position_tex_color.sc" "v" "bgfx-shaders/core/position_color_tex_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_position_tex_color.sc" "f" "bgfx-shaders/core/position_color_tex_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_rendertype_lines.sc" "v" "bgfx-shaders/core/lines_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_rendertype_lines.sc" "f" "bgfx-shaders/core/lines_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_sky.sc" "v" "bgfx-shaders/core/position_fog_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_sky.sc" "f" "bgfx-shaders/core/position_fog_varying.def.sc"

compile_shader "bgfx-shaders/core/vs_terrain.sc" "v" "bgfx-shaders/core/terrain_varying.def.sc"
compile_shader "bgfx-shaders/core/fs_terrain.sc" "f" "bgfx-shaders/core/terrain_varying.def.sc"

# Simple shaders with basic varying
compile_shader "bgfx-shaders/core/vs_blit_screen.sc" "v" "bgfx-shaders/core/varying.def.sc"
compile_shader "bgfx-shaders/core/fs_blit_screen.sc" "f" "bgfx-shaders/core/varying.def.sc"

compile_shader "bgfx-shaders/core/vs_stars.sc" "v" "bgfx-shaders/core/varying.def.sc"
compile_shader "bgfx-shaders/core/fs_stars.sc" "f" "bgfx-shaders/core/varying.def.sc"

compile_shader "bgfx-shaders/core/fs_lightmap.sc" "f" "bgfx-shaders/core/lightmap_varying.def.sc"

# Post-processing shaders
for shader in bgfx-shaders/post/*.sc; do
    if [[ -f "$shader" && ! "$shader" =~ \.def\.sc$ ]]; then
        compile_shader "$shader" "f" "bgfx-shaders/post/post_varying.def.sc"
    fi
done

# Post vertex shaders with specific varying definitions
compile_shader "bgfx-shaders/post/vs_blit.sc" "v" "bgfx-shaders/post/blit_varying.def.sc"
compile_shader "bgfx-shaders/post/vs_blur.sc" "v" "bgfx-shaders/post/dual_texcoord_varying.def.sc"
compile_shader "bgfx-shaders/post/vs_invert.sc" "v" "bgfx-shaders/post/simple_texcoord_varying.def.sc"
compile_shader "bgfx-shaders/post/vs_rotscale.sc" "v" "bgfx-shaders/post/dual_texcoord_varying.def.sc"
compile_shader "bgfx-shaders/post/vs_screenquad.sc" "v" "bgfx-shaders/post/simple_texcoord_varying.def.sc"
compile_shader "bgfx-shaders/post/vs_sobel.sc" "v" "bgfx-shaders/post/dual_texcoord_varying.def.sc"

echo ""
echo "Compilation complete: $success/$total shaders compiled successfully"