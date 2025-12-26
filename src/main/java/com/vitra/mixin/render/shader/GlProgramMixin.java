package com.vitra.mixin.render.shader;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GlProgram mixin for Minecraft 26.1 shader system.
 * Intercepts shader compilation/linking to create DirectX shader pipelines.
 * 
 * 26.1 Shader Architecture:
 * - RenderPipeline: State configuration (blend, depth, cull, etc.)
 * - GlProgram: Actual shader program with uniforms
 * - GlRenderPipeline: Record pairing RenderPipeline + GlProgram
 * - GlShaderModule: Individual vertex/fragment shader modules
 * 
 * CRITICAL: This mixin now creates actual D3D11 shaders by:
 * 1. Mapping GLSL shader names to precompiled HLSL (.cso) files
 * 2. Loading the compiled shader bytecode
 * 3. Creating D3D11 VS/PS and input layout
 * 4. Storing handles for later use during rendering
 */
@Mixin(GlProgram.class)
public abstract class GlProgramMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GlProgram");
    
    // Cache of created D3D11 pipelines by OpenGL program ID
    @Unique private static final Map<Integer, Long> vitraPipelineCache = new HashMap<>();
    
    @Shadow public abstract int getProgramId();
    @Shadow public abstract Map<String, Uniform> getUniforms();
    @Shadow public abstract String getDebugLabel();

    @Unique private long vitraVsHandle;
    @Unique private long vitraPsHandle;
    @Unique private long vitraPipelineHandle;

    /**
     * Intercept program linking to create DirectX shader pipeline.
     * The link() method compiles and links GLSL shaders - we create D3D11 equivalents.
     */
    @Inject(method = "link", at = @At("RETURN"))
    private static void onLink(GlShaderModule vertexShader, GlShaderModule fragmentShader,
                               VertexFormat vertexFormat, String debugLabel,
                               CallbackInfoReturnable<GlProgram> cir) {
        GlProgram program = cir.getReturnValue();
        if (program != null && program != GlProgram.INVALID_PROGRAM) {
            int programId = program.getProgramId();
            
            LOGGER.info("[GlProgram] Linked shader '{}' with programId={}, format={}",
                debugLabel, programId, vertexFormat.getElements().size() + " elements");
            
            // Try to create D3D11 shader pipeline
            try {
                long pipelineHandle = createD3D11Pipeline(debugLabel, vertexFormat);
                if (pipelineHandle != 0) {
                    vitraPipelineCache.put(programId, pipelineHandle);
                    LOGGER.info("[GlProgram] ✓ Created D3D11 pipeline for '{}': handle=0x{}",
                        debugLabel, Long.toHexString(pipelineHandle));
                } else {
                    LOGGER.debug("[GlProgram] No D3D11 pipeline for '{}' (shader may not have HLSL equivalent)",
                        debugLabel);
                }
            } catch (Exception e) {
                LOGGER.warn("[GlProgram] Failed to create D3D11 pipeline for '{}': {}", 
                    debugLabel, e.getMessage());
            }
        }
    }
    
    /**
     * Create D3D11 shader pipeline from the GLSL shader name.
     * Maps GLSL shader names to precompiled HLSL files.
     */
    @Unique
    private static long createD3D11Pipeline(String shaderName, VertexFormat vertexFormat) {
        // Map common shader names to HLSL equivalents
        // MC 26.1 uses shaders like "core/position_tex_color", "core/entity", etc.
        String hlslName = mapGlslToHlsl(shaderName);
        
        if (hlslName == null) {
            return 0; // No HLSL equivalent
        }
        
        // Try to load the precompiled shader pipeline
        return D3D11ShaderManager.getOrCreatePipeline(hlslName, vertexFormat);
    }
    
    /**
     * Map GLSL shader names to HLSL equivalents.
     * Returns null if no mapping exists (shader not yet ported to HLSL).
     */
    @Unique
    private static String mapGlslToHlsl(String glslName) {
        // Common MC 26.1 shaders that we need to support
        return switch (glslName) {
            // GUI shaders
            case "core/position_tex_color", "position_tex_color" -> "position_tex_color";
            case "core/position_color", "position_color" -> "position_color";
            case "core/gui", "gui" -> "position_color";
            
            // Text shaders
            case "core/rendertype_text", "rendertype_text" -> "text";
            case "core/rendertype_text_background" -> "text_background";
            case "core/rendertype_text_see_through" -> "text_see_through";
            case "core/rendertype_text_intensity" -> "text_intensity";
            
            // Entity shaders
            case "core/entity", "entity" -> "entity";
            case "core/rendertype_entity_solid" -> "entity";
            case "core/rendertype_entity_cutout" -> "entity";
            case "core/rendertype_entity_translucent" -> "entity";
            
            // Block/terrain shaders
            case "core/block", "block" -> "block";
            case "core/terrain", "terrain" -> "terrain";
            
            // Sky shaders
            case "core/sky", "sky" -> "sky";
            case "core/position_tex", "position_tex" -> "position_tex";
            case "core/position", "position" -> "position";
            case "core/stars" -> "stars";
            
            // Particle shaders
            case "core/particle", "particle" -> "particle";
            
            // Line shaders
            case "core/rendertype_lines" -> "lines";
            
            // Special shaders
            case "core/rendertype_beacon_beam" -> "beacon_beam";
            case "core/rendertype_end_portal" -> "end_portal";
            case "core/rendertype_clouds" -> "clouds";
            case "core/glint" -> "glint";
            case "core/rendertype_crumbling" -> "crumbling";
            case "core/rendertype_lightning" -> "lightning";
            
            // Blit/post-processing shaders
            case "core/screenquad" -> "screenquad";
            case "core/blit_screen" -> "blit_screen";
            
            // Default - shader not mapped yet
            default -> {
                LOGGER.debug("[SHADER_MAP] No HLSL mapping for GLSL shader: {}", glslName);
                yield null;
            }
        };
    }
    
    /**
     * Get the D3D11 pipeline handle for a given OpenGL program ID.
     */
    @Unique
    public static long getD3D11Pipeline(int programId) {
        return vitraPipelineCache.getOrDefault(programId, 0L);
    }

    /**
     * Intercept uniform setup to track DirectX constant buffer requirements.
     */
    @Inject(method = "setupUniforms", at = @At("RETURN"))
    private void onSetupUniforms(List<RenderPipeline.UniformDescription> uniforms,
                                 List<String> samplers, CallbackInfo ci) {
        int uniformCount = uniforms.size();
        int samplerCount = samplers.size();
        
        if (uniformCount > 0 || samplerCount > 0) {
            LOGGER.debug("[GlProgram] Setup uniforms for '{}': {} uniforms, {} samplers",
                getDebugLabel(), uniformCount, samplerCount);
            
            // Log uniform details for DirectX constant buffer layout
            for (RenderPipeline.UniformDescription uniform : uniforms) {
                LOGGER.trace("[GlProgram]   Uniform: {} ({})", uniform.name(), uniform.type());
            }
        }
    }

    /**
     * Intercept program close to cleanup DirectX resources.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        int programId = getProgramId();
        Long cachedPipeline = vitraPipelineCache.remove(programId);
        
        if (cachedPipeline != null && cachedPipeline != 0) {
            LOGGER.debug("[GlProgram] Closing D3D11 pipeline for '{}': handle=0x{}", 
                getDebugLabel(), Long.toHexString(cachedPipeline));
            VitraD3D11Renderer.destroyResource(cachedPipeline);
        }
        
        if (vitraPipelineHandle != 0) {
            LOGGER.info("[GlProgram] Closing DirectX resources for '{}'", getDebugLabel());
            VitraD3D11Renderer.destroyResource(vitraPipelineHandle);
            vitraPipelineHandle = 0;
            vitraVsHandle = 0;
            vitraPsHandle = 0;
        }
    }
}
