package com.vitra.render.bridge;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.vitra.render.d3d11.D3D11Pipeline;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.VitraD3D11Renderer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 11 implementation of CompiledRenderPipeline.
 * Wraps a compiled D3D11 shader pipeline (vertex + pixel shader + input layout).
 * 
 * Uses the existing D3D11ShaderManager infrastructure for shader compilation.
 */
public class VitraCompiledPipeline implements CompiledRenderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/CompiledPipeline");
    
    private static final D3D11ShaderManager shaderManager = new D3D11ShaderManager();
    
    private final long id;
    private final RenderPipeline sourcePipeline;
    private final String pipelineName;
    
    // Native D3D11 pipeline handle
    private long nativeHandle = 0;
    private boolean closed = false;
    
    // Use existing D3D11Pipeline if available
    private D3D11Pipeline d3d11Pipeline = null;
    
    public VitraCompiledPipeline(long id, RenderPipeline sourcePipeline) {
        this.id = id;
        this.sourcePipeline = sourcePipeline;
        this.pipelineName = extractPipelineName(sourcePipeline);
        
        // Try to compile via existing infrastructure
        compileFromExistingInfrastructure();
    }
    
    public VitraCompiledPipeline(long id, RenderPipeline sourcePipeline, ShaderSource shaderSource) {
        this.id = id;
        this.sourcePipeline = sourcePipeline;
        this.pipelineName = extractPipelineName(sourcePipeline);
        
        // Compile with shader source
        compileWithShaderSource(shaderSource);
    }
    
    private String extractPipelineName(RenderPipeline pipeline) {
        // Extract shader name from the pipeline location
        // e.g., "minecraft:pipeline/solid_block" -> "solid_block"
        Identifier location = pipeline.getLocation();
        String path = location.getPath();
        if (path.startsWith("pipeline/")) {
            return path.substring("pipeline/".length());
        }
        return path;
    }
    
    private void compileFromExistingInfrastructure() {
        try {
            // Map 26.1 pipeline names to our existing shader names
            String shaderName = mapPipelineToShader(pipelineName);
            
            // Use existing D3D11ShaderManager to create pipeline
            nativeHandle = shaderManager.createPipeline(shaderName);
            
            if (nativeHandle != 0) {
                LOGGER.debug("Compiled pipeline '{}' -> '{}' (handle: 0x{})", 
                    pipelineName, shaderName, Long.toHexString(nativeHandle));
            } else {
                LOGGER.warn("Failed to compile pipeline '{}' (mapped to '{}')", pipelineName, shaderName);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to compile pipeline '{}': {}", pipelineName, e.getMessage());
        }
    }
    
    private void compileWithShaderSource(ShaderSource shaderSource) {
        try {
            // Get shader source from Minecraft's shader system
            Identifier vertexShaderId = sourcePipeline.getVertexShader();
            Identifier fragmentShaderId = sourcePipeline.getFragmentShader();
            
            // Get GLSL source
            String vertexSource = shaderSource.get(vertexShaderId, com.mojang.blaze3d.shaders.ShaderType.VERTEX);
            String fragmentSource = shaderSource.get(fragmentShaderId, com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
            
            if (vertexSource == null || fragmentSource == null) {
                LOGGER.warn("Missing shader source for pipeline '{}': VS={}, FS={}", 
                    pipelineName, vertexSource != null, fragmentSource != null);
                // Fall back to existing infrastructure
                compileFromExistingInfrastructure();
                return;
            }
            
            // For now, still use our compiled shaders since we need HLSL, not GLSL
            // In the future, we could add GLSL->HLSL transpilation here
            LOGGER.debug("Pipeline '{}' has GLSL shaders - using pre-compiled HLSL fallback", pipelineName);
            compileFromExistingInfrastructure();
            
        } catch (Exception e) {
            LOGGER.warn("Failed to compile pipeline with shader source '{}': {}", pipelineName, e.getMessage());
            compileFromExistingInfrastructure();
        }
    }
    
    /**
     * Map Minecraft 26.1 pipeline names to our existing shader names.
     */
    private String mapPipelineToShader(String pipelineName) {
        // Try exact match first
        String mapped = switch (pipelineName) {
            // Core terrain/block shaders
            case "solid_block", "solid_terrain", "cutout_block", "cutout_terrain" -> "rendertype_solid";
            case "translucent_terrain", "translucent_moving_block" -> "rendertype_translucent";
            
            // Entity shaders
            case "entity_solid", "entity_solid_offset_forward" -> "rendertype_entity_solid";
            case "entity_cutout", "entity_cutout_no_cull", "entity_cutout_no_cull_z_offset" -> "rendertype_entity_cutout";
            case "entity_translucent", "entity_translucent_emissive" -> "rendertype_entity_translucent";
            case "entity_smooth_cutout" -> "rendertype_entity_smooth_cutout";
            case "entity_decal" -> "rendertype_entity_decal";
            case "entity_no_outline" -> "rendertype_entity_no_outline";
            case "entity_shadow" -> "rendertype_entity_shadow";
            case "entity_outline_blit" -> "rendertype_outline";
            
            // Armor shaders
            case "armor_cutout_no_cull", "armor_decal_cutout_no_cull" -> "rendertype_armor_cutout_no_cull";
            case "armor_translucent" -> "rendertype_entity_translucent";
            
            // GUI shaders
            case "gui", "gui_opaque_textured_background" -> "position_tex_color";
            case "gui_textured", "gui_textured_premultiplied_alpha" -> "position_tex_color";
            case "gui_text", "gui_text_highlight", "gui_text_intensity" -> "rendertype_text";
            case "gui_nausea_overlay", "gui_invert" -> "position_tex_color";
            
            // Text shaders
            case "text", "text_see_through", "text_polygon_offset" -> "rendertype_text";
            case "text_background", "text_background_see_through" -> "rendertype_text_background";
            case "text_intensity", "text_intensity_see_through" -> "rendertype_text_intensity";
            
            // Sky/weather shaders
            case "sky", "end_sky" -> "position_color";
            case "stars" -> "position";
            case "clouds", "flat_clouds" -> "rendertype_clouds";
            case "sunrise_sunset" -> "position_color";
            case "celestial" -> "position_tex";
            case "weather_depth_write", "weather_no_depth_write" -> "particle";
            
            // Effects shaders
            case "glint" -> "rendertype_glint";
            case "beacon_beam_opaque", "beacon_beam_translucent" -> "rendertype_beacon_beam";
            case "lightning" -> "rendertype_lightning";
            case "energy_swirl" -> "rendertype_energy_swirl";
            case "eyes" -> "rendertype_eyes";
            case "leash" -> "rendertype_leash";
            case "end_gateway", "end_portal" -> "rendertype_end_gateway";
            case "dragon_rays", "dragon_rays_depth", "dragon_explosion_alpha" -> "position_color";
            case "breeze_wind" -> "rendertype_breeze_wind";
            
            // Particles
            case "opaque_particle", "translucent_particle" -> "particle";
            
            // Lines
            case "lines", "lines_translucent" -> "rendertype_lines";
            
            // Debug
            case "debug_filled_box", "debug_quads", "debug_triangle_fan", "debug_points" -> "position_color";
            
            // Other
            case "crumbling" -> "rendertype_crumbling";
            case "tripwire_block", "tripwire_terrain" -> "rendertype_tripwire";
            case "outline_cull", "outline_no_cull" -> "rendertype_outline";
            case "world_border" -> "position_tex_color";
            case "lightmap" -> "position_tex";
            case "vignette" -> "position_tex_color";
            case "mojang_logo" -> "position_tex_color";
            case "panorama" -> "position_tex";
            case "crosshair" -> "position_tex_color";
            case "fire_screen_effect", "block_screen_effect" -> "position_tex_color";
            case "water_mask" -> "position";
            case "item_entity_translucent_cull" -> "rendertype_item_entity_translucent_cull";
            case "secondary_block_outline" -> "position_color";
            case "wireframe" -> "position_color";
            case "animate_sprite_interpolate", "animate_sprite_blit", "tracy_blit" -> "blit_screen";
            
            // Default fallback
            default -> "position_tex_color";
        };
        
        return mapped;
    }
    
    @Override
    public boolean isValid() {
        return !closed && nativeHandle != 0;
    }
    
    public void close() {
        if (!closed && nativeHandle != 0) {
            // Don't destroy shared shader manager pipelines
            // They're managed by the shader manager cache
            nativeHandle = 0;
            closed = true;
            LOGGER.debug("Closed pipeline {}", id);
        }
    }
    
    public long getId() {
        return id;
    }
    
    public RenderPipeline getSourcePipeline() {
        return sourcePipeline;
    }
    
    public long getNativeHandle() {
        return nativeHandle;
    }
    
    public String getPipelineName() {
        return pipelineName;
    }
    
    /**
     * Bind this pipeline for rendering.
     */
    public void bind() {
        if (!closed && nativeHandle != 0) {
            VitraD3D11Renderer.bindShaderPipeline(nativeHandle);
        }
    }
}
